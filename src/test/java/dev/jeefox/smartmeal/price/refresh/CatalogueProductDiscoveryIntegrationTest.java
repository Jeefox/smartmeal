package dev.jeefox.smartmeal.price.refresh;

import dev.jeefox.smartmeal.product.entity.Product;
import dev.jeefox.smartmeal.product.entity.ProductCategory;
import jakarta.persistence.EntityManager;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@Transactional
class CatalogueProductDiscoveryIntegrationTest {
    @Autowired CatalogueProductDiscovery discovery;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @MockitoBean ChizhikOfficialPriceClient client;

    @Test void discoversNewRecipeIngredientWithoutManualSourceConfiguration() throws Exception {
        jdbc.update("insert into catalogue_product_discovery(product_id) select distinct product_id from recipe_ingredients on conflict do nothing");
        jdbc.update("update catalogue_product_discovery set next_attempt_at=now()+interval '7 days'");
        var category=new ProductCategory("DISC_"+UUID.randomUUID(),"Тест"); em.persist(category);
        var product=new Product(category,"Кабачок",null,25,BigDecimal.ONE,BigDecimal.ONE,BigDecimal.ONE); em.persist(product); em.flush();
        UUID recipe=UUID.randomUUID();
        jdbc.update("insert into recipes(id,name,instructions,preparation_time_min,servings,created_at,updated_at) values(?,'Тест','Тест',1,1,now(),now())",recipe);
        jdbc.update("insert into recipe_ingredients(id,recipe_id,product_id,quantity_g,position) values(?,?,?,100,1)",UUID.randomUUID(),recipe,product.getId());
        when(client.categoryPages(category.getCode())).thenReturn(java.util.List.of(Jsoup.parse("""
                <h1>Овощи и фрукты</h1><div class="product-card"><a href="/product/kabachok--123/">
                <span class="product-title">Кабачок 500г</span><span>500 г Цена 100 00 ₽</span></a></div>
                """)));
        discovery.discoverNext();
        assertEquals(1,jdbc.queryForObject("select count(*) from catalogue_price_refresh_sources where product_id=?",Integer.class,product.getId()));
        assertEquals(0,jdbc.queryForObject("select count(*) from catalogue_price_references where product_id=?",Integer.class,product.getId()));
        assertTrue(discovery.coverage().stream().anyMatch(row -> row.productId()==product.getId() && !row.priced()));
        discovery.discoverNext(); verify(client,times(1)).categoryPages(category.getCode());
    }
}
