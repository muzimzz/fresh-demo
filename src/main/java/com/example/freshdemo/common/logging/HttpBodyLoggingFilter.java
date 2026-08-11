package com.example.freshdemo.common.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * 요청/응답 바디를 로그로 남기되, 민감정보는 마스킹해서 찍는 필터.
 *
 * - ContentCachingRequestWrapper/ResponseWrapper로 스트림을 감싸서 바디를 다시 읽을 수 있게 만든다
 *   (서블릿 InputStream/OutputStream은 원래 한 번만 읽고 버려짐).
 * - password/token류 "키"는 값을 통째로 REDACTED 처리, email/phone은 패턴으로 찾아 부분 마스킹.
 * - 응답은 반드시 copyBodyToResponse()로 실제 클라이언트에게 흘려보내야 한다 — 안 하면 빈 응답이 나감.
 * - 바이너리(이미지 업로드 등)나 SSE처럼 긴 스트리밍 응답은 본문 로깅 대상에서 제외.
 *
 * MdcLoggingFilter(HIGHEST_PRECEDENCE)보다 뒤에 실행되게 순서를 한 칸 늦췄다 — traceId가 먼저 MDC에 박혀 있어야
 * 이 필터가 남기는 로그도 같은 traceId로 묶인다.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class HttpBodyLoggingFilter extends OncePerRequestFilter {

    private static final int MAX_BODY_LOG_LENGTH = 2000;

    // ContentCachingRequestWrapper가 실제로 메모리에 캐싱해둘 최대 바이트 수. MAX_BODY_LOG_LENGTH(문자 수
    // 기준 자르기)보다 넉넉하게 잡는다 — UTF-8에서 한글 등 멀티바이트 문자는 1자당 최대 3바이트라,
    // 바이트 기준 캐시 한도를 문자 수랑 똑같이 잡으면 로그로 남기기도 전에 글자가 중간에 잘릴 수 있다.
    private static final int MAX_BODY_CACHE_BYTES = 8192;

    private static final List<String> EXCLUDED_PATH_PREFIXES = List.of(
            "/notifications/stream" // SSE 등 스트리밍 응답은 캐싱하면 안 됨
    );

    // "password":"1234", "refreshToken": "eyJ..." 같은 키-값 쌍의 값을 통째로 마스킹
    private static final Pattern SENSITIVE_JSON_FIELD = Pattern.compile(
            "(?i)(\"(password|accessToken|refreshToken|token|secret|authorization|idToken|clientSecret)\"\\s*:\\s*\")[^\"]*(\")");

    // 위 키-값 패턴에 안 걸린 이메일도 한 번 더 잡아서 부분 마스킹
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return EXCLUDED_PATH_PREFIXES.stream().anyMatch(uri::startsWith);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        // 이 버전의 스프링은 무제한 캐싱하는 단일 인자 생성자를 없애고 캐시 한도를 강제한다 —
        // 요청 바디가 얼마나 크든 무제한으로 메모리에 다 올려두는 걸 막기 위한 변경.
        ContentCachingRequestWrapper wrappedRequest = new ContentCachingRequestWrapper(request, MAX_BODY_CACHE_BYTES);
        ContentCachingResponseWrapper wrappedResponse = new ContentCachingResponseWrapper(response);

        long start = System.currentTimeMillis();
        try {
            filterChain.doFilter(wrappedRequest, wrappedResponse);
        } finally {
            long durationMs = System.currentTimeMillis() - start;

            if (log.isInfoEnabled()) {
                String reqBody = mask(extractBody(wrappedRequest.getContentAsByteArray()));
                String resBody = mask(extractBody(wrappedResponse.getContentAsByteArray()));

                log.info("event=HTTP_ACCESS status={} durationMs={} reqBody=\"{}\" resBody=\"{}\"",
                        wrappedResponse.getStatus(), durationMs, reqBody, resBody);
            }

            // 캐싱 래퍼로 감쌌으니 실제 응답 스트림으로 반드시 다시 흘려보내야 클라이언트가 정상 응답을 받는다
            wrappedResponse.copyBodyToResponse();
        }
    }

    private String extractBody(byte[] content) {
        if (content == null || content.length == 0) {
            return "";
        }
        String body = new String(content, StandardCharsets.UTF_8);
        return body.length() > MAX_BODY_LOG_LENGTH
                ? body.substring(0, MAX_BODY_LOG_LENGTH) + "...(truncated)"
                : body;
    }

    private String mask(String body) {
        if (body.isBlank()) {
            return body;
        }
        String masked = SENSITIVE_JSON_FIELD.matcher(body).replaceAll(mr -> mr.group(1) + "***REDACTED***" + mr.group(3));

        Matcher emailMatcher = EMAIL_PATTERN.matcher(masked);
        StringBuilder sb = new StringBuilder();
        while (emailMatcher.find()) {
            emailMatcher.appendReplacement(sb, Matcher.quoteReplacement(PiiMasker.maskEmail(emailMatcher.group())));
        }
        emailMatcher.appendTail(sb);
        return sb.toString();
    }
}
