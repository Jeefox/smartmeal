package dev.jeefox.smartmeal.price.service;

import dev.jeefox.smartmeal.exception.*;
import dev.jeefox.smartmeal.price.dto.*;
import dev.jeefox.smartmeal.price.entity.PriceObservation;
import dev.jeefox.smartmeal.price.entity.PriceSource;
import dev.jeefox.smartmeal.price.repository.PriceObservationRepository;
import dev.jeefox.smartmeal.product.entity.Product;
import dev.jeefox.smartmeal.store.entity.Store;
import dev.jeefox.smartmeal.store.entity.StoreProduct;
import dev.jeefox.smartmeal.store.repository.StoreProductRepository;
import dev.jeefox.smartmeal.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class PriceEntryService {
    private final EntityManager em;
    private final StoreProductRepository packages;
    private final PriceObservationRepository prices;
    private final UserRepository users;
    private final ObjectMapper mapper;

    public PriceEntryService(EntityManager em, StoreProductRepository packages, PriceObservationRepository prices,
                             UserRepository users, ObjectMapper mapper) {
        this.em = em;
        this.packages = packages;
        this.prices = prices;
        this.users = users;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public PriceEntryPageResponse list(Long storeId, boolean missingOnly, String query, int page, int size) {
        activeStore(storeId, false);
        String filter = """
                from products p join recipe_ingredients ri on ri.product_id=p.id
                where (lower(p.name) like :q escape '!' or lower(p.shopping_name) like :q escape '!')
                """;
        if (missingOnly) filter += """
                and not exists (select 1 from store_products sp join price_observations po on po.store_product_id=sp.id
                    where sp.store_id=:store and sp.product_id=p.id and sp.active
                      and not po.promotional and po.invalidated_at is null)
                """;
        String search = "%" + query.trim().toLowerCase(java.util.Locale.ROOT)
                .replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        var count = em.createNativeQuery("select count(distinct p.id) " + filter).setParameter("q", search);
        var rows = em.createNativeQuery("select p.id,p.name,p.shopping_name,count(distinct ri.recipe_id) " + filter
                + " group by p.id,p.name,p.shopping_name order by count(distinct ri.recipe_id) desc,p.name,p.id")
                .setParameter("q", search).setFirstResult(Math.multiplyExact(page, size)).setMaxResults(size);
        if (missingOnly) {
            count.setParameter("store", storeId);
            rows.setParameter("store", storeId);
        }
        long total = ((Number) count.getSingleResult()).longValue();
        @SuppressWarnings("unchecked")
        List<Object[]> result = rows.getResultList();
        List<Long> ids = result.stream().map(row -> ((Number) row[0]).longValue()).toList();
        Map<Long, List<StoreProduct>> grouped = ids.isEmpty() ? Map.of() : packages
                .findByStore_IdAndActiveTrueAndProduct_IdIn(storeId, ids).stream()
                .collect(Collectors.groupingBy(sku -> sku.getProduct().getId()));
        List<PriceEntryPageResponse.Item> items = new ArrayList<>();
        for (Object[] row : result) {
            Long id = ((Number) row[0]).longValue();
            var offers = grouped.getOrDefault(id, List.of()).stream()
                    .sorted(java.util.Comparator.comparing(StoreProduct::getName).thenComparing(StoreProduct::getId))
                    .map(sku -> {
                        var observation = prices
                                .findFirstByStoreProduct_IdAndPromotionalFalseAndInvalidatedAtIsNullOrderByObservedAtDescIdDesc(sku.getId())
                                .orElse(null);
                        return new PriceEntryPageResponse.Offer(sku.getId(), sku.getName(), sku.getPackageWeightG(),
                                observation == null ? null : observation.getPrice(),
                                observation == null ? null : observation.getObservedAt());
                    }).toList();
            items.add(new PriceEntryPageResponse.Item(id, (String) row[1], (String) row[2],
                    ((Number) row[3]).longValue(), offers));
        }
        return new PriceEntryPageResponse(List.copyOf(items), page, size, total, (int) ((total + size - 1) / size));
    }

    @Transactional
    public SavePriceEntriesResponse save(String email, Long storeId, SavePriceEntriesRequest request) {
        var owner = users.findByEmailForUpdate(email).orElseThrow(() -> new UsernameNotFoundException(email));
        String hash = hash(storeId, request);
        @SuppressWarnings("unchecked")
        List<Object[]> previous = em.createNativeQuery("""
                select payload_hash,cast(response_json as text) from price_entry_batches
                where user_id=:user and request_id=:request
                """).setParameter("user", owner.getId()).setParameter("request", request.requestId()).getResultList();
        if (!previous.isEmpty()) {
            if (!hash.equals(previous.getFirst()[0])) {
                throw new PriceEntryConflictException("Этот запрос уже сохранён с другими данными. Обновите форму.");
            }
            return mapper.readValue((String) previous.getFirst()[1], SavePriceEntriesResponse.class);
        }
        // Serialize batches for one store, including creation/reuse of matching packages.
        Store store = activeStore(storeId, true);
        Set<Long> productIds = new HashSet<>();
        List<SavePriceEntriesResponse.Entry> saved = new ArrayList<>();
        for (var entry : request.entries()) {
            if (!productIds.add(entry.productId())) {
                throw new InvalidPriceEntryException("Один продукт можно указать только один раз в списке");
            }
            Product product = em.find(Product.class, entry.productId());
            if (product == null) throw new ProductNotFoundException(entry.productId());
            StoreProduct sku;
            boolean created = false;
            if (entry.storeProductId() != null) {
                if (entry.name() != null || entry.packageWeightG() != null) {
                    throw new InvalidPriceEntryException("Для существующей упаковки передайте только её идентификатор");
                }
                sku = packages.findForUpdate(entry.storeProductId())
                        .orElseThrow(() -> new StoreProductNotFoundException(entry.storeProductId()));
                if (!sku.isActive() || !sku.getStore().getId().equals(storeId)
                        || !sku.getProduct().getId().equals(entry.productId())) {
                    throw new PriceEntryConflictException("Упаковка недоступна или относится к другому магазину/продукту");
                }
            } else {
                if (entry.name() == null || entry.name().isBlank() || entry.packageWeightG() == null) {
                    throw new InvalidPriceEntryException("Для новой упаковки укажите название и фактический вес в граммах");
                }
                String name = entry.name().trim();
                int packageWeightG = entry.packageWeightG().intValueExact();
                List<StoreProduct> matching = em.createQuery("""
                        select s from StoreProduct s where s.store.id=:store and s.product.id=:product
                        and s.active=true and s.name=:name and s.packageWeightG=:weight order by s.id
                        """, StoreProduct.class).setParameter("store", storeId).setParameter("product", product.getId())
                        .setParameter("name", name).setParameter("weight", packageWeightG).setMaxResults(1).getResultList();
                if (matching.isEmpty()) {
                    sku = new StoreProduct(store, product, name, packageWeightG);
                    em.persist(sku);
                    created = true;
                } else {
                    sku = matching.getFirst();
                }
            }
            var price = new PriceObservation(sku, entry.price(), request.observedAt(), PriceSource.MANUAL, entry.promotional());
            em.persist(price);
            saved.add(new SavePriceEntriesResponse.Entry(product.getId(), sku.getId(), price.getId(), created));
        }
        var response = new SavePriceEntriesResponse(request.requestId(), storeId, saved.size(), List.copyOf(saved));
        em.createNativeQuery("""
                insert into price_entry_batches(user_id,request_id,store_id,payload_hash,response_json)
                values(:user,:request,:store,:hash,cast(:response as jsonb))
                """).setParameter("user", owner.getId()).setParameter("request", request.requestId())
                .setParameter("store", storeId).setParameter("hash", hash)
                .setParameter("response", mapper.writeValueAsString(response)).executeUpdate();
        return response;
    }

    private Store activeStore(Long id, boolean lock) {
        Store store = lock ? em.find(Store.class, id, LockModeType.PESSIMISTIC_WRITE) : em.find(Store.class, id);
        if (store == null) throw new StoreNotFoundException(id);
        if (!store.isActive()) throw new StoreInactiveException(id);
        return store;
    }

    private String hash(Long storeId, SavePriceEntriesRequest request) {
        try {
            byte[] payload = (storeId + ":" + mapper.writeValueAsString(request)).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
