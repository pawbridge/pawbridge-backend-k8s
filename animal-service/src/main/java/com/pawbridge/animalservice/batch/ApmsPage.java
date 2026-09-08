package com.pawbridge.animalservice.batch;

import com.pawbridge.animalservice.client.ApmsApiClient;
import com.pawbridge.animalservice.dto.apms.ApmsAnimal;
import com.pawbridge.animalservice.dto.apms.ApmsRootResponse;

import java.util.List;

/** A validated provider page shared by shelter preparation and animal ingestion. */
public record ApmsPage(List<ApmsAnimal> items, boolean last) {
    public static ApmsPage fetch(ApmsApiClient client, String key, int page, int size,
                                 String beginDate, String endDate) {
        ApmsRootResponse<ApmsAnimal> root;
        try {
            root = client.getAbandonmentAnimals(key, page, size, beginDate, endDate, null, null, "json");
        } catch (RuntimeException exception) {
            // Feign exceptions can contain the service key in the request URL.
            throw new IllegalStateException("APMS request failed at page " + page);
        }
        if (root == null || root.getResponse() == null
                || root.getResponse().getHeader() == null
                || !"00".equals(root.getResponse().getHeader().getResultCode())
                || root.getResponse().getBody() == null) {
            throw new IllegalStateException("APMS response was unsuccessful at page " + page);
        }
        var body = root.getResponse().getBody();
        long total;
        try {
            total = Long.parseLong(body.getTotalCount());
            if (total < 0 || Integer.parseInt(body.getPageNo()) != page) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("APMS pagination metadata is invalid at page " + page);
        }
        List<ApmsAnimal> items = body.getItems() == null || body.getItems().getItem() == null
                ? List.of() : body.getItems().getItem();
        long offset = (long) (page - 1) * size;
        long expected = Math.min(size, Math.max(0, total - offset));
        if (items.size() != expected || items.stream().anyMatch(item -> item == null)) {
            throw new IllegalStateException("APMS page is incomplete at page " + page);
        }
        return new ApmsPage(items, offset + items.size() >= total);
    }
}
