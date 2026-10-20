package com.pawbridge.backend.store.internal.directorder.infrastructure;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.pawbridge.backend.store.api.StoreOrderActors;
import com.pawbridge.backend.store.internal.catalog.infrastructure.KoreanSearchTerms;
import com.pawbridge.backend.store.internal.directorder.application.DirectOrders;
import com.pawbridge.backend.store.internal.directorder.infrastructure.jpa.model.Outbox;
import com.pawbridge.backend.store.internal.directorder.infrastructure.jpa.repository.*;
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.*;
import java.time.*;
import java.util.*;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.*;

final class DirectOrderFixture implements AutoCloseable {
    final List<String> events=new ArrayList<>();
    final Manager writer=new Manager("writer"),reader=new Manager("reader");
    final TransactionTemplate write=new TransactionTemplate(writer),read=new TransactionTemplate(reader);
    final OrderRepository writerOrders=mock(OrderRepository.class),readerOrders=mock(OrderRepository.class);
    final ProductRepository products=mock(ProductRepository.class);
    final ProductSKURepository skus=mock(ProductSKURepository.class);
    final OutboxRepository outbox=mock(OutboxRepository.class);
    final NamedParameterJdbcOperations jdbc=mock(NamedParameterJdbcOperations.class);
    final StoreOrderActors actors=mock(StoreOrderActors.class);
    final KoreanSearchTerms terms=new KoreanSearchTerms();
    final ObjectMapper json=new ObjectMapper().registerModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    final Product product=Product.builder().name("fixture product").status(ProductStatus.ACTIVE).build();
    final ProductSKU target=ProductSKU.builder().product(product).skuCode("CURRENT-80").price(100L).stockQuantity(5).build();
    final ProductSKU sibling=ProductSKU.builder().product(product).skuCode("CURRENT-81").price(50L).stockQuantity(8).build();
    final List<Outbox> snapshots=new ArrayList<>();
    final Map<Long,Order> orders=new LinkedHashMap<>();
    final List<Map<String,?>> searchParameters=new ArrayList<>();
    final LocalDateTime time=LocalDateTime.of(2026,10,20,22,0,1,123456789);
    final ProductSnapshots publisher;
    final JpaStockAdjustments stocks;
    final JpaDirectOrders service;
    StoreOrderActors.Actor actor=new StoreOrderActors.Actor(7,true,false);
    String failAt="";
    int outboxCalls;
    long identity=33;
    DirectOrderFixture() {
        write.setName("writer");read.setName("reader");read.setReadOnly(true);
        id(product,90);id(target,80);id(sibling,81);product.getSkus().addAll(List.of(target,sibling));
        ReflectionTestUtils.setField(product,"createdAt",time);
        var reference=mock(ProductSKURepository.SkuProductId.class);when(reference.getSkuId()).thenReturn(80L);when(reference.getProductId()).thenReturn(90L);
        when(actors.currentForWrite(7)).thenAnswer(call->{writer();events.add("actor:write");return actor;});
        when(actors.currentForRead(7)).thenAnswer(call->{reader();events.add("actor:read");return actor;});
        when(skus.findProductIdsBySkuIdIn(anyCollection())).thenAnswer(call->{writer();events.add("reference");return List.of(reference);});
        when(products.findByIdWithLock(90L)).thenAnswer(call->{writer();events.add("product-lock");return Optional.of(product);});
        when(skus.findAllByProductIdWithLock(90L)).thenAnswer(call->{writer();events.add("all-sku-lock");return List.of(target,sibling);});
        when(skus.findById(80L)).thenAnswer(call->{writer();events.add("sku-read");return Optional.of(target);});
        when(jdbc.update(anyString(),anyMap())).thenAnswer(call->{writer();events.add("search");if(failAt.equals("search"))throw failure();searchParameters.add(new HashMap<>(call.getArgument(1)));return 1;});
        when(outbox.save(any(Outbox.class))).thenAnswer(call->{writer();Outbox row=call.getArgument(0);events.add("outbox:"+row.getAggregateId());outboxCalls++;if(failAt.equals("outbox"+outboxCalls))throw failure();snapshots.add(row);return row;});
        when(writerOrders.save(any(Order.class))).thenAnswer(call->{writer();events.add("order-save");if(failAt.equals("order"))throw failure();Order order=call.getArgument(0);id(order,identity++);ReflectionTestUtils.setField(order,"createdAt",time);orders.put(order.getId(),order);return order;});
        when(readerOrders.findById(anyLong())).thenAnswer(call->{reader();events.add("order-read");return Optional.ofNullable(orders.get(call.getArgument(0)));});
        publisher=new ProductSnapshots(outbox,json,new PostgresqlSearchDocuments(jdbc,terms),Clock.fixed(time.toInstant(ZoneOffset.ofHours(9)),ZoneId.of("Asia/Seoul")));
        stocks=new JpaStockAdjustments(products,skus,publisher);
        service=new JpaDirectOrders(writerOrders,readerOrders,skus,stocks,actors,write,read);
    }
    DirectOrders.Command command(int quantity) {return new DirectOrders.Command(80L,quantity,"fixture receiver","fixture phone","fixture address","fixture message");}
    void writer() {assertTrue(TransactionSynchronizationManager.isActualTransactionActive());assertFalse(TransactionSynchronizationManager.isCurrentTransactionReadOnly());assertEquals("writer",TransactionSynchronizationManager.getCurrentTransactionName());}
    void reader() {assertTrue(TransactionSynchronizationManager.isActualTransactionActive());assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly());assertEquals("reader",TransactionSynchronizationManager.getCurrentTransactionName());}
    static void id(Object entity,long id) {ReflectionTestUtils.setField(entity,"id",id);}
    static DataAccessResourceFailureException failure() {return new DataAccessResourceFailureException("synthetic persistence failure");}
    public void close() {terms.close();assertFalse(TransactionSynchronizationManager.isActualTransactionActive());}
    final class Manager extends AbstractPlatformTransactionManager {
        final String name;
        Manager(String name){this.name=name;}
        protected Object doGetTransaction(){return new Object();}
        protected void doBegin(Object tx,TransactionDefinition definition){events.add("begin:"+name);}
        protected void doCommit(DefaultTransactionStatus status){events.add("commit:"+name);if(failAt.equals("commit"))throw new TransactionSystemException("synthetic commit failure");}
        protected void doRollback(DefaultTransactionStatus status){events.add("rollback:"+name);}
    }
}
