package com.pawbridge.communityservice.contact;

import com.pawbridge.communityservice.contact.PrivateNoteModels.Notification;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Component
@Profile("postgresql")
public class PrivateNoteStream {
    private static final long MAX_CONNECTION_LIFETIME_MILLIS = 300_000;
    private static final int MAX_CONNECTIONS_PER_MEMBER = 3;
    private static final int MAX_CONNECTIONS = 500;

    private record Connection(SseEmitter emitter, Instant expiresAt) {}

    private final ConcurrentHashMap<Long, ConcurrentHashMap<UUID, Connection>> connections = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor writes = new ThreadPoolExecutor(
            2, 2, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(256),
            work -> daemonThread(work, "private-note-stream"));
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(
            work -> daemonThread(work, "private-note-heartbeat"));

    public PrivateNoteStream() {
        heartbeat.scheduleAtFixedRate(this::heartbeat, 15, 15, TimeUnit.SECONDS);
    }

    public synchronized SseEmitter open(long memberId, Instant tokenExpiresAt) {
        Instant now = Instant.now();
        long lifetime = Math.min(MAX_CONNECTION_LIFETIME_MILLIS, Duration.between(now, tokenExpiresAt).toMillis());
        if (lifetime <= 0) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        var memberConnections = connections.computeIfAbsent(memberId, ignored -> new ConcurrentHashMap<>());
        int connectionCount = connections.values().stream().mapToInt(Map::size).sum();
        if (memberConnections.size() >= MAX_CONNECTIONS_PER_MEMBER || connectionCount >= MAX_CONNECTIONS) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "알림 연결이 너무 많습니다.");
        }

        UUID connectionId = UUID.randomUUID();
        SseEmitter emitter = new SseEmitter(lifetime);
        memberConnections.put(connectionId, new Connection(emitter, now.plusMillis(lifetime)));
        Runnable removeConnection = () -> remove(memberId, memberConnections, connectionId);
        emitter.onCompletion(removeConnection);
        emitter.onTimeout(() -> {
            removeConnection.run();
            emitter.complete();
        });
        emitter.onError(error -> removeConnection.run());
        // Write the first event so HTTP headers are flushed before REST restoration.
        send(memberConnections, connectionId, "ready", null);
        return emitter;
    }

    public void publish(long memberId, Notification notification) {
        var memberConnections = connections.get(memberId);
        if (memberConnections != null) {
            memberConnections.keySet().forEach(connectionId ->
                    enqueue(() -> send(memberConnections, connectionId, "note", notification)));
        }
    }

    public void resync(long memberId) {
        var memberConnections = connections.get(memberId);
        if (memberConnections != null) {
            memberConnections.keySet().forEach(connectionId ->
                    enqueue(() -> send(memberConnections, connectionId, "resync", null)));
        }
    }

    public void resyncAll() {
        connections.keySet().forEach(this::resync);
    }

    public void close(long memberId) {
        var memberConnections = connections.remove(memberId);
        if (memberConnections != null) {
            memberConnections.values().forEach(connection -> connection.emitter().complete());
        }
    }

    private synchronized void heartbeat() {
        connections.forEach((memberId, memberConnections) -> memberConnections.forEach((connectionId, connection) -> {
            if (!connection.expiresAt().isAfter(Instant.now())) {
                memberConnections.remove(connectionId);
                connection.emitter().complete();
            } else {
                enqueue(() -> send(memberConnections, connectionId, "heartbeat", null));
            }
        }));
        connections.entrySet().removeIf(entry -> entry.getValue().isEmpty());
    }

    private synchronized void remove(
            long memberId, ConcurrentHashMap<UUID, Connection> memberConnections, UUID connectionId) {
        memberConnections.remove(connectionId);
        if (memberConnections.isEmpty()) {
            connections.remove(memberId, memberConnections);
        }
    }

    private void enqueue(Runnable work) {
        try {
            writes.execute(work);
        } catch (RejectedExecutionException full) {
            // The bounded queue may drop this hint. Periodic REST re-query restores stored notifications.
        }
    }

    private void send(
            ConcurrentHashMap<UUID, Connection> memberConnections,
            UUID connectionId, String eventName, Object payload) {
        Connection connection = memberConnections.get(connectionId);
        if (connection == null) {
            return;
        }
        try {
            connection.emitter().send(SseEmitter.event().name(eventName).data(payload == null ? "{}" : payload));
        } catch (IOException | IllegalStateException disconnected) {
            memberConnections.remove(connectionId);
        }
    }

    private static Thread daemonThread(Runnable work, String name) {
        Thread thread = new Thread(work, name);
        thread.setDaemon(true);
        return thread;
    }

    @PreDestroy
    public void shutdown() {
        heartbeat.shutdownNow();
        writes.shutdownNow();
        connections.keySet().forEach(this::close);
    }
}
