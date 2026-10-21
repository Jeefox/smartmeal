package dev.jeefox.smartmeal.price.refresh;

import java.util.UUID;

public record CatalogueRefreshSource(long id, long productId, String url, String sku, String name,
                                     int weightG, boolean soldByWeight, UUID leaseToken, String expectedWeightLabel, String conversionNote, String expectedCategory, String ingredientName) {
    public CatalogueRefreshSource(long id, long productId, String url, String sku, String name,
                                  int weightG, boolean soldByWeight, UUID leaseToken) {
        this(id,productId,url,sku,name,weightG,soldByWeight,leaseToken,null,null,null,null);
    }
}
