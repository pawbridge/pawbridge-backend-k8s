package com.pawbridge.backend.store.internal.catalogwrite.infrastructure;

import com.pawbridge.backend.store.internal.catalog.infrastructure.KoreanSearchTerms;
import com.pawbridge.backend.store.internal.catalogwrite.infrastructure.jpa.repository.*;
import com.pawbridge.backend.store.internal.catalogwrite.web.*;
import com.pawbridge.backend.store.internal.directorder.infrastructure.ProductSnapshots;
import com.pawbridge.backend.store.internal.directorder.infrastructure.jpa.repository.ProductRepository;
import com.pawbridge.backend.store.internal.directorder.infrastructure.jpa.repository.ProductSKURepository;
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.*;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.transaction.support.TransactionOperations;

/** Original OptionService writes; the existing catalog controller continues to own GET. */
public final class JpaCatalogOptions {
    private final OptionGroupRepository groups;
    private final OptionValueRepository values;
    private final ProductRepository products;
    private final ProductSKURepository skus;
    private final ProductSnapshots snapshots;
    private final TransactionOperations writes;

    public JpaCatalogOptions(EntityManager sharedWriter,TransactionOperations writes,
            NamedParameterJdbcOperations search,KoreanSearchTerms terms) {
        var repositories=new JpaRepositoryFactory(sharedWriter);
        this.groups=repositories.getRepository(OptionGroupRepository.class);
        this.values=repositories.getRepository(OptionValueRepository.class);
        this.products=repositories.getRepository(ProductRepository.class);
        this.skus=repositories.getRepository(ProductSKURepository.class);
        this.snapshots=new ProductSnapshots(search,terms);
        this.writes=writes;
    }

    JpaCatalogOptions(OptionGroupRepository groups,OptionValueRepository values,ProductRepository products,
            ProductSKURepository skus,ProductSnapshots snapshots,TransactionOperations writes) {
        this.groups=groups;this.values=values;this.products=products;this.skus=skus;this.snapshots=snapshots;this.writes=writes;
    }

    public OptionGroupResponse createOptionGroup(OptionGroupRequest request) {
        return writes.execute(tx->{
            JpaCatalogProducts.requireWriter();
            if(groups.existsByName(request.getName())) throw new IllegalStateException("이미 존재하는 옵션 그룹입니다: "+request.getName());
            OptionGroup group=OptionGroup.builder().name(request.getName()).build();groups.save(group);
            if(request.getValues()!=null&&!request.getValues().isEmpty()) for(String name:request.getValues()) {
                OptionValue value=OptionValue.builder().optionGroup(group).name(name).build();values.save(value);group.getOptionValues().add(value);
            }
            return OptionGroupResponse.from(group);
        });
    }

    public OptionGroupResponse updateOptionGroup(Long id,OptionGroupRequest request) {
        return writes.execute(tx->{
            JpaCatalogProducts.requireWriter();
            OptionGroup group=groups.findByIdWithLock(id).orElseThrow(()->new IllegalArgumentException("옵션 그룹을 찾을 수 없습니다: "+id));
            values.findAllByOptionGroupIdWithLock(id);
            List<Long> affected=skus.findProductIdsByOptionGroupId(id);
            group.updateName(request.getName());publishAffected(affected);
            return OptionGroupResponse.from(group);
        });
    }

    public void deleteOptionGroup(Long id) {
        writes.executeWithoutResult(tx->{
            JpaCatalogProducts.requireWriter();
            OptionGroup group=groups.findById(id).orElseThrow(()->new IllegalArgumentException("옵션 그룹을 찾을 수 없습니다: "+id));
            if(group.getOptionValues().stream().anyMatch(value->values.isOptionValueInUse(value.getId())))
                throw new IllegalStateException("사용 중인 옵션 그룹은 삭제할 수 없습니다.");
            groups.delete(group);
        });
    }

    public OptionValueResponse addOptionValue(Long groupId,OptionValueRequest request) {
        return writes.execute(tx->{
            JpaCatalogProducts.requireWriter();
            OptionGroup group=groups.findById(groupId).orElseThrow(()->new IllegalArgumentException("옵션 그룹을 찾을 수 없습니다: "+groupId));
            OptionValue value=OptionValue.builder().optionGroup(group).name(request.getName()).build();
            values.save(value);group.getOptionValues().add(value);return OptionValueResponse.from(value);
        });
    }

    public OptionValueResponse updateOptionValue(Long id,OptionValueRequest request) {
        return writes.execute(tx->{
            JpaCatalogProducts.requireWriter();
            OptionValue value=values.findByIdWithLock(id).orElseThrow(()->new IllegalArgumentException("옵션 값을 찾을 수 없습니다: "+id));
            List<Long> affected=skus.findProductIdsByOptionValueId(id);
            value.updateName(request.getName());publishAffected(affected);return OptionValueResponse.from(value);
        });
    }

    public void deleteOptionValue(Long id) {
        writes.executeWithoutResult(tx->{
            JpaCatalogProducts.requireWriter();
            OptionValue value=values.findById(id).orElseThrow(()->new IllegalArgumentException("옵션 값을 찾을 수 없습니다: "+id));
            if(values.isOptionValueInUse(id)) throw new IllegalStateException("사용 중인 옵션 값은 삭제할 수 없습니다.");
            values.delete(value);
        });
    }

    private void publishAffected(List<Long> affected) {
        affected.stream().sorted().forEach(id->{
            Product product=products.findByIdWithLock(id).orElseThrow(()->new IllegalStateException("옵션에 연결된 상품을 찾을 수 없습니다: "+id));
            if(!skus.findAllByProductIdWithLock(id).isEmpty()) snapshots.publishProductSnapshot(product);
        });
    }
}
