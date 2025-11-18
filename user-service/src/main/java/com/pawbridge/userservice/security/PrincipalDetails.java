package com.pawbridge.userservice.security;

import com.pawbridge.userservice.entity.User;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.oauth2.core.user.OAuth2User;

import java.util.Collection;
import java.util.List;
import java.util.Map;

@Getter
public class PrincipalDetails implements UserDetails, OAuth2User {

    private final User user;
    private Map<String, Object> attributes;

    // 일반 로그인용 생성자 (email + password)
    public PrincipalDetails(User user) {
        this.user = user;
    }

    // OAuth2 로그인용 생성자
    public PrincipalDetails(User user, Map<String, Object> attributes) {
        this.user = user;
        this.attributes = attributes;
    }

    // ========== UserDetails 인터페이스 구현 ==========

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        // User 엔티티의 role 필드를 GrantedAuthority로 변환
        // user.getRole().name() → "ROLE_USER" 또는 "ROLE_ADMIN"
        return List.of(new SimpleGrantedAuthority(user.getRole().name()));
    }

    @Override
    public String getPassword() {
        return user.getPassword();
    }

    @Override
    public String getUsername() {
        // Spring Security는 username을 식별자로 사용
        return user.getEmail();
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    // ========== OAuth2User 인터페이스 구현 ==========

    @Override
    public Map<String, Object> getAttributes() {
        return attributes;
    }

    @Override
    public String getName() {
        // OAuth2User의 getName()은 사용자 식별자를 반환
        return user.getEmail();
    }
}
