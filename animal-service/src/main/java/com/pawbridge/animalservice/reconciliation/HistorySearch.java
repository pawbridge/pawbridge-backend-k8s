package com.pawbridge.animalservice.reconciliation;

import java.util.*;

/** Compare-and-set partial updates. An existing document's user fields and image_vector are never sent. */
public class HistorySearch {
    private final String base;
    private final String authorization;
    private final HistoryHttp http;
    public HistorySearch(String base, String authorization) {
        this(base, authorization, null);
    }
    public HistorySearch(String base, String authorization, String certificatePath) {
        this.http = new HistoryHttp(certificatePath);
        this.base = base.replaceAll("/+$", ""); this.authorization = authorization;
    }
    public String identity() throws Exception {
        var cluster = http.request("GET", base, null, authorization, false).body().path("cluster_uuid").asText();
        var aliases = http.request("GET", base + "/_alias/animals", null, authorization, false).body();
        if (cluster.isBlank() || aliases.size() != 1) HistoryPlan.fail("Expected one existing animals alias target");
        String index = aliases.fieldNames().next();
        var alias = aliases.path(index).path("aliases").path("animals");
        if (alias.isMissingNode() || alias.has("filter") || alias.has("routing") || alias.has("search_routing")
                || alias.has("index_routing") || (alias.has("is_write_index") && !alias.path("is_write_index").asBoolean()))
            HistoryPlan.fail("Unsupported animals alias configuration");
        var settings = http.request("GET", base + "/" + index + "/_settings", null, authorization, false).body();
        String uuid = settings.path(index).path("settings").path("index").path("uuid").asText();
        if (uuid.isBlank()) HistoryPlan.fail("Missing search index identity");
        return cluster + "/" + index + "/" + uuid;
    }
    public void sync(String expected, long id, Map<String, Object> current) throws Exception {
        if (!expected.equals(identity())) HistoryPlan.fail("Search alias or index changed; stop before writing");
        // Pin the reviewed physical index so an alias switch cannot redirect an in-flight request.
        String index = expected.split("/", -1)[1];
        var existing = http.request("GET", base + "/" + index + "/_doc/" + id, null, authorization, true);
        if (existing.status() == 404) {
            http.request("PUT", base + "/" + index + "/_create/" + id, current, authorization, false);
        } else {
            var body = existing.body();
            var source = body.path("_source");
            if (!Objects.toString(current.get("apms_desertion_no"), "").equals(source.path("apms_desertion_no").asText())
                    || source.path("id").asLong(-1) != id || !body.has("_seq_no") || !body.has("_primary_term"))
                HistoryPlan.fail("Search document identity or version mismatch");
            var fields = new LinkedHashMap<String, Object>();
            for (String field : List.of("status", "apms_process_state", "happen_date", "apms_updated_at"))
                fields.put(field, current.get(field));
            http.request("POST", base + "/" + index + "/_update/" + id + "?if_seq_no=" + body.path("_seq_no").asLong()
                    + "&if_primary_term=" + body.path("_primary_term").asLong(), Map.of("doc", fields), authorization, false);
        }
        if (!expected.equals(identity())) HistoryPlan.fail("Search alias changed during synchronization; inspect before resuming");
    }
    public void refresh(String expected) throws Exception {
        if (!expected.equals(identity())) HistoryPlan.fail("Search alias changed before refresh");
        http.request("POST", base + "/" + expected.split("/", -1)[1] + "/_refresh", null, authorization, false);
    }
}
