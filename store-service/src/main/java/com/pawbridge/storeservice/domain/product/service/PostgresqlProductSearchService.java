package com.pawbridge.storeservice.domain.product.service;

import com.pawbridge.storeservice.domain.product.dto.ProductSearchRequest;
import com.pawbridge.storeservice.domain.product.dto.ProductSearchResponse;
import com.pawbridge.storeservice.domain.product.dto.ProductSearchItem;
import com.pawbridge.storeservice.search.KoreanSearchTerms;
import com.pawbridge.storeservice.search.SearchUnavailableException;
import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name="pawbridge.search.backend",havingValue="postgresql")
public class PostgresqlProductSearchService implements ProductSearch {
    private final NamedParameterJdbcTemplate jdbc;
    private final KoreanSearchTerms terms;
    public PostgresqlProductSearchService(DataSource source,KoreanSearchTerms terms) {
        this.jdbc=new NamedParameterJdbcTemplate(source);this.terms=terms;
        this.jdbc.getJdbcTemplate().setQueryTimeout(5);
    }
    @Override public ProductSearchResponse searchProducts(ProductSearchRequest request) {
        int page=request.getPage()==null?0:request.getPage(),size=request.getSize()==null?20:request.getSize();
        if(page<0 || size<1 || size>100 || (long)page*size>100_000L)
            throw new IllegalArgumentException("검색 페이지 범위를 확인해 주세요.");
        if(request.getMinPrice()!=null && request.getMinPrice()<0 || request.getMaxPrice()!=null && request.getMaxPrice()<0
            || request.getMinPrice()!=null && request.getMaxPrice()!=null && request.getMinPrice()>request.getMaxPrice())
            throw new IllegalArgumentException("가격 범위를 확인해 주세요.");
        String order=switch(request.getSortBy()==null?"skuId":request.getSortBy()) {
            case "skuId" -> "sku_id";
            case "price","minPrice" -> "price";
            case "createdAt" -> "created_at";
            default -> throw new IllegalArgumentException("지원하지 않는 정렬입니다.");
        };
        String direction=request.getSortOrder()==null?"desc":request.getSortOrder();
        if(!direction.equalsIgnoreCase("asc") && !direction.equalsIgnoreCase("desc"))
            throw new IllegalArgumentException("지원하지 않는 정렬 방향입니다.");
        String query=terms.query(request.getKeyword());
        if(query.isEmpty() && request.getKeyword()!=null && !request.getKeyword().isBlank())
            return response(List.of(),0,page,size);
        Map<String,Object> params=new HashMap<>(Map.of("offset",(long)page*size,"size",size,"query",query,"version",KoreanSearchTerms.VERSION));
        List<String> filters=new ArrayList<>(List.of("s.status='ACTIVE'"));
        if(request.getCategoryId()!=null) { filters.add("s.category_id=:category");params.put("category",request.getCategoryId()); }
        if(request.getMinPrice()!=null) { filters.add("s.price>=:min");params.put("min",request.getMinPrice()); }
        if(request.getMaxPrice()!=null) { filters.add("s.price<=:max");params.put("max",request.getMaxPrice()); }
        if(Boolean.TRUE.equals(request.getInStockOnly())) filters.add("s.total_stock>0");
        if(!query.isEmpty()) filters.add("d.tokens @@ to_tsquery('simple',:query)");
        String readiness=query.isEmpty()?"true":"""
                NOT EXISTS(SELECT 1 FROM product_search_source s LEFT JOIN product_search_documents d ON d.product_id=s.product_id
                    WHERE s.status='ACTIVE' AND (d.product_id IS NULL OR d.sku_id<>s.sku_id OR d.source_name<>s.name
                        OR d.source_option<>s.option_name OR d.analyzer_version<>:version))
                """;
        String sort=order+" "+direction.toUpperCase(java.util.Locale.ROOT)+" NULLS LAST,sku_id ASC";
        // All interpolated SQL fragments come from fixed code; user values are bound parameters.
        String sql="WITH readiness AS (SELECT "+readiness+" AS ready), matched AS ("
                +"SELECT s.* FROM product_search_source s LEFT JOIN product_search_documents d ON d.product_id=s.product_id WHERE "
                +String.join(" AND ",filters)+"), total AS (SELECT count(*) AS total_count FROM matched), sliced AS ("
                +"SELECT * FROM matched ORDER BY "+sort+" LIMIT :size OFFSET :offset) "
                +"SELECT readiness.ready,total.total_count,sliced.* FROM readiness CROSS JOIN total LEFT JOIN sliced ON readiness.ready ORDER BY "+sort;
        try {
            return jdbc.query(sql,params,rs -> {
                List<ProductSearchItem> items=new ArrayList<>();long count=0;
                while(rs.next()) {
                    if(!rs.getBoolean("ready")) throw new SearchUnavailableException();
                    count=rs.getLong("total_count");
                    if(rs.getObject("product_id")==null) continue;
                    items.add(ProductSearchItem.builder().id(rs.getLong("product_id")).skuId(rs.getLong("sku_id"))
                        .name(rs.getString("name")).description(rs.getString("name")).optionName(rs.getString("option_name"))
                        .imageUrl(rs.getString("image_url")).status(rs.getString("status")).price(rs.getLong("price"))
                        .totalStock(Math.toIntExact(rs.getLong("total_stock"))).createdAt(rs.getObject("created_at",java.time.LocalDateTime.class))
                        .updatedAt(rs.getObject("updated_at",java.time.LocalDateTime.class)).build());
                }
                return response(items,count,page,size);
            });
        } catch(org.springframework.dao.DataAccessException failure) { throw new SearchUnavailableException(); }
    }
    private ProductSearchResponse response(List<ProductSearchItem> items,long count,int page,int size) {
        int pages=Math.toIntExact((count+size-1)/size);
        return ProductSearchResponse.builder().items(items).totalCount(count).currentPage(page).totalPages(pages).hasNext((long)page+1<pages).build();
    }
}
