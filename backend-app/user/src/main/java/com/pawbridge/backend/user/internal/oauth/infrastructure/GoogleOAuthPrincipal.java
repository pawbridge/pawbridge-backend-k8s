package com.pawbridge.backend.user.internal.oauth.infrastructure;

import com.pawbridge.backend.user.internal.oauth.application.GoogleLogin.GoogleUser;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.user.OAuth2User;

public final class GoogleOAuthPrincipal implements OAuth2User {
    private final GoogleUser user;
    private final Map<String,Object> attributes;
    public GoogleOAuthPrincipal(GoogleUser user,Map<String,Object> attributes){this.user=user;this.attributes=attributes;}
    public GoogleUser user(){return user;}
    @Override public Map<String,Object> getAttributes(){return attributes;}
    @Override public Collection<? extends GrantedAuthority> getAuthorities(){return List.of(new SimpleGrantedAuthority(user.role()));}
    @Override public String getName(){return user.email();}
    @Override public String toString(){return "GoogleOAuthPrincipal";}
}
