package com.pawbridge.backend.user.internal.oauth.application;

import com.pawbridge.backend.user.internal.session.application.AuthSessions;
import java.util.Map;

public interface GoogleLogin {
    GoogleUser load(String registrationId,Map<String,Object> attributes);
    AuthSessions.Tokens complete(GoogleUser user);
    record GoogleUser(long userId,String email,String name,String role,String careRegNo,String provider) {}
}
