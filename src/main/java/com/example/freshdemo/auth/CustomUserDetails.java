package com.example.freshdemo.auth;

import com.example.freshdemo.auth.jwt.TokenType;
import java.util.Collection;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;


// member/admin 공용 — type으로 어느 쪽 토큰인지 구분한다.
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

    /**
     * role(권한) 문자열뿐 아니라 "TYPE_MEMBER"/"TYPE_ADMIN"이라는 합성 authority도 같이 얹는다 —
     * role은 권한 수준을, 이 합성 authority는 "이 신원이 어느 도메인(회원/관리자) 소속인가"를
     * 나타낸다. SecurityConfig가 /addresses/**, /members/** 같은 회원 전용 API를 role 종류가
     * 몇 개로 늘어나든 영향 없이 "TYPE_MEMBER인가"로만 막을 수 있게 하기 위함(role만으로는
     * "회원이면 다 통과"를 표현하려면 회원 role이 늘어날 때마다 requestMatchers도 같이 늘려야 함).
     */
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(
                new SimpleGrantedAuthority(role),
                new SimpleGrantedAuthority("TYPE_" + type.name())
        );
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
