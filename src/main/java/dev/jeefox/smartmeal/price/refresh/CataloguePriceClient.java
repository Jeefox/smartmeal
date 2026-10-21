package dev.jeefox.smartmeal.price.refresh;

import java.io.IOException;
import java.math.BigDecimal;

/** Adapter for a price catalogue source. Implementations must validate the product match before returning a price. */
public interface CataloguePriceClient {
    boolean supports(CatalogueRefreshSource source);

    BigDecimal fetch(CatalogueRefreshSource source) throws IOException;
}
