package com.pawbridge.animalservice.photo;

/** Stable codes only: do not put credentials, source URLs or response bodies in errors. */
public final class PhotoArchiveFailure extends RuntimeException {
    private final boolean slowRetry;
    public PhotoArchiveFailure(String code, boolean slowRetry) {
        super(code);
        this.slowRetry = slowRetry;
    }
    public boolean slowRetry() { return slowRetry; }
}
