package com.pawbridge.backend.store.internal.catalogwrite.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.store.internal.catalogwrite.infrastructure.jpa.repository.CategoryRepository;
import com.pawbridge.backend.store.internal.catalogwrite.web.*;
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.Category;
import java.util.Optional;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class JpaCatalogCategoriesTest {
    private final CategoryRepository categories=mock(CategoryRepository.class);
    private final Writer manager=new Writer();
    private final JpaCatalogCategories subject=new JpaCatalogCategories(categories,new TransactionTemplate(manager));
    private final ObjectMapper mapper=new ObjectMapper();

    @AfterEach void resourcesReturned(){assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty();}

    @Test void givenParentId__whenCreating__thenSaveUnderParentAndReturnOriginalShapeInsideWriter() throws Exception {
        Category parent=category(1L,"food",null);when(categories.findById(1L)).thenReturn(Optional.of(parent));
        when(categories.save(any())).thenAnswer(call->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            Category saved=call.getArgument(0);ReflectionTestUtils.setField(saved,"id",2L);return saved;});
        var result=subject.createCategory(mapper.readValue("{\"name\":\"dry\",\"description\":\"dry food\",\"parentId\":1}",CategoryCreateRequest.class));
        assertThat(result.getId()).isEqualTo(2L);assertThat(result.getName()).isEqualTo("dry");assertThat(result.getDescription()).isEqualTo("dry food");
        assertThat(result.getParentId()).isEqualTo(1L);assertThat(result.getChildren()).isEmpty();assertThat(manager.commits).isEqualTo(1);
    }

    @Test void givenNoParent__whenCreating__thenSaveRootWithoutParentLookup() throws Exception {
        when(categories.save(any())).thenAnswer(call->{Category saved=call.getArgument(0);ReflectionTestUtils.setField(saved,"id",3L);return saved;});
        var result=subject.createCategory(mapper.readValue("{\"name\":\"toy\"}",CategoryCreateRequest.class));
        assertThat(result.getParentId()).isNull();verify(categories,never()).findById(any());assertThat(manager.commits).isEqualTo(1);
    }

    @Test void givenMissingParent__whenCreating__thenRejectAsInputBeforeSaving() throws Exception {
        when(categories.findById(9L)).thenReturn(Optional.empty());var request=mapper.readValue("{\"name\":\"dry\",\"parentId\":9}",CategoryCreateRequest.class);
        assertThatThrownBy(()->subject.createCategory(request)).isInstanceOf(IllegalArgumentException.class);verify(categories,never()).save(any());assertThat(manager.rollbacks).isEqualTo(1);
    }

    @Test void givenBlankNameNullDescriptionAndNoParent__whenUpdating__thenKeepNameButClearDescriptionAndMoveToRoot() throws Exception {
        Category parent=category(1L,"food",null),child=category(2L,"dry",parent);when(categories.findById(2L)).thenReturn(Optional.of(child));
        var result=subject.updateCategory(2L,mapper.readValue("{\"name\":\" \"}",CategoryUpdateRequest.class));
        assertThat(result.getName()).isEqualTo("dry");assertThat(result.getDescription()).isNull();assertThat(result.getParentId()).isNull();
        assertThat(child.getParent()).isNull();assertThat(manager.commits).isEqualTo(1);
    }

    @Test void givenNewParent__whenUpdating__thenApplyNameDescriptionAndParent() throws Exception {
        Category target=category(2L,"dry",null),parent=category(1L,"food",null);
        when(categories.findById(2L)).thenReturn(Optional.of(target));when(categories.findById(1L)).thenReturn(Optional.of(parent));
        var result=subject.updateCategory(2L,mapper.readValue("{\"name\":\"wet\",\"description\":\"wet food\",\"parentId\":1}",CategoryUpdateRequest.class));
        assertThat(result.getName()).isEqualTo("wet");assertThat(result.getDescription()).isEqualTo("wet food");assertThat(result.getParentId()).isEqualTo(1L);
    }

    @Test void givenSelfOrDescendantParent__whenUpdating__thenRejectAsInputWithoutChanges() throws Exception {
        Category root=category(1L,"food",null),child=category(2L,"dry",root),grandchild=category(3L,"kibble",child);
        root.getChildren().add(child);child.getChildren().add(grandchild);
        when(categories.findById(1L)).thenReturn(Optional.of(root));when(categories.findById(3L)).thenReturn(Optional.of(grandchild));
        var self=mapper.readValue("{\"name\":\"x\",\"parentId\":1}",CategoryUpdateRequest.class);
        var descendant=mapper.readValue("{\"name\":\"x\",\"parentId\":3}",CategoryUpdateRequest.class);
        assertThatThrownBy(()->subject.updateCategory(1L,self)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->subject.updateCategory(1L,descendant)).isInstanceOf(IllegalArgumentException.class);
        assertThat(root.getName()).isEqualTo("food");assertThat(root.getParent()).isNull();assertThat(manager.rollbacks).isEqualTo(2);
    }

    @Test void givenMissingCategory__whenUpdatingOrDeleting__thenRejectAsInput() throws Exception {
        when(categories.findById(9L)).thenReturn(Optional.empty());var request=mapper.readValue("{\"name\":\"x\"}",CategoryUpdateRequest.class);
        assertThatThrownBy(()->subject.updateCategory(9L,request)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->subject.deleteCategory(9L)).isInstanceOf(IllegalArgumentException.class);verify(categories,never()).delete(any());assertThat(manager.rollbacks).isEqualTo(2);
    }

    @Test void givenChildCategory__whenDeletingParent__thenRefuseWithOriginalStateFailure() {
        Category root=category(1L,"food",null);root.getChildren().add(category(2L,"dry",root));when(categories.findById(1L)).thenReturn(Optional.of(root));
        assertThatThrownBy(()->subject.deleteCategory(1L)).isInstanceOf(IllegalStateException.class);verify(categories,never()).delete(any());assertThat(manager.rollbacks).isEqualTo(1);
    }

    @Test void givenLeafCategory__whenDeleting__thenKeepOriginalPhysicalDelete() {
        Category leaf=category(2L,"dry",null);when(categories.findById(2L)).thenReturn(Optional.of(leaf));
        subject.deleteCategory(2L);verify(categories).delete(leaf);assertThat(manager.commits).isEqualTo(1);
    }

    @Test void givenNoActiveWriter__whenCallingWithoutTransaction__thenRefuseBeforeRepositoryAccess() throws Exception {
        var outside=new JpaCatalogCategories(categories,TransactionOperations.withoutTransaction());
        var request=mapper.readValue("{\"name\":\"dry\"}",CategoryCreateRequest.class);
        assertThatThrownBy(()->outside.createCategory(request)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->outside.deleteCategory(2L)).isInstanceOf(IllegalStateException.class);verifyNoInteractions(categories);
    }

    private static Category category(long id,String name,Category parent){
        var category=Category.builder().name(name).parent(parent).build();ReflectionTestUtils.setField(category,"id",id);return category;
    }
    private static final class Writer extends AbstractPlatformTransactionManager {
        int commits,rollbacks;
        @Override protected Object doGetTransaction(){return new Object();}
        @Override protected void doBegin(Object tx,TransactionDefinition definition){}
        @Override protected void doCommit(DefaultTransactionStatus status){commits++;}
        @Override protected void doRollback(DefaultTransactionStatus status){rollbacks++;}
    }
}
