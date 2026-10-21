package dev.jeefox.smartmeal.price.refresh;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetailMatchingRegressionTest {
    private final CatalogueProductMatcher matcher = new CatalogueProductMatcher();

    @Test
    void appleCultivarNamedBananaIsNotABanana() {
        assertTrue(matcher.matches("Банан без кожуры", "Бананы мини, вес"));
        assertFalse(matcher.matches("Банан без кожуры", "Яблоки Зимний банан, вес"));
    }

    @Test
    void boiledPotatoesAreNotRawPotatoes() {
        assertTrue(matcher.matches("Картофель очищенный", "Картофель, вес"));
        assertFalse(matcher.matches("Картофель очищенный", "Картофель вареный в мундире, 0,5кг"));
        assertFalse(matcher.matches("Картофель очищенный", "Картофель варёный, 500г"));
    }

    @Test
    void microgreensAndSproutsAreNotBroccoliFlorets() {
        assertTrue(matcher.matches("Брокколи", "Капуста брокколи, вес"));
        assertFalse(matcher.matches("Брокколи", "Микрозелень брокколи, 50г"));
        assertFalse(matcher.matches("Брокколи", "Проростки брокколи, 30г"));
        assertFalse(matcher.matches("Брокколи", "Капуста цветная и брокколи, 400г"));
    }

    @Test
    void recognizesActualMeatAndDairySectionsOfTheNewNetworks() {
        assertTrue(matcher.matchesCategory("Куриное филе сырое", "Птица, мясо"));
        assertTrue(matcher.matchesCategory("Куриное филе сырое", "Мясо, птица, колбасы"));
        assertFalse(matcher.matchesCategory("Куриное филе сырое", "Готовая еда"));
        assertTrue(matcher.matchesCategory("Йогурт натуральный без сахара", "Молочный прилавок"));
        assertFalse(matcher.matchesCategory("Йогурт натуральный без сахара", "Десерты"));
    }

    @Test
    void matchesPlainWalnutsInSnacksWithoutAcceptingCandiedNuts() {
        assertTrue(matcher.matchesCategory("Грецкий орех очищенный", "Снеки"));
        assertTrue(matcher.matches("Грецкий орех очищенный", "Ядра грецкого ореха, 100г"));
        assertFalse(matcher.matches("Грецкий орех очищенный", "Ядра грецкого ореха в карамельной глазури, 100г"));
    }

    @Test
    void matchesPlainCannedBeansAndNaturalTunaOnlyInCannedFood() {
        assertTrue(matcher.matches("Фасоль консервированная без заливки", "Фасоль Магнит белая 400г"));
        assertFalse(matcher.matches("Фасоль консервированная без заливки", "Фасоль Магнит белая в томатном соусе 400г"));
        assertTrue(matcher.matchesCategory("Фасоль консервированная без заливки", "Консервы"));
        assertFalse(matcher.matchesCategory("Фасоль консервированная без заливки", "Бакалея"));

        assertTrue(matcher.matches("Тунец консервированный в собственном соку", "Тунец Ryoshi натуральный 185г"));
        assertFalse(matcher.matches("Тунец консервированный в собственном соку", "Тунец в растительном масле 185г"));
        assertTrue(matcher.matchesCategory("Тунец консервированный в собственном соку", "Консервы"));
        assertFalse(matcher.matchesCategory("Тунец консервированный в собственном соку", "Рыба замороженная"));
    }
}
