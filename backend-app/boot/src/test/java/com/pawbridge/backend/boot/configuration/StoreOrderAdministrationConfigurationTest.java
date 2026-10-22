package com.pawbridge.backend.boot.configuration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.store.api.StoreOrderAdministrators;
import com.pawbridge.backend.store.internal.catalog.infrastructure.KoreanSearchTerms;
import com.pawbridge.backend.store.internal.directorder.web.DirectOrderController;
import com.pawbridge.backend.store.internal.directorder.web.OrderReadController;
import com.pawbridge.backend.store.internal.cart.web.CartController;
import com.pawbridge.backend.store.internal.orderadministration.application.AdminOrders;
import com.pawbridge.backend.store.internal.orderadministration.web.AdminOrderController;
import com.pawbridge.backend.store.internal.orderadministration.web.AdminOrderExceptionHandler;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.auditing.AuditingHandler;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

class StoreOrderAdministrationConfigurationTest {
    @ParameterizedTest @ValueSource(strings={"absent","false","yes","on","1","unknown"})
    void given__administration_not_explicitly_enabled__when__assembling_without_database_inputs__then__register_no_admin_or_writer_beans(String flag){
        var runner=runner();if(!flag.equals("absent"))runner=runner.withPropertyValues("pawbridge.store-order-administration.enabled="+flag);
        runner.run(context->assertThat(context).hasNotFailed().doesNotHaveBean(AdminOrders.class)
                .doesNotHaveBean(StoreOrderAdministrators.class).doesNotHaveBean(AdminOrderController.class)
                .doesNotHaveBean(EntityManagerFactory.class).doesNotHaveBean(StoreJpaWriterConfiguration.class)
                .doesNotHaveBean(AuditingHandler.class).doesNotHaveBean(KoreanSearchTerms.class));
    }
    @ParameterizedTest @ValueSource(strings={"admin","admin-and-direct","all"})
    void given__explicit_admin_and_optional_personal_features__when__assembling__then__share_one_writer_without_exposing_disabled_routes(String mode){
        boolean direct=!mode.equals("admin"),carts=mode.equals("all");
        DataSource writer=mock(DataSource.class),reader=mock(DataSource.class);
        PlatformTransactionManager readerManager=mock(PlatformTransactionManager.class);
        AtomicReference<EntityManagerFactory> unit=new AtomicReference<>();
        runner().withBean("writeDataSource",DataSource.class,()->writer)
                .withBean("writeJdbc",JdbcTemplate.class,()->new JdbcTemplate(writer))
                .withBean("dataSource",DataSource.class,()->reader)
                .withBean("transactionManager",PlatformTransactionManager.class,()->readerManager)
                .withBean(ObjectMapper.class,ObjectMapper::new)
                .withPropertyValues("pawbridge.store-order-administration.enabled=TrUe",
                        "pawbridge.store-direct-orders.enabled="+direct,"pawbridge.store-carts.enabled="+carts)
                .run(context->{
                    assertThat(context).hasNotFailed().hasSingleBean(AdminOrders.class).hasSingleBean(AdminOrderController.class)
                            .hasSingleBean(AdminOrderExceptionHandler.class).hasSingleBean(StoreOrderAdministrators.class)
                            .hasSingleBean(StoreJpaWriterConfiguration.class).hasSingleBean(EntityManagerFactory.class)
                            .hasSingleBean(JpaTransactionManager.class).hasSingleBean(AuditingHandler.class);
                    unit.set(context.getBean("storeDirectOrderEntityManagerFactory",EntityManagerFactory.class));
                    var factory=context.getBean("&storeDirectOrderEntityManagerFactory",LocalContainerEntityManagerFactoryBean.class);
                    assertThat(factory.getDataSource()).isSameAs(writer).isNotSameAs(reader);
                    assertThat(factory.getPersistenceUnitInfo().getManagedClassNames()).hasSize(carts?12:10);
                    assertThat(factory.getJpaPropertyMap()).containsEntry("hibernate.boot.allow_jdbc_metadata_access",false)
                            .containsEntry("hibernate.hbm2ddl.auto","none").containsEntry("hibernate.default_schema","pawbridge_store");
                    var manager=context.getBean("storeDirectOrderJpaTransactionManager",JpaTransactionManager.class);
                    assertThat(manager.getDataSource()).isSameAs(writer);assertThat(manager.getDefaultTimeout()).isEqualTo(15);
                    var reads=context.getBean("storeDirectOrderReads",TransactionTemplate.class);
                    var writes=context.getBean("storeDirectOrderWrites",TransactionTemplate.class);
                    assertThat(reads.isReadOnly()).isTrue();assertThat(writes.isReadOnly()).isFalse();
                    for(var tx:java.util.List.of(reads,writes)){
                        assertThat(tx.getTransactionManager()).isSameAs(manager).isNotSameAs(readerManager);
                        assertThat(tx.getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRED);
                    }
                    var jdbc=(JdbcTemplate)ReflectionTestUtils.getField(context.getBean(StoreOrderAdministrators.class),"jdbc");
                    assertThat(jdbc.getDataSource()).isSameAs(writer);
                    assertThat(context.getBeansOfType(DirectOrderController.class)).hasSize(direct?1:0);
                    assertThat(context.getBeansOfType(CartController.class)).hasSize(carts?1:0);
                    assertThat(context.getBeansOfType(OrderReadController.class)).hasSize(direct||carts?1:0);
                    assertThat(context.getBeansOfType(KoreanSearchTerms.class)).hasSize(direct||carts?1:0);
                    assertThat(context.getBeansOfType(StoreOrderSupportConfiguration.class)).hasSize(direct||carts?1:0);
                    EntityManager shared=context.getBean("storeDirectOrderEntityManager",EntityManager.class);
                    assertThat(shared.getEntityManagerFactory()).isSameAs(unit.get());
                    verifyNoInteractions(writer,reader,readerManager);
                });
        assertThat(unit.get().isOpen()).isFalse();
    }
    @ParameterizedTest @ValueSource(strings={"reader-only","mismatched-jdbc"})
    void given__no_matching_writer__when__administration_is_enabled__then__fail_without_reader_fallback(String mode){
        DataSource writer=mock(DataSource.class),reader=mock(DataSource.class);
        var runner=runner().withPropertyValues("pawbridge.store-order-administration.enabled=true");
        if(mode.equals("reader-only"))runner=runner.withBean("dataSource",DataSource.class,()->reader);
        else runner=runner.withBean("writeDataSource",DataSource.class,()->writer)
                .withBean("writeJdbc",JdbcTemplate.class,()->new JdbcTemplate(reader));
        runner.run(context->{assertThat(context).hasFailed();verifyNoInteractions(writer,reader);});
    }
    private static ApplicationContextRunner runner(){
        return new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
                .withUserConfiguration(StoreOrderAdministrationConfiguration.class,StoreDirectOrderConfiguration.class,StoreCartConfiguration.class);
    }
}
