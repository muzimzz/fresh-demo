package com.example.freshdemo.member.service;

import com.example.freshdemo.member.client.KakaoUnlinkClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * DB 탈퇴 트랜잭션이 실제로 커밋된 뒤에만 카카오 unlink를 호출한다.
 * DB 쓰기가 없는 순수 외부 API 호출이라 REQUIRES_NEW 같은 propagation 문제는 여기선 해당 없다
 * (그 문제는 AFTER_COMMIT 리스너 안에서 "우리 DB에 다시 쓰기"를 할 때만 생긴다).
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
