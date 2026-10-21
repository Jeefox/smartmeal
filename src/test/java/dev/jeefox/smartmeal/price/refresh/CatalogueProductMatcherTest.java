package dev.jeefox.smartmeal.price.refresh;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CatalogueProductMatcherTest {
    private final CatalogueProductMatcher matcher=new CatalogueProductMatcher();

    @Test void distinguishesIngredientsFromPreparedFoodAndSimilarProducts() {
        assertTrue(matcher.matches("Куриное филе сырое","Филе куриное охлаждённое"));
        assertTrue(matcher.matches("Молоко 2,5%","Молоко 2,5% в бутылке, 900 мл"));
        assertTrue(matcher.matches("Банан без кожуры","Бананы"));
        assertFalse(matcher.matches("Банан без кожуры","Конфеты со вкусом Клубника-банан в белом шок."));
        assertFalse(matcher.matchesCategory("Банан без кожуры","Сладости//Конфеты"));
        assertTrue(matcher.matchesCategory("Йогурт натуральный без сахара","Молочные продукты, яйцо//Йогурты"));
        assertFalse(matcher.matchesCategory("Йогурт натуральный без сахара","Колбаса, сосиски, деликатесы//Сосиски"));
        assertTrue(matcher.matchesCategory("Банан без кожуры","Овощи, фрукты, ягоды, зелень//Фрукты"));
        assertTrue(matcher.matches("Творог 5%","Творог 5%, 200 г"));
        for (String title:List.of("Филе куриное запечённое","Филе индейки","Куриное филе маринованное","Салат с куриным филе")) {
            assertFalse(matcher.matches("Куриное филе сырое",title),title);
        }
        assertTrue(matcher.matches("Куриное филе сырое","Филе грудки цыпленка-бройлера"));
        assertTrue(matcher.matchesCategory("Куриное филе сырое","Мясо, птица//Курица"));
        assertFalse(matcher.matchesCategory("Куриное филе сырое","Колбаса, сосиски, деликатесы//Мясные деликатесы"));
        assertFalse(matcher.matches("Куриное филе сырое","Филе куриной грудки с прованскими травами"));
        assertFalse(matcher.matches("Филе индейки сырое","Филе индейки гриль с кускусом и брокколи"));
        assertFalse(matcher.matches("Минтай филе сырое","Минтай филе без кожи вяленое,вес"));
        assertFalse(matcher.matches("Соль","Фасоль"));
        assertFalse(matcher.matches("???","Любой продукт"));
        assertFalse(matcher.matches("Рис сухой","Мука рисовая"));
        assertFalse(matcher.matches("Молоко 2,5%","Молоко козье 2,5%"));
        assertFalse(matcher.matches("Молоко 2,5%","Молоко 3,2%"));
        assertFalse(matcher.matches("Творог 5%","Творог 15%"));
        assertFalse(matcher.matches("Йогурт натуральный без сахара","Йогурт натуральный с клубникой"));
        assertFalse(matcher.matches("Йогурт натуральный без сахара","Йогурт Epica натуральный Вишня черешня 4,8% 130 г"));
        assertTrue(matcher.matches("Йогурт натуральный без сахара","Йогурт Греческий"));
        assertFalse(matcher.matches("Банан без кожуры","Бананы сушёные"));
    }

    @Test void handlesMassVolumeAndEggCountsWithExplicitApproximation() {
        assertEquals(400,matcher.measure("Рис сухой","400 г",false).grams());
        assertEquals(2500,matcher.measure("Картофель","2,5 кг",false).grams());
        assertEquals(1000,matcher.measure("Морковь","",true).grams());
        var milk=matcher.measure("Молоко 2,5%","900 мл",false);
        assertEquals(927,milk.grams()); assertNotNull(milk.note());
        var oil=matcher.measure("Масло подсолнечное","1 л",false);
        assertEquals(920,oil.grams()); assertNotNull(oil.note());
        var eggs=matcher.measure("Яйцо куриное без скорлупы","10 шт",false);
        assertEquals(500,eggs.grams()); assertNotNull(eggs.note());
    }

    @Test void estimatesDrainedWeightAndRejectsUnknownConversion() {
        assertThrows(IllegalArgumentException.class,() -> matcher.measure("Йогурт","1 л",false));
        var beans=matcher.measure("Фасоль консервированная без заливки","400 г",false);
        assertEquals(240,beans.grams()); assertNotNull(beans.note());
        var tuna=matcher.measure("Тунец консервированный в собственном соку","185 г",false);
        assertEquals(130,tuna.grams()); assertNotNull(tuna.note());
        assertThrows(IllegalArgumentException.class,() -> matcher.measure("Огурец","1 шт",false));
    }
}
