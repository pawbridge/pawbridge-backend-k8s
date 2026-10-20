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
}
