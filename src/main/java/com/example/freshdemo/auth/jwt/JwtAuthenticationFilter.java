package com.example.freshdemo.auth.jwt;

import com.example.freshdemo.auth.CustomUserDetails;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 회원(MEMBER)·관리자(ADMIN) 토큰을 한 필터에서 같이 처리한다 — 별도 필터체인으로 쪼개지 않고,
 * type/role 클레임으로 구분해서 인가는 SecurityConfig의 requestMatchers가 담당하게 했다.
 *
 * type/role 클레임이 없는 토큰(형식이 이상하거나 예전 버전 토큰)은 인증 없이 통과시켜
 * 뒤의 AuthorizationFilter가 401/403으로 걸러내게 한다.
 */
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider jwtTokenProvider;
    private final AccessTokenBlacklistRepository accessTokenBlacklistRepository;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String token = resolveToken(request);

        if (token == null) {
            filterChain.doFilter(request, response);
            return;
        }

        if (jwtTokenProvider.validateToken(token)) {
            UUID id = jwtTokenProvider.getId(token);
            TokenType type = jwtTokenProvider.getType(token);
            String role = jwtTokenProvider.getRole(token);

            if (type == null || role == null) {
                filterChain.doFilter(request, response);
                return;
            }

            if (accessTokenBlacklistRepository.isBlacklisted(role, id)) {
                // 서명은 유효하지만 탈퇴/계정삭제 등으로 차단된 토큰 — 인증 없이 다음 필터로.
                filterChain.doFilter(request, response);
                return;
            }

            CustomUserDetails userDetails = new CustomUserDetails(id, type, role);

            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

            SecurityContextHolder.getContext().setAuthentication(authentication);
        }

        filterChain.doFilter(request, response);
    }

    private String resolveToken(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (bearerToken != null && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }

        if (request.getCookies() != null) {
            for (Cookie cookie : request.getCookies()) {
                if ("accessToken".equals(cookie.getName())) {
                    return cookie.getValue();
                }
            }
        }

        return null;
    }
}
