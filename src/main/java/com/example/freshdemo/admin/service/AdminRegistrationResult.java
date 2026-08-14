package com.example.freshdemo.admin.service;

import com.example.freshdemo.admin.domain.Admin;

/**
 * register()의 반환값. temporaryPassword는 이 요청-응답 사이클에서만 살아있는 평문이고,
 * Admin 엔티티에는 해시만 저장된다 — 다른 서비스 메서드들처럼 엔티티만 반환하면 이 값을 어디에도
 * 못 담아 컨트롤러까지 전달할 수 없어서, 이 결과 전용 레코드를 하나 뒀다.
 */
public record AdminRegistrationResult(Admin admin, String temporaryPassword) {
}
