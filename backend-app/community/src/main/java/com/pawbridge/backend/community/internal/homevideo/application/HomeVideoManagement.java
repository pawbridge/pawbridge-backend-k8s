package com.pawbridge.backend.community.internal.homevideo.application;

import java.util.List;
import java.util.UUID;

public interface HomeVideoManagement {
    Board board();
    Metadata preview(String url);
    Board save(UUID id, Save request);
    Board publish(UUID id, Publication request);
    Board reorder(Order request);
    Board recheck(UUID id, long revision);
    void refresh();
    record Metadata(String videoId,String title,String channelTitle,String thumbnailUrl,long durationSeconds,boolean available) {}
    record Board(long revision,List<HomeVideo> videos) {}
    record Save(String url,boolean published,long revision) {}
    record Publication(boolean published,long revision) {}
    record Order(List<UUID> ids,long revision) {}
}
