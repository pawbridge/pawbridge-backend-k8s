package com.pawbridge.backend.store.internal.catalogwrite.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.store.internal.catalogwrite.infrastructure.jpa.repository.*;
import com.pawbridge.backend.store.internal.catalogwrite.web.*;
import com.pawbridge.backend.store.internal.directorder.infrastructure.ProductSnapshots;
import com.pawbridge.backend.store.internal.directorder.infrastructure.jpa.repository.ProductRepository;
import com.pawbridge.backend.store.internal.directorder.infrastructure.jpa.repository.ProductSKURepository;
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.*;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class JpaCatalogOptionsTest {
    private final OptionGroupRepository groups=mock(OptionGroupRepository.class);
    private final OptionValueRepository values=mock(OptionValueRepository.class);
    private final ProductRepository products=mock(ProductRepository.class);
    private final ProductSKURepository skus=mock(ProductSKURepository.class);
    private final ProductSnapshots snapshots=mock(ProductSnapshots.class);
    private final Writer manager=new Writer();
    private final JpaCatalogOptions subject=new JpaCatalogOptions(groups,values,products,skus,snapshots,new TransactionTemplate(manager));
    private final ObjectMapper mapper=new ObjectMapper();

    @AfterEach void resourcesReturned(){assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty();}

    @Test void givenNewGroupAndValues__whenCreated__thenReturnOriginalGroupAndValueShape() throws Exception {
        when(groups.save(any())).thenAnswer(call->{OptionGroup group=call.getArgument(0);ReflectionTestUtils.setField(group,"id",1L);return group;});
        var sequence=new java.util.concurrent.atomic.AtomicLong(10L);
        when(values.save(any())).thenAnswer(call->{OptionValue value=call.getArgument(0);ReflectionTestUtils.setField(value,"id",sequence.incrementAndGet());return value;});
        var result=subject.createOptionGroup(mapper.readValue("{\"name\":\"size\",\"values\":[\"small\",\"large\"]}",OptionGroupRequest.class));
        assertThat(result.getId()).isEqualTo(1L);assertThat(result.getName()).isEqualTo("size");
        assertThat(result.getValues()).extracting(OptionValueResponse::getName).containsExactly("small","large");
        assertThat(result.getValues()).allSatisfy(value->{assertThat(value.getGroupId()).isEqualTo(1L);assertThat(value.getGroupName()).isEqualTo("size");});
        verifyNoInteractions(products,skus,snapshots);assertThat(manager.commits).isEqualTo(1);
    }

    @Test void givenDuplicateGroupName__whenCreating__thenRejectBeforePersisting() throws Exception {
        when(groups.existsByName("size")).thenReturn(true);var request=mapper.readValue("{\"name\":\"size\"}",OptionGroupRequest.class);
        assertThatThrownBy(()->subject.createOptionGroup(request)).isInstanceOf(IllegalStateException.class);verify(groups,never()).save(any());verifyNoInteractions(values,snapshots);assertThat(manager.rollbacks).isEqualTo(1);
    }

    @Test void givenUsedGroup__whenRenamed__thenLockAffectedProductsInOrderAndPublishEach() throws Exception {
        OptionGroup group=group();Product first=product(10L),second=product(20L);
        when(groups.findByIdWithLock(1L)).thenReturn(Optional.of(group));when(skus.findProductIdsByOptionGroupId(1L)).thenReturn(List.of(20L,10L));
        when(products.findByIdWithLock(10L)).thenReturn(Optional.of(first));when(products.findByIdWithLock(20L)).thenReturn(Optional.of(second));
        when(skus.findAllByProductIdWithLock(10L)).thenReturn(first.getSkus());when(skus.findAllByProductIdWithLock(20L)).thenReturn(second.getSkus());
        var result=subject.updateOptionGroup(1L,mapper.readValue("{\"name\":\"new size\"}",OptionGroupRequest.class));
        assertThat(result.getName()).isEqualTo("new size");var order=inOrder(groups,values,skus,products,snapshots);
        order.verify(groups).findByIdWithLock(1L);order.verify(values).findAllByOptionGroupIdWithLock(1L);order.verify(skus).findProductIdsByOptionGroupId(1L);
        order.verify(products).findByIdWithLock(10L);order.verify(skus).findAllByProductIdWithLock(10L);order.verify(snapshots).publishProductSnapshot(first);
        order.verify(products).findByIdWithLock(20L);order.verify(skus).findAllByProductIdWithLock(20L);order.verify(snapshots).publishProductSnapshot(second);assertThat(manager.commits).isEqualTo(1);
    }

    @Test void givenUsedValue__whenRenamed__thenRepublishAffectedProduct() throws Exception {
        OptionValue value=value();Product product=product(10L);
        when(values.findByIdWithLock(2L)).thenReturn(Optional.of(value));when(skus.findProductIdsByOptionValueId(2L)).thenReturn(List.of(10L));
        when(products.findByIdWithLock(10L)).thenReturn(Optional.of(product));when(skus.findAllByProductIdWithLock(10L)).thenReturn(product.getSkus());
        var result=subject.updateOptionValue(2L,mapper.readValue("{\"name\":\"new small\"}",OptionValueRequest.class));
        assertThat(result.getName()).isEqualTo("new small");assertThat(result.getGroupName()).isEqualTo("size");verify(snapshots).publishProductSnapshot(product);assertThat(manager.commits).isEqualTo(1);
    }

    @Test void givenGroupWithReferencedValue__whenDeleting__thenRefusePhysicalDelete() {
        OptionValue value=value();OptionGroup group=value.getOptionGroup();group.getOptionValues().add(value);
        when(groups.findById(1L)).thenReturn(Optional.of(group));when(values.isOptionValueInUse(2L)).thenReturn(true);
        assertThatThrownBy(()->subject.deleteOptionGroup(1L)).isInstanceOf(IllegalStateException.class);verify(groups,never()).delete(any());assertThat(manager.rollbacks).isEqualTo(1);
    }

    @Test void givenReferencedValue__whenDeleting__thenRefusePhysicalDelete() {
        when(values.findById(2L)).thenReturn(Optional.of(value()));when(values.isOptionValueInUse(2L)).thenReturn(true);
        assertThatThrownBy(()->subject.deleteOptionValue(2L)).isInstanceOf(IllegalStateException.class);verify(values,never()).delete(any());assertThat(manager.rollbacks).isEqualTo(1);
    }

    @Test void givenUnreferencedGroupAndValue__whenDeleted__thenKeepOriginalPhysicalDeletes() {
        OptionGroup group=group();OptionValue value=value();when(groups.findById(1L)).thenReturn(Optional.of(group));when(values.findById(2L)).thenReturn(Optional.of(value));
        subject.deleteOptionGroup(1L);subject.deleteOptionValue(2L);verify(groups).delete(group);verify(values).delete(value);verifyNoInteractions(snapshots);assertThat(manager.commits).isEqualTo(2);
    }

    @Test void givenExistingGroup__whenAddingValue__thenAttachAndReturnGroupIdentity() throws Exception {
        OptionGroup group=group();when(groups.findById(1L)).thenReturn(Optional.of(group));
        when(values.save(any())).thenAnswer(call->{OptionValue value=call.getArgument(0);ReflectionTestUtils.setField(value,"id",2L);return value;});
        var result=subject.addOptionValue(1L,mapper.readValue("{\"name\":\"small\"}",OptionValueRequest.class));
        assertThat(result.getId()).isEqualTo(2L);assertThat(result.getName()).isEqualTo("small");assertThat(result.getGroupId()).isEqualTo(1L);assertThat(result.getGroupName()).isEqualTo("size");assertThat(group.getOptionValues()).hasSize(1);
    }

    @Test void givenSnapshotFailure__whenRenaming__thenPropagateToWriterRollback() throws Exception {
        OptionValue value=value();Product product=product(10L);when(values.findByIdWithLock(2L)).thenReturn(Optional.of(value));when(skus.findProductIdsByOptionValueId(2L)).thenReturn(List.of(10L));
        when(products.findByIdWithLock(10L)).thenReturn(Optional.of(product));when(skus.findAllByProductIdWithLock(10L)).thenReturn(product.getSkus());
        var failure=new IllegalStateException("synthetic snapshot failure");doThrow(failure).when(snapshots).publishProductSnapshot(product);
        var request=mapper.readValue("{\"name\":\"new small\"}",OptionValueRequest.class);
        assertThatThrownBy(()->subject.updateOptionValue(2L,request)).isSameAs(failure);assertThat(manager.rollbacks).isEqualTo(1);assertThat(manager.commits).isZero();
    }

    private static OptionGroup group(){var group=OptionGroup.builder().name("size").build();ReflectionTestUtils.setField(group,"id",1L);return group;}
    private static OptionValue value(){var value=OptionValue.builder().optionGroup(group()).name("small").build();ReflectionTestUtils.setField(value,"id",2L);return value;}
    private static Product product(long id){var p=Product.builder().name("food").status(ProductStatus.ACTIVE).build();ReflectionTestUtils.setField(p,"id",id);var sku=ProductSKU.builder().product(p).skuCode("FOOD-"+id).price(100L).stockQuantity(3).build();ReflectionTestUtils.setField(sku,"id",id*10);p.getSkus().add(sku);return p;}
    private static final class Writer extends AbstractPlatformTransactionManager {
        int commits,rollbacks;
        @Override protected Object doGetTransaction(){return new Object();}
        @Override protected void doBegin(Object tx,TransactionDefinition definition){}
        @Override protected void doCommit(DefaultTransactionStatus status){commits++;}
        @Override protected void doRollback(DefaultTransactionStatus status){rollbacks++;}
    }
}
