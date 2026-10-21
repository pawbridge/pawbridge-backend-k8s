package com.pawbridge.backend.store.internal.payment.infrastructure;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.store.api.StorePaymentEvents;
import com.pawbridge.backend.store.internal.directorder.infrastructure.jpa.model.Outbox;
import com.pawbridge.backend.store.internal.directorder.infrastructure.jpa.repository.OrderRepository;
import com.pawbridge.backend.store.internal.directorder.infrastructure.jpa.repository.OutboxRepository;
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Original branch protection with synthetic transactions; no JPA/database/Kafka/Redis execution. */
class JpaStorePaymentEventsTest {
    @ParameterizedTest @ValueSource(strings={"raw","object","escaped"})
    void given__the_original_payload_shapes__when__done_is_consumed__then__mark_paid_and_increment_each_product_item_in_the_local_calendar_day(String shape) throws Exception {
        var f=new Fixture();String raw=f.event("DONE");
        String message=switch(shape) {case "object"->"{\"payload\":"+raw+"}";case "escaped"->f.mapper.writeValueAsString(Map.of("payload",raw));default->raw;};
        f.service.handle(message);
        assertThat(f.order.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(f.ranks).containsExactly("store:ranking:daily:20261021|100|2.0","store:ranking:daily:20261021|100|3.0","store:ranking:daily:20261021|200|4.0");
        assertThat(f.events).containsExactly("begin","order-uuid","rank","rank","rank","commit");
        verifyNoInteractions(f.outbox);assertThat(f.restorations).isEmpty();
    }

    @Test void given__an_already_paid_order__when__done_is_consumed_again__then__return_without_ranking_or_new_events() {
        var f=new Fixture();f.order.paid();f.service.handle(f.event("DONE"));
        assertThat(f.ranks).isEmpty();assertThat(f.restorations).isEmpty();verifyNoInteractions(f.outbox);
        assertThat(f.events).containsExactly("begin","order-uuid","commit");
    }

    @ParameterizedTest @EnumSource(value=OrderStatus.class,names={"CANCELLED","FAILED","COMPLETED"})
    void given__a_non_paid_source_order__when__late_done_arrives__then__retain_original_unrestricted_paid_transition_as_a_deferred_limit(OrderStatus before) {
        var f=new Fixture();f.order.updateStatus(before);f.service.handle(f.event("DONE"));
        assertThat(f.order.getStatus()).isEqualTo(OrderStatus.PAID);assertThat(f.ranks).hasSize(3);
    }

    @ParameterizedTest @ValueSource(strings={"ABORTED","CANCELED"})
    void given__the_original_failure_status__when__consumed__then__cancel_and_restore_aggregated_skus_inside_one_writer_transaction(String status) {
        var f=new Fixture();f.service.handle(f.event(status));
        assertThat(f.order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(f.restorations).containsExactly(new LinkedHashMap<>(Map.of(11L,5,12L,4)));
        assertThat(f.events).containsExactly("begin","order-uuid","restore","commit");
        assertThat(f.ranks).isEmpty();verifyNoInteractions(f.outbox);
    }

    @Test void given__an_order_uuid__when__the_original_public_service_cancel_is_called__then__restore_through_the_same_stock_boundary() {
        var f=new Fixture();f.service.cancelOrder("order-uuid");
        assertThat(f.order.getStatus()).isEqualTo(OrderStatus.CANCELLED);assertThat(f.restorations).hasSize(1);
        assertThat(f.events).containsExactly("begin","order-uuid","restore","commit");
    }

    @Test void given__an_already_cancelled_order__when__the_original_cancel_is_repeated__then__do_not_restore_twice_in_the_sequential_case() {
        var f=new Fixture();f.order.cancelOrder();f.service.cancelOrder("order-uuid");
        assertThat(f.restorations).isEmpty();assertThat(f.ranks).isEmpty();verifyNoInteractions(f.outbox);
    }

    @Test void given__an_already_paid_order__when__an_original_failure_arrives__then__retain_original_cancel_restore_behavior_as_a_deferred_limit() {
        var f=new Fixture();f.order.paid();f.service.handle(f.event("ABORTED"));
        assertThat(f.order.getStatus()).isEqualTo(OrderStatus.CANCELLED);assertThat(f.restorations).hasSize(1);
    }

    @ParameterizedTest @ValueSource(strings={"READY","PENDING","FAILED","done"})
    void given__an_unhandled_original_status__when__consumed__then__skip_without_loading_or_mutating_orders(String status) {
        var f=new Fixture();f.service.handle(f.event(status));
        verifyNoInteractions(f.orders,f.outbox);assertThat(f.ranks).isEmpty();assertThat(f.restorations).isEmpty();
        assertThat(f.events).containsExactly("begin","commit");
    }

    @ParameterizedTest @ValueSource(strings={"null","{}","{\"orderId\":1,\"status\":\"DONE\"}","{\"orderId\":\" \",\"status\":\"DONE\"}","{\"orderId\":\"order-uuid\",\"status\":false}","{\"orderId\":\"order-uuid\",\"status\":\" \"}"})
    void given__missing_or_nontext_required_fields__when__consumed__then__keep_the_original_validation_failure_before_order_access(String event) {
        var f=new Fixture();assertThatThrownBy(()->f.service.handle(event)).isInstanceOf(IllegalArgumentException.class).hasMessage("Payment event requires orderId and status");
        verifyNoInteractions(f.orders,f.outbox);assertThat(f.events).containsExactly("begin","rollback");
    }

    @Test void given__malformed_json__when__consumed__then__wrap_the_original_parse_failure_without_order_access() {
        var f=new Fixture();assertThatThrownBy(()->f.service.handle("{")).isInstanceOf(IllegalArgumentException.class).hasMessage("Payment Event Parsing Failed");
        verifyNoInteractions(f.orders,f.outbox);assertThat(f.events).containsExactly("begin","rollback");
    }

    @Test void given__a_pending_order__when__the_separate_process_payment_operation_runs__then__write_order_paid_outbox_without_consumer_ranking() throws Exception {
        var f=new Fixture();f.service.processPayment(33L);
        assertThat(f.order.getStatus()).isEqualTo(OrderStatus.PAID);assertThat(f.rows).hasSize(1);
        var row=f.rows.getFirst();assertThat(row.getAggregateType()).isEqualTo("order");assertThat(row.getAggregateId()).isEqualTo("33");assertThat(row.getEventType()).isEqualTo("ORDER_PAID");
        assertThat(f.mapper.readTree(row.getPayload())).isEqualTo(f.mapper.readTree("{\"orderId\":33,\"userId\":7,\"totalAmount\":900,\"status\":\"PAID\"}"));
        assertThat(f.ranks).isEmpty();assertThat(f.restorations).isEmpty();assertThat(f.events).containsExactly("begin","order-id","outbox","commit");
    }

    @ParameterizedTest @EnumSource(value=OrderStatus.class,names={"PENDING"},mode=EnumSource.Mode.EXCLUDE)
    void given__a_non_pending_order__when__the_separate_process_payment_operation_runs__then__preserve_the_original_state_guard(OrderStatus state) {
        var f=new Fixture();f.order.updateStatus(state);
        assertThatThrownBy(()->f.service.processPayment(33L)).isInstanceOf(IllegalStateException.class).hasMessageContaining("Order is not in PENDING state");
        verifyNoInteractions(f.outbox);assertThat(f.events.getLast()).isEqualTo("rollback");
    }

    @ParameterizedTest @ValueSource(strings={"done","cancel","process"})
    void given__a_missing_order__when__an_original_operation_runs__then__fail_without_stock_ranking_or_outbox(String operation) {
        var f=new Fixture();when(f.orders.findByOrderUuid("missing")).thenReturn(Optional.empty());when(f.orders.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(()->{switch(operation){case "done"->f.service.handle("{\"orderId\":\"missing\",\"status\":\"DONE\"}");case "cancel"->f.service.cancelOrder("missing");default->f.service.processPayment(99L);}}).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Order not found");
        assertThat(f.restorations).isEmpty();assertThat(f.ranks).isEmpty();verifyNoInteractions(f.outbox);assertThat(f.events.getLast()).isEqualTo("rollback");
    }

    @Test void given__a_ranking_failure_after_paid__when__done_is_consumed__then__propagate_for_retry_without_claiming_redis_is_transactional() {
        var f=new Fixture();var failure=new IllegalStateException("synthetic ranking failure");
        var service=f.service(f.mapper,quantities->{},(key,product,quantity)->{throw failure;});
        assertThatThrownBy(()->service.handle(f.event("DONE"))).isSameAs(failure);
        assertThat(f.events.getLast()).isEqualTo("rollback");verifyNoInteractions(f.outbox);
        // The synthetic transaction does not undo the in-memory entity or any external increment.
    }

    @Test void given__a_stock_restore_failure__when__cancel_is_consumed__then__propagate_the_original_failure_from_the_same_writer_boundary() {
        var f=new Fixture();var failure=new IllegalStateException("synthetic stock failure");
        var service=f.service(f.mapper,quantities->{throw failure;},(key,product,quantity)->{});
        assertThatThrownBy(()->service.handle(f.event("CANCELED"))).isSameAs(failure);
        assertThat(f.events.getLast()).isEqualTo("rollback");verifyNoInteractions(f.outbox);
    }

    @Test void given__outbox_json_serialization_failure__when__process_payment_runs__then__request_rollback_with_the_original_message() throws Exception {
        var f=new Fixture();var mapper=mock(ObjectMapper.class);
        when(mapper.writeValueAsString(any())).thenThrow(new JsonProcessingException("synthetic"){});
        assertThatThrownBy(()->f.service(mapper,quantities->{},(key,product,quantity)->{}).processPayment(33L)).isInstanceOf(RuntimeException.class).hasMessage("Failed to publish payment event");
        verifyNoInteractions(f.outbox);assertThat(f.events.getLast()).isEqualTo("rollback");
    }

    private static final class Fixture {
        final List<String> events=new ArrayList<>(),ranks=new ArrayList<>();
        final List<Map<Long,Integer>> restorations=new ArrayList<>();final List<Outbox> rows=new ArrayList<>();
        final OrderRepository orders=mock(OrderRepository.class);final OutboxRepository outbox=mock(OutboxRepository.class);
        final ObjectMapper mapper=new ObjectMapper();
        final RecordingManager manager=new RecordingManager(events);
        final Clock clock=Clock.fixed(Instant.parse("2026-10-20T15:30:00Z"),ZoneId.of("Asia/Seoul"));
        final Order order=Order.builder().userId(7L).orderUuid("order-uuid").totalAmount(900L)
                .deliveryAddress("synthetic").receiverName("synthetic").receiverPhone("000").build();
        final JpaStorePaymentEvents service;
        Fixture() {
            ReflectionTestUtils.setField(order,"id",33L);
            addItem(11L,100L,2);addItem(11L,100L,3);addItem(12L,200L,4);
            when(orders.findByOrderUuid("order-uuid")).thenAnswer(call->{events.add("order-uuid");return Optional.of(order);});
            when(orders.findById(33L)).thenAnswer(call->{events.add("order-id");return Optional.of(order);});
            when(outbox.save(any())).thenAnswer(call->{events.add("outbox");Outbox row=call.getArgument(0);rows.add(row);return row;});
            service=service(mapper,quantities->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);events.add("restore");restorations.add(new LinkedHashMap<>(quantities));},
                    (key,product,quantity)->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);events.add("rank");ranks.add(key+"|"+product+"|"+quantity);});
        }
        JpaStorePaymentEvents service(ObjectMapper selected,StorePaymentEvents.StockRestorer stock,StorePaymentEvents.Ranking ranking) {
            return new JpaStorePaymentEvents(orders,outbox,new TransactionTemplate(manager),stock,ranking,selected,clock);
        }
        String event(String status) {return "{\"orderId\":\"order-uuid\",\"status\":\""+status+"\"}";}
        void addItem(long skuId,long productId,int quantity) {
            var product=Product.builder().id(productId).name("synthetic").status(ProductStatus.ACTIVE).build();
            var sku=ProductSKU.builder().product(product).skuCode("synthetic-"+skuId).price(100L).stockQuantity(0).build();ReflectionTestUtils.setField(sku,"id",skuId);
            order.getOrderItems().add(OrderItem.builder().order(order).productSKU(sku).price(100L).productName("snapshot").skuCode("snapshot").quantity(quantity).build());
        }
    }
    private static final class RecordingManager extends AbstractPlatformTransactionManager {
        final List<String> events;RecordingManager(List<String> events){this.events=events;}
        @Override protected Object doGetTransaction(){return new Object();}
        @Override protected void doBegin(Object transaction,TransactionDefinition definition){events.add("begin");}
        @Override protected void doCommit(DefaultTransactionStatus status){events.add("commit");}
        @Override protected void doRollback(DefaultTransactionStatus status){events.add("rollback");}
    }
}
