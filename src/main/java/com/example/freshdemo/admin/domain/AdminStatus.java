package com.example.freshdemo.admin.domain;

/**
 * 목표 DDL의 admin.status — ACTIVE(활성)/DELETED(삭제) 두 값뿐이다. Member의 status와 달리
 * PENDING_PROFILE 같은 중간 상태가 없다(관리자는 등록 즉시 완전한 계정으로 시작하므로).
 *
 * DDL의 chk_admin_deleted CHECK 제약이 이 값과 deleted_at/refresh_token_hash를 서로 묶는다 —
 * DELETED면 deleted_at도 채워지고 refresh_token_hash는 반드시 NULL이어야 한다. 이 프로젝트는
 * Flyway가 없어 그 CHECK를 DB 레벨로 강제하지 못하니, Admin.delete()와 AdminService가 그 짝을
 * 코드로 맞춘다(Member.deletedAt/status='WITHDRAWN' 짝과 같은 처리 방식).
 */
public enum AdminStatus {
    ACTIVE,
    DELETED,
}
