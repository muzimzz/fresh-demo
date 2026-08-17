package com.example.freshdemo.admin.domain;

import com.example.freshdemo.admin.domain.entity.Admin;

/**
 * [ArchUnit 대응] admin.domain.service -> admin.domain으로 이동. Service 접미사가 없는 클래스라
 * domain.service 패키지(커버리지 100% 대상, DPB-4-10)에 있으면 안 된다 — 이 클래스는 서비스가
 * 아니라 AdminService.register()의 결과값을 담는 값 객체다.
 */
public record AdminRegistrationResult(Admin admin, String temporaryPassword) {
}
