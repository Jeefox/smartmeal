package dev.jeefox.smartmeal.price.controller;

import dev.jeefox.smartmeal.price.dto.CataloguePriceReference;
import dev.jeefox.smartmeal.price.refresh.CatalogueProductDiscovery;
import dev.jeefox.smartmeal.price.refresh.CatalogueRefreshStore;
import dev.jeefox.smartmeal.price.service.CataloguePriceReferenceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/price-references")
public class CataloguePriceReferenceController {
    private final CataloguePriceReferenceService service;
    private final CatalogueProductDiscovery discovery;
    private final CatalogueRefreshStore refreshStore;

    public CataloguePriceReferenceController(CataloguePriceReferenceService service, CatalogueRefreshStore refreshStore, CatalogueProductDiscovery discovery) {
        this.discovery=discovery;
        this.refreshStore = refreshStore;
        this.service = service;
    }

    @GetMapping("/coverage")
    public List<CatalogueProductDiscovery.Coverage> coverage() {
        return discovery.coverage();
    }

    @GetMapping("/status")
    public List<CatalogueRefreshStore.Status> status() {
        return refreshStore.status();
    }

    @GetMapping
    public List<CataloguePriceReference> list() {
        return service.list();
    }
}
