package com.paypilot.commerce.catalog.api;

import com.paypilot.commerce.catalog.CatalogService;
import com.paypilot.commerce.catalog.api.dto.PageResponse;
import com.paypilot.commerce.catalog.api.dto.ProductDetail;
import com.paypilot.commerce.catalog.api.dto.ProductSummary;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public catalog reads. Thin: every parameter lands in {@link CatalogService},
 * which owns normalization, validation and mapping.
 */
@RestController
@RequestMapping("/api/v1/products")
public class ProductController {

    private final CatalogService catalogService;

    public ProductController(CatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @GetMapping
    public PageResponse<ProductSummary> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String minPrice,
            @RequestParam(required = false) String maxPrice,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return catalogService.listProducts(q, category, minPrice, maxPrice, sort, page, size);
    }

    @GetMapping("/{sku}")
    public ProductDetail detail(@PathVariable String sku) {
        return catalogService.getProduct(sku);
    }
}
