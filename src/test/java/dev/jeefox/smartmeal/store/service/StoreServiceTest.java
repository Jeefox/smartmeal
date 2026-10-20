package dev.jeefox.smartmeal.store.service;

import dev.jeefox.smartmeal.exception.InvalidStoreFilterException;
import dev.jeefox.smartmeal.store.dto.RetailChainResponse;
import dev.jeefox.smartmeal.store.dto.StoreResponse;
import dev.jeefox.smartmeal.store.entity.RetailChain;
import dev.jeefox.smartmeal.store.entity.Store;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@Transactional
class StoreServiceTest {
    @Autowired private EntityManager em;
    @Autowired private StoreService service;
    @Autowired private WebApplicationContext context;
    private RetailChain firstChain;
    private RetailChain secondChain;
    private String city;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString();
        city = "Город-" + suffix;
        firstChain = new RetailChain("TEST_A_" + suffix, "Альфа " + suffix);
        secondChain = new RetailChain("TEST_B_" + suffix, "Бета " + suffix);
        em.persist(firstChain);
        em.persist(secondChain);
    }

    @Test
    void returnsRetailChainsSortedByNameAndId() {
        em.flush();
        em.clear();
        List<RetailChainResponse> chains = service.findRetailChains();
        assertTrue(chains.contains(new RetailChainResponse(firstChain.getId(), firstChain.getCode(), firstChain.getName())));
        // The seeded names plus Latin fixture identifiers are sorted by PostgreSQL collation.
        List<Long> expected = em.createQuery("select c.id from RetailChain c order by c.name, c.id", Long.class)
                .getResultList();
        assertEquals(expected, chains.stream().map(RetailChainResponse::id).toList());
    }

    @Test
    void filtersActiveStoresByCityIgnoringCaseAndTrimmingInput() {
        Store later = store(firstChain, city, "Б улица, 2");
        Store earlier = store(secondChain, city, "А улица, 1");
        store(firstChain, "Другой город", "А улица, 1");
        Store inactive = store(firstChain, city, "Закрытый магазин");
        em.flush();
        em.createNativeQuery("update stores set active = false where id = :id")
                .setParameter("id", inactive.getId()).executeUpdate();
        em.clear();
        List<StoreResponse> result = service.findStores("  " + city.toUpperCase(java.util.Locale.ROOT) + "  ", null);
        assertEquals(List.of(earlier.getId(), later.getId()), result.stream().map(StoreResponse::id).toList());
        assertEquals(secondChain.getName(), result.getFirst().chainName());
        assertEquals(secondChain.getId(), result.getFirst().chainId());
    }

    @Test
    void filtersByChainAndResolvesEqualAddressesById() {
        Store first = store(firstChain, city, "Одинаковый адрес");
        Store second = store(firstChain, city, "Одинаковый адрес");
        store(secondChain, city, "Другой адрес");
        em.flush();
        em.clear();
        assertEquals(List.of(first.getId(), second.getId()), service.findStores(city, firstChain.getId())
                .stream().map(StoreResponse::id).toList());
    }

    @Test
    void returnsEmptyListForUnknownCityOrChain() {
        store(firstChain, city, "Адрес");
        assertTrue(service.findStores("Неизвестный город", null).isEmpty());
        assertTrue(service.findStores(city, Long.MAX_VALUE).isEmpty());
    }

    @Test
    void rejectsBlankCityAndNonPositiveChainId() {
        for (String invalid : new String[]{null, "", "   "}) {
            assertThrows(InvalidStoreFilterException.class, () -> service.findStores(invalid, null));
        }
        assertThrows(InvalidStoreFilterException.class, () -> service.findStores(city, 0L));
        assertThrows(InvalidStoreFilterException.class, () -> service.findStores(city, -1L));
    }

    @Test
    void createsActiveStoreWithTimestamps() {
        Store store = store(firstChain, city, "Адрес");
        em.flush();
        Long id = store.getId();
        em.clear();
        Store loaded = em.find(Store.class, id);
        assertTrue(loaded.isActive());
        assertNotNull(loaded.getCreatedAt());
        assertNotNull(loaded.getUpdatedAt());
    }

    @Test
    void controllerReturnsDtoAndRequiresAuthentication() throws Exception {
        Store store = store(firstChain, city, "Адрес");
        MockMvc mvc = mvc();
        mvc.perform(get("/api/stores").param("city", city).with(user("test@example.invalid")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(store.getId()))
                .andExpect(jsonPath("$[0].chainName").value(firstChain.getName()))
                .andExpect(jsonPath("$[0].chain").doesNotExist());
        mvc.perform(get("/api/retail-chains").with(user("test@example.invalid")))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].code").exists());
        mvc.perform(get("/api/stores").param("city", city)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/retail-chains")).andExpect(status().isUnauthorized());
    }

    @Test
    void controllerRejectsMissingOrMalformedFilters() throws Exception {
        MockMvc mvc = mvc();
        mvc.perform(get("/api/stores").with(user("test@example.invalid")))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/stores").param("city", "   ").with(user("test@example.invalid")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400));
        for (String chainId : new String[]{"0", "-1", "abc"}) {
            mvc.perform(get("/api/stores").param("city", city).param("chainId", chainId)
                            .with(user("test@example.invalid")))
                    .andExpect(status().isBadRequest());
        }
    }

    private Store store(RetailChain chain, String city, String address) {
        Store store = new Store(chain, city, address);
        em.persist(store);
        return store;
    }

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }
}
