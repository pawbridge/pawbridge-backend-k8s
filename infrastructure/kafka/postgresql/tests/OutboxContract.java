import com.fasterxml.jackson.databind.ObjectMapper;
import io.debezium.data.Envelope;
import io.debezium.transforms.outbox.EventRouter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.json.JsonConverter;
import org.apache.kafka.connect.source.SourceRecord;
import org.apache.kafka.connect.storage.Converter;
import org.apache.kafka.connect.transforms.predicates.TopicNameMatches;

// Runs the real installed Debezium SMT; does not pretend to exercise PostgreSQL WAL or Kafka delivery.
public class OutboxContract {
    static final ObjectMapper JSON = new ObjectMapper();
    static int passed;
    static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
        passed++;
    }
    public static void main(String[] args) throws Exception {
        for (String service : new String[]{"animal", "user", "community", "payment", "store"}) {
            Map<String, Object> config = JSON.readValue(Files.readString(Path.of(args[0], service + "-outbox-connector.json")), Map.class);
            Map<String, Object> options = (Map<String, Object>) config.get("config");
            Map<String, Object> transform = new HashMap<>();
            options.forEach((key, value) -> {
                if (key.startsWith("transforms.outbox.")) transform.put(key.substring("transforms.outbox.".length()), value);
            });
            try (EventRouter<SourceRecord> router = new EventRouter<>();
                 TopicNameMatches<SourceRecord> predicate = new TopicNameMatches<>()) {
                router.configure(transform);
                predicate.configure(Map.of("pattern", options.get("predicates.isOutbox.pattern")));
                boolean numericId = service.equals("payment") || service.equals("store");
                String idField = (String) transform.get("table.field.event.id");
                String typeField = service.equals("community") ? "type" : "event_type";
                SchemaBuilder rowBuilder = SchemaBuilder.struct().name(service + ".Outbox").optional()
                        .field(idField, numericId ? Schema.INT64_SCHEMA : Schema.STRING_SCHEMA)
                        .field("aggregate_id", Schema.STRING_SCHEMA)
                        .field("aggregate_type", Schema.STRING_SCHEMA)
                        .field(typeField, Schema.STRING_SCHEMA)
                        .field("created_at", io.debezium.time.MicroTimestamp.schema())
                        .field("payload", io.debezium.data.Json.schema());
                if ((service.equals("animal") || service.equals("user"))) rowBuilder.field("topic", Schema.STRING_SCHEMA);
                Schema rowSchema = rowBuilder.build();
                Struct row = new Struct(rowSchema).put(idField, numericId ? 17L : "event-17")
                        .put("aggregate_id", "42").put("aggregate_type", "product-sku")
                        .put(typeField, "CONTRACT_TEST").put("created_at", 1_789_862_400_000_000L)
                        .put("payload", "{\"eventId\":\"payload-event-17\",\"animalId\":42,\"title\":\"보호 중\"}");
                if (service.equals("animal") || service.equals("user")) row.put("topic", service.equals("animal") ? "animal.events" : "user.favorite.events");
                Schema sourceSchema = SchemaBuilder.struct().name(service + ".Source").build();
                Envelope envelope = Envelope.defineSchema().withName(service + ".Envelope")
                        .withRecord(rowSchema).withSource(sourceSchema).build();
                String table = service.equals("payment") || service.equals("store") ? "outbox" : "outbox_events";
                String inputTopic = options.get("topic.prefix") + ".pawbridge_" + service + "." + table;
                SourceRecord input = new SourceRecord(Map.of("server", "contract"), Map.of("lsn", 1L), inputTopic,
                        null, Schema.INT64_SCHEMA, 1L, envelope.schema(),
                        envelope.create(row, new Struct(sourceSchema), Instant.ofEpochMilli(1_789_862_401_234L)), 1_789_862_401_234L);
                check(predicate.test(input), service + " outbox predicate");
                check(!predicate.test(new SourceRecord(Map.of(), Map.of(), "__debezium-heartbeat.pawbridge-pg-" + service,
                        null, null)), service + " heartbeat excluded");
                SourceRecord output = router.apply(input);
                String expectedTopic = switch(service) {
                    case "animal" -> "animal.events";
                    case "user" -> "user.favorite.events";
                    case "community" -> "community.post.events";
                    case "payment" -> "payment.events";
                    default -> "store.product-sku.events";
                };
                check(output.topic().equals(expectedTopic), service + " topic");
                check(output.key().equals("42"), service + " key");
                check(output.headers().lastWithName("eventType").value().equals("CONTRACT_TEST"), service + " eventType header");
                check(output.headers().lastWithName("id").value().equals(numericId ? 17L : "event-17"), service + " event id header");
                check(output.timestamp().equals(1_789_862_401_234L), service + " preserves source timestamp");
                try (JsonConverter converter = new JsonConverter()) {
                    converter.configure(Map.of("schemas.enable", false), false);
                    byte[] bytes = converter.fromConnectData(output.topic(), output.valueSchema(), output.value());
                    check(JSON.readTree(bytes).get("animalId").asLong() == 42L, service + " expanded JSON");
                    check(JSON.readTree(bytes).get("title").asText().equals("보호 중"), service + " UTF-8");
                    check(!JSON.readTree(bytes).has("schema"), service + " no schema envelope");
                }
                try (Converter keyConverter = (Converter) Class.forName((String) options.get("key.converter")).getConstructor().newInstance()) {
                    keyConverter.configure(Map.of("schemas.enable", false), true);
                    String key = new String(keyConverter.fromConnectData(output.topic(), output.keySchema(), output.key()), StandardCharsets.UTF_8);
                    check(key.equals("\"42\""), service + " wire key encoding");
                }
                SourceRecord deleted = new SourceRecord(Map.of(), Map.of(), inputTopic, null, Schema.INT64_SCHEMA, 1L,
                        envelope.schema(), envelope.delete(row, new Struct(sourceSchema), Instant.now()));
                check(router.apply(deleted) == null, service + " cleanup deletion suppressed");
                SourceRecord updated = new SourceRecord(Map.of(), Map.of(), inputTopic, null, Schema.INT64_SCHEMA, 1L,
                        envelope.schema(), envelope.update(row, row, new Struct(sourceSchema), Instant.now()));
                boolean updateFailed = false;
                try { router.apply(updated); } catch (RuntimeException expected) { updateFailed = true; }
                check(updateFailed, service + " append-only violation fails");
            }
        }
        System.out.println("OUTBOX_CONTRACT_CHECKS=" + passed);
    }
}
