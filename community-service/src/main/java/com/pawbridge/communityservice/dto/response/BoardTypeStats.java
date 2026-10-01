package com.pawbridge.communityservice.dto.response;

import com.pawbridge.communityservice.domain.entity.BoardType;

public record BoardTypeStats(BoardType boardType, long count) {}
