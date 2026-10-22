package com.pawbridge.backend.user.internal.login.web;

import com.pawbridge.backend.user.internal.login.application.LocalLogin;
import java.util.Collection;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

public record LocalLoginPrincipal(LocalLogin.Account account) implements UserDetails {
    @Override public Collection<? extends GrantedAuthority> getAuthorities(){return List.of(new SimpleGrantedAuthority(account.role()));}
    @Override public String getPassword(){return account.password();}
    @Override public String getUsername(){return account.email();}
    @Override public boolean isAccountNonExpired(){return true;}
    @Override public boolean isAccountNonLocked(){return true;}
    @Override public boolean isCredentialsNonExpired(){return true;}
    @Override public boolean isEnabled(){return true;}
    @Override public String toString(){return "LocalLoginPrincipal";}
}
