package com.pawbridge.userservice.migration;

import com.pawbridge.userservice.client.AnimalServiceClient;
import com.pawbridge.userservice.entity.*;
import com.pawbridge.userservice.jwt.JwtProvider;
import com.pawbridge.userservice.repository.UserRepository;
import com.pawbridge.userservice.shelter.*;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.SessionFactory;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.aop.framework.ProxyFactory;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

/** Real MySQL/JPA repositories and production transaction annotations; only external lookup/token parsing are stubbed. */
final class ShelterTransactionHarness implements AutoCloseable {
    final StandardServiceRegistry registry;
    final SessionFactory factory;
    final AnimalServiceClient animals = mock(AnimalServiceClient.class);
    final CountDownLatch secondRead = new CountDownLatch(1);
    final ShelterApplicationService service;
    final UserRepository users;
    final ShelterApplicationRepository applications;
    final TransactionTemplate tx;
    final Long memberId, applicationId;
    final String adminToken = "Bearer admin", otherAdminToken = "Bearer other-admin";

    ShelterTransactionHarness(String url, String username, String password) {
        registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.connection.url", url)
                .applySetting("hibernate.connection.username", username)
                .applySetting("hibernate.connection.password", password)
                .applySetting("hibernate.hbm2ddl.auto", "validate")
                .applySetting("hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy")
                .build();
        factory = new MetadataSources(registry).addAnnotatedClass(User.class)
                .addAnnotatedClass(ShelterApplication.class).buildMetadata().buildSessionFactory();
        var em = SharedEntityManagerCreator.createSharedEntityManager(factory);
        var repositories = new JpaRepositoryFactory(em);
        users = repositories.getRepository(UserRepository.class);
        applications = repositories.getRepository(ShelterApplicationRepository.class);
        var manager = new JpaTransactionManager(factory);
        tx = new TransactionTemplate(manager);
        memberId = tx.execute(status -> users.save(member("member", Role.ROLE_USER)).getUserId());
        Long adminId = tx.execute(status -> users.save(member("admin", Role.ROLE_ADMIN)).getUserId());
        Long otherAdminId = tx.execute(status -> users.save(member("otheradmin", Role.ROLE_ADMIN)).getUserId());
        applicationId = tx.execute(status -> applications.save(ShelterApplication.request(memberId, "shelter")).getId());
        var reads = new AtomicInteger();
        var repositoryProxy = new ProxyFactory(applications);
        repositoryProxy.addAdvice((org.aopalliance.intercept.MethodInterceptor) call -> {
            var result = call.proceed();
            if (call.getMethod().getName().equals("findById") && reads.incrementAndGet() == 2) secondRead.countDown();
            return result;
        });
        var observed = (ShelterApplicationRepository) repositoryProxy.getProxy();
        var jwt = mock(JwtProvider.class);
        when(jwt.getAccessUserId("admin")).thenReturn(adminId);
        when(jwt.getAccessUserId("other-admin")).thenReturn(otherAdminId);
        var proxy = new ProxyFactory(new ShelterApplicationService(observed, users, animals, jwt, em));
        var transactionAdvice = new TransactionInterceptor();
        transactionAdvice.setTransactionManager(manager);
        transactionAdvice.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        proxy.addAdvice(transactionAdvice);
        service = (ShelterApplicationService) proxy.getProxy();
    }
    private User member(String name, Role role) {
        return User.createLocalUser(name + "@example.invalid", name, "unused", name, role, null);
    }
    void assertStored(ShelterApplicationStatus status, Role role, String registration) {
        tx.executeWithoutResult(ignored -> {
            var application = applications.findById(applicationId).orElseThrow();
            var user = users.findById(memberId).orElseThrow();
            assertThat(application.getStatus()).isEqualTo(status);
            assertThat(user.getRole()).isEqualTo(role);
            assertThat(application.getCareRegNo()).isEqualTo(registration);
            assertThat(user.getCareRegNo()).isEqualTo(registration);
            if (status == ShelterApplicationStatus.PENDING) {
                assertThat(application.getReviewedAt()).isNull();
                assertThat(application.getReviewedBy()).isNull();
            }
        });
    }
    public void close() {
        factory.close();
        StandardServiceRegistryBuilder.destroy(registry);
    }
}
