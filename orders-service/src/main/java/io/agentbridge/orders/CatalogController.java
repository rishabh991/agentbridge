package io.agentbridge.orders;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/catalog")
@Tag(name = "catalog", description = "Read-only product catalogue")
class CatalogController {

    private final Catalog catalog;

    CatalogController(Catalog catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/items")
    @Operation(operationId = "listCatalogItems", summary = "List catalogue items", description = "Read-only. Safe for a guest key.")
    List<Dtos.CatalogItem> items() {
        return catalog.items();
    }
}
