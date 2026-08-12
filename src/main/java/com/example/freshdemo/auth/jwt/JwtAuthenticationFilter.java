package com.example.freshdemo.auth.jwt;

import com.example.freshdemo.auth.CustomUserDetails;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
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
@Slf4j
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider jwtTokenProvider;
    private final AccessTokenValidAfterRepository accessTokenValidAfterRepository;

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

            if (!isValidAfterCutoff(role, id, jwtTokenProvider.getIssuedAt(token))) {
                // 서명은 유효하지만 탈퇴/계정삭제/토큰탈취 의심 등으로 무효화된 토큰 — 인증 없이 다음 필터로.
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

    /**
     * AccessTokenValidAfterRepository는 인증이 필요한 "모든" 요청마다 확인해야 하는 2차 방어선이라
     * DB 백업이 없다(AccessTokenValidAfterRepository 클래스 주석 참고) — Redis 자체가 죽어서
     * 이 확인을 못 하는 상황이면, 서명/만료 검증은 이미 통과한 요청을 굳이 막지 않고 통과시킨다
     * (fail-open). Redis 순간 장애 하나로 인증이 필요한 API 전체가 막히는 것보다는, 그 순간만
     * 이 방어선이 비활성화되는 쪽이 서비스 가용성 관점에서 낫다는 판단.
     */
    private boolean isValidAfterCutoff(String role, UUID id, LocalDateTime issuedAt) {
        try {
            return accessTokenValidAfterRepository.isValidAfter(role, id, issuedAt);
        } catch (DataAccessException e) {
            log.warn("event=ACCESS_TOKEN_VALID_AFTER_CHECK_FAILED role={} id={} — fail-open으로 통과", role, id, e);
            return true;
        }
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
