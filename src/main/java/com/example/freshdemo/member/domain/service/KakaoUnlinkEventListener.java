package com.example.freshdemo.member.domain.service;

import com.example.freshdemo.member.domain.client.KakaoUnlinkClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
public class KakaoUnlinkEventListener {

    private final KakaoUnlinkClient kakaoUnlinkClient;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handle(MemberWithdrawalEvent event) {
        kakaoUnlinkClient.unlink(event.kakaoUserId());
    }
}
