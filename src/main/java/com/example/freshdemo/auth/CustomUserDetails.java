package com.example.freshdemo.auth;

import com.example.freshdemo.auth.jwt.TokenType;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;


// member/admin 공용 — type으로 어느 쪽 토큰인지 구분한다.
public class CustomUserDetails implements UserDetails {

    private final UUID id;
    private final TokenType type;
    private final String role;

    public CustomUserDetails(UUID id, TokenType type, String role) {
        this.id = id;
        this.type = type;
        this.role = role;
    }

    public UUID getId() {
        return id;
    }

    public TokenType getType() {
        return type;
    }

    public String getRole() {
        return role;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority(role));
    }

    // jwt는 비밀번호가 없음
    @Override
    public @Nullable String getPassword() {
        return null;
    }

    @Override
    public String getUsername() {
        return String.valueOf(id);
    }
}
