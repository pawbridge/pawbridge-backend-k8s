package com.pawbridge.backend.user.internal.session.application;

/**
 * A newly issued opaque refresh token and how long the browser may keep it.
 *
 * @param value the raw token; it is only ever placed in the refresh cookie
 * @param maxAgeSeconds seconds until the earlier of the idle and absolute session expiry
 */
public record IssuedRefreshToken(String value, long maxAgeSeconds) {

    @Override
    public String toString() {
        return "IssuedRefreshToken[maxAgeSeconds=" + maxAgeSeconds + "]";
    }
}
