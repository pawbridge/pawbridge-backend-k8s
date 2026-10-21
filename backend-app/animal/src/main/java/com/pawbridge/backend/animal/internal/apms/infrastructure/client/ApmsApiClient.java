package com.pawbridge.backend.animal.internal.apms.infrastructure.client;

import com.pawbridge.backend.animal.internal.apms.infrastructure.dto.ApmsAnimal;
import com.pawbridge.backend.animal.internal.apms.infrastructure.dto.ApmsRootResponse;

/**
 * Narrow original abandonmentPublic_v2 call contract; no Feign scan or HTTP runtime is created here.
 * Boot must bind a transport with finite connect/read or whole-request waits, no credential/body
 * logging, and no additional automatic retry beyond the snapshot's original bounded recovery.
 * Actual transport timeout/cancellation is an activation gate, not proved by this interface.
 */
@FunctionalInterface
public interface ApmsApiClient {
    ApmsRootResponse<ApmsAnimal> getAbandonmentAnimals(String serviceKey, int pageNo, int numOfRows,
            String bgnde, String endde, String upkind, String state, String type,
            String updatedBeginDate, String updatedEndDate);
}
