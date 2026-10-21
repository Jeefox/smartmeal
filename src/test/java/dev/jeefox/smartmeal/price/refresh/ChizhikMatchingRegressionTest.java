package dev.jeefox.smartmeal.price.refresh;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChizhikMatchingRegressionTest {
    private final CatalogueProductMatcher matcher = new CatalogueProductMatcher();

    @Test
    void doesNotSubstitutePreparedVegetablesForFreshIngredients() {
        assertTrue(matcher.matches("Морковь", "Морковь отечественная"));
        assertFalse(matcher.matches("Морковь", "Морковь GREENOLA по-корейски 500г"));
        assertFalse(matcher.matches("Морковь", "Капуста GREENOLA квашеная с морковью 700г"));
        assertTrue(matcher.matches("Помидор", "Томаты красные круглые"));
        assertFalse(matcher.matches("Помидор", "Мякоть томатов Пиканта протёртая, 500г"));
        assertFalse(matcher.matches("Помидор", "Пассата GREENOLA Протертая мякоть томатов 500 г"));
    }

    @Test
    void comparesCompleteFatPercentageAndRejectsMixedOil() {
        assertTrue(matcher.matches("Творог 5%", "Творог СВЕТАЕВО мягкий 5% БЗМЖ 150г"));
        assertFalse(matcher.matches("Творог 5%", "Творог СВЕТАЕВО мягкий 0.5% БЗМЖ 150г"));
        assertTrue(matcher.matches("Молоко 2,5%", "Молоко 2.5% 1л"));
        assertTrue(matcher.matches("Масло подсолнечное", "Масло Олейна подсолнечное рафинированное 1л"));
        assertFalse(matcher.matches("Масло подсолнечное", "Масло Altero подсолнечное с добавлением оливкового 810мл"));
    }
}
