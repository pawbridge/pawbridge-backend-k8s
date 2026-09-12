package com.pawbridge.animalservice.travel;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "tourapi")
public class TourApiProperties {
    private boolean enabled;
    private String serviceKey = "";
    // Per-operation daily ceiling. Failed attempts consume budget; not a provider entitlement.
    private int dailyRequestLimit = 900;
    private int maxPagesPerRun = 10;
    private int maxDetailsPerRun = 18;
    private int detailRefreshDays = 14;
    public int getDetailRefreshDays() { return detailRefreshDays; }
    public void setDetailRefreshDays(int value) {
        if (value < 1) throw new IllegalArgumentException("Detail refresh days must be positive");
        detailRefreshDays = value;
    }
    public int getMaxPagesPerRun() { return maxPagesPerRun; }
    public void setMaxPagesPerRun(int value) {
        if (value < 1 || value > 100) throw new IllegalArgumentException("Page limit must be 1..100");
        maxPagesPerRun = value;
    }
    public int getMaxDetailsPerRun() { return maxDetailsPerRun; }
    public void setMaxDetailsPerRun(int value) {
        if (value < 1 || value > 100) throw new IllegalArgumentException("Detail limit must be 1..100");
        maxDetailsPerRun = value;
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getServiceKey() { return serviceKey; }
    public void setServiceKey(String serviceKey) { this.serviceKey = serviceKey; }
    public int getDailyRequestLimit() { return dailyRequestLimit; }
    public void setDailyRequestLimit(int value) {
        if (value < 1 || value > 1000) throw new IllegalArgumentException("TourAPI budget must be between 1 and 1000");
        this.dailyRequestLimit = value;
    }
}
