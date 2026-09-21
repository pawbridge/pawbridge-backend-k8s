package com.pawbridge.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;

/** Read-only offset inspection in a separate bounded host JVM, not inside the broker's RAM budget. */
public final class CutoverOffsets {
    public static void main(String[] args) throws Exception {
        List<Map<String,Object>> rows = new ArrayList<>();
        try (AdminClient admin = AdminClient.create(Map.of("bootstrap.servers",CutoverProbe.broker(),
                "default.api.timeout.ms",10000,"request.timeout.ms",5000))) {
            for (String group : List.of("animal-service-favorite-group","user-service-group","payment-group")) {
                Map<TopicPartition,OffsetAndMetadata> offsets = admin.listConsumerGroupOffsets(group)
                        .partitionsToOffsetAndMetadata().get(12,TimeUnit.SECONDS);
                if (offsets.isEmpty()) throw new IllegalStateException("Missing group offsets: " + group);
                Map<TopicPartition,OffsetSpec> requests = new HashMap<>();
                offsets.keySet().forEach(partition -> requests.put(partition,OffsetSpec.latest()));
                Map<TopicPartition,ListOffsetsResult.ListOffsetsResultInfo> ends = admin.listOffsets(requests)
                        .all().get(12,TimeUnit.SECONDS);
                for (Map.Entry<TopicPartition,OffsetAndMetadata> entry : offsets.entrySet()) {
                    long end = ends.get(entry.getKey()).offset();
                    if (entry.getValue().offset() != end) throw new IllegalStateException("Consumer not drained: " + group);
                    rows.add(Map.of("group",group,"topic",entry.getKey().topic(),"partition",entry.getKey().partition(),
                            "offset",entry.getValue().offset(),"end",end));
                }
            }
        }
        rows.sort(Comparator.comparing(row -> row.get("group")+":"+row.get("topic")+":"+row.get("partition")));
        System.out.println("CUTOVER_OFFSETS="+new ObjectMapper().writeValueAsString(rows));
    }
}
