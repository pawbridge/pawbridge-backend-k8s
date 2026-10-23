package com.pawbridge.backend.user.internal.oauth.application;

import com.pawbridge.backend.user.internal.session.application.AuthSessions;
import java.util.Map;

public interface SocialLogin {
    SocialUser load(String registrationId,Map<String,Object> attributes);
    AuthSessions.Tokens complete(SocialUser user);
    record SocialUser(long userId,String email,String name,String role,String careRegNo,String provider) {}
}
