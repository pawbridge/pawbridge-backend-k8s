package com.pawbridge.backend.user.internal.oauth.application;

import com.pawbridge.backend.user.internal.session.application.IssuedRefreshToken;
import java.util.Map;

public interface SocialLogin {
    SocialUser load(String registrationId,Map<String,Object> attributes);

    /** Starts a new refresh session for the user; the caller puts the token only in the refresh cookie. */
    IssuedRefreshToken complete(SocialUser user);

    record SocialUser(long userId,String email,String name,String role,String careRegNo,String provider) {}
}
