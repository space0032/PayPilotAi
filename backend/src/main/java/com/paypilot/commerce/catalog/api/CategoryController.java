package com.paypilot.commerce.catalog.api;

import com.paypilot.commerce.catalog.CatalogService;
import com.paypilot.commerce.catalog.api.dto.CategoryDto;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/categories")
public class CategoryController {

    private final CatalogService catalogService;

    public CategoryController(CatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @GetMapping
    public List<CategoryDto> list() {
        return catalogService.listCategories();
    }
}
