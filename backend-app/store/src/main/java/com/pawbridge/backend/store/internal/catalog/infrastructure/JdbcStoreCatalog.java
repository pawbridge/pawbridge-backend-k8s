package com.pawbridge.backend.store.internal.catalog.infrastructure;

import com.pawbridge.backend.store.internal.catalog.application.CatalogSearchUnavailableException;
import com.pawbridge.backend.store.internal.catalog.application.StoreCatalog;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.support.TransactionOperations;

/** Explicitly assembled by Boot with the Store reader and a read-only transaction template. */
public final class JdbcStoreCatalog implements StoreCatalog, AutoCloseable {
    private static final String PRODUCT = """
            SELECT p.id AS product_id,p.name,p.description,p.image_url,p.status,p.category_id,
                   c.name AS category_name,p.view_count,p.created_at,p.updated_at
            FROM pawbridge_store.products p LEFT JOIN pawbridge_store.categories c ON c.id=p.category_id
            WHERE p.id=:id
            """;
    private static final String SKUS = """
            SELECT s.id AS sku_id,s.sku_code,s.price,s.stock_quantity,
                   v.id AS option_value_id,g.name AS group_name,v.name AS value_name
            FROM pawbridge_store.product_skus s
            LEFT JOIN pawbridge_store.sku_values sv ON sv.product_sku_id=s.id
            LEFT JOIN pawbridge_store.option_values v ON v.id=sv.option_value_id
            LEFT JOIN pawbridge_store.option_groups g ON g.id=v.option_group_id
            WHERE s.product_id=:id
            """;
    private static final String CATEGORIES = """
            SELECT id,name,description,parent_id FROM pawbridge_store.categories
            """;
    private static final String OPTIONS = """
            SELECT g.id AS group_id,g.name AS group_name,v.id AS value_id,v.name AS value_name
            FROM pawbridge_store.option_groups g
            LEFT JOIN pawbridge_store.option_values v ON v.option_group_id=g.id
            """;
    private final NamedParameterJdbcOperations jdbc;
    private final TransactionOperations transactions;
    private final KoreanSearchTerms terms = new KoreanSearchTerms();

    public JdbcStoreCatalog(NamedParameterJdbcOperations jdbc, TransactionOperations transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    @Override
    public SearchResult search(Search request) {
        int page=request.page()==null?0:request.page(),size=request.size()==null?20:request.size();
        if(page<0 || size<1 || size>100 || (long)page*size>100_000L)
            throw new IllegalArgumentException("검색 페이지 범위를 확인해 주세요.");
        if(request.minPrice()!=null && request.minPrice()<0 || request.maxPrice()!=null && request.maxPrice()<0
            || request.minPrice()!=null && request.maxPrice()!=null && request.minPrice()>request.maxPrice())
            throw new IllegalArgumentException("가격 범위를 확인해 주세요.");
        String order=switch(request.sortBy()==null?"skuId":request.sortBy()) {
            case "skuId" -> "sku_id";
            case "price","minPrice" -> "price";
            case "createdAt" -> "created_at";
            default -> throw new IllegalArgumentException("지원하지 않는 정렬입니다.");
        };
        String direction=request.sortOrder()==null?"desc":request.sortOrder();
        if(!direction.equalsIgnoreCase("asc") && !direction.equalsIgnoreCase("desc"))
            throw new IllegalArgumentException("지원하지 않는 정렬 방향입니다.");
        String query=terms.query(request.keyword());
        if(query.isEmpty() && request.keyword()!=null && !request.keyword().isBlank())
            return response(List.of(),0,page,size);
        Map<String,Object> params=new HashMap<>(Map.of("offset",(long)page*size,"size",size,"query",query,"version",KoreanSearchTerms.VERSION));
        List<String> filters=new ArrayList<>(List.of("s.status='ACTIVE'"));
        if(request.categoryId()!=null) { filters.add("s.category_id=:category");params.put("category",request.categoryId()); }
        if(request.minPrice()!=null) { filters.add("s.price>=:min");params.put("min",request.minPrice()); }
        if(request.maxPrice()!=null) { filters.add("s.price<=:max");params.put("max",request.maxPrice()); }
        if(Boolean.TRUE.equals(request.inStockOnly())) filters.add("s.total_stock>0");
        if(!query.isEmpty()) filters.add("d.tokens @@ to_tsquery('simple',:query)");
        String readiness=query.isEmpty()?"true":"""
                NOT EXISTS(SELECT 1 FROM pawbridge_store.product_search_source s LEFT JOIN pawbridge_store.product_search_documents d ON d.product_id=s.product_id
                    WHERE s.status='ACTIVE' AND (d.product_id IS NULL OR d.sku_id<>s.sku_id OR d.source_name<>s.name
                        OR d.source_option<>s.option_name OR d.analyzer_version<>:version))
                """;
        String sort=order+" "+direction.toUpperCase(Locale.ROOT)+" NULLS LAST,sku_id ASC";
        // Only fixed fragments enter SQL; user values remain named parameters. This is the original Store CTE.
        String sql="WITH readiness AS (SELECT "+readiness+" AS ready), matched AS ("
                +"SELECT s.* FROM pawbridge_store.product_search_source s LEFT JOIN pawbridge_store.product_search_documents d ON d.product_id=s.product_id WHERE "
                +String.join(" AND ",filters)+"), total AS (SELECT count(*) AS total_count FROM matched), sliced AS ("
                +"SELECT * FROM matched ORDER BY "+sort+" LIMIT :size OFFSET :offset) "
                +"SELECT readiness.ready,total.total_count,sliced.* FROM readiness CROSS JOIN total LEFT JOIN sliced ON readiness.ready ORDER BY "+sort;
        try {
            return transactions.execute(status -> jdbc.query(sql,params,rs -> {
                List<Item> items=new ArrayList<>();long count=0;
                while(rs.next()) {
                    if(!rs.getBoolean("ready")) throw new CatalogSearchUnavailableException();
                    count=rs.getLong("total_count");
                    if(rs.getObject("product_id")==null) continue;
                    items.add(new Item(rs.getLong("product_id"),rs.getLong("sku_id"),rs.getString("name"),
                        rs.getString("option_name"),rs.getString("name"),rs.getString("image_url"),
                        rs.getLong("price"),Math.toIntExact(rs.getLong("total_stock")),rs.getString("status"),
                        rs.getObject("created_at",LocalDateTime.class),rs.getObject("updated_at",LocalDateTime.class)));
                }
                return response(items,count,page,size);
            }));
        } catch(DataAccessException | CannotCreateTransactionException | TransactionTimedOutException failure) {
            throw new CatalogSearchUnavailableException(failure);
        }
    }

    private SearchResult response(List<Item> items,long count,int page,int size) {
        int pages=Math.toIntExact((count+size-1)/size);
        return new SearchResult(items,count,page,pages,(long)page+1<pages);
    }

    @Override
    public Product product(long productId) {
        return transactions.execute(status -> {
            List<Product> products = jdbc.query(PRODUCT,Map.of("id",productId),(rs,n) -> new Product(
                    rs.getLong("product_id"),rs.getString("name"),rs.getString("description"),
                    rs.getString("image_url"),rs.getString("status"),rs.getObject("category_id",Long.class),
                    rs.getString("category_name"),rs.getObject("view_count",Long.class),
                    rs.getObject("created_at",LocalDateTime.class),rs.getObject("updated_at",LocalDateTime.class),List.of()));
            if(products.isEmpty()) throw new IllegalArgumentException("Product not found: " + productId);
            Product product = products.getFirst();
            List<Sku> skus = jdbc.query(SKUS,Map.of("id",productId),rs -> {
                Map<Long,Sku> result = new LinkedHashMap<>();
                while(rs.next()) {
                    long id = rs.getLong("sku_id");
                    Sku sku = result.get(id);
                    if(sku == null) {
                        sku = new Sku(id,rs.getString("sku_code"),rs.getLong("price"),
                                rs.getInt("stock_quantity"),new HashMap<>());
                        result.put(id,sku);
                    }
                    if(rs.getObject("option_value_id") != null) {
                        sku.options().put(rs.getString("group_name"),rs.getString("value_name"));
                    }
                }
                return new ArrayList<>(result.values());
            });
            return new Product(product.productId(),product.name(),product.description(),product.imageUrl(),
                    product.status(),product.categoryId(),product.categoryName(),product.viewCount(),
                    product.createdAt(),product.updatedAt(),skus);
        });
    }

    private record CategoryRow(Long id,String name,String description,Long parentId) { }

    @Override
    public List<Category> categories() {
        return transactions.execute(status -> {
            List<CategoryRow> rows = jdbc.query(CATEGORIES,Map.of(),(rs,n) -> new CategoryRow(
                    rs.getLong("id"),rs.getString("name"),rs.getString("description"),rs.getObject("parent_id",Long.class)));
            return rows.stream().filter(row -> row.parentId()==null).map(row -> category(row,rows)).toList();
        });
    }

    private Category category(CategoryRow row,List<CategoryRow> rows) {
        return new Category(row.id(),row.name(),row.description(),row.parentId(),rows.stream()
                .filter(child -> row.id().equals(child.parentId())).map(child -> category(child,rows)).toList());
    }

    @Override
    public List<OptionGroup> optionGroups() {
        return transactions.execute(status -> readOptions(OPTIONS,Map.of()));
    }

    @Override
    public OptionGroup optionGroup(long groupId) {
        return transactions.execute(status -> {
            List<OptionGroup> groups = readOptions(OPTIONS + " WHERE g.id=:id",Map.of("id",groupId));
            if(groups.isEmpty()) throw new IllegalArgumentException("옵션 그룹을 찾을 수 없습니다: " + groupId);
            return groups.getFirst();
        });
    }

    private List<OptionGroup> readOptions(String sql,Map<String,?> parameters) {
        return jdbc.query(sql,parameters,rs -> {
            Map<Long,OptionGroup> groups = new LinkedHashMap<>();
            while(rs.next()) {
                long id = rs.getLong("group_id");
                OptionGroup group = groups.get(id);
                if(group == null) {
                    group = new OptionGroup(id,rs.getString("group_name"),new ArrayList<>());
                    groups.put(id,group);
                }
                if(rs.getObject("value_id") != null) {
                    group.values().add(new OptionValue(rs.getLong("value_id"),rs.getString("value_name"),id,group.name()));
                }
            }
            return new ArrayList<>(groups.values());
        });
    }

    @Override public void close() { terms.close(); }
}
