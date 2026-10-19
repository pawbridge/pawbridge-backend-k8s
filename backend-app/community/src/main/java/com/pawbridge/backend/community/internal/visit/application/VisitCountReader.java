package com.pawbridge.backend.community.internal.visit.application;

import java.time.LocalDate;

public interface VisitCountReader {
    long count(LocalDate day);
}
