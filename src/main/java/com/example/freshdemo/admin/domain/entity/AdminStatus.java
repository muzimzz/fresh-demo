package com.example.freshdemo.admin.domain.entity;

/**
 * 목표 DDL의 admin.status — ACTIVE(활성)/DELETED(삭제) 두 값뿐이다. 관리자는 등록 즉시 완전한
 * 계정으로 시작해 PENDING 같은 중간 상태가 없다. chk_admin_deleted CHECK(Admin @Check)가 이
 * 값과 deleted_at/refresh_token_hash를 서로 묶는다.
 */
public enum AdminStatus {
    ACTIVE,
    DELETED,
}
