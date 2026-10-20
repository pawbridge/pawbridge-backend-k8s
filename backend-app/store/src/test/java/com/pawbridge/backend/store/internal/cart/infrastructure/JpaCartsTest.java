package com.pawbridge.backend.store.internal.cart.infrastructure;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.pawbridge.backend.store.api.StoreOrderActors;
import com.pawbridge.backend.store.internal.cart.application.CartActorRejectedException;
import com.pawbridge.backend.store.internal.cart.application.Carts;
import com.pawbridge.backend.store.internal.cart.infrastructure.jpa.model.Cart;
import com.pawbridge.backend.store.internal.cart.infrastructure.jpa.model.CartItem;
import com.pawbridge.backend.store.internal.cart.infrastructure.jpa.repository.CartRepository;
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.Product;
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.ProductSKU;
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.ProductStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.*;

class JpaCartsTest {
    final EntityManager em=mock(EntityManager.class);
    final CartRepository repository=mock(CartRepository.class);
    final StoreOrderActors actors=mock(StoreOrderActors.class);
    final List<String> events=new ArrayList<>();
    final TransactionTemplate write=new TransactionTemplate(new Manager(false)),read=new TransactionTemplate(new Manager(true));
    final JpaCarts carts=new JpaCarts(em,repository,write,read,actors);
    final Product product=Product.builder().name("live product").imageUrl("unused image").status(ProductStatus.HIDDEN).build();
    final ProductSKU sku=ProductSKU.builder().product(product).skuCode("LIVE-80").price(100L).stockQuantity(5).build();
    final TypedQuery<ProductSKU> query=mock(TypedQuery.class);
    Cart stored;
    StoreOrderActors.Actor actor=new StoreOrderActors.Actor(7,true,false);
    String failure="";
    @BeforeEach void prepare() {
        write.setName("cart-write");read.setName("cart-read");read.setReadOnly(true);
        ReflectionTestUtils.setField(sku,"id",80L);
        when(actors.currentForWrite(7)).thenAnswer(call->{active(false);events.add("actor:write");return actor;});
        when(actors.currentForRead(7)).thenAnswer(call->{active(true);events.add("actor:read");return actor;});
        when(repository.findByUserId(7L)).thenAnswer(call->{assertTrue(TransactionSynchronizationManager.isActualTransactionActive());events.add("cart");if(failure.equals("query"))throw problem();return Optional.ofNullable(stored);});
        when(repository.save(any(Cart.class))).thenAnswer(call->{active(false);events.add("save");stored=call.getArgument(0);return stored;});
        when(em.find(ProductSKU.class,80L)).thenAnswer(call->{active(false);events.add("sku");return sku;});
        when(em.createQuery(anyString(),eq(ProductSKU.class))).thenAnswer(call->{active(true);events.add("sku-list");return query;});
        when(query.setParameter(eq("ids"),any())).thenReturn(query);
        when(query.getResultList()).thenAnswer(call->{active(true);return List.of(sku);});
    }
    @AfterEach void clean(){assertFalse(TransactionSynchronizationManager.isActualTransactionActive());}
    static DataAccessResourceFailureException problem(){return new DataAccessResourceFailureException("synthetic persistence failure");}
    void active(boolean ro){assertTrue(TransactionSynchronizationManager.isActualTransactionActive());assertEquals(ro,TransactionSynchronizationManager.isCurrentTransactionReadOnly());}
    CartItem existing(int quantity){stored=Cart.builder().userId(7L).build();var item=CartItem.builder().skuId(80L).quantity(quantity).build();stored.addItem(item);return item;}

    @Test void given__active_member__when__adding_twice_then_reading__then__serialize_actor_first_and_preserve_wire_and_per_add_stock_check() {
        carts.add(7,80L,3);carts.add(7,80L,3);
        assertEquals(6,stored.getItems().getFirst().getQuantity());assertEquals(5,sku.getStockQuantity());
        assertEquals(1,stored.getItems().size());assertEquals(80L,stored.getItems().getFirst().getProductSkuId());
        assertSame(stored,stored.getItems().getFirst().getCart());
        assertEquals(List.of("begin:write","actor:write","sku","cart","save","commit:write","begin:write","actor:write","sku","cart","commit:write"),events);
        assertEquals(List.of(new Carts.Item(null,80L,"LIVE-80","live product",100L,6,600L)),carts.get(7));
        verify(query).setParameter("ids",Set.of(80L));verify(repository,times(1)).save(any());
    }
    @ParameterizedTest @ValueSource(strings={"no-parent","empty-parent","missing-sku"})
    void given__empty_or_stale_cart__when__reading__then__empty_array_without_creating_or_deleting(String kind) {
        if(kind.equals("empty-parent"))stored=Cart.builder().userId(7L).build();
        if(kind.equals("missing-sku")){existing(1);doReturn(List.of()).when(query).getResultList();}
        assertEquals(List.of(),carts.get(7));verify(repository,never()).save(any());
        if(!kind.equals("missing-sku"))verifyNoInteractions(em,query);
        else assertEquals(1,stored.getItems().size());
    }
    static Stream<Arguments> rejectedActors(){return Stream.of("null","wrong","inactive","deleted").flatMap(state->Stream.of("get","add","update","remove","clear").map(op->Arguments.of(state,op)));}
    @ParameterizedTest @MethodSource("rejectedActors")
    void given__invalid_current_actor__when__accessing_any_cart_operation__then__reject_before_store(String state,String op) {
        actor=switch(state){case "null"->null;case "wrong"->new StoreOrderActors.Actor(8,true,false);case "inactive"->new StoreOrderActors.Actor(7,false,false);default->new StoreOrderActors.Actor(7,true,true);};
        assertThrows(CartActorRejectedException.class,()->operation(carts,op));verifyNoInteractions(em,repository,query);
        if(op.equals("get")){verify(actors).currentForRead(7);verify(actors,never()).currentForWrite(anyLong());}
        else {verify(actors).currentForWrite(7);verify(actors,never()).currentForRead(anyLong());}
        assertEquals("rollback:"+(op.equals("get")?"read":"write"),events.getLast());
    }
    static Stream<Arguments> badAdds(){return Stream.of(Arguments.of(null,1),Arguments.of(0L,1),Arguments.of(80L,null),Arguments.of(80L,0),Arguments.of(80L,-1));}
    @ParameterizedTest @MethodSource("badAdds")
    void given__invalid_add_input__when__adding__then__bad_input_without_cart_or_sku_write(Long id,Integer quantity) {
        assertThrows(IllegalArgumentException.class,()->carts.add(7,id,quantity));verifyNoInteractions(repository,em);assertEquals("rollback:write",events.getLast());
    }
    @ParameterizedTest @ValueSource(strings={"missing","stock"})
    void given__original_sku_rejection__when__adding__then__reject_before_creating_cart(String kind) {
        if(kind.equals("missing"))doReturn(null).when(em).find(ProductSKU.class,80L);
        assertThrows(IllegalArgumentException.class,()->carts.add(7,80L,kind.equals("stock")?6:1));
        verifyNoInteractions(repository);assertEquals("rollback:write",events.getLast());
    }
    @Test void given__integer_max_existing_quantity__when__adding__then__reject_overflow_without_changing_item() {
        var item=existing(Integer.MAX_VALUE);assertThrows(IllegalArgumentException.class,()->carts.add(7,80L,1));assertEquals(Integer.MAX_VALUE,item.getQuantity());assertEquals("rollback:write",events.getLast());
    }
    @ParameterizedTest @ValueSource(strings={"add","update","get"})
    void given__long_price_overflow__when__accessing_cart__then__reject_with_transaction_rollback(String op) {
        var item=existing(2);sku.updatePrice(Long.MAX_VALUE);
        assertThrows(IllegalArgumentException.class,()->{if(op.equals("add"))carts.add(7,80L,2);else if(op.equals("update"))carts.update(7,80,2);else carts.get(7);});
        assertEquals(2,item.getQuantity());assertEquals("rollback:"+(op.equals("get")?"read":"write"),events.getLast());
    }
    @ParameterizedTest @ValueSource(ints={0,-1})
    void given__nonpositive_patch__when__updating__then__remove_existing_item_without_sku_lookup(int quantity) {
        existing(1);carts.update(7,80,quantity);assertTrue(stored.getItems().isEmpty());verifyNoInteractions(em);
    }
    @Test void given__existing_item__when__setting_positive_quantity__then__replace_quantity_without_stock_reservation() {
        var item=existing(1);sku.updatePrice(1L);carts.update(7,80,Integer.MAX_VALUE);assertEquals(Integer.MAX_VALUE,item.getQuantity());assertEquals(5,sku.getStockQuantity());
    }
    @Test void given__missing_item__when__positive_patch__then__keep_original_bad_input() {assertThrows(IllegalArgumentException.class,()->carts.update(7,80,1));assertNull(stored);verifyNoInteractions(em);}
    @ParameterizedTest @ValueSource(strings={"remove","clear","patch-delete"})
    void given__missing_cart__when__removing__then__successful_noop_without_creating_parent(String op) {
        if(op.equals("remove"))carts.remove(7,80);else if(op.equals("clear"))carts.clear(7);else carts.update(7,80,0);
        verify(repository,never()).save(any());verifyNoInteractions(em);assertNull(stored);
    }
    @Test void given__two_items__when__removing_then_clearing__then__mutate_only_current_cart_children() {
        existing(1);stored.addItem(CartItem.builder().skuId(81L).quantity(2).build());carts.remove(7,80);assertEquals(81L,stored.getItems().getFirst().getSkuId());carts.clear(7);assertTrue(stored.getItems().isEmpty());
    }
    @ParameterizedTest @ValueSource(strings={"query","commit"})
    void given__persistence_or_commit_failure__when__adding__then__never_return_success(String stage) {
        failure=stage;Class<? extends RuntimeException> expected=stage.equals("query")?DataAccessResourceFailureException.class:TransactionSystemException.class;
        assertThrows(expected,()->carts.add(7,80L,1));
    }
    @ParameterizedTest @ValueSource(strings={"get","add"})
    void given__no_actual_transaction__when__accessing__then__fail_before_actor(String op) {
        var no=TransactionOperations.withoutTransaction();var service=new JpaCarts(em,repository,no,no,actors);
        assertThrows(IllegalStateException.class,()->operation(service,op));verifyNoInteractions(actors,em,repository);
    }
    @ParameterizedTest @ValueSource(strings={"get","add"})
    void given__wrong_read_mode__when__accessing__then__fail_before_actor(String op) {
        if(op.equals("get"))read.setReadOnly(false);else write.setReadOnly(true);
        assertThrows(IllegalStateException.class,()->operation(carts,op));verifyNoInteractions(actors,em,repository);
    }
    private static void operation(JpaCarts carts,String op) {switch(op){case "get"->carts.get(7);case "add"->carts.add(7,80L,1);case "update"->carts.update(7,80,1);case "remove"->carts.remove(7,80);default->carts.clear(7);}}
    final class Manager extends AbstractPlatformTransactionManager {
        final boolean readMode;Manager(boolean ro){readMode=ro;}
        protected Object doGetTransaction(){return new Object();}
        protected void doBegin(Object tx,TransactionDefinition definition){events.add("begin:"+(readMode?"read":"write"));}
        protected void doCommit(DefaultTransactionStatus status){events.add("commit:"+(readMode?"read":"write"));if(failure.equals("commit"))throw new TransactionSystemException("synthetic commit failure");}
        protected void doRollback(DefaultTransactionStatus status){events.add("rollback:"+(readMode?"read":"write"));}
    }
}
