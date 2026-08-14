package com.example.freshdemo.common.auth;

import com.example.freshdemo.common.auth.jwt.TokenType;
import java.util.Collection;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

// member/admin 공용 — type으로 어느 쪽 토큰인지 구분한다. [LG-fm 컨벤션 리팩토링] auth ->
// common.auth로 이동, 로직 무변경.
public class CustomUserDetails implements UserDetails {

    private final Long id;
    private final TokenType type;
    private final String role;

    public CustomUserDetails(Long id, TokenType type, String role) {
        this.id = id;
        this.type = type;
        this.role = role;
    }

    public Long getId() {
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
        return List.of(
                new SimpleGrantedAuthority(role),
                new SimpleGrantedAuthority("TYPE_" + type.name())
        );
    }

    @Override
    public @Nullable String getPassword() {
        return null;
    }

    @Override
    public String getUsername() {
        return String.valueOf(id);
    }
}
