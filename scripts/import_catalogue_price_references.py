#!/usr/bin/env python3
"""Load reviewed catalogue reference prices, separately from actual store observations.

Default: validate and roll back. --apply commits. Re-running never refreshes the fixed
retrieval/expiry timestamps from the data file. No external requests are performed.
"""
import argparse
import json
import subprocess
from decimal import Decimal
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apply', action='store_true')
    parser.add_argument('--container', default='smartmeal-postgres-1')
    args = parser.parse_args()
    rows = json.loads((ROOT / 'data/prices/basic-catalogue-references.json').read_text())
    seen = set()
    for row in rows:
        key = (row['productName'], row['sourceUrl'])
        if key in seen or not row['sourceUrl'].startswith('https://www.perekrestok.ru/'):
            raise ValueError('Duplicate or unsupported source')
        seen.add(key)
        price = Decimal(row['price'])
        if not price.is_finite() or price <= 0 or price.as_tuple().exponent < -2:
            raise ValueError('Invalid price')
        if not isinstance(row['packageWeightG'], int) or not 0 < row['packageWeightG'] <= 2147483647:
            raise ValueError('Invalid package weight')
        if datetime.fromisoformat(row['expiresAt']) <= datetime.fromisoformat(row['retrievedAt']):
            raise ValueError('Invalid expiry')
    payload = json.dumps(rows, ensure_ascii=False).replace("'", "''")
    sql = """
BEGIN;
SELECT pg_advisory_xact_lock(hashtext('smartmeal-catalogue-price-references-v1'));
DO $import$
DECLARE
    item jsonb;
    matches integer;
    product bigint;
BEGIN
    FOR item IN SELECT value FROM jsonb_array_elements('__PAYLOAD__'::jsonb) LOOP
        SELECT count(*),min(id) INTO matches,product FROM products WHERE name=item->>'productName';
        IF matches <> 1 THEN
            RAISE EXCEPTION 'Expected exactly one product named %, found %',item->>'productName',matches;
        END IF;
        INSERT INTO catalogue_price_references(product_id,name,package_weight_g,price,source_name,source_url,
            source_scope,retrieved_at,expires_at)
        VALUES(product,item->>'name',(item->>'packageWeightG')::integer,(item->>'price')::numeric,
            item->>'sourceName',item->>'sourceUrl',item->>'sourceScope',(item->>'retrievedAt')::timestamptz,
            (item->>'expiresAt')::timestamptz)
        ON CONFLICT(product_id,source_url) DO UPDATE SET
            name=excluded.name,package_weight_g=excluded.package_weight_g,price=excluded.price,
            source_name=excluded.source_name,source_scope=excluded.source_scope,
            retrieved_at=excluded.retrieved_at,expires_at=excluded.expires_at,active=true
        WHERE catalogue_price_references.retrieved_at <= excluded.retrieved_at;
    END LOOP;
END $import$;
SELECT p.name,r.price,r.package_weight_g,r.source_name,r.expires_at
FROM catalogue_price_references r JOIN products p ON p.id=r.product_id ORDER BY p.name;
__END__;
""".replace('__PAYLOAD__', payload).replace('__END__', 'COMMIT' if args.apply else 'ROLLBACK')
    result = subprocess.run(['docker', 'exec', '-i', args.container, 'psql', '-U', 'smartmeal', '-d', 'smartmeal',
                             '-v', 'ON_ERROR_STOP=1'], input=sql, text=True, capture_output=True)
    if result.returncode:
        raise SystemExit(result.stderr)
    print(result.stdout.strip())
    print('Applied.' if args.apply else 'Validated; rolled back. Use --apply to save.')


if __name__ == '__main__':
    main()
