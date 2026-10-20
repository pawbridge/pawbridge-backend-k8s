package com.pawbridge.backend.user.internal.deletion.application;

@FunctionalInterface
public interface UserDeletion {
    void delete(long userId);
}
