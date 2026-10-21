package com.pawbridge.backend.store.internal.catalogwrite.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.store.internal.catalogwrite.infrastructure.jpa.repository.*;
import com.pawbridge.backend.store.internal.catalogwrite.web.SkuCreateDto;
import com.pawbridge.backend.store.internal.directorder.infrastructure.jpa.repository.ProductSKURepository;
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProductSkusTest {
    private final ProductSKURepository skus=mock(ProductSKURepository.class);
    private final SKUValueRepository links=mock(SKUValueRepository.class);
    private final OptionValueRepository options=mock(OptionValueRepository.class);
    private final ProductSkus subject=new ProductSkus(skus,links,options);

    @Test void givenNoSkuRequests__whenCreating__thenKeepOriginalEmptyResult(){
        assertThat(subject.createSkus(Product.builder().build(),null)).isEmpty();assertThat(subject.createSkus(Product.builder().build(),List.of())).isEmpty();verifyNoInteractions(skus,links,options);
    }

    @Test void givenUnorderedOptionIds__whenCreating__thenLockDistinctIdsFirstAndAttachOriginalLinks() throws Exception {
        Product product=Product.builder().name("food").status(ProductStatus.ACTIVE).build();
        OptionGroup group=OptionGroup.builder().name("size").build();
        OptionValue first=OptionValue.builder().optionGroup(group).name("small").build(),second=OptionValue.builder().optionGroup(group).name("large").build();
        ReflectionTestUtils.setField(first,"id",1L);ReflectionTestUtils.setField(second,"id",2L);
        when(options.findAllByIdWithLock(List.of(1L,2L))).thenReturn(List.of(first,second));
        var dto=new ObjectMapper().readValue("{\"skuCode\":\"FOOD-L\",\"price\":100,\"stockQuantity\":3,\"optionValueIds\":[2,1]}",SkuCreateDto.class);
        var created=subject.createSkus(product,List.of(dto));
        assertThat(created).hasSize(1);assertThat(product.getSkus()).containsExactlyElementsOf(created);
        assertThat(created.getFirst().getSkuCode()).isEqualTo("FOOD-L");assertThat(created.getFirst().getPrice()).isEqualTo(100L);assertThat(created.getFirst().getStockQuantity()).isEqualTo(3);
        assertThat(created.getFirst().getSkuValues()).extracting(SKUValue::getOptionValue).containsExactly(second,first);
        var order=inOrder(options,skus,links);order.verify(options).findAllByIdWithLock(List.of(1L,2L));order.verify(skus).save(created.getFirst());order.verify(links,times(2)).save(any());
    }

    @Test void givenMissingOptionValue__whenCreating__thenRefuseBeforeAnySkuOrLinkSave() throws Exception {
        when(options.findAllByIdWithLock(List.of(9L))).thenReturn(List.of());
        var dto=new ObjectMapper().readValue("{\"skuCode\":\"FOOD-L\",\"price\":100,\"stockQuantity\":3,\"optionValueIds\":[9]}",SkuCreateDto.class);
        assertThatThrownBy(()->subject.createSkus(Product.builder().build(),List.of(dto))).isInstanceOf(IllegalArgumentException.class);verifyNoInteractions(skus,links);
    }
}
