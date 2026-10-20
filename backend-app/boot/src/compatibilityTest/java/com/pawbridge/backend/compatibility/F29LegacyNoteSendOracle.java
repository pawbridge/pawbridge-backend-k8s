package com.pawbridge.backend.compatibility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.pawbridge.communityservice.client.UserServiceClient;
import com.pawbridge.communityservice.contact.F29LegacyNoteSendService;
import com.pawbridge.communityservice.contact.PrivateNoteModels;
import com.pawbridge.communityservice.contact.PrivateNoteStream;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.concurrent.atomic.AtomicInteger;
import static org.mockito.Mockito.doAnswer;
import com.pawbridge.communityservice.contact.PrivateNoteRepository;
import feign.FeignException;
import feign.Request;
import feign.Response;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Selected original send/afterCommit under actual Hibernate/JpaTM; each source User query runs on one independent bounded thread.
 * EMF bootstrap connections precede request observations; this does not run whole legacy HTTP/Feign. */
final class F29LegacyNoteSendOracle implements AutoCloseable {
    private final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
    private final ThreadPoolExecutor userThread = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(1), action -> {
                var thread = new Thread(action, "f29-original-user-boundary"); thread.setDaemon(true); return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private final F29LegacyNoteSendService original;
    private final DataSource referenceDataSource;
    private final JpaTransactionManager manager;
    private final AtomicInteger insertAttempts = new AtomicInteger();
    private final List<UUID> generatedIds = new CopyOnWriteArrayList<>();
    private final List<UUID> insertedIds = new CopyOnWriteArrayList<>();
    private final List<Integer> affectedRows = new CopyOnWriteArrayList<>();
    private final List<Long> contactIds = new CopyOnWriteArrayList<>();

    F29LegacyNoteSendOracle(F3PostgresqlFixture fixture, DataSource reader, Clock clock, BiConsumer<Long, PrivateNoteModels.Notification> committedHint) throws Exception {
        referenceDataSource = new AbstractDataSource() {
            @Override public Connection getConnection() throws SQLException {
                Connection connection = fixture.admin();
                try (var statement = connection.createStatement()) {
                    statement.execute("SET statement_timeout='18s'; SET lock_timeout='10s'; SET application_name='f29-original-note-send'");
                } catch (SQLException | RuntimeException failure) { connection.close(); throw failure; }
                return connection;
            }
            @Override public Connection getConnection(String user, String password) throws SQLException {
                throw new SQLException("Only the owned reference datasource is permitted");
            }
        };
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("f29-owned-original-jpa", Map.of(
                "spring.jpa.hibernate.ddl-auto", "none", "spring.jpa.open-in-view", "false",
                "spring.jpa.properties.hibernate.default_schema", "pawbridge_community",
                "spring.jpa.properties.hibernate.jdbc.time_zone", "UTC")));
        context.registerBean("dataSource", DataSource.class, () -> referenceDataSource);
        // The existing F5 reference owns the actual entity/auditing/Hibernate auto-configuration shape.
        context.registerBean("legacyUpdateAuditTime", DateTimeProvider.class,
                () -> () -> Optional.of(LocalDateTime.of(2031, 1, 1, 0, 0)));
        context.register(F5LegacyPostOracle.LegacyJpa.class);
        try { context.refresh(); }
        catch (RuntimeException | Error failure) { userThread.shutdownNow(); context.close(); throw failure; }
        try {
        PlatformTransactionManager actual = context.getBean(PlatformTransactionManager.class);
        assertThat(actual).isInstanceOf(JpaTransactionManager.class);
        manager = (JpaTransactionManager) actual;
        assertThat(manager.getDataSource()).isSameAs(referenceDataSource);
        var transaction = new TransactionTemplate(manager); transaction.setTimeout(15);
        var jdbc = new JdbcTemplate(referenceDataSource) {
            @Override public int update(String sql, Object... arguments) {
                String normalized = sql.replaceAll("\\s+", " ").strip();
                boolean insertion = normalized.startsWith("INSERT INTO pawbridge_community.private_notes(")
                        || normalized.startsWith("INSERT INTO pawbridge_community.private_note_mailboxes(")
                        || normalized.startsWith("INSERT INTO pawbridge_community.private_note_requests(");
                if (normalized.startsWith("INSERT INTO pawbridge_community.private_notes(")) generatedIds.add((UUID)arguments[0]);
                if (insertion) insertAttempts.incrementAndGet();
                int rows = super.update(sql, arguments);
                if (normalized.startsWith("INSERT INTO pawbridge_community.private_notes(") && rows == 1) insertedIds.add((UUID)arguments[0]);
                if (insertion) affectedRows.add(rows);
                return rows;
            }
        };
        var oldJdbc = new JdbcTemplate(reader); oldJdbc.setQueryTimeout(3);
        var oldContacts = new com.pawbridge.userservice.contact.ContactMemberQuery(oldJdbc);
        UserServiceClient client = mock(UserServiceClient.class);
        when(client.getContactMember(anyLong())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(TransactionSynchronizationManager.hasResource(referenceDataSource)).isTrue();
            long id = invocation.getArgument(0, Long.class);
            contactIds.add(id);
            var task = userThread.submit(() -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
                assertThat(TransactionSynchronizationManager.hasResource(reader)).isFalse();
                try {
                    var member = oldContacts.get(id);
                    return new PrivateNoteModels.ContactMember(member.userId(), member.nickname(), member.active(), member.deletionPending());
                } finally {
                    assertThat(TransactionSynchronizationManager.hasResource(reader)).isFalse();
                    assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
                    assertThat(((com.zaxxer.hikari.HikariDataSource) reader).getHikariPoolMXBean().getActiveConnections()).isZero();
                }
            });
            try { return task.get(5, TimeUnit.SECONDS); }
            catch (ExecutionException failure) {
                if (failure.getCause() instanceof Error error) throw error;
                throw unavailable();
            }
            catch (InterruptedException failure) { task.cancel(true); Thread.currentThread().interrupt(); throw unavailable(); }
            catch (java.util.concurrent.TimeoutException failure) { task.cancel(true); throw unavailable(); }
            finally {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                assertThat(TransactionSynchronizationManager.hasResource(referenceDataSource)).isTrue();
                assertThat(TransactionSynchronizationManager.hasResource(reader)).isFalse();
                assertThat(((com.zaxxer.hikari.HikariDataSource) reader).getHikariPoolMXBean().getActiveConnections()).isZero();
            }
        });
        PrivateNoteStream stream = mock(PrivateNoteStream.class); // Constructor is not invoked; no original heartbeat/executor is started.
        doAnswer(invocation -> { committedHint.accept(invocation.getArgument(0, Long.class), invocation.getArgument(1, PrivateNoteModels.Notification.class)); return null; })
                .when(stream).publish(anyLong(), org.mockito.ArgumentMatchers.any(PrivateNoteModels.Notification.class));
        original = new F29LegacyNoteSendService(new PrivateNoteRepository(jdbc), client, transaction, clock, stream);
        } catch (RuntimeException | Error failure) {
            userThread.shutdownNow(); context.close(); throw failure;
        }
    }

    /** F30 reuses the exact selected void source expiry method under this same JpaTM/Clock. */
    void expire() { original.expire(); }

    UUID send(long caller, PrivateNoteModels.SendNote request) { return original.send(caller, request).noteId(); }
    int insertAttempts() { return insertAttempts.get(); }
    List<UUID> generatedIds() { return List.copyOf(generatedIds); }
    List<UUID> insertedIds() { return List.copyOf(insertedIds); }
    List<Integer> affectedRows() { return List.copyOf(affectedRows); }
    List<Long> contactIds() { return List.copyOf(contactIds); }
    void resetObservations() { contactIds.clear(); insertAttempts.set(0); affectedRows.clear(); generatedIds.clear(); insertedIds.clear(); }
    boolean isResourceBound() { return TransactionSynchronizationManager.hasResource(referenceDataSource); }

    private FeignException unavailable() {
        var request = Request.create(Request.HttpMethod.GET, "https://fixture.invalid/contact", Map.of(), new byte[0], StandardCharsets.UTF_8, null);
        return FeignException.errorStatus("synthetic", Response.builder().status(500).reason("synthetic").request(request).headers(Map.of()).build());
    }

    @Override public void close() {
        userThread.shutdownNow();
        try { assertThat(userThread.awaitTermination(5, TimeUnit.SECONDS)).isTrue(); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException("Original user boundary did not terminate"); }
        finally { context.close(); }
    }

    /** Late member-cleanup only. Reuses this Community JpaTM/datasource/User thread;
     * adds the distinct original User JpaTM (User schema), never a new app pool. */
    LegacyMemberCleanup memberCleanup(F3PostgresqlFixture fixture, DataSource reader,
            Runnable pendingVisible, Runnable afterCommunity, java.util.function.Consumer<String> committed) {
        return new LegacyMemberCleanup(fixture, reader, pendingVisible, afterCommunity, committed);
    }

    final class LegacyMemberCleanup implements AutoCloseable {
        private final AnnotationConfigApplicationContext userContext = new AnnotationConfigApplicationContext();
        private final AnnotationConfigApplicationContext eventContext = new AnnotationConfigApplicationContext();
        private final DataSource userDataSource;
        private final com.pawbridge.userservice.contact.ContactMemberDeletion deletion;
        private final List<Long> observedContacts = new CopyOnWriteArrayList<>();
        private final AtomicInteger chatCalls = new AtomicInteger();
        private RuntimeException communityFailure;
        private Long loadedUserHookId;
        private Runnable loadedUserHook;
        private final AtomicInteger loadedUserHookCalls = new AtomicInteger();

        LegacyMemberCleanup(F3PostgresqlFixture fixture, DataSource reader, Runnable pendingVisible,
                Runnable afterCommunity, java.util.function.Consumer<String> committed) {
            userDataSource = new AbstractDataSource() {
                @Override public Connection getConnection() throws SQLException {
                    Connection connection = fixture.admin();
                    try (var statement = connection.createStatement()) {
                        statement.execute("SET statement_timeout='18s'; SET lock_timeout='10s'; SET application_name='member-cleanup-source-user'");
                    } catch (SQLException | RuntimeException failure) { connection.close(); throw failure; }
                    return connection;
                }
                @Override public Connection getConnection(String user, String password) throws SQLException {
                    throw new SQLException("Only the owned User reference datasource is permitted");
                }
            };
            try {
                userContext.getEnvironment().getPropertySources().addFirst(new MapPropertySource("member-cleanup-user-jpa", Map.of(
                        "spring.jpa.hibernate.ddl-auto", "none", "spring.jpa.open-in-view", "false",
                        "spring.jpa.properties.hibernate.default_schema", "pawbridge_user",
                        "spring.jpa.properties.hibernate.jdbc.time_zone", "UTC")));
                userContext.registerBean("dataSource", DataSource.class, () -> userDataSource);
                userContext.register(F19LegacyTotalUserCountOracle.LegacyJpa.class);
                userContext.refresh();
                var userManager = userContext.getBean(PlatformTransactionManager.class);
                assertThat(userManager).isInstanceOf(JpaTransactionManager.class);
                assertThat(((JpaTransactionManager)userManager).getDataSource()).isSameAs(userDataSource);
                var entityManager = org.springframework.orm.jpa.SharedEntityManagerCreator.createSharedEntityManager(
                        userContext.getBean(jakarta.persistence.EntityManagerFactory.class));
                // The real inherited find/delete/flush remain in place; only this optional post-find observation is injected.
                var observedEntityManager = (jakarta.persistence.EntityManager)java.lang.reflect.Proxy.newProxyInstance(
                        jakarta.persistence.EntityManager.class.getClassLoader(),
                        new Class<?>[]{jakarta.persistence.EntityManager.class}, (proxy, method, args) -> {
                            Object result;
                            try { result=method.invoke(entityManager,args); }
                            catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}
                            if(loadedUserHook!=null && method.getName().equals("find") && args!=null && args.length>=2
                                    && args[0]==com.pawbridge.userservice.entity.User.class
                                    && java.util.Objects.equals(args[1],loadedUserHookId) && result!=null){
                                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                                assertThat(TransactionSynchronizationManager.hasResource(userDataSource)).isTrue();
                                assertThat(entityManager.contains(result)).isTrue();
                                var action=loadedUserHook;loadedUserHook=null;loadedUserHookCalls.incrementAndGet();action.run();
                            }
                            return result;
                        });
                var inherited = new org.springframework.data.jpa.repository.support.SimpleJpaRepository<
                        com.pawbridge.userservice.entity.User, Long>(com.pawbridge.userservice.entity.User.class, observedEntityManager);
                // Narrow only the two actually called inherited operations; no repository query bootstrap/mock delete.
                var repository = (com.pawbridge.userservice.repository.UserRepository)java.lang.reflect.Proxy.newProxyInstance(
                        com.pawbridge.userservice.repository.UserRepository.class.getClassLoader(),
                        new Class<?>[]{com.pawbridge.userservice.repository.UserRepository.class}, (proxy, method, args) -> {
                            if (method.getName().equals("deleteById") && method.getParameterCount()==1) {
                                inherited.deleteById((Long)args[0]); return null;
                            }
                            if (method.getName().equals("flush") && method.getParameterCount()==0) {
                                inherited.flush(); return null;
                            }
                            throw new UnsupportedOperationException("Unselected original User repository operation");
                        });
                var jdbc = new JdbcTemplate(referenceDataSource);
                var sourceChat = new com.pawbridge.communityservice.chat.MemberChatRepository(jdbc) {
                    @Override public void withdraw(long id) {
                        assertThat(TransactionSynchronizationManager.hasResource(referenceDataSource)).isTrue();
                        assertThat(TransactionSynchronizationManager.hasResource(userDataSource)).isFalse();
                        chatCalls.incrementAndGet();
                        super.withdraw(id);
                    }
                };
                eventContext.registerBean(com.pawbridge.communityservice.chat.MemberChatDataLifecycle.class,
                        () -> new com.pawbridge.communityservice.chat.MemberChatDataLifecycle(
                                sourceChat, Clock.systemUTC(), manager, false));
                // @EventListener is synchronous; no @EnableScheduling/bean scan/Redis transport is registered.
                eventContext.refresh();
                var sourceContacts = new com.pawbridge.userservice.contact.ContactMemberQuery(new JdbcTemplate(reader));
                UserServiceClient client = mock(UserServiceClient.class);
                when(client.getContactMember(anyLong())).thenAnswer(invocation -> {
                    assertThat(TransactionSynchronizationManager.hasResource(referenceDataSource)).isTrue();
                    assertThat(TransactionSynchronizationManager.hasResource(userDataSource)).isFalse();
                    long id=invocation.getArgument(0,Long.class);observedContacts.add(id);
                    var task=userThread.submit(() -> {
                        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                        try {
                            var member=sourceContacts.get(id);
                            return new PrivateNoteModels.ContactMember(member.userId(),member.nickname(),member.active(),member.deletionPending());
                        } finally {
                            assertThat(TransactionSynchronizationManager.hasResource(reader)).isFalse();
                            assertThat(((com.zaxxer.hikari.HikariDataSource)reader).getHikariPoolMXBean().getActiveConnections()).isZero();
                        }
                    });
                    try {return task.get(5,TimeUnit.SECONDS);}
                    catch(ExecutionException failure){if(failure.getCause() instanceof Error error)throw error;throw unavailable();}
                    catch(InterruptedException failure){task.cancel(true);Thread.currentThread().interrupt();throw unavailable();}
                    catch(java.util.concurrent.TimeoutException failure){task.cancel(true);throw unavailable();}
                });
                var stream=mock(PrivateNoteStream.class);
                doAnswer(invocation->{committed.accept("close:"+invocation.getArgument(0,Long.class));return null;})
                        .when(stream).close(anyLong());
                doAnswer(invocation->{committed.accept("resyncAll");return null;}).when(stream).resyncAll();
                var tx=new TransactionTemplate(manager);tx.setTimeout(15);
                var withdrawal=new com.pawbridge.communityservice.contact.F29LegacyMemberCleanupService(
                        new PrivateNoteRepository(jdbc),client,tx,stream,eventContext);
                var boundary=mock(com.pawbridge.userservice.contact.CommunityContactClient.class);
                doAnswer(invocation->{
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                    assertThat(TransactionSynchronizationManager.hasResource(userDataSource)).isFalse();
                    assertThat(TransactionSynchronizationManager.hasResource(referenceDataSource)).isFalse();
                    pendingVisible.run();
                    try {withdrawal.withdraw(invocation.getArgument(0,Long.class));}
                    catch(RuntimeException failure){communityFailure=failure;throw failure;}
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                    afterCommunity.run();return null;
                }).when(boundary).removeMailboxes(anyLong());
                deletion=new com.pawbridge.userservice.contact.ContactMemberDeletion(
                        new JdbcTemplate(userDataSource),boundary,repository,userManager);
            } catch(RuntimeException|Error failure) {eventContext.close();userContext.close();throw failure;}
        }

        void delete(long id){deletion.delete(id);}
        void afterManagedUserLoaded(long id,Runnable action){loadedUserHookId=id;loadedUserHook=java.util.Objects.requireNonNull(action);}
        int loadedUserHookCalls(){return loadedUserHookCalls.get();}
        List<Long> contactIds(){return List.copyOf(observedContacts);}
        int chatCalls(){return chatCalls.get();}
        RuntimeException communityFailure(){return communityFailure;}
        boolean userResourceBound(){return TransactionSynchronizationManager.hasResource(userDataSource);}
        @Override public void close(){eventContext.close();userContext.close();}
    }

}
