package com.pawbridge.animalservice.travel;

import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;

/** One bounded collection invocation; never reachable from public GET controllers. */
@Component
public class PetTravelCollector {
    private static final String LOCK = "pawbridge_animal.petTravelCollection";
    private final DataSource dataSource;
    private final PetTravelCatalog catalog;
    private final TourApiClient client;
    private final TourApiProperties properties;
    private final Clock clock;

    public PetTravelCollector(DataSource dataSource, PetTravelCatalog catalog, TourApiClient client,
                              TourApiProperties properties, Clock clock) {
        this.dataSource=dataSource; this.catalog=catalog; this.client=client; this.properties=properties; this.clock=clock;
    }
    public record Result(String status, int requests, int discovered, int published) {}
    private static final class BudgetExhausted extends RuntimeException {}

    public Result collect() throws Exception {
        if (!properties.isEnabled()) throw new IllegalStateException("TRAVEL_COLLECTION_DISABLED");
        try (var connection=dataSource.getConnection()) {
            boolean acquired;
            try { acquired=lock(connection, "SELECT GET_LOCK(?,0)"); }
            catch (Exception exception) { connection.abort(Runnable::run); throw new IllegalStateException("TRAVEL_LOCK_UNAVAILABLE"); }
            if (!acquired) throw new IllegalStateException("TRAVEL_COLLECTION_BUSY");
            try { return collectLocked(connection); }
            finally {
                try {
                    if (!lock(connection, "SELECT RELEASE_LOCK(?)")) connection.abort(Runnable::run);
                } catch (Exception exception) { connection.abort(Runnable::run); }
            }
        }
    }

    private Result collectLocked(Connection connection) throws Exception {
        var id=UUID.randomUUID().toString();
        catalog.startRun(id,clock.instant());
        int[] counts=new int[3];
        boolean inventoryCompleted=false;
        try {
            String petError=properties.isBulkPetEnabled() ? collectPetPages(connection,counts) : null;
            reserve(connection,TourApiClient.Operation.REGIONS,counts);
            var regions=new LinkedHashMap<String,String>();
            for (var row:client.fetch(TourApiClient.Operation.REGIONS,"")) regions.put(row.get("code"),row.get("name"));
            if (regions.isEmpty()) throw PetTravelException.unavailable();
            catalog.saveRegions(regions,clock.instant());
            for (int pages=0;pages<properties.getMaxPagesPerRun();pages++) {
                var state=catalog.collectionState();
                String shown="HIDDEN".equals(state.phase())?"0":"1";
                reserve(connection,TourApiClient.Operation.SYNC,counts);
                var page=client.fetchPage(TourApiClient.Operation.SYNC,shown,state.nextPage());
                // An unexpected empty middle page is not evidence of completed inventory.
                long remaining=(long)page.totalCount()-(long)(state.nextPage()-1)*100;
                if (page.items().size()!=Math.min(100,Math.max(0,remaining))) throw PetTravelException.unavailable();
                for (var row:page.items()) {
                    if (!shown.equals(row.get("showflag"))) throw PetTravelException.unavailable();
                    // Never interpret legacy areacode as a legal-district code.
                    String region = row.get("lDongRegnCd");
                    catalog.observeBasic(row,regions.containsKey(region) ? region : null,clock.instant());
                    counts[1]++;
                }
                // Advancing only after all rows persist makes interrupted pages safely replayable.
                if ((long)state.nextPage()*100>=page.totalCount()) {
                    if ("HIDDEN".equals(state.phase())) catalog.checkpoint("SHOWN",1);
                    else {
                        catalog.beginDetails(clock.instant().minus(Duration.ofDays(properties.getDetailRefreshDays())));
                        catalog.completeCycle(clock.instant());
                        inventoryCompleted=true;
                        break;
                    }
                } else catalog.checkpoint(state.phase(),state.nextPage()+1);
            }
            int detailFailures=0;
            {
                for (var target:catalog.pending(properties.getMaxDetailsPerRun())) {
                    try {
                    reserve(connection,TourApiClient.Operation.COMMON,counts);
                    var common=client.fetch(TourApiClient.Operation.COMMON,target.contentId());
                    if (common.size()!=1) throw PetTravelException.unavailable();
                    if (properties.isBulkPetEnabled()) {
                        if (catalog.publishCommon(target,common.get(0),clock.instant())) counts[2]++;
                    } else {
                        reserve(connection,TourApiClient.Operation.PET,counts);
                        var pet=client.fetch(TourApiClient.Operation.PET,target.contentId());
                        if (pet.size()>1) throw PetTravelException.unavailable();
                        if (catalog.publish(target,common.get(0),pet.isEmpty()?Map.of():pet.get(0),clock.instant())) counts[2]++;
                    }
                    } catch (BudgetExhausted exception) {
                        throw exception;
                    } catch (Exception exception) {
                        if ("TRAVEL_LOCK_LOST".equals(exception.getMessage())) throw exception;
                        catalog.detailFailed(target,clock.instant());
                        detailFailures++;
                    }
                }
                String visitError=collectVisitDetails(connection,counts);
                boolean visitPending=catalog.hasPendingVisitDetails();
                if (inventoryCompleted && catalog.pending(1).isEmpty() && !visitPending && petError==null && visitError==null) {
                    if (catalog.hasUnresolvedRegions()) {
                        // Keep unresolved rows durable, publish healthy rows, and retry discovery next run.
                        catalog.finishRun(id,"PARTIAL","REGION_UNRESOLVED",counts[0],counts[1],counts[2],clock.instant());
                        return new Result("PARTIAL",counts[0],counts[1],counts[2]);
                    }
                    catalog.finishRun(id,"COMPLETED",null,counts[0],counts[1],counts[2],clock.instant());
                    return new Result("COMPLETED",counts[0],counts[1],counts[2]);
                }
                String error=petError!=null?petError:detailFailures>0?"DETAIL_FAILED":visitError;
                catalog.finishRun(id,"PARTIAL",error,counts[0],counts[1],counts[2],clock.instant());
                return new Result("PARTIAL",counts[0],counts[1],counts[2]);
            }
        } catch (BudgetExhausted exception) {
            catalog.finishRun(id,"QUOTA","REQUEST_BUDGET",counts[0],counts[1],counts[2],clock.instant());
            return new Result("QUOTA",counts[0],counts[1],counts[2]);
        } catch (Exception exception) {
            catalog.finishRun(id,"FAILED","COLLECTION_FAILED",counts[0],counts[1],counts[2],clock.instant());
            // Do not retain upstream URL/credential-bearing causes in Batch metadata.
            throw new IllegalStateException("TRAVEL_COLLECTION_FAILED");
        }
    }

    /** Independent per-operation work queues keep one provider detail failure from blocking the others. */
    private String collectVisitDetails(Connection connection, int[] counts) throws Exception {
        boolean failed=false;
        boolean quota=false;
        for (var resource : PetTravelCatalog.VisitResource.values()) {
            var operation=switch (resource) {
                case INTRO -> TourApiClient.Operation.INTRO;
                case INFO -> TourApiClient.Operation.INFO;
                case IMAGES -> TourApiClient.Operation.IMAGES;
            };
            for (var target : catalog.pendingVisitDetails(resource,properties.getMaxVisitDetailsPerRun())) {
                try {
                    reserve(connection,operation,counts);
                    var rows=client.fetchDetail(operation,target.contentId(),target.contentTypeId());
                    if (!lock(connection,"SELECT IS_USED_LOCK(?)=CONNECTION_ID()"))
                        throw new IllegalStateException("TRAVEL_LOCK_LOST");
                    switch (resource) {
                        case INTRO -> catalog.saveVisitIntro(target,rows,clock.instant());
                        case INFO -> catalog.saveVisitInformation(target,rows,clock.instant());
                        case IMAGES -> catalog.saveVisitImages(target,rows,clock.instant());
                    }
                } catch (BudgetExhausted exception) {
                    quota=true;
                    break;
                } catch (Exception exception) {
                    if ("TRAVEL_LOCK_LOST".equals(exception.getMessage())) throw exception;
                    catalog.visitDetailFailed(resource,target,clock.instant());
                    failed=true;
                }
            }
        }
        if (quota) return "VISIT_DETAIL_QUOTA";
        if (failed) return "VISIT_DETAIL_FAILED";
        return catalog.hasPendingVisitDetails()?"VISIT_DETAIL_PENDING":null;
    }

    /** Separate provider operation/budget: a failed bulk page never triggers N individual requests. */
    private String collectPetPages(Connection connection,int[] counts) throws Exception {
        try {
            for (int pages=0;pages<properties.getMaxBulkPagesPerRun();pages++) {
                var state=catalog.petCollectionState();
                if (state.nextPage()==1 && state.completedAt()!=null
                        && state.completedAt().plus(Duration.ofDays(1)).isAfter(clock.instant())) return null;
                reserve(connection,TourApiClient.Operation.PET_BULK,counts);
                var startedAt=clock.instant();
                var page=client.fetchPage(TourApiClient.Operation.PET_BULK,"",state.nextPage());
                if (!lock(connection,"SELECT IS_USED_LOCK(?)=CONNECTION_ID()"))
                    throw new IllegalStateException("TRAVEL_LOCK_LOST");
                catalog.savePetPage(state,page,startedAt,startedAt.plus(Duration.ofDays(properties.getDetailRefreshDays())));
                if ((long)state.nextPage()*100>=page.totalCount()) return null;
            }
            return "PET_BULK_PENDING";
        } catch (BudgetExhausted exception) {
            catalog.petCollectionFailed("PET_BULK_QUOTA",false);
            return "PET_BULK_QUOTA";
        } catch (Exception exception) {
            if ("TRAVEL_LOCK_LOST".equals(exception.getMessage())) throw exception;
            boolean changed="PET_INVENTORY_CHANGED".equals(exception.getMessage());
            catalog.petCollectionFailed(changed?"PET_INVENTORY_CHANGED":"PET_BULK_FAILED",changed);
            return "PET_BULK_FAILED";
        }
    }

    private void reserve(Connection connection,TourApiClient.Operation operation,int[] counts) throws Exception {
        if (!lock(connection,"SELECT IS_USED_LOCK(?)=CONNECTION_ID()")) throw new IllegalStateException("TRAVEL_LOCK_LOST");
        if (!catalog.reserveRequest(operation.name(),LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul"))),
                properties.getDailyRequestLimit())) throw new BudgetExhausted();
        counts[0]++;
    }
    private boolean lock(Connection connection,String sql) throws Exception {
        try (var statement=connection.prepareStatement(sql)) {
            statement.setString(1,LOCK); statement.setQueryTimeout(5);
            try (var result=statement.executeQuery()) { return result.next() && result.getInt(1)==1 && !result.wasNull(); }
        }
    }
}
