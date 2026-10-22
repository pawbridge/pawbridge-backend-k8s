package com.pawbridge.backend.store.internal.payment.infrastructure;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.pawbridge.backend.store.api.StorePaymentEvents;
import com.pawbridge.backend.store.api.StorePaymentEvents.Completion;
import com.pawbridge.backend.store.internal.directorder.infrastructure.jpa.repository.OrderRepository;
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.SmartTransactionObject;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Direct payment calls with synthetic transactions; no JPA/database/Redis execution. */
class JpaStorePaymentEventsTest {
    @Test void given__no_caller_transaction__when__completing_or_failing__then__reject_before_locking_the_order() {
        var f=new Fixture();
        assertThatThrownBy(()->f.service.completePayment("order-uuid",900L)).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(()->f.service.failPayment("order-uuid")).isInstanceOf(IllegalTransactionStateException.class);
        assertThat(f.events).isEmpty();assertThat(f.order.getStatus()).isEqualTo(OrderStatus.PENDING);
    }

    @Test void given__a_read_only_caller_transaction__when__completing__then__reject_before_locking() {
        var f=new Fixture();var reads=new TransactionTemplate(f.manager);reads.setReadOnly(true);
        assertThatThrownBy(()->reads.execute(status->f.service.completePayment("order-uuid",900L)))
                .isInstanceOf(IllegalStateException.class).hasMessage("An active Store writer transaction is required");
        assertThat(f.events).doesNotContain("lock");assertThat(f.order.getStatus()).isEqualTo(OrderStatus.PENDING);
    }

    @Test void given__a_locked_pending_order_with_the_same_amount__when__completing__then__mark_paid_and_rank_only_after_commit() {
        var f=new Fixture();
        Completion result=f.writes.execute(status->{
            var completion=f.service.completePayment("order-uuid",900L);
            assertThat(f.ranks).isEmpty();f.events.add("caller-work");return completion;
        });
        assertThat(result).isEqualTo(Completion.APPLIED);assertThat(f.order.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(f.events).containsExactly("begin","lock","caller-work","commit","rank","rank","rank");
        assertThat(f.ranks).containsExactly("store:ranking:daily:20261021|100|2.0","store:ranking:daily:20261021|100|3.0","store:ranking:daily:20261021|200|4.0");
        assertThat(f.restorations).isEmpty();
    }

    @Test void given__the_caller_rolls_back__when__completion_was_applied__then__never_increment_ranking() {
        var f=new Fixture();
        f.writes.executeWithoutResult(status->{f.service.completePayment("order-uuid",900L);status.setRollbackOnly();});
        assertThat(f.events).containsExactly("begin","lock","rollback");assertThat(f.ranks).isEmpty();
    }

    @Test void given__ranking_fails_after_commit__when__completing__then__swallow_the_failure_and_keep_the_commit() {
        var f=new Fixture();List<String> attempts=new ArrayList<>();
        var service=f.service(quantities->{},(key,product,quantity)->{attempts.add(product);throw new IllegalStateException("synthetic redis failure");});
        Completion result=f.writes.execute(status->service.completePayment("order-uuid",900L));
        assertThat(result).isEqualTo(Completion.APPLIED);assertThat(f.events).containsExactly("begin","lock","commit");
        assertThat(attempts).containsExactly("100","100","200");
    }

    @Test void given__an_already_paid_order__when__completing_again__then__report_already_paid_without_ranking() {
        var f=new Fixture();f.order.paid();
        assertThat(f.complete("order-uuid",900L)).isEqualTo(Completion.ALREADY_PAID);
        assertThat(f.ranks).isEmpty();assertThat(f.events).containsExactly("begin","lock","commit");
    }

    @ParameterizedTest @EnumSource(value=OrderStatus.class,names={"CANCELLED","FAILED","COMPLETED"})
    void given__a_non_pending_order__when__completing__then__reject_without_changing_it(OrderStatus before) {
        var f=new Fixture();f.order.updateStatus(before);
        assertThat(f.complete("order-uuid",900L)).isEqualTo(Completion.REJECTED);
        assertThat(f.order.getStatus()).isEqualTo(before);assertThat(f.ranks).isEmpty();
    }

    @Test void given__a_different_amount_or_missing_order__when__completing__then__reject_without_changing_it() {
        var f=new Fixture();
        assertThat(f.complete("order-uuid",901L)).isEqualTo(Completion.REJECTED);
        assertThat(f.complete("missing",900L)).isEqualTo(Completion.REJECTED);
        assertThat(f.order.getStatus()).isEqualTo(OrderStatus.PENDING);assertThat(f.ranks).isEmpty();
    }

    @Test void given__a_locked_pending_order__when__failing__then__cancel_and_restore_aggregated_skus_in_the_caller_transaction() {
        var f=new Fixture();
        assertThat(f.fail()).isTrue();
        assertThat(f.order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(f.restorations).containsExactly(new LinkedHashMap<>(Map.of(11L,5,12L,4)));
        assertThat(f.events).containsExactly("begin","lock","restore","commit");assertThat(f.ranks).isEmpty();
    }

    @ParameterizedTest @EnumSource(value=OrderStatus.class,names={"PENDING"},mode=EnumSource.Mode.EXCLUDE)
    void given__a_non_pending_order__when__failing__then__neither_cancel_nor_restore(OrderStatus before) {
        var f=new Fixture();f.order.updateStatus(before);
        assertThat(f.fail()).isFalse();
        assertThat(f.order.getStatus()).isEqualTo(before);assertThat(f.restorations).isEmpty();
    }

    @Test void given__a_stock_restore_failure__when__failing__then__propagate_and_roll_back_the_caller_transaction() {
        var f=new Fixture();var failure=new IllegalStateException("synthetic stock failure");
        var service=f.service(quantities->{throw failure;},(key,product,quantity)->{});
        assertThatThrownBy(()->f.writes.execute(status->service.failPayment("order-uuid"))).isSameAs(failure);
        assertThat(f.events.getLast()).isEqualTo("rollback");
    }

    @Test void given__a_swallowed_participant_failure__when__the_caller_commits__then__the_shared_transaction_still_rolls_back() {
        var f=new Fixture();var service=f.service(quantities->{throw new IllegalStateException("synthetic");},(key,product,quantity)->{});
        assertThatThrownBy(()->f.writes.executeWithoutResult(status->{
            try { service.failPayment("order-uuid"); } catch(IllegalStateException ignored) { }
        })).isInstanceOf(UnexpectedRollbackException.class);
        assertThat(f.events.getLast()).isEqualTo("rollback");
    }

    @Test void given__a_pending_order__when__the_mock_payment_runs__then__mark_paid_in_its_own_transaction_without_ranking_or_events() {
        var f=new Fixture();f.service.processPayment(33L);
        assertThat(f.order.getStatus()).isEqualTo(OrderStatus.PAID);assertThat(f.ranks).isEmpty();assertThat(f.restorations).isEmpty();
        assertThat(f.events).containsExactly("begin","order-id","commit");
    }

    @ParameterizedTest @EnumSource(value=OrderStatus.class,names={"PENDING"},mode=EnumSource.Mode.EXCLUDE)
    void given__a_non_pending_order__when__the_mock_payment_runs__then__preserve_the_original_state_guard(OrderStatus state) {
        var f=new Fixture();f.order.updateStatus(state);
        assertThatThrownBy(()->f.service.processPayment(33L)).isInstanceOf(IllegalStateException.class).hasMessageContaining("Order is not in PENDING state");
        assertThat(f.events.getLast()).isEqualTo("rollback");
    }

    @Test void given__a_missing_order__when__the_mock_payment_runs__then__fail_without_changes() {
        var f=new Fixture();when(f.orders.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(()->f.service.processPayment(99L)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Order not found");
        assertThat(f.events.getLast()).isEqualTo("rollback");
    }

    private static final class Fixture {
        final List<String> events=new ArrayList<>(),ranks=new ArrayList<>();
        final List<Map<Long,Integer>> restorations=new ArrayList<>();
        final OrderRepository orders=mock(OrderRepository.class);
        final RecordingManager manager=new RecordingManager(events);
        final TransactionTemplate writes=new TransactionTemplate(manager);
        final TransactionTemplate mandatory=new TransactionTemplate(manager);
        final Clock clock=Clock.fixed(Instant.parse("2026-10-20T15:30:00Z"),ZoneId.of("Asia/Seoul"));
        final Order order=Order.builder().userId(7L).orderUuid("order-uuid").totalAmount(900L)
                .deliveryAddress("synthetic").receiverName("synthetic").receiverPhone("000").build();
        final JpaStorePaymentEvents service;
        Fixture() {
            mandatory.setPropagationBehavior(TransactionDefinition.PROPAGATION_MANDATORY);
            ReflectionTestUtils.setField(order,"id",33L);
            addItem(11L,100L,2);addItem(11L,100L,3);addItem(12L,200L,4);
            when(orders.findById(33L)).thenAnswer(call->{events.add("order-id");return Optional.of(order);});
            service=service(quantities->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);events.add("restore");restorations.add(new LinkedHashMap<>(quantities));},
                    (key,product,quantity)->{assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);events.add("rank");ranks.add(key+"|"+product+"|"+quantity);});
        }
        JpaStorePaymentEvents service(StorePaymentEvents.StockRestorer stock,StorePaymentEvents.Ranking ranking) {
            return new JpaStorePaymentEvents(orders,uuid->{events.add("lock");return "order-uuid".equals(uuid)?Optional.of(order):Optional.empty();},
                    writes,mandatory,stock,ranking,clock);
        }
        Completion complete(String uuid,long amount){return writes.execute(status->service.completePayment(uuid,amount));}
        boolean fail(){Boolean cancelled=writes.execute(status->service.failPayment("order-uuid"));return cancelled;}
        void addItem(long skuId,long productId,int quantity) {
            var product=Product.builder().id(productId).name("synthetic").status(ProductStatus.ACTIVE).build();
            var sku=ProductSKU.builder().product(product).skuCode("synthetic-"+skuId).price(100L).stockQuantity(0).build();ReflectionTestUtils.setField(sku,"id",skuId);
            order.getOrderItems().add(OrderItem.builder().order(order).productSKU(sku).price(100L).productName("snapshot").skuCode("snapshot").quantity(quantity).build());
        }
    }
    /** Supports joining (MANDATORY/REQUIRED) so participant failures mark the shared transaction rollback-only. */
    private static final class RecordingManager extends AbstractPlatformTransactionManager {
        final List<String> events;boolean rollbackOnly;
        RecordingManager(List<String> events){this.events=events;}
        @Override protected Object doGetTransaction(){return (SmartTransactionObject)new SmartTransactionObject(){
            @Override public boolean isRollbackOnly(){return rollbackOnly;}
            @Override public void flush(){}
        };}
        @Override protected boolean isExistingTransaction(Object transaction){return TransactionSynchronizationManager.isActualTransactionActive();}
        @Override protected void doBegin(Object transaction,TransactionDefinition definition){rollbackOnly=false;events.add("begin");}
        @Override protected void doSetRollbackOnly(DefaultTransactionStatus status){rollbackOnly=true;}
        @Override protected void doCommit(DefaultTransactionStatus status){events.add("commit");}
        @Override protected void doRollback(DefaultTransactionStatus status){events.add("rollback");}
    }
}
