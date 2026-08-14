package com.example.freshdemo.member.domain.oauth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

@Slf4j
@Component
public class OAuth2LoginFailureHandler implements AuthenticationFailureHandler {

    @Value("${app.frontend.callback-url}")
    private String frontendCallbackUrl;

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                         AuthenticationException exception) throws IOException {
        log.warn("event=MEMBER_LOGIN_FAILED reason={}", exception.getClass().getSimpleName(), exception);

        String targetUrl = UriComponentsBuilder.fromUriString(frontendCallbackUrl)
                .queryParam("loginFailed", true)
                .build().toUriString();
        response.sendRedirect(targetUrl);
    }
}
