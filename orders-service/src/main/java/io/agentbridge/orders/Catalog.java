package io.agentbridge.orders;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * A fixed catalogue keeps the demo deterministic: the same SKU always prices the
 * same way, so evals in M4 can assert on exact amounts.
 */
@Component
public class Catalog {

    private static final List<Dtos.CatalogItem> ITEMS = List.of(
            new Dtos.CatalogItem("SKU-ROAM-10", "Roaming pack 10GB", 1999, "USD", 500),
            new Dtos.CatalogItem("SKU-VOICE-UNL", "Unlimited voice add-on", 999, "USD", 500),
            new Dtos.CatalogItem("SKU-DATA-50", "Data top-up 50GB", 4999, "USD", 120),
            new Dtos.CatalogItem("SKU-DEVICE-INS", "Device insurance, monthly", 799, "USD", 999),
            new Dtos.CatalogItem("SKU-ESIM-ACT", "eSIM activation", 0, "USD", 999));

    public List<Dtos.CatalogItem> items() {
        return ITEMS;
    }

    public Optional<Dtos.CatalogItem> bySku(String sku) {
        return ITEMS.stream().filter(i -> i.sku().equals(sku)).findFirst();
    }
}
