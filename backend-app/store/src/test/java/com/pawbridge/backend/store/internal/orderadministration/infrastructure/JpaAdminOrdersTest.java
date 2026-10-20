package com.pawbridge.backend.store.internal.orderadministration.infrastructure;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.pawbridge.backend.store.api.StoreOrderAdministrators;
import com.pawbridge.backend.store.internal.orderadministration.application.*;
import com.pawbridge.backend.store.internal.directorder.infrastructure.jpa.repository.OrderRepository;
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.Order;
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.OrderItem;
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.DeliveryStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.criteria.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.*;

class JpaAdminOrdersTest {
    final EntityManager em=mock(EntityManager.class);
    final OrderRepository orders=mock(OrderRepository.class);
    final StoreOrderAdministrators administrators=mock(StoreOrderAdministrators.class);
    final List<String> events=new ArrayList<>();
    final TransactionTemplate writes=new TransactionTemplate(new Manager("write")),reads=new TransactionTemplate(new Manager("read"));
    final JpaAdminOrders service=new JpaAdminOrders(em,orders,writes,reads,administrators);
    final AdminOrders.Query query=new AdminOrders.Query(null,null,null,null,"createdAt","desc",0,20);
    StoreOrderAdministrators.Actor actor=new StoreOrderAdministrators.Actor(7,true,false,"ROLE_ADMIN");
    Order order;
    String failure="";
    @BeforeEach void setup() {
        reads.setReadOnly(true);
        var entity=Order.builder().userId(88L).orderUuid("stored-uuid").totalAmount(9007199254740993L)
                .receiverName("stored receiver").receiverPhone("stored phone").deliveryAddress("stored address").deliveryMessage("stored message").build();
        ReflectionTestUtils.setField(entity,"id",33L);ReflectionTestUtils.setField(entity,"createdAt",LocalDateTime.of(2026,10,21,2,0,1,123456789));
        entity.getOrderItems().add(OrderItem.builder().order(entity).productName("stored product").skuCode("STORED-SKU").price(9007199254740993L).quantity(1).build());
        order=spy(entity);
        doAnswer(call->{assertTrue(TransactionSynchronizationManager.isActualTransactionActive());events.add("map");return call.callRealMethod();}).when(order).getOrderItems();
        when(administrators.currentForRead(7)).thenAnswer(call->{active(true);events.add("actor:read");return actor;});
        when(administrators.currentForWrite(7)).thenAnswer(call->{active(false);events.add("actor:write");return actor;});
        doAnswer(call->{active(true);events.add("list");if(failure.equals("query"))throw problem();return new PageImpl<>(List.of(order),call.getArgument(1),1);})
                .when(orders).findAll(any(Specification.class),any(org.springframework.data.domain.Pageable.class));
        when(orders.findById(33L)).thenAnswer(call->{active(true);events.add("get");if(failure.equals("query"))throw problem();return Optional.of(order);});
        when(em.find(Order.class,33L,LockModeType.PESSIMISTIC_WRITE)).thenAnswer(call->{active(false);events.add("order-lock");if(failure.equals("query"))throw problem();return order;});
        doAnswer(call->{active(false);events.add("flush");if(order.getDeliveryStatus()==null || failure.equals("flush"))throw new PersistenceException("synthetic constraint failure");return null;}).when(em).flush();
    }
    @AfterEach void close(){assertFalse(TransactionSynchronizationManager.isActualTransactionActive());}
    static DataAccessResourceFailureException problem(){return new DataAccessResourceFailureException("synthetic query failure");}
    void active(boolean readOnly){assertTrue(TransactionSynchronizationManager.isActualTransactionActive());assertEquals(readOnly,TransactionSynchronizationManager.isCurrentTransactionReadOnly());}
    void access(String op){switch(op){case "list"->service.list(7,query);case "get"->service.get(7,33);default->service.updateDelivery(7,33,"SHIPPING");}}

    @Test void given__current_database_admin__when__reading_other_members_order__then__preserve_full_snapshot_inside_read_only_transaction() {
        var result=service.get(7,33);assertEquals(33L,result.orderId());assertEquals(88L,result.userId());assertEquals(9007199254740993L,result.totalAmount());
        assertEquals("stored-uuid",result.orderUuid());assertEquals("PENDING",result.status());assertEquals("READY",result.deliveryStatus());
        assertEquals("stored receiver",result.receiverName());assertEquals("stored phone",result.receiverPhone());assertEquals("stored address",result.deliveryAddress());assertEquals("stored message",result.deliveryMessage());
        assertEquals(9007199254740993L,result.items().getFirst().price());assertEquals("stored product",result.items().getFirst().productName());
        assertEquals(List.of("begin:read","actor:read","get","map","commit:read"),events);verifyNoInteractions(em);verify(administrators,never()).currentForWrite(anyLong());
    }
    @ParameterizedTest @ValueSource(strings={"READY","SHIPPING","DELIVERED"})
    void given__admin_and_locked_order__when__changing_delivery__then__only_delivery_changes_after_user_then_order_lock_and_flush(String delivery) {
        var result=service.updateDelivery(7,33,delivery);assertEquals(delivery,result.deliveryStatus());assertEquals("PENDING",result.status());assertEquals(9007199254740993L,result.totalAmount());
        assertEquals(88L,result.userId());assertEquals("stored-uuid",result.orderUuid());assertEquals("stored address",result.deliveryAddress());assertEquals(1,result.items().size());
        assertEquals(List.of("begin:write","actor:write","order-lock","flush","map","commit:write"),events);
        verify(em).find(Order.class,33L,LockModeType.PESSIMISTIC_WRITE);verify(em).flush();verifyNoMoreInteractions(em);verifyNoInteractions(orders);verify(administrators,never()).currentForRead(anyLong());
    }
    static Stream<Arguments> invalidActors() {return Stream.of("null","wrong-id","inactive","deleted","user","shelter","lowercase","space","prefix","no-role")
            .flatMap(state->Stream.of("list","get","write").map(op->Arguments.of(state,op)));}
    @ParameterizedTest @MethodSource("invalidActors")
    void given__ineligible_or_nonexact_current_database_admin__when__using_any_operation__then__deny_before_order_rows(String state,String op) {
        actor=switch(state){case "null"->null;case "wrong-id"->new StoreOrderAdministrators.Actor(8,true,false,"ROLE_ADMIN");case "inactive"->new StoreOrderAdministrators.Actor(7,false,false,"ROLE_ADMIN");
            case "deleted"->new StoreOrderAdministrators.Actor(7,true,true,"ROLE_ADMIN");default->new StoreOrderAdministrators.Actor(7,true,false,switch(state){case "user"->"ROLE_USER";case "shelter"->"ROLE_SHELTER";case "lowercase"->"role_admin";case "space"->"ROLE_ADMIN ";case "prefix"->"ROLE_ADMIN_MORE";default->null;});};
        assertThrows(AdministratorRejectedException.class,()->access(op));verifyNoInteractions(em,orders);assertEquals("rollback:"+(op.equals("write")?"write":"read"),events.getLast());
    }
    @ParameterizedTest @ValueSource(strings={"id","userId","orderUuid","totalAmount","status","deliveryAddress","deliveryStatus","receiverName","receiverPhone","deliveryMessage","createdAt","updatedAt"})
    void given__existing_root_scalar_sort__when__listing__then__original_repository_spec_and_page_metadata_inside_read_transaction(String property) {
        var input=new AdminOrders.Query(null,null,null,null,property,"ASC",0,20);var result=service.list(7,input);
        assertEquals(1,result.content().size());assertEquals(1,result.totalElements());assertEquals(20,result.size());assertEquals(0,result.number());assertEquals(property,result.sortBy());assertTrue(result.ascending());
        verify(orders).findAll(any(Specification.class),eq(PageRequest.of(0,20,Sort.by(Sort.Direction.ASC,property))));verifyNoInteractions(em);assertEquals(List.of("begin:read","actor:read","list","map","commit:read"),events);
    }
    static Stream<Arguments> badQueries(){return Stream.concat(Stream.of("orderItems.price","orderItemsPrice","orderItems.order.orderItems.price","user.id","sum(totalAmount)","id DESC"," ",null)
            .map(sort->Arguments.of(new AdminOrders.Query(null,null,null,null,sort,"asc",0,20))),Stream.of(
                    Arguments.of(new AdminOrders.Query(null,null,null,null,"id","desc",-1,20)),Arguments.of(new AdminOrders.Query(null,null,null,null,"id","desc",0,0)),Arguments.of(new AdminOrders.Query(null,null,null,null,"id","desc",0,101))));}
    @ParameterizedTest @MethodSource("badQueries")
    void given__unsupported_sort_or_page_resource_bound__when__listing__then__fixed_bad_input_before_transaction_or_admin_lookup(AdminOrders.Query input) {
        var failure=assertThrows(IllegalArgumentException.class,()->service.list(7,input));assertEquals("Admin order query is not supported",failure.getMessage());assertTrue(events.isEmpty());verifyNoInteractions(em,orders,administrators);
    }
    @ParameterizedTest @ValueSource(strings={"ASC","desc","anything"})
    void given__original_sort_order_text__when__listing_at_size_bound__then__only_asc_means_ascending(String direction) {
        var result=service.list(7,new AdminOrders.Query(null,null,null,null,"id",direction,0,100));assertEquals("ASC".equals(direction),result.ascending());assertEquals(100,result.size());
    }
    @Test void given__all_filters_and_wildcard_keyword__when__building_specification__then__preserve_original_fields_and_unescaped_like_pattern() {
        service.list(7,new AdminOrders.Query("PAID","SHIPPING",99L," A%_ B ","createdAt","desc",0,20));
        ArgumentCaptor<Specification<Order>> captor=ArgumentCaptor.forClass(Specification.class);verify(orders).findAll(captor.capture(),any(org.springframework.data.domain.Pageable.class));
        Root<Order> root=mock(Root.class);CriteriaQuery<?> query=mock(CriteriaQuery.class);CriteriaBuilder cb=mock(CriteriaBuilder.class);Predicate predicate=mock(Predicate.class);
        Path status=mock(Path.class),delivery=mock(Path.class),user=mock(Path.class),uuid=mock(Path.class),receiver=mock(Path.class);
        when(root.get("status")).thenReturn(status);when(root.get("deliveryStatus")).thenReturn(delivery);when(root.get("userId")).thenReturn(user);
        when(root.get("orderUuid")).thenReturn(uuid);when(root.get("receiverName")).thenReturn(receiver);
        when(cb.equal(any(Expression.class),any())).thenReturn(predicate);when(cb.lower(any())).thenAnswer(call->call.getArgument(0));when(cb.like(any(Expression.class),anyString())).thenReturn(predicate);
        when(cb.and(any(Expression.class),any(Expression.class))).thenReturn(predicate);when(cb.or(any(Expression.class),any(Expression.class))).thenReturn(predicate);
        captor.getValue().toPredicate(root,query,cb);
        verify(cb).equal(status,com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.OrderStatus.PAID);
        verify(cb).equal(delivery,DeliveryStatus.SHIPPING);verify(cb).equal(user,99L);verify(cb).like(uuid,"% a%_ b %");verify(cb).like(receiver,"% a%_ b %");
    }
    @Test void given__blank_keyword__when__building_specification__then__do_not_add_like_predicates() {
        service.list(7,new AdminOrders.Query(null,null,null,"  ","id","desc",0,20));ArgumentCaptor<Specification<Order>> captor=ArgumentCaptor.forClass(Specification.class);
        verify(orders).findAll(captor.capture(),any(org.springframework.data.domain.Pageable.class));Root<Order> root=mock(Root.class);CriteriaBuilder cb=mock(CriteriaBuilder.class);
        assertNull(captor.getValue().toPredicate(root,mock(CriteriaQuery.class),cb));verifyNoInteractions(root,cb);
    }
    @Test void given__missing_order__when__reading_and_updating__then__distinct_original_404_and_400_families() {
        doReturn(Optional.empty()).when(orders).findById(33L);doReturn(null).when(em).find(Order.class,33L,LockModeType.PESSIMISTIC_WRITE);
        assertThrows(AdminOrderNotFoundException.class,()->service.get(7,33));assertThrows(IllegalArgumentException.class,()->service.updateDelivery(7,33,"SHIPPING"));verify(em,never()).flush();
    }
    @Test void given__null_delivery_status__when__updating__then__generic_failure_after_actor_and_order_lock_without_update_or_flush() {
        var failure=assertThrows(IllegalStateException.class,()->service.updateDelivery(7,33,null));
        assertEquals("Delivery status cannot be stored",failure.getMessage());assertEquals(DeliveryStatus.READY,order.getDeliveryStatus());
        assertEquals(List.of("begin:write","actor:write","order-lock","rollback:write"),events);
        verify(order,never()).updateDeliveryStatus(nullable(DeliveryStatus.class));verify(em,never()).flush();
    }
    static Stream<Arguments> failures(){return Stream.of("list","get","write").flatMap(op->Stream.of("query","commit").map(stage->Arguments.of(op,stage)));}
    @ParameterizedTest @MethodSource("failures")
    void given__query_or_commit_failure__when__administering_orders__then__propagate_without_success(String op,String stage) {
        failure=stage;Class<? extends RuntimeException> type=stage.equals("commit")?TransactionSystemException.class:DataAccessResourceFailureException.class;
        assertThrows(type,()->access(op));
    }
    @Test void given__flush_failure__when__updating_delivery__then__propagate_and_request_rollback() {failure="flush";assertThrows(PersistenceException.class,()->service.updateDelivery(7,33,"DELIVERED"));assertEquals("rollback:write",events.getLast());}
    static Stream<Arguments> txModes(){return Stream.of("list","get","write").flatMap(op->Stream.of("none","wrong").map(kind->Arguments.of(op,kind)));}
    @ParameterizedTest @MethodSource("txModes")
    void given__missing_or_wrong_transaction__when__administering_orders__then__fail_before_admin_or_order_lookup(String op,String kind) {
        TransactionOperations w=writes,r=reads;
        if(kind.equals("none")){w=TransactionOperations.withoutTransaction();r=w;}else {writes.setReadOnly(true);reads.setReadOnly(false);}
        var service=new JpaAdminOrders(em,orders,w,r,administrators);
        assertThrows(IllegalStateException.class,()->{switch(op){case "list"->service.list(7,query);case "get"->service.get(7,33);default->service.updateDelivery(7,33,"SHIPPING");}});verifyNoInteractions(em,orders,administrators);
    }
    final class Manager extends AbstractPlatformTransactionManager {
        final String name;Manager(String name){this.name=name;}
        protected Object doGetTransaction(){return new Object();}
        protected void doBegin(Object tx,TransactionDefinition definition){events.add("begin:"+name);}
        protected void doCommit(DefaultTransactionStatus status){events.add("commit:"+name);if(failure.equals("commit"))throw new TransactionSystemException("synthetic commit failure");}
        protected void doRollback(DefaultTransactionStatus status){events.add("rollback:"+name);}
    }
}
