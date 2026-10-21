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

class JpaCatalogProductsTest {
    private final ProductRepository products=mock(ProductRepository.class);
    private final CategoryRepository categories=mock(CategoryRepository.class);
    private final ProductSKURepository skus=mock(ProductSKURepository.class);
    private final CartItemRepository carts=mock(CartItemRepository.class);
    private final SKUValueRepository links=mock(SKUValueRepository.class);
    private final OptionValueRepository options=mock(OptionValueRepository.class);
    private final ProductSnapshots snapshots=mock(ProductSnapshots.class);
    private final Writer manager=new Writer();
    private final JpaCatalogProducts subject=new JpaCatalogProducts(products,categories,skus,carts,
            new ProductSkus(skus,links,options),snapshots,new TransactionTemplate(manager));
    private final ObjectMapper mapper=new ObjectMapper();

    @AfterEach void resourcesReturned(){assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty();}

    @Test void givenCreateWithSku__whenSaved__thenReturnOriginalFieldsAndPublishWithinWriter() throws Exception {
        when(products.save(any())).thenAnswer(call->{Product p=call.getArgument(0);ReflectionTestUtils.setField(p,"id",10L);return p;});
        when(skus.save(any())).thenAnswer(call->{ProductSKU sku=call.getArgument(0);ReflectionTestUtils.setField(sku,"id",101L);return sku;});
        doAnswer(call->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();return null;}).when(snapshots).publishProductSnapshot(any());
        ProductResponse response=subject.createProduct(mapper.readValue("""
                {"name":"food","description":"description","imageUrl":"https://assets.invalid/p.png",
                 "skus":[{"skuCode":"FOOD-S","price":100,"stockQuantity":3}]}
                """,ProductCreateRequest.class));
        assertThat(response.getId()).isEqualTo(10L);assertThat(response.getStatus()).isEqualTo("ACTIVE");
        assertThat(response.getName()).isEqualTo("food");assertThat(response.getDescription()).isEqualTo("description");
        assertThat(response.getImageUrl()).isEqualTo("https://assets.invalid/p.png");
        assertThat(response.getSkus()).singleElement().satisfies(sku->{assertThat(sku.getSkuId()).isEqualTo(101L);assertThat(sku.getSkuCode()).isEqualTo("FOOD-S");assertThat(sku.getPrice()).isEqualTo(100L);assertThat(sku.getStockQuantity()).isEqualTo(3);assertThat(sku.getOptions()).isEmpty();});
        verify(snapshots).publishProductSnapshot(any());assertThat(manager.commits).isEqualTo(1);
    }

    @Test void givenCreateWithoutSkus__whenSaved__thenKeepOriginalNoSnapshotBranch() throws Exception {
        when(products.save(any())).thenAnswer(call->call.getArgument(0));
        var response=subject.createProduct(mapper.readValue("{\"name\":\"no sku\"}",ProductCreateRequest.class));
        assertThat(response.getSkus()).isEmpty();verifyNoInteractions(snapshots,skus,options,links);assertThat(manager.commits).isEqualTo(1);
    }

    @Test void givenProductAndSkuPatch__whenUpdated__thenLockAndPublishWholeProduct() throws Exception {
        Product p=product();when(products.findByIdWithLock(10L)).thenReturn(Optional.of(p));
        var response=subject.updateProduct(10L,mapper.readValue("""
                {"name":"new food","description":"new description","imageUrl":"new.png","status":"HIDDEN",
                 "skus":[{"id":101,"price":300,"stockQuantity":8}]}
                """,ProductUpdateRequest.class));
        var order=inOrder(products,skus,snapshots);order.verify(products).findByIdWithLock(10L);order.verify(skus).findAllByProductIdWithLock(10L);order.verify(snapshots).publishProductSnapshot(p);
        assertThat(response.getName()).isEqualTo("new food");assertThat(response.getDescription()).isEqualTo("new description");
        assertThat(response.getImageUrl()).isEqualTo("new.png");assertThat(response.getStatus()).isEqualTo("HIDDEN");
        assertThat(response.getSkus().getFirst().getPrice()).isEqualTo(300L);assertThat(response.getSkus().getFirst().getStockQuantity()).isEqualTo(8);
        assertThat(response.getSkus()).hasSize(2);assertThat(manager.commits).isEqualTo(1);
    }

    @Test void givenSkuReferencedByCart__whenDeleting__thenRefuseBeforeStatusAndSnapshot() {
        Product p=product();when(products.findByIdWithLock(10L)).thenReturn(Optional.of(p));when(carts.existsByProductSkuIdIn(List.of(101L,102L))).thenReturn(true);
        assertThatThrownBy(()->subject.deleteProduct(10L)).isInstanceOf(IllegalStateException.class);
        assertThat(p.getStatus()).isEqualTo(ProductStatus.ACTIVE);verifyNoInteractions(snapshots);assertThat(manager.rollbacks).isEqualTo(1);assertThat(manager.commits).isZero();
    }

    @Test void givenAlreadyDeletedProduct__whenDeleting__thenRefuseBeforeCartAndSnapshot() {
        Product p=product();p.updateStatus(ProductStatus.DELETED);when(products.findByIdWithLock(10L)).thenReturn(Optional.of(p));
        assertThatThrownBy(()->subject.deleteProduct(10L)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(carts,snapshots);assertThat(manager.rollbacks).isEqualTo(1);
    }

    @Test void givenUnreferencedProduct__whenDeleting__thenSoftDeleteAndPublishWithinOneWriter() {
        Product p=product();when(products.findByIdWithLock(10L)).thenReturn(Optional.of(p));
        subject.deleteProduct(10L);
        assertThat(p.getStatus()).isEqualTo(ProductStatus.DELETED);verify(carts).existsByProductSkuIdIn(List.of(101L,102L));verify(snapshots).publishProductSnapshot(p);verify(products,never()).delete(any());assertThat(manager.commits).isEqualTo(1);
    }

    @Test void givenSnapshotFailure__whenUpdating__thenPropagateAndRequestWriterRollback() {
        Product p=product();when(products.findByIdWithLock(10L)).thenReturn(Optional.of(p));
        var failure=new IllegalStateException("synthetic snapshot failure");doThrow(failure).when(snapshots).publishProductSnapshot(p);
        assertThatThrownBy(()->subject.updateProduct(10L,new ProductUpdateRequest())).isSameAs(failure);
        assertThat(manager.rollbacks).isEqualTo(1);assertThat(manager.commits).isZero();
        // This unit proves rollback dispatch, not actual database restoration.
    }

    @Test void givenMissingCategory__whenCreating__thenRefuseBeforeProductSave() throws Exception {
        when(categories.findById(99L)).thenReturn(Optional.empty());
        var request=mapper.readValue("{\"name\":\"food\",\"categoryId\":99}",ProductCreateRequest.class);
        assertThatThrownBy(()->subject.createProduct(request)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(products,skus,snapshots);assertThat(manager.rollbacks).isEqualTo(1);
    }

    private static Product product(){
        Product p=Product.builder().name("food").status(ProductStatus.ACTIVE).build();ReflectionTestUtils.setField(p,"id",10L);
        for(long id:List.of(101L,102L)){ProductSKU sku=ProductSKU.builder().product(p).skuCode("FOOD-"+id).price(100L).stockQuantity(3).build();ReflectionTestUtils.setField(sku,"id",id);p.getSkus().add(sku);}return p;
    }
    private static final class Writer extends AbstractPlatformTransactionManager {
        int commits,rollbacks;
        @Override protected Object doGetTransaction(){return new Object();}
        @Override protected void doBegin(Object tx,TransactionDefinition definition){}
        @Override protected void doCommit(DefaultTransactionStatus status){commits++;}
        @Override protected void doRollback(DefaultTransactionStatus status){rollbacks++;}
    }
}
