package com.pawbridge.backend.community.internal.note.web;

import com.pawbridge.backend.community.internal.note.application.NoteChangeHints;
import com.pawbridge.backend.community.internal.note.application.NoteDeliveryHints;
import com.pawbridge.backend.community.internal.note.application.NoteNotifications;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.SmartLifecycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.LongFunction;
import java.util.function.BooleanSupplier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public final class PrivateNoteStreamRegistry implements NoteChangeHints, NoteDeliveryHints, SmartLifecycle, DisposableBean {
    private static final Logger log = LoggerFactory.getLogger(PrivateNoteStreamRegistry.class);
    private static final long SHUTDOWN_BUDGET_NANOS = TimeUnit.SECONDS.toNanos(5);
    private static final long MAX_CONNECTION_LIFETIME_MILLIS = 300_000;
    private static final int MAX_CONNECTIONS_PER_MEMBER = 3;
    private static final int MAX_PENDING_REPLACEMENTS_PER_MEMBER = 3;
    private static final int MAX_CONNECTIONS = 500;
    private static final int HEARTBEAT_SECONDS = 15;

    static class StreamEmitter extends SseEmitter {
        StreamEmitter(long lifetime) {
            super(lifetime);
        }

        final boolean sendIfActive(SseEventBuilder event, BooleanSupplier active) throws IOException {
            // Use Spring's actual write lock: a queued writer may expire while waiting for it.
            writeLock.lock();
            try {
                if (!active.getAsBoolean()) return false;
                send(event);
                return true;
            } finally {
                writeLock.unlock();
            }
        }
    }

    private static final class ConnectionLimitException extends ResponseStatusException {
        private final HttpHeaders headers = new HttpHeaders();

        private ConnectionLimitException() {
            super(HttpStatus.TOO_MANY_REQUESTS, "알림 연결이 너무 많습니다.");
            headers.set(HttpHeaders.RETRY_AFTER, Integer.toString(HEARTBEAT_SECONDS));
        }

        @Override
        public HttpHeaders getHeaders() {
            return headers;
        }
    }

    private static final class Connection {
        private final StreamEmitter emitter;
        private final Instant expiresAt;
        private final UUID clientId;
        private volatile boolean closing;
        // Protected by registryLock. A rejected completion is retried by heartbeat.
        private boolean completionQueued;

        private Connection(StreamEmitter emitter, Instant expiresAt, UUID clientId) {
            this.emitter = emitter;
            this.expiresAt = expiresAt;
            this.clientId = clientId;
        }
    }

    private record Registration(long memberId, UUID connectionId, Connection connection) {}
    private record Opening(Registration current, List<Registration> previous) {}

    private final Object registryLock = new Object();
    private final Map<Long, Map<UUID, Connection>> connections = new HashMap<>();
    private final Clock clock;
    private final LongFunction<StreamEmitter> emitterFactory;
    private final ExecutorService writes;
    private final ExecutorService completions;
    private final ScheduledExecutorService heartbeat;
    private volatile boolean stopping;

    public PrivateNoteStreamRegistry(Clock clock) {
        this(clock, StreamEmitter::new,
                boundedExecutor("private-note-stream", 256),
                boundedExecutor("private-note-close", MAX_CONNECTIONS),
                Executors.newSingleThreadScheduledExecutor(work -> daemonThread(work, "private-note-heartbeat")));
    }

    PrivateNoteStreamRegistry(Clock clock, LongFunction<StreamEmitter> emitterFactory,
            ExecutorService writes, ExecutorService completions, ScheduledExecutorService heartbeat) {
        this.clock = clock;
        this.emitterFactory = emitterFactory;
        this.writes = writes;
        this.completions = completions;
        this.heartbeat = heartbeat;
        heartbeat.scheduleAtFixedRate(this::heartbeat, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
    }

    public SseEmitter open(long memberId, Instant tokenExpiresAt) {
        return open(memberId, tokenExpiresAt, null);
    }

    public SseEmitter open(long memberId, Instant tokenExpiresAt, UUID clientId) {
        Opening opening = register(memberId, tokenExpiresAt, clientId);
        Registration registration = opening.current();
        SseEmitter emitter = registration.connection().emitter;
        emitter.onCompletion(() -> remove(registration));
        emitter.onTimeout(() -> requestCompletion(registration));
        emitter.onError(error -> remove(registration));
        opening.previous().forEach(this::requestCompletion);
        // The initial event is buffered by Spring until the controller initializes the response.
        send(registration, "ready", null);
        return emitter;
    }

    private Opening register(long memberId, Instant tokenExpiresAt, UUID clientId) {
        synchronized (registryLock) {
            Instant now = clock.instant();
            long lifetime = Math.min(MAX_CONNECTION_LIFETIME_MILLIS, Duration.between(now, tokenExpiresAt).toMillis());
            if (lifetime <= 0) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
            }
            if (stopping) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
            }
            Map<UUID, Connection> memberConnections = connections.get(memberId);
            int connectionCount = connections.values().stream().mapToInt(Map::size).sum();
            List<Registration> previous = clientId == null || memberConnections == null ? List.of()
                    : memberConnections.entrySet().stream()
                            .filter(entry -> clientId.equals(entry.getValue().clientId))
                            .map(entry -> new Registration(memberId, entry.getKey(), entry.getValue())).toList();
            long memberSlots = memberConnections == null ? 0
                    : memberConnections.values().stream().filter(connection -> connection.clientId == null).count()
                            + memberConnections.values().stream().map(connection -> connection.clientId)
                                    .filter(Objects::nonNull).distinct().count();
            long pendingReplacements = memberConnections == null ? 0
                    : memberConnections.values().stream()
                            .filter(connection -> connection.clientId != null && connection.closing).count();
            // Same-tab replacements share one member slot. Pending physical connections still
            // count toward the global cap until completion, including rejected close tasks.
            if ((previous.isEmpty() && memberSlots >= MAX_CONNECTIONS_PER_MEMBER)
                    || (!previous.isEmpty() && pendingReplacements >= MAX_PENDING_REPLACEMENTS_PER_MEMBER)
                    || connectionCount >= MAX_CONNECTIONS) {
                throw new ConnectionLimitException();
            }
            UUID connectionId = UUID.randomUUID();
            Connection connection = new Connection(emitterFactory.apply(lifetime), now.plusMillis(lifetime), clientId);
            previous.forEach(registration -> registration.connection().closing = true);
            connections.computeIfAbsent(memberId, ignored -> new HashMap<>()).put(connectionId, connection);
            return new Opening(new Registration(memberId, connectionId, connection), previous);
        }
    }

    @Override
    public void resync(long memberId) {
        memberConnections(memberId).forEach(registration -> enqueue(() -> send(registration, "resync", null)));
    }

    @Override
    public void publish(long memberId, NoteNotifications.Notification notification) {
        memberConnections(memberId).forEach(registration -> enqueue(() -> send(registration, "note", notification)));
    }

    void heartbeat() {
        for (Registration registration : allConnections()) {
            Connection connection = registration.connection();
            if (connection.closing || !connection.expiresAt.isAfter(clock.instant())) {
                requestCompletion(registration);
            } else {
                enqueue(() -> send(registration, "heartbeat", null));
            }
        }
    }

    private List<Registration> memberConnections(long memberId) {
        synchronized (registryLock) {
            Map<UUID, Connection> memberConnections = connections.get(memberId);
            if (memberConnections == null) {
                return List.of();
            }
            return memberConnections.entrySet().stream()
                    .map(entry -> new Registration(memberId, entry.getKey(), entry.getValue())).toList();
        }
    }

    private List<Registration> allConnections() {
        synchronized (registryLock) {
            return connections.entrySet().stream()
                    .flatMap(member -> member.getValue().entrySet().stream()
                            .map(entry -> new Registration(member.getKey(), entry.getKey(), entry.getValue())))
                    .toList();
        }
    }

    // Caller holds registryLock.
    private boolean registered(Registration registration) {
        Map<UUID, Connection> memberConnections = connections.get(registration.memberId());
        return memberConnections != null
                && memberConnections.get(registration.connectionId()) == registration.connection();
    }

    private void remove(Registration registration) {
        synchronized (registryLock) {
            if (!registered(registration)) {
                return;
            }
            Map<UUID, Connection> memberConnections = connections.get(registration.memberId());
            registration.connection().closing = true;
            memberConnections.remove(registration.connectionId());
            if (memberConnections.isEmpty()) {
                connections.remove(registration.memberId());
            }
        }
    }

    private void requestCompletion(Registration registration) {
        Connection connection = registration.connection();
        synchronized (registryLock) {
            if (!registered(registration) || connection.completionQueued) {
                return;
            }
            connection.closing = true;
            connection.completionQueued = true;
        }
        try {
            completions.execute(() -> {
                try {
                    connection.emitter.complete();
                } finally {
                    remove(registration);
                }
            });
        } catch (RejectedExecutionException full) {
            synchronized (registryLock) {
                connection.completionQueued = false;
            }
            // Keep this closing connection tracked and counted until completion can be retried.
        }
    }

    private void enqueue(Runnable work) {
        try {
            writes.execute(work);
        } catch (RejectedExecutionException full) {
            // REST repairs a dropped notification hint; a later heartbeat retries disconnect detection.
        }
    }

    private void send(Registration registration, String eventName, Object payload) {
        Connection connection = registration.connection();
        synchronized (registryLock) {
            if (!registered(registration) || connection.closing) {
                return;
            }
        }
        if (!connection.expiresAt.isAfter(clock.instant())) {
            requestCompletion(registration);
            return;
        }
        try {
            boolean sent = connection.emitter.sendIfActive(
                    SseEmitter.event().name(eventName).data(payload == null ? "{}" : payload),
                    () -> !connection.closing && connection.expiresAt.isAfter(clock.instant()));
            if (!sent) requestCompletion(registration);
        } catch (IOException | IllegalStateException disconnected) {
            // Spring/container error callbacks finish a failed write; do not complete it again here.
            remove(registration);
        }
    }

    private static ExecutorService boundedExecutor(String name, int capacity) {
        return new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(capacity),
                work -> daemonThread(work, name));
    }

    private static Thread daemonThread(Runnable work, String name) {
        Thread thread = new Thread(work, name);
        thread.setDaemon(true);
        return thread;
    }

    // Stop before Boot 3.4.11 web-server graceful shutdown (phase 2147482623).
    // This closes owned SSEs before the server waits for active HTTP requests.
    @Override public int getPhase() { return Integer.MAX_VALUE; }
    @Override public boolean isRunning() { return !stopping; }
    @Override public void start() {
        if (stopping) throw new IllegalStateException("A stopped note stream registry cannot be restarted");
    }

    @Override public void stop(Runnable callback) {
        try { stop(); }
        finally { callback.run(); }
    }

    @Override public void stop() {
        synchronized (registryLock) {
            if (stopping) return;
            stopping = true;
        }
        long deadline = System.nanoTime() + SHUTDOWN_BUDGET_NANOS;
        heartbeat.shutdownNow();
        writes.shutdownNow();
        allConnections().forEach(this::requestCompletion);
        completions.shutdown();
        boolean terminated = true;
        for (ExecutorService executor : List.of(heartbeat, writes, completions)) {
            try {
                long remaining = Math.max(0, deadline - System.nanoTime());
                if (!executor.awaitTermination(remaining, TimeUnit.NANOSECONDS)) {
                    terminated = false;
                    executor.shutdownNow();
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                terminated = false;
                executor.shutdownNow();
            }
        }
        if (terminated && allConnections().isEmpty()) {
            log.info("Private note stream resources stopped");
        } else {
            // Bounded stop is not proof that a blocked network writer terminated.
            // The real opened-SSE shutdown gate requires the success signal above.
            log.warn("Private note stream resources did not stop within budget");
        }
    }

    @Override public void destroy() { stop(); }
}
