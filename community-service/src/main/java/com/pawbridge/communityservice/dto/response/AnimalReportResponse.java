package com.pawbridge.communityservice.dto.response;

import com.pawbridge.communityservice.domain.entity.AnimalReport;

public record AnimalReportResponse(PostResponse post, AnimalReport detail, boolean legacy) {
}
