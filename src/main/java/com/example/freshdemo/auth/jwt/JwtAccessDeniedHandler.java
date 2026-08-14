package com.example.freshdemo.auth.jwt;

import com.example.freshdemo.common.exception.ErrorCode;
import com.example.freshdemo.common.response.ApiResponse;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

// (2026-08-14 13:50) member.oauth.error에서 이동. SecurityConfig의 전역 exceptionHandling에
// 등록돼서 member/admin 요청 전부의 JWT 인증 실패를 처리하는데, "member 카카오 로그인 전용"처럼
// 보이는 옛 위치가 오해 소지가 있어 auth.jwt(JwtAuthenticationFilter 등 나머지 JWT 인프라)로 옮김.
@Component
@RequiredArgsConstructor
public class JwtAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException accessDeniedException)
            throws IOException, ServletException {

        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");

        objectMapper.writeValue(response.getWriter(), ApiResponse.fail(ErrorCode.FORBIDDEN));
    }
}
