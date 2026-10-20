package com.pawbridge.backend.boot.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.animal.internal.command.infrastructure.service.AnimalCommandService;
import com.pawbridge.backend.animal.internal.lostsearch.infrastructure.LostSearchService;
import com.pawbridge.backend.animal.internal.lostsearch.infrastructure.PythonLostSearchClient;
import com.pawbridge.backend.animal.internal.lostsearch.web.LostSearchController;
import com.pawbridge.backend.boot.security.PostTokenAuthenticator;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.SecretKey;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AnimalLostSearchConfigurationTest {
    @ParameterizedTest @ValueSource(strings={"absent","false","yes","on","1"})
    void given__feature_not_explicitly_true__when__assembling_without_inputs__then__create_no_reader_or_client(String flag) {
        var runner=runner();
        if(!flag.equals("absent")) runner=runner.withPropertyValues("pawbridge.animal-lost-search.enabled="+flag);
        runner.run(context->assertThat(context).hasNotFailed().doesNotHaveBean(EntityManagerFactory.class)
                .doesNotHaveBean(PythonLostSearchClient.class).doesNotHaveBean(LostSearchService.class)
                .doesNotHaveBean(LostSearchController.class));
    }

    @ParameterizedTest @ValueSource(booleans={true,false})
    void given__explicit_feature_and_urls__when__assembling__then__select_the_reader_unit_and_url_without_connecting(boolean preferred) {
        DataSource reader=mock(DataSource.class),writer=mock(DataSource.class);
        AtomicReference<EntityManagerFactory> owned=new AtomicReference<>();
        var runner=readerInputs(reader).withBean("writeDataSource",DataSource.class,()->writer)
                .withPropertyValues("pawbridge.animal-lost-search.enabled=TrUe","python-ai-service.url=https://fallback.example.invalid");
        if(preferred) runner=runner.withPropertyValues("lost-search.python-url=https://preferred.example.invalid");
        runner.run(context->{
            assertThat(context).hasNotFailed().hasSingleBean(LostSearchService.class).hasSingleBean(LostSearchController.class);
            var emf=context.getBean("animalLostSearchEntityManagerFactory",EntityManagerFactory.class);owned.set(emf);
            var factory=context.getBean("&animalLostSearchEntityManagerFactory",LocalContainerEntityManagerFactoryBean.class);
            String model="com.pawbridge.backend.animal.internal.command.infrastructure.entity.";
            assertThat(factory.getPersistenceUnitInfo().getManagedClassNames()).containsExactlyInAnyOrder(model+"Animal",model+"Shelter",model+"BaseTimeEntity");
            assertThat(factory.getDataSource()).isSameAs(reader);
            assertThat(factory.getJpaPropertyMap()).containsEntry("hibernate.boot.allow_jdbc_metadata_access",false)
                    .containsEntry("hibernate.hbm2ddl.auto","none").containsEntry("jakarta.persistence.schema-generation.database.action","none")
                    .containsEntry("hibernate.default_schema","pawbridge_animal").containsEntry("hibernate.jdbc.time_zone","UTC");
            var manager=context.getBean("animalLostSearchTransactions",JpaTransactionManager.class);
            assertThat(manager.getDataSource()).isSameAs(reader);assertThat(manager.getEntityManagerFactory()).isSameAs(emf);
            assertThat(manager.getDefaultTimeout()).isEqualTo(5);
            var service=context.getBean(LostSearchService.class);
            var reads=(TransactionTemplate)ReflectionTestUtils.getField(service,"reads");
            assertThat(reads.getTransactionManager()).isSameAs(manager);assertThat(reads.isReadOnly()).isTrue();
            assertThat(reads.getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_DEFAULT);
            assertThat(ReflectionTestUtils.getField(service,"internalKey")).isEqualTo("p10-public-synthetic-key");
            assertThat(repositoryUnit(ReflectionTestUtils.getField(service,"animals"))).isSameAs(emf);
            Object endpoint=ReflectionTestUtils.getField(context.getBean(PythonLostSearchClient.class),"endpoint");
            assertThat(endpoint.toString()).contains(preferred?"https://preferred.example.invalid":"https://fallback.example.invalid");
            assertThat(context.getBeansOfType(DataSource.class)).hasSize(2);
            assertThat(context.getBeansOfType(EntityManagerFactory.class)).hasSize(1);
            assertThat(context).doesNotHaveBean("jpaAuditingHandler").doesNotHaveBean(AnimalCommandService.class);
            verifyNoInteractions(reader,writer);
        });
        assertThat(owned.get().isOpen()).isFalse();
    }

    @Test
    void given__commands_and_photo_search_enabled__when__assembling__then__keep_each_repository_on_its_named_reader_or_writer_unit() {
        DataSource reader=mock(DataSource.class),writer=mock(DataSource.class);
        AtomicReference<List<EntityManagerFactory>> units=new AtomicReference<>();
        readerInputs(reader).withBean("writeDataSource",DataSource.class,()->writer)
                .withBean("writeJdbc",JdbcTemplate.class,()->new JdbcTemplate(writer))
                .withPropertyValues("pawbridge.animal-lost-search.enabled=true","pawbridge.animal-commands.enabled=true",
                        "lost-search.python-url=https://fixture.example.invalid").run(context->{
                    assertThat(context).hasNotFailed();
                    units.set(List.copyOf(context.getBeansOfType(EntityManagerFactory.class).values()));assertThat(units.get()).hasSize(2);
                    var read=context.getBean("animalLostSearchEntityManagerFactory",EntityManagerFactory.class);
                    var write=context.getBean("animalCommandEntityManagerFactory",EntityManagerFactory.class);
                    assertThat(read).isNotSameAs(write);
                    assertThat(repositoryUnit(ReflectionTestUtils.getField(context.getBean(LostSearchService.class),"animals"))).isSameAs(read);
                    assertThat(repositoryUnit(ReflectionTestUtils.getField(context.getBean(AnimalCommandService.class),"animalRepository"))).isSameAs(write);
                    assertThat(context.getBean("animalLostSearchTransactions",JpaTransactionManager.class).getDataSource()).isSameAs(reader);
                    assertThat(context.getBean("animalCommandJpaTransactionManager",JpaTransactionManager.class).getDataSource()).isSameAs(writer);
                    verifyNoInteractions(reader,writer);
                });
        assertThat(units.get()).allSatisfy(factory->assertThat(factory.isOpen()).isFalse());
    }

    @Test
    void given__feature_enabled_without_a_python_url__when__assembling__then__fail_instead_of_selecting_an_external_fallback() {
        DataSource reader=mock(DataSource.class);
        readerInputs(reader).withPropertyValues("pawbridge.animal-lost-search.enabled=true").run(context->{
            assertThat(context).hasFailed();verifyNoInteractions(reader);
        });
    }

    private static EntityManagerFactory repositoryUnit(Object repository) {
        Object target=AopTestUtils.getUltimateTargetObject(repository);
        return ((EntityManager)ReflectionTestUtils.getField(target,"entityManager")).getEntityManagerFactory();
    }
    private static ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
                .withUserConfiguration(AnimalLostSearchConfiguration.class,AnimalCommandConfiguration.class);
    }
    private static ApplicationContextRunner readerInputs(DataSource reader) {
        return runner().withBean("dataSource",DataSource.class,()->reader).withBean(ObjectMapper.class,ObjectMapper::new)
                .withPropertyValues("python-ai-service.internal-api-key=p10-public-synthetic-key");
    }
}

@Configuration(proxyBeanMethods=false)
class LostSearchTestSecurity {
    static final SecretKey KEY=Keys.hmacShaKeyFor("p10-public-synthetic-security-key-only-for-test".getBytes(StandardCharsets.UTF_8));
    @Bean PostTokenAuthenticator tokens(){return new PostTokenAuthenticator(KEY);}
    static String token(){return "Bearer "+Jwts.builder().claim("userId",7L).claim("role","ROLE_ADMIN")
            .expiration(new Date(System.currentTimeMillis()+300_000)).signWith(KEY).compact();}
}

@WebMvcTest(excludeAutoConfiguration=org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@ContextConfiguration(classes={LostSearchController.class,ReadSecurityConfiguration.class,LostSearchTestSecurity.class})
class AnimalLostSearchDisabledHttpTest {
    @Autowired MockMvc mvc;@MockitoBean LostSearchService service;
    @Test void given__default_off__when__verified_user_uploads__then__deny_before_search() throws Exception {
        mvc.perform(multipart("/api/v1/animals/lost-candidates").file(new MockMultipartFile("image",new byte[]{1}))
                .param("species","DOG").header("Authorization",LostSearchTestSecurity.token())).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }
}

@WebMvcTest(properties="pawbridge.animal-lost-search.enabled=on",excludeAutoConfiguration=org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@ContextConfiguration(classes={LostSearchController.class,ReadSecurityConfiguration.class,LostSearchTestSecurity.class})
class AnimalLostSearchAliasDisabledHttpTest {
    @Autowired MockMvc mvc;@MockitoBean LostSearchService service;
    @Test void given__alias_on__when__verified_user_uploads__then__keep_owner_closed() throws Exception {
        mvc.perform(multipart("/api/v1/animals/lost-candidates").file(new MockMultipartFile("image",new byte[]{1}))
                .param("species","DOG").header("Authorization",LostSearchTestSecurity.token())).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }
}

@WebMvcTest(properties="pawbridge.animal-lost-search.enabled=true",excludeAutoConfiguration=org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@ContextConfiguration(classes={LostSearchController.class,ReadSecurityConfiguration.class,LostSearchTestSecurity.class})
class AnimalLostSearchNeighborHttpTest {
    @Autowired MockMvc mvc;@MockitoBean LostSearchService service;
    @Test void given__only_public_photo_post_enabled__when__using_get_or_deeper_post__then__deny_both_neighbors() throws Exception {
        mvc.perform(get("/api/v1/animals/lost-candidates").header("Authorization",LostSearchTestSecurity.token())).andExpect(status().isForbidden());
        mvc.perform(multipart("/api/v1/animals/lost-candidates/extra").file(new MockMultipartFile("image",new byte[]{1}))
                .param("species","DOG").header("Authorization",LostSearchTestSecurity.token())).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }
}
