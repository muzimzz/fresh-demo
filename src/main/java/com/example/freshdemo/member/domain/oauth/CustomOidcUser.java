package com.example.freshdemo.member.domain.oauth;

import com.example.freshdemo.member.domain.entity.Member;
import java.util.Collection;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;

@Getter
public class CustomOidcUser extends DefaultOidcUser {

    private final Member member;

    public CustomOidcUser(Member member,
                           Collection<? extends GrantedAuthority> authorities,
                           OidcIdToken idToken,
                           OidcUserInfo userInfo,
                           String nameAttributeKey) {
        super(authorities, idToken, userInfo, nameAttributeKey);
        this.member = member;
    }

    @Override
    public String getName() {
        return String.valueOf(member.getId());
    }
}
