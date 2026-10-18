package com.pawbridge.animalservice.batch;

import com.pawbridge.animalservice.client.ApmsApiClient;
import com.pawbridge.animalservice.dto.apms.ApmsAnimal;
import com.pawbridge.animalservice.dto.apms.ApmsRootResponse;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** A validated provider page shared by shelter preparation and animal ingestion. */
public record ApmsPage(List<ApmsAnimal> items, boolean last, long total, boolean countMatches) {
    public static ApmsPage fetch(ApmsApiClient client, String key, int page, int size,
                                 ApmsSyncPlan.Query query) {
        ApmsRootResponse<ApmsAnimal> root;
        try {
            root = client.getAbandonmentAnimals(key, page, size, format(query.intakeStart()), format(query.intakeEnd()),
                    null, null, "json", format(query.updatedStart()), format(query.updatedEnd()));
        } catch (RuntimeException exception) {
            // Feign exceptions can contain the service key in the request URL.
            throw new FetchException("REQUEST_FAILED");
        }
        if (root == null || root.getResponse() == null
                || root.getResponse().getHeader() == null
                || !"00".equals(root.getResponse().getHeader().getResultCode())
                || root.getResponse().getBody() == null) {
            throw new FetchException("UNSUCCESSFUL_RESPONSE");
        }
        var body = root.getResponse().getBody();
        long total;
        try {
            total = Long.parseLong(body.getTotalCount());
            if (total < 0 || Integer.parseInt(body.getPageNo()) != page) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException exception) {
            throw new FetchException("INVALID_PAGINATION");
        }
        List<ApmsAnimal> items = body.getItems() == null || body.getItems().getItem() == null
                ? List.of() : body.getItems().getItem();
        long offset = (long) (page - 1) * size;
        long expected = Math.min(size, Math.max(0, total - offset));
        if (items.stream().anyMatch(item -> item == null)) {
            throw new IllegalStateException("APMS page is incomplete at page " + page);
        }
        return new ApmsPage(items, offset + size >= total, total, items.size() == expected);
    }
    /** Only sanitized provider failures are recoverable; application defects still propagate. */
    public static class FetchException extends IllegalStateException {
        public FetchException(String reason) { super(reason); }
    }

    private static String format(LocalDate date) {
        return date == null ? null : date.format(DateTimeFormatter.BASIC_ISO_DATE);
    }

}
