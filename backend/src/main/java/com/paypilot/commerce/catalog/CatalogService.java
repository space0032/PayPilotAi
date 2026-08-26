package com.paypilot.commerce.catalog;

import com.paypilot.common.money.CurrencyConverter;
import com.paypilot.common.error.BadRequestException;
import com.paypilot.common.error.NotFoundException;
import com.paypilot.commerce.catalog.api.dto.CategoryDto;
import com.paypilot.commerce.catalog.api.dto.PageResponse;
import com.paypilot.commerce.catalog.api.dto.ProductDetail;
import com.paypilot.commerce.catalog.api.dto.ProductSummary;
import com.paypilot.commerce.catalog.domain.Product;
import com.paypilot.commerce.catalog.repo.CategoryRepository;
import com.paypilot.commerce.catalog.repo.InventoryRepository;
import com.paypilot.commerce.catalog.repo.ProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/**
 * Read-side catalog logic shared by REST controllers and (later) the agent's
 * searchProducts tool. All client input is normalized here so both callers
 * inherit identical validation and escaping semantics.
 */
@Service
public class CatalogService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final Set<String> SORTS = Set.of("relevant", "price_asc", "price_desc");

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final InventoryRepository inventoryRepository;
    private final CurrencyConverter currencyConverter;

    public CatalogService(ProductRepository productRepository,
                          CategoryRepository categoryRepository,
                          InventoryRepository inventoryRepository,
                          CurrencyConverter currencyConverter) {
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
        this.inventoryRepository = inventoryRepository;
        this.currencyConverter = currencyConverter;
    }

    @Transactional(readOnly = true)
    public PageResponse<ProductSummary> listProducts(String term,
                                                     String categorySlug,
                                                     String minPrice,
                                                     String maxPrice,
                                                     String sort,
                                                     Integer page,
                                                     Integer size,
                                                     String currency) {
        String normalizedTerm = normalizeTerm(term);
        Long categoryId = resolveCategory(categorySlug);
        Long minPaise = parseRupees(minPrice, "minPrice");
        Long maxPaise = parseRupees(maxPrice, "maxPrice");
        if (minPaise != null && maxPaise != null && minPaise > maxPaise) {
            throw new BadRequestException("INVALID_PRICE_RANGE", "minPrice must not exceed maxPrice");
        }
        String sortKey = validateSort(sort);
        int safePage = page == null ? 0 : Math.max(0, page);
        int safeSize = size == null ? DEFAULT_PAGE_SIZE : Math.min(Math.max(1, size), MAX_PAGE_SIZE);

        String displayCurrency = resolveCurrency(currency);
        List<ProductSummary> items = productRepository
                .searchCatalog(normalizedTerm, categoryId, minPaise, maxPaise, sortKey,
                        safeSize, safePage * safeSize)
                .stream()
                .map(p -> toSummary(p, displayCurrency))
                .toList();
        long total = productRepository.countCatalog(normalizedTerm, categoryId, minPaise, maxPaise);
        return PageResponse.of(items, safePage, safeSize, total);
    }

    @Transactional(readOnly = true)
    public ProductDetail getProduct(String sku, String currency) {
        Product product = productRepository.findBySku(sku)
                .orElseThrow(() -> new NotFoundException("Product", sku));
        var category = categoryRepository.findById(product.getCategoryId())
                .map(c -> new CategoryDto(c.getId(), c.getName(), c.getSlug()))
                .orElse(null);
        int available = inventoryRepository.findById(product.getId())
                .map(inv -> inv.getAvailable())
                .orElse(0);
        String displayCurrency = resolveCurrency(currency);
        String effectiveCurrency = displayCurrency != null ? displayCurrency : product.getCurrency();
        return new ProductDetail(
                product.getId(),
                product.getSku(),
                product.getBrand(),
                product.getTitle(),
                product.getDescription(),
                displayPrice(product.getPricePaise(), product.getCurrency(), effectiveCurrency),
                effectiveCurrency,
                product.getRating(),
                product.getAttributes(),
                category,
                available);
    }

    @Transactional(readOnly = true)
    public List<CategoryDto> listCategories() {
        return categoryRepository.findAll().stream()
                .sorted((a, b) -> a.getName().compareToIgnoreCase(b.getName()))
                .map(c -> new CategoryDto(c.getId(), c.getName(), c.getSlug()))
                .toList();
    }

    private ProductSummary toSummary(Product p, String displayCurrency) {
        String effectiveCurrency = displayCurrency != null ? displayCurrency : p.getCurrency();
        return new ProductSummary(p.getId(), p.getSku(), p.getBrand(), p.getTitle(),
                displayPrice(p.getPricePaise(), p.getCurrency(), effectiveCurrency),
                effectiveCurrency,
                p.getRating());
    }

    /**
     * Convert a price from the product's native currency to the requested
     * display currency.  If the currencies match or the converter doesn't
     * support the pair, the original paise value is returned unchanged.
     */
    private BigDecimal displayPrice(long pricePaise, String from, String to) {
        if (to == null || from.equalsIgnoreCase(to)
                || !currencyConverter.supports(from, to)) {
            return BigDecimal.valueOf(pricePaise, 2);
        }
        long converted = currencyConverter.convert(pricePaise, from, to);
        return BigDecimal.valueOf(converted, 2);
    }

    private String resolveCurrency(String requested) {
        if (requested == null || requested.isBlank()) {
            return null;   // null = use product's native currency
        }
        String code = requested.trim().toUpperCase();
        if (code.length() != 3 || !code.matches("[A-Z]{3}")) {
            throw new BadRequestException("INVALID_CURRENCY",
                    "currency must be a valid 3-letter ISO 4217 code");
        }
        return code;
    }

    private BigDecimal rupees(long paise) {
        return BigDecimal.valueOf(paise, 2);
    }

    /**
     * Trims and length-caps the search term, then escapes LIKE metacharacters
     * so user input stays literal inside the ILIKE pattern.
     */
    private String normalizeTerm(String term) {
        if (term == null) {
            return null;
        }
        String trimmed = term.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > 100) {
            trimmed = trimmed.substring(0, 100);
        }
        return trimmed
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    private Long resolveCategory(String slug) {
        if (slug == null || slug.isBlank()) {
            return null;
        }
        return categoryRepository.findBySlug(slug.trim())
                .orElseThrow(() -> new NotFoundException("Category", slug))
                .getId();
    }

    /** Query-param prices are decimal rupees; storage is exact paise. */
    private Long parseRupees(String value, String param) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            BigDecimal rupees = new BigDecimal(value.trim());
            if (rupees.signum() < 0) {
                throw new NumberFormatException("negative");
            }
            return rupees.movePointRight(2).longValueExact();
        } catch (NumberFormatException | ArithmeticException e) {
            throw new BadRequestException("INVALID_PRICE",
                    "Parameter '" + param + "' must be a non-negative amount in rupees");
        }
    }

    private String validateSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return "relevant";
        }
        String key = sort.trim();
        if (!SORTS.contains(key)) {
            throw new BadRequestException("INVALID_SORT",
                    "sort must be one of: relevant, price_asc, price_desc");
        }
        return key;
    }
}
