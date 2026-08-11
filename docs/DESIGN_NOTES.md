# fresh-demo 설계 노트

지금까지 논의/설계한 내용을 정리한 문서. 구현 완료(✅) / 논의만 하고 미구현(⬜) / 의도적으로 범위 밖(🚫)으로 구분했다.

---

## 1. 인증 / 토큰(JWT & Redis)

- ✅ MEMBER/ADMIN 공용 JWT 인프라. `type`(MEMBER/ADMIN) + `role`(Spring Security 권한 문자열) 클레임으로 한 토큰 체계를 공유.
- ✅ Redis refreshToken 키 규칙 `refreshToken:{role}:{id}`. role이 바뀌면(예: ADMIN→SUPER_ADMIN) 키 자체가 달라져서 예전 토큰이 자연 무효화됨.
- ✅ `remember`(자동로그인) 클레임. JWT 만료시간 자체는 안 바꾸고, 쿠키를 영속/세션 쿠키 중 뭘로 내려줄지만 결정. 재발급 때도 이전 값을 이어감.
- ✅ Refresh Token Rotation(RTR). 재발급마다 refreshToken을 새로 발급해서 Redis 값을 교체 — 탈취된 옛 토큰 재사용을 막음.
- ✅ **RTR 동시성(race condition) 처리.** Redis Lua 스크립트(`scripts/refresh_token_cas.lua`)로 "비교+저장"을 원자적 CAS로 묶음(`RefreshTokenRepository.compareAndSave`). `AuthController.reissue()`가 기존의 조회→비교→저장 3단계를 이 CAS 호출 하나로 대체.
- ✅ **탈취/재사용 의심 시 즉시 무효화.** CAS가 실패하면(정상적인 동시 요청 race인지, 폐기된 토큰의 재사용인지 구분 불가) 안전하게 재사용으로 간주해 해당 `(role, id)`의 세션을 즉시 삭제 + `UNAUTHORIZED`. Grace period(직전 토큰 잠깐 허용)는 넣지 않음 — 정상적인 동시 요청(예: 여러 탭에서 거의 동시에 재발급)까지도 로그아웃될 수 있는 트레이드오프를 감수하고 보안을 우선한 선택.
- ✅ **Redis 장애 대비 DB 백업 계층.** `RefreshTokenBackup`(MySQL) 엔티티에 `save`/`find`/`delete`/`compareAndSave` 전부 write-through로 같이 기록. Redis 호출이 "정상적으로 값이 없음"이 아니라 "연결 자체가 안 됨"(`DataAccessException`)일 때만 DB로 폴백— CAS도 Redis가 죽으면 DB의 조건부 `UPDATE`(영향받은 row 수로 성공 판단)로 대체.
- ✅ **DB 백업 만료 정리 배치.** `RefreshTokenCleanupScheduler`(`@Scheduled`, 매시 정각)가 `expiresAt` 지난 `RefreshTokenBackup` row를 삭제. Redis처럼 TTL 자동 만료가 없어서 필요한 보완.

## 2. 카카오 로그인 & 회원 식별

- ✅ Kakao OIDC `scope=openid` 명시 필요 여부 확인. Spring Security의 oauth2Login은 scope 파라미터를 항상 명시적으로 보내는 구조라 openid를 빼면 안 됨 — 기존 설정이 이미 맞았음(검증만 하고 코드 변경 없음).
- ✅ `provider_user_id`(카카오 `sub`)와 내부 `memberId`(UUID PK)는 완전히 분리된 필드. 애초부터 그렇게 구현돼 있었음.
- ✅ 카카오 `sub`이 unlink→relink 후에도 값이 안 바뀐다는 것을 카카오 공식 지원 답변(devtalk, 2026-03)으로 확인 — `active_provider_key` 설계가 안전하게 성립하는 전제.
- ✅ **`active_provider_key` 도입.** `"{social_type}:{social_type_id}"` 형태의 필드 하나에만 UNIQUE를 걸고, `social_type`/`social_type_id` 자체의 유니크 제약은 제거. 탈퇴 시 이 필드를 null로 비워서 이력(social_type_id 등)은 행에 남기되 같은 소셜 계정으로 재가입할 자리를 비켜줌.
- ✅ `reactivate()` 제거. 재가입은 옛 행을 되살리는 게 아니라 새 행을 만드는 방식으로 전환(`CustomOidcUserService`, `MemberWithdrawalService`가 전부 `findByActiveProviderKey` 기준으로 전환됨).

## 3. 온보딩 & 회원 상태

- ✅ `isNewMember`(생성 이벤트 플래그) → `pendingProfile`(상태 기반) 전환. 온보딩 중 브라우저를 닫아도 다음 로그인 때 정확한 값이 다시 내려옴.
- ✅ 필수 항목(닉네임 + 약관동의)과 선택 항목(전화/주소, 첫 배송 시 수집) 분리. `MemberOnboardingRequest`가 필수만 받음.
- ✅ 닉네임은 카카오 제공값이 아니라 자체 폼으로 수집.

## 4. 탈퇴

- ✅ 소프트 삭제(`status = WITHDRAWN`) + `active_provider_key` null 처리.
- ✅ 로그아웃 시 카카오 `POST /v1/user/logout`(Admin Key 기반) 추가 호출 — "카카오계정과 함께 로그아웃"(브라우저 기반 완전 로그아웃)과는 다른 것.
- ✅ 카카오 unlink 이벤트를 트랜잭션 커밋 이후(AFTER_COMMIT)에 비동기로 처리.
- ✅ 카카오 unlink 웹훅 수신 처리 — `active_provider_key` 기준으로 조회하도록 갱신됨.
- ✅ 카카오 unlink 웹훅 진위 확인 강화. `app_id`(위조 가능한 값) 일치만으로는 부족해서, 카카오가 함께 보내는 `Authorization: KakaoAK {admin key}` 헤더까지 검증하도록 추가.
- 🚫 "카카오계정과 함께 로그아웃"(브라우저 기반, `GET /oauth/logout`) — 의도적으로 미구현.
- ⬜ 진행 중 주문/미완료 환불이 있으면 탈퇴 차단. 주문 도메인이 아직 없어서 `Member.withdraw()`/`MemberWithdrawalService`에 TODO 주석만 남겨둠.

## 5. 관리자(Admin)

- ✅ JWT 기반 로그인/계정 발급/삭제(SUPER_ADMIN 전용). fm-backend의 Admin 엔티티를 참고하되 PK는 UUID(v7)로 단순화(fm-backend는 Long AUTO_INCREMENT).
- ✅ 계정 존재 여부 비노출(로그인 실패 시 "없음"과 "비번 틀림"을 같은 에러로 응답).

## 6. 배송지(Address)

- ✅ CRUD 전체(`GET/POST /addresses`, `PUT/DELETE /addresses/{id}`).
- ✅ 기본 배송지 1개 보장 — 서비스 레이어(`AddressService`)에서 트랜잭션으로 "기존 기본 해제 후 새로 지정" 처리.
- ⬜ 목표 DDL의 `GENERATED ALWAYS AS` 컬럼 기반 단일 기본값 강제. Hibernate `ddl-auto: update`로 이 패턴을 깔끔하게 재현하기 어려워 서비스 레이어 로직으로 대체 — 정식 스키마(Flyway/Liquibase 등으로 DDL 직접 관리) 전환 시 재검토 대상.

## 7. API 응답 규약

- ✅ `ApiResponse<T>`로 성공/실패 응답 통일. 컨트롤러 전부 `ResponseEntity<ApiResponse<T>>` 반환(카카오 웹훅 컨트롤러만 외부 계약이라 예외).
- ⬜→🚫 `SuccessCode` enum. 처음엔 후보로 거론됐으나 "성공 응답은 코드 분기가 거의 필요 없다"는 이유로 기각 — 대신 `ApiResponse.of(T data)`가 고정된 `"OK"` 사용.
- ✅ `ErrorCode` 체계(HttpStatus + 메시지). Address 관련(`ADDRESS_NOT_FOUND`) 추가됨.

## 8. 로깅 & PII

- ✅ `HttpBodyLoggingFilter`의 자동 마스킹(password/token류 키, 이메일 정규식) — 기존 구현.
- ✅ `PiiMasker` 유틸(email/phone/name/generic/redact) — 기존 구현, 어노테이션이 아니라 DTO `from()` 팩토리에서 명시적으로 호출하는 방식.
- ✅ `PiiMasker.maskProviderId()` 추가. `KakaoUnlinkClient`/`KakaoLogoutClient`/`KakaoUnlinkWebhookController`에서 평문으로 찍히던 `kakaoUserId`/`provider_user_id`를 마스킹.
- ✅(원칙 확인) `log.debug`로 엔티티를 통째로 찍는 건 지양 — 코드베이스에 실제 사례는 없었고, 원칙만 재확인.
- ✅ `MdcLoggingFilter`의 `requestId`를 `traceId`로 개명 + `TraceIdExchangeFilter`로 아웃바운드 WebClient 호출(`kakaoApiWebClient`)에 전파. 정식 분산 추적(OpenTelemetry 등)이 아니라 문자열 하나를 그대로 복사해서 넘기는 간이 구현이고, MSA로 쪼개지기 전까지는(카카오는 이 규약을 모르니) 사실상 효과가 없다는 점을 클래스 주석에 명시. 지금 모놀리스 안에서도 `traceId` 자체는 HTTP 계층 로그와 비즈니스 계층 로그를 잇는 용도로 값어치가 있어 유지.
- ✅ `logback-spring.xml` 추가. `MdcLoggingFilter`가 채워두던 MDC(requestId/method/uri/clientIp)가 실제로는 로깅 패턴에 안 걸려 있어서 콘솔에 전혀 안 보이던 걸 발견 — local/dev 프로필은 사람이 읽기 좋은 콘솔 패턴에 MDC를 노출, prod 프로필은 `logstash-logback-encoder`로 MDC 필드가 JSON 키로 자동 포함되는 구조화 로그 + `AsyncAppender`(요청 스레드 블로킹 방지, WARN/ERROR는 큐 포화해도 안 버림). `net.logstash.logback:logstash-logback-encoder:8.0` 의존성 추가 필요.

## 9. 목표 스키마(DDL) 정합성

두 종류의 DDL 초안과 비교·분석했지만, "정식 스키마로 넘어갈 때" 다루기로 하고 지금 fresh-demo에는 반영 보류한 항목들.

**상세 DDL**(member_grade/member/address/admin, `public_id BINARY(16)`, generated column 포함)
- ✅ `active_provider_key` — 이번에 fresh-demo에 실제 반영 완료.
- ⬜ PK 전략 불일치. 목표 DDL은 내부 PK(BIGINT AUTO_INCREMENT) + 외부 노출용 `public_id`(BINARY(16))를 분리하는데, fresh-demo는 UUID(v7) 단일 PK로 단순화된 상태 — 정식 스키마 전환 시 재검토.
- ⬜ `member_grade` 테이블 — 미구현.
- ⬜ `nickname` vs `name` 필드 역할 구분 — 목표 DDL엔 카카오 제공 `nickname`과 폼 입력 `name`이 별도 필드로 존재하는 뉘앙스가 있었는데, fresh-demo는 단일 `nickname`(폼 수집)으로 단순화. 완전히 정리된 결론은 아님.
- ⬜ `is_marketing_agreed` — 미구현.
- ⬜ `BLOCKED` 상태 — 미구현(현재 `MemberStatus`는 PENDING_PROFILE/ACTIVE/WITHDRAWN만 존재).

**단순화된 ERD 초안**(cart/member_grade/member/address, PK만)
- ⬜ 팀플 발표용 초안이라 fresh-demo에 반영 안 함. 비교 중 발견된 불일치만 기록: email/nickname NOT NULL 여부 충돌, 정체불명의 `password_hash` 컬럼(카카오 전용인데 왜 있는지 불명확), 약관동의 필드 누락, `DEFAULT BOOLEAN` 오타.
- ⬜ `cart` 테이블 — fresh-demo는 인증 데모 범위라 주문/장바구니 도메인 자체가 없음.

## 10. 앞으로 더 설계하면 좋을 것들

- Grace period / token family 기반 탈취 탐지 — 지금은 CAS 실패를 전부 "재사용 의심"으로 간주해 무조건 세션을 끊는 단순한 방식. 정상적인 동시 요청까지 로그아웃되는 게 UX상 거슬리면, 직전 토큰을 잠깐 봐주는 grace window나 토큰 계보(family) 기반 탐지로 정교화 — 실서비스 전환 시점에 필요성 재평가.
- 정식 스키마 전환 시 PK 전략 결정(UUID 유지 vs BIGINT+public_id 분리) — 이번 `active_provider_key`처럼 스키마가 바뀌는 결정이라 미리 팀 합의 필요.
- 주문 도메인이 생기면 `Member.withdraw()`의 "진행 중 주문 있으면 탈퇴 차단" TODO 실제 구현.
- 카카오 unlink 웹훅을 SSF(Shared Signals Framework) 기반 "계정 상태 변경 웹훅"으로 이전 — admin key 헤더 검증보다 더 강한 RS256 서명 검증(JWKS)이 가능.
- 운영 전환 체크리스트: `JWT_SECRET` 관리 방식(시크릿 매니저 도입 여부), Redis 이중화(Sentinel/Cluster) 여부 — 지금은 단일 Redis 인스턴스 전제로 설계됨. (`jwt.cookie.secure`는 `application-local.yaml`/`application-prod.yaml` 프로필 분리로 이미 반영됨 — 배포 시 `SPRING_PROFILES_ACTIVE=prod` 지정 필요.)
- `Address`의 기본 배송지 강제 로직을 DDL의 generated column 방식으로 옮길지, 지금처럼 서비스 레이어 방식을 유지할지 — Flyway/Liquibase 도입 여부와 맞물린 결정.
- `RefreshTokenBackup` 테이블도 실서비스 규모에서는 row 수가 활성 사용자 수만큼 쌓이는데, 지금은 매시 정각 배치 하나로만 정리 — 트래픽이 커지면 배치 주기/인덱스 전략 재검토.
