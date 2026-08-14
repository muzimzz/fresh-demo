package com.example.freshdemo.common.auth.jwt;

import com.example.freshdemo.common.auth.CustomUserDetails;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.LocalDateTime;
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
 * [LG-fm 컨벤션 리팩토링] common.auth.jwt로 이동. 인증 실패 시 여기서 JSON을 직접 쓰지 않고 그냥
 * 인증 없이 다음 필터로 넘기는 원래 동작은 그대로 두되, SecurityConfig가 이제 필터 예외를
 * HandlerExceptionResolver로 GlobalExceptionHandler에 위임하는 구조로 바뀌면서 기존의
 * JwtAuthenticationEntryPoint/JwtAccessDeniedHandler(직접 ApiResponse JSON 작성)는 삭제했다 —
 * GlobalExceptionHandler의 AuthenticationException/AccessDeniedException 핸들러가 대신한다.
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
            Long id = jwtTokenProvider.getId(token);
            TokenType type = jwtTokenProvider.getType(token);
            String role = jwtTokenProvider.getRole(token);

            if (type == null || role == null) {
                filterChain.doFilter(request, response);
                return;
            }

            if (!isValidAfterCutoff(role, id, jwtTokenProvider.getIssuedAt(token))) {
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

    private boolean isValidAfterCutoff(String role, Long id, LocalDateTime issuedAt) {
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
