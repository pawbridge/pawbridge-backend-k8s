package com.pawbridge.communityservice.contact;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Component
@Profile("postgresql")
public class PrivateNoteStream {
    private record Connection(SseEmitter emitter,Instant expiresAt) {}
    private final ConcurrentHashMap<Long,ConcurrentHashMap<UUID,Connection>> connections=new ConcurrentHashMap<>();
    private final ThreadPoolExecutor writes=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(256),
            r -> {Thread t=new Thread(r,"private-note-stream");t.setDaemon(true);return t;});
    private final ScheduledExecutorService heartbeat=Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t=new Thread(r,"private-note-heartbeat");t.setDaemon(true);return t;
    });
    public PrivateNoteStream() { heartbeat.scheduleAtFixedRate(this::heartbeat,15,15,TimeUnit.SECONDS); }

    public synchronized SseEmitter open(long member,Instant tokenExpiry) {
        Instant now=Instant.now();
        long lifetime=Math.min(300000,java.time.Duration.between(now,tokenExpiry).toMillis());
        if(lifetime<=0) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        var current=connections.computeIfAbsent(member,id -> new ConcurrentHashMap<>());
        if(current.size()>=3 || connections.values().stream().mapToInt(Map::size).sum()>=500)
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"알림 연결이 너무 많습니다.");
        UUID id=UUID.randomUUID();
        SseEmitter emitter=new SseEmitter(lifetime);
        current.put(id,new Connection(emitter,now.plusMillis(lifetime)));
        Runnable remove=() -> remove(member,current,id);
        emitter.onCompletion(remove);emitter.onTimeout(() -> {remove.run();emitter.complete();});emitter.onError(error -> remove.run());
        // Flush headers immediately; REST restoration starts after this connection is established.
        send(current,id,"ready",null);
        return emitter;
    }
    public void publish(long member,PrivateNoteModels.Notification event) {
        var current=connections.get(member);
        if(current!=null) current.keySet().forEach(id -> enqueue(() -> send(current,id,"note",event)));
    }
    public void resync(long member) {
        var current=connections.get(member);
        if(current!=null) current.keySet().forEach(id -> enqueue(() -> send(current,id,"resync",null)));
    }
    public void resyncAll() { connections.keySet().forEach(this::resync); }
    public void close(long member) {
        var current=connections.remove(member);
        if(current!=null) current.values().forEach(c -> c.emitter().complete());
    }
    private synchronized void heartbeat() {
        connections.forEach((member,current) -> current.forEach((id,c) -> {
            if(!c.expiresAt().isAfter(Instant.now())) {current.remove(id);c.emitter().complete();}
            else enqueue(() -> send(current,id,"heartbeat",null));
        }));
        connections.entrySet().removeIf(e -> e.getValue().isEmpty());
    }
    private synchronized void remove(long member,ConcurrentHashMap<UUID,Connection> current,UUID id) {
        current.remove(id);
        if(current.isEmpty()) connections.remove(member,current);
    }
    private void enqueue(Runnable work) {
        try {writes.execute(work);} catch(RejectedExecutionException full) { /* REST repair remains authoritative. */ }
    }
    private void send(ConcurrentHashMap<UUID,Connection> current,UUID id,String kind,Object value) {
        Connection c=current.get(id);
        if(c==null) return;
        try { c.emitter().send(SseEmitter.event().name(kind).data(value==null?"{}":value)); }
        catch(IOException disconnected) {current.remove(id);}
        catch(IllegalStateException completed) {current.remove(id);}
    }
    @PreDestroy public void shutdown() {
        heartbeat.shutdownNow();writes.shutdownNow();connections.keySet().forEach(this::close);
    }
}
