package dev.jeefox.smartmeal.price.service;

import dev.jeefox.smartmeal.price.dto.CataloguePriceReference;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class CataloguePriceReferenceService {
    private final EntityManager em;

    public CataloguePriceReferenceService(EntityManager em) {
        this.em = em;
    }

    @Transactional(readOnly = true)
    public List<CataloguePriceReference> list() {
        return load(null);
    }

    @Transactional(readOnly = true)
    public Map<Long, List<CataloguePriceReference>> forProducts(List<Long> ids) {
        if (ids.isEmpty()) return Map.of();
        return load(ids).stream().collect(Collectors.groupingBy(CataloguePriceReference::productId));
    }

    private List<CataloguePriceReference> load(List<Long> ids) {
        var query = em.createNativeQuery("""
                select r.id,r.product_id,p.name,r.name,r.package_weight_g,r.price,r.source_name,r.source_url,
                       r.source_scope,r.retrieved_at,r.expires_at,r.sold_by_weight
                from catalogue_price_references r join products p on p.id=r.product_id
                where r.active and r.expires_at > current_timestamp
                """ + (ids == null ? "" : " and r.product_id in (:ids)")
                + " order by r.retrieved_at desc,r.id");
        if (ids != null) query.setParameter("ids", ids);
        else query.setMaxResults(500);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        return rows.stream().map(row -> new CataloguePriceReference(((Number) row[0]).longValue(),
                ((Number) row[1]).longValue(), (String) row[2], (String) row[3], ((Number) row[4]).intValue(),
                (BigDecimal) row[5], (String) row[6], (String) row[7], (String) row[8], instant(row[9]), instant(row[10]), (Boolean) row[11])).toList();
    }

    private Instant instant(Object value) {
        if (value instanceof Instant instant) return instant;
        if (value instanceof OffsetDateTime date) return date.toInstant();
        return ((java.sql.Timestamp) value).toInstant();
    }
}
