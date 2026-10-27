package dev.jeefox.smartmeal.shopping.purchase;

import static dev.jeefox.smartmeal.shopping.purchase.ShoppingPurchaseModels.*;

import dev.jeefox.smartmeal.pantry.PantryModels;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
@Transactional
public class ShoppingPurchaseService {
    private final ShoppingSessionService sessions;
    private final dev.jeefox.smartmeal.pantry.PantryService pantry;
    private final org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc;

    public ShoppingPurchaseService(
            ShoppingSessionService sessions,
            dev.jeefox.smartmeal.pantry.PantryService pantry,
            org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc) {
        this.sessions = sessions;
        this.pantry = pantry;
        this.jdbc = jdbc;
    }

    public View purchase(String email, UUID id, UUID key, Purchase request) {
        UUID owner = pantry.lockOwner(email);
        var normalized =
                new Purchase(
                        request.expectedRevision(),
                        request.expectedPantryRevision(),
                        request.items().stream()
                                .map(
                                        l ->
                                                new Line(
                                                        l.itemId(),
                                                        l.quantityG().stripTrailingZeros(),
                                                        l.actualTotalRub() == null
                                                                ? null
                                                                : l.actualTotalRub()
                                                                        .stripTrailingZeros(),
                                                        l.storeProductId(),
                                                        l.receivedOn(),
                                                        l.expiresOn()))
                                .sorted(Comparator.comparing(Line::itemId))
                                .toList());
        String hash = sessions.hash("purchase:" + id, normalized);
        var prior = sessions.repeat(owner, key, hash);
        if (prior != null) return prior;
        var row = sessions.owned(owner, id);
        sessions.revision(row, request.expectedRevision());
        var before = sessions.read(email, owner, id);
        if (before.status().equals("COMPLETED"))
            throw ShoppingPurchaseException.conflict(
                    "SHOPPING_SESSION_COMPLETED",
                    "В списке нет оставшихся продуктов. Обновите список.");
        var byId = new HashMap<UUID, Item>();
        before.items().forEach(i -> byId.put(i.id(), i));
        var unique = new HashSet<UUID>();
        for (var line : normalized.items()) {
            var item = byId.get(line.itemId());
            if (item == null) throw ShoppingPurchaseException.missing();
            if (!unique.add(line.itemId()))
                throw ShoppingPurchaseException.invalid("Позиция повторяется в одной покупке");
            if (item.skipped())
                throw ShoppingPurchaseException.conflict(
                        "SHOPPING_ITEM_SKIPPED",
                        "Верните пропущенный продукт в список перед покупкой.");
            if (item.remainingG().signum() <= 0)
                throw ShoppingPurchaseException.conflict(
                        "SHOPPING_ITEM_COMPLETED", "Эта позиция уже куплена. Обновите список.");
        }
        UUID pantryKey = UUID.randomUUID();
        var purchase =
                new PantryModels.Purchase(
                        request.expectedPantryRevision(),
                        normalized.items().stream()
                                .map(
                                        l ->
                                                new PantryModels.PurchaseLine(
                                                        byId.get(l.itemId()).snapshot().productId(),
                                                        l.quantityG(),
                                                        l.receivedOn(),
                                                        l.expiresOn(),
                                                        l.storeProductId(),
                                                        null))
                                .toList());
        var stock = pantry.purchase(email, pantryKey, purchase);
        UUID command = sessions.record(owner, id, key, hash, null);
        jdbc.update(
                "insert into shopping_purchase_batches(id,pantry_operation_id) values(:id,:op)",
                Map.of("id", command, "op", stock.operationId()));
        for (int n = 0; n < normalized.items().size(); n++) {
            var l = normalized.items().get(n);
            var p = new HashMap<String, Object>();
            p.put("id", UUID.randomUUID());
            p.put("batch", command);
            p.put("item", l.itemId());
            p.put("q", l.quantityG());
            p.put("total", l.actualTotalRub());
            p.put("sku", l.storeProductId());
            p.put("received", l.receivedOn());
            p.put("expiry", l.expiresOn());
            p.put("lot", stock.lotIds().get(n));
            jdbc.update(
                    """
                    insert into shopping_purchase_lines(id,batch_id,item_id,quantity_g,actual_total_rub,store_product_id,received_on,expires_on,pantry_lot_id)
                    values(:id,:batch,:item,:q,:total,:sku,:received,:expiry,:lot)
                    """,
                    p);
            jdbc.update("update shopping_session_items set picked=false where id=:item", p);
        }
        sessions.bump(owner, id);
        var result = sessions.read(email, owner, id);
        jdbc.update(
                "update shopping_session_commands set result_payload=cast(:result as jsonb) where"
                        + " id=:id",
                Map.of("result", sessions.encode(result), "id", command));
        return result;
    }
}
