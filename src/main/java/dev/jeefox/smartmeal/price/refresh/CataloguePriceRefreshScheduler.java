package dev.jeefox.smartmeal.price.refresh;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "smartmeal.prices.auto-refresh.enabled", havingValue = "true", matchIfMissing = true)
public class CataloguePriceRefreshScheduler {
    private static final Logger log = LoggerFactory.getLogger(CataloguePriceRefreshScheduler.class);
    private final CatalogueRefreshStore store;
    private final VkusvillPriceClient client;
    private final ChizhikOfficialPriceClient chizhikClient;
    private final CatalogueProductDiscovery discovery;

    public CataloguePriceRefreshScheduler(CatalogueRefreshStore store, VkusvillPriceClient client,
                                          ChizhikOfficialPriceClient chizhikClient,
                                          CatalogueProductDiscovery discovery) {
        this.discovery=discovery;
        this.store = store;
        this.client = client;
        this.chizhikClient = chizhikClient;
    }

    /** One page per tick respects the source crawl delay and keeps external work off request threads. */
    @Scheduled(initialDelayString = "${smartmeal.prices.auto-refresh.initial-delay-ms:15000}",
            fixedDelayString = "${smartmeal.prices.auto-refresh.poll-delay-ms:5000}")
    public void refreshNext() {
        CatalogueRefreshSource source = null;
        try {
            source = store.claim();
            if (source == null) {
                discovery.discoverNext();
                return;
            }
            var priceClient = chizhikClient.supports(source) ? chizhikClient : client;
            if (!priceClient.supports(source)) {
                throw new IllegalArgumentException("UNSUPPORTED_CATALOGUE_SOURCE");
            }
            var price = priceClient.fetch(source);
            store.success(source,price);
            log.info("Catalogue price checked: source={}, price={}",source.id(),price);
        } catch (Exception error) {
            String code="SOURCE_UNAVAILABLE_OR_CHANGED";
            if (error instanceof org.jsoup.HttpStatusException http) code="SOURCE_HTTP_"+http.getStatusCode();
            else if (error instanceof java.net.SocketTimeoutException) code="SOURCE_TIMEOUT";
            else if (error instanceof IllegalArgumentException && error.getMessage()!=null
                    && error.getMessage().matches("[A-Z_]{3,100}")) code=error.getMessage();
            log.warn("Catalogue refresh failed: source={}, code={}",source == null ? null : source.id(),code);
            if (source != null) store.failure(source,code);
        }
    }
}
