package com.example.freshdemo.member.domain;

import com.example.freshdemo.member.domain.client.KakaoUnlinkClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * [ArchUnit 대응] member.domain.service -> member.domain으로 이동. 이벤트 리스너 어댑터라
 * 서비스가 아니고, domain.service 패키지(커버리지 100% 대상)에 있으면 안 된다.
 */
@Component
@RequiredArgsConstructor
public class KakaoUnlinkEventListener {

    private final KakaoUnlinkClient kakaoUnlinkClient;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handle(MemberWithdrawalEvent event) {
        kakaoUnlinkClient.unlink(event.kakaoUserId());
    }
}
