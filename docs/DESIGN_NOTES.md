# fresh-demo 설계 노트

지금까지 논의/설계한 내용을 정리한 문서. 구현 완료(✅) / 논의만 하고 미구현(⬜) / 의도적으로 범위 밖(🚫)으로 구분했다.

---

## 1. 인증 / 토큰(JWT & Redis)

- ✅ MEMBER/ADMIN 공용 JWT 인프라. `type`(MEMBER/ADMIN) + `role`(Spring Security 권한 문자열) 클레임으로 한 토큰 체계를 공유.
- ✅ Redis refreshToken 키 규칙 `refreshToken:{role}:{id}`. role이 바뀌면(예: ADMIN→SUPER_ADMIN) 키 자체가 달라져서 예전 토큰이 자연 무효화됨.
- ✅ `remember`(자동로그인) 클레임. JWT 만료시간 자체는 안 바꾸고, 쿠키를 영속/세션 쿠키 중 뭘로 내려줄지만 결정. 재발급 때도 이전 값을 이어감.
- ✅ Refresh Token Rotation(RTR). 재발급마다 refreshToken을 새로 발급해서 Redis 값을 교체 — 탈취된 옛 토큰 재사용을 막음.
- ✅ **RTR 동시성(race condition) 처리.** Redis Lua 스크립트(`scripts/refresh_token_cas.lua`)로 "비교+저장"을 원자적 CAS로 묶음(`RefreshTokenRepository.compareAndSave`). `AuthController.reissue()`가 기존의 조회→비교→저장 3단계를 이 CAS 호출 하나로 대체.
- ✅ **탈취/재사용 의심 시 즉시 무효화.** CAS가 실패하면(정상적인 동시 요청 race인지, 폐기된 토큰의 재사용인지 구분 불가) 안전하게 재사용으로 간주해 해당 `(role, id)`의 RT 세션을 즉시 삭제 + `UNAUTHORIZED`. Grace period(직전 토큰 잠깐 허용)는 넣지 않음 — 정상적인 동시 요청(예: 여러 탭에서 거의 동시에 재발급)까지도 로그아웃될 수 있는 트레이드오프를 감수하고 보안을 우선한 선택.
- ✅ **RT에만 `jti` 클레임 추가.** `event=REFRESH_TOKEN_REUSE_SUSPECTED` 로그에 "정확히 어떤 토큰 인스턴스가 재사용됐는지" 남기기 위함. jti는 서명 없는 순수 랜덤 라벨이라(그 자체로는 아무 권한도 증명 못 함) 로그에 평문으로 남겨도 안전함 — accessToken/refreshToken 원문 전체(서명 포함)를 로그에 남기면 안 되는 것과는 다른 문제. AT는 "재사용 의심" 개념 자체가 성립 안 해서(같은 AT를 수명 내내 반복 사용하는 게 정상) jti를 안 넣음.
- ✅ **AT(accessToken) 탈취 대비 무효화(`AccessTokenValidAfterRepository`).** Redis에 계정 단위 "커트라인 시각"을 저장해두고(`accessTokenValidAfter:{role}:{id}`), `JwtAuthenticationFilter`가 매 요청마다 AT의 `iat`(발급 시각)와 이 커트라인을 비교 — 커트라인 이전에 발급된 토큰은 서명/만료가 멀쩡해도 인증을 안 세워준다. RT 재사용 의심(`AuthController.reissue()`), 회원탈퇴/관리자 계정삭제(`MemberWithdrawalService`) 시점에 커트라인=지금 시각으로 등록한다. boolean 블랙리스트(예전 `AccessTokenBlacklistRepository`) 대신 커트라인+`iat` 비교 방식을 쓴 이유: boolean 플래그는 "이 계정은 TTL 동안 무조건 다 차단"이라, 탈취 의심 직후 사용자가 바로 재로그인해서 받는 새 토큰까지 막아버리는 문제가 있었다 — 커트라인 이후 발급된 토큰은 정상 통과되게 해서 이 문제를 없앴다.
- ✅ **AT 무효화 체크는 fail-open.** `AccessTokenValidAfterRepository`는 인증이 필요한 모든 요청마다 확인해야 해서 DB 백업을 안 둔다(매 요청 DB 조회는 성능 병목) — Redis 장애 시엔 이 2차 방어선 체크를 건너뛰고 통과시킨다(WARN 로그만 남김). Redis 순간 장애 하나로 인증 API 전체가 막히는 것보다 낫다는 판단.
- ✅ **Redis 장애 대비 DB 백업 계층 — `member`/`admin` 컬럼 방식으로 전환.** 원래는 `RefreshTokenBackup`이라는 별도 테이블에 write-through로 기록했으나, 목표 DDL이 refreshToken 백업을 `member.refresh_token_hash`/`refresh_token_expires_at` 컬럼으로 갖는 걸 보고 그 방식으로 맞췄다(하이브리드: Redis는 그대로 1차 저장소로 유지하고, DB 백업 대상만 별도 테이블→소유 엔티티의 컬럼으로 이전). `RefreshTokenRepository`가 이제 `TokenType`(MEMBER/ADMIN)을 받아 `MemberRepository`/`AdminRepository`의 `updateRefreshToken`/`clearRefreshToken`/`compareAndSetRefreshToken`(전부 `@Modifying @Query` 벌크 UPDATE) 중 하나로 라우팅한다. `save`/`delete`/`compareAndSave` 전부 write-through 유지, Redis 호출이 "정상적으로 값이 없음"이 아니라 "연결 자체가 안 됨"(`DataAccessException`)일 때만 DB로 폴백 — CAS도 Redis가 죽으면 DB의 조건부 `UPDATE`(영향받은 row 수로 성공 판단)로 대체. 목표 DDL의 `admin` 테이블엔 이 두 컬럼이 없지만, `RefreshTokenRepository`가 Member/Admin 세션을 공용으로 다루는 구조라 `Admin`에도 동일하게 추가했다(DDL엔 없는 의도적 확장, 안 넣으면 Admin 세션만 DB 백업 커버리지가 사라지는 회귀였음). Redis 이중화(Sentinel/Cluster)는 별도로 구성하지 않기로 하고, 이 write-through+폴백 계층으로 대신하기로 한 결정은 그대로 유지.
- ✅ **DB 백업 실패는 롤백하지 않음.** DB는 어디까지나 Redis의 보조 수단이라, DB 백업 쓰기/삭제가 실패해도(DB 다운 등) 예외를 삼키고 로그만 남긴다(`trySaveBackup`) — Redis가 멀쩡한데 DB 장애 때문에 로그인/재발급 자체가 실패하는 걸 막기 위함. 대가로 "이번 회차 DB 백업이 아예 안 남을 수 있다"는 리스크는 감수(Redis와 DB가 동시에 죽는 이중 장애가 아닌 이상 문제 없음).
- ✅→🚫 **DB 백업 만료 정리 배치 — 제거됨.** 별도 테이블(`RefreshTokenBackup`)일 때는 만료된 row를 지워야 했지만(`RefreshTokenCleanupScheduler`, `@Scheduled` 매시 정각), 이제 백업이 `member`/`admin`의 컬럼이라 그 회원/관리자 행 자체가 살아있는 한 정리할 "별도 row"가 없다 — 스케줄러 자체를 삭제했다. 참고로 이 삭제로 인해 프로젝트 전체에 `@Scheduled` 메서드가 하나도 안 남게 됐다 — `FreshDemoApplication`의 `@EnableScheduling`과 `SchedulerLoggingAspect`는 당장은 대상이 없지만 해롭지 않아 그대로 유지(나중에 스케줄러가 다시 생기면 바로 씀).
- ✅ **refreshToken은 원문이 아니라 SHA-256 해시로 저장(`TokenHasher`).** Redis value / `member.refresh_token_hash`(`admin.refresh_token_hash`) 둘 다 해시만 저장 — 저장소가 유출돼도 그 값을 그대로 제시해서 로그인할 수 없다. 서버가 원문을 다시 복원할 필요가 없는(클라이언트 쿠키의 원문과 "일치 여부"만 확인하면 되는) 값이라 암호화가 아니라 해싱을 썼다(비밀번호와 같은 이유). 비밀번호(bcrypt)와 달리 salt/느린 해시는 안 씀 — JWT는 서명 자체가 고엔트로피라 브루트포스 방어 목적의 느린 해시가 필요 없고, 매 요청마다 검증해야 해서 오히려 빨라야 함. 죽은 코드였던 `matches(role, id, candidateToken)`(호출부가 하나도 없음을 grep으로 재확인)는 이번 리팩터링 중 완전히 제거했다.

## 2. 카카오 로그인 & 회원 식별

- ✅ Kakao OIDC `scope=openid` 명시 필요 여부 확인. Spring Security의 oauth2Login은 scope 파라미터를 항상 명시적으로 보내는 구조라 openid를 빼면 안 됨 — 기존 설정이 이미 맞았음(검증만 하고 코드 변경 없음).
- ✅ `provider_user_id`(카카오 `sub`)와 내부 `memberId`(Long PK)는 완전히 분리된 필드. 애초부터 그렇게 구현돼 있었음.
- ✅ 카카오 `sub`이 unlink→relink 후에도 값이 안 바뀐다는 것을 카카오 공식 지원 답변(devtalk, 2026-03)으로 확인 — `active_provider_key` 설계가 안전하게 성립하는 전제.
- ✅ **email을 카카오에서 받지 않기로 결정 — 온보딩 폼 입력으로 전환.** 목표 DDL 코멘트는 "카카오 제공 이메일"이지만, 카카오 OIDC는 로그인 전용으로만 쓰고 개인정보(이메일 포함)는 전부 폼으로 받기로 했다(요구사항 예외사항 "카카오 oidc는 로그인 전용, 개인정보는 폼으로 입력받기"와 원래부터 일치하는 방향). `account_email` scope 동의 요청 자체를 없앴고(`application.yaml`), `OAuthAttributes`도 `socialTypeId`만 남기고 email 추출을 없앴다. 최초 가입 시에도 카카오 값으로 채우지 않고, 로그인마다 덮어쓰던 `Member.update(String)`도 완전히 제거했다 — "카카오에서는 최초 1회도 받지 않고, 이후로도 절대 덮어쓰지 않는다"는 원칙. `nickname`도 이미 같은 이유로 카카오 값을 안 쓰고 있었다(자체 폼 수집, 변경 없음) — DDL 코멘트("카카오 제공 닉네임")와 실제 동작이 다르다는 점은 email과 동일하지만, 이건 이미 이전부터 그렇게 구현돼 있었다.
- ✅ **`active_provider_key` 도입.** `"{social_type}:{social_type_id}"` 형태의 필드 하나에만 UNIQUE를 걸고, `social_type`/`social_type_id` 자체의 유니크 제약은 제거. 탈퇴 시 이 필드를 null로 비워서 이력(social_type_id 등)은 행에 남기되 같은 소셜 계정으로 재가입할 자리를 비켜줌.
- ✅ `reactivate()` 제거. 재가입은 옛 행을 되살리는 게 아니라 새 행을 만드는 방식으로 전환(`CustomOidcUserService`, `MemberWithdrawalService`가 전부 `findByActiveProviderKey` 기준으로 전환됨).

## 3. 온보딩 & 회원 상태

- ✅ `isNewMember`(생성 이벤트 플래그) → `pendingProfile`(상태 기반) 전환. 온보딩 중 브라우저를 닫아도 다음 로그인 때 정확한 값이 다시 내려옴.
- ✅ 필수 항목(이름 + 이메일 + 닉네임 + 약관동의)과 선택 항목(전화/주소, 첫 배송 시 수집) 분리. `MemberOnboardingRequest`가 필수만 받음(email 추가 — 위 2번 항목 참고).
- ✅ 닉네임은 카카오 제공값이 아니라 자체 폼으로 수집.
- ✅ **`PATCH /members/me`(회원 정보 관리) 신규 추가.** 요구사항의 "이름, 닉네임, 이메일, 휴대폰, 주소 변경" 다섯 항목 전부 구현. 처음엔 email을 뺐었다(그때는 카카오 로그인마다 email을 덮어쓰는 구조라 이 API로 바꿔도 되돌아갔음) — 이후 email 자체를 카카오에서 안 받기로 하면서(2번 항목) 그 제약이 없어져 다시 넣었다. 온보딩(`MemberOnboardingService`, PENDING_PROFILE→ACTIVE 전이 담당)과는 별도 서비스(`MemberProfileUpdateService`, 원래 이름 `MemberProfileService`에서 개명 — `MemberOnboardingService`와 이름이 너무 비슷해서 헷갈림)로 분리 — 이미 ACTIVE인 회원이 상태 전이 없이 프로필만 고치는 유스케이스라 책임이 다름. phone/address는 `null`이면 유지, 빈 문자열이면 명시적으로 지움(PATCH 부분수정 시맨틱).

## 4. 탈퇴

- ✅ 소프트 삭제(`status = WITHDRAWN`) + `active_provider_key` null 처리.
- ✅ 로그아웃 시 카카오 `POST /v1/user/logout`(Admin Key 기반) 추가 호출 — "카카오계정과 함께 로그아웃"(브라우저 기반 완전 로그아웃)과는 다른 것.
- ✅ 카카오 unlink 이벤트를 트랜잭션 커밋 이후(AFTER_COMMIT)에 비동기로 처리.
- ✅ 카카오 unlink 웹훅 수신 처리 — `active_provider_key` 기준으로 조회하도록 갱신됨.
- ✅ 카카오 unlink 웹훅 진위 확인 강화. `app_id`(위조 가능한 값) 일치만으로는 부족해서, 카카오가 함께 보내는 `Authorization: KakaoAK {admin key}` 헤더까지 검증하도록 추가.
- 🚫 "카카오계정과 함께 로그아웃"(브라우저 기반, `GET /oauth/logout`) — 의도적으로 미구현.
- ⬜ 진행 중 주문/미완료 환불이 있으면 탈퇴 차단. 주문 도메인이 아직 없어서 `Member.withdraw()`/`MemberWithdrawalService`에 TODO 주석만 남겨둠.

## 5. 관리자(Admin)

- ✅ JWT 기반 로그인/계정 발급/삭제(SUPER_ADMIN 전용). fm-backend의 Admin 엔티티를 참고했고, PK도 fm-backend와 동일한 Long AUTO_INCREMENT(9번 항목의 전면 통일 결정 참고).
- ✅ 계정 존재 여부 비노출(로그인 실패 시 "없음"과 "비번 틀림"을 같은 에러로 응답).
- ✅ 관리자 액션 감사 로그. 로그인 성공/계정 발급/계정 삭제에 `event=ADMIN_LOGIN_SUCCESS` / `ADMIN_REGISTERED` / `ADMIN_DELETED`로 actorId(요청자)/targetId(대상)만 남김 — 권한 상승·회수로 직결되는 민감 액션이라 "누가 언제 누구에게" 했는지는 추적 가능해야 한다는 원칙(8번 항목의 "비즈니스 로그는 id만" 원칙과 동일선상).
- ✅ **관리자 로그인 실패 로그(`ADMIN_LOGIN_FAILED`).** 계정 없음(`reason=NO_SUCH_ACCOUNT`, loginId로 식별)과 비번 틀림(`reason=WRONG_PASSWORD`, adminId로 식별)을 로그에서만 구분해서 남김 — HTTP 응답은 기존처럼 계정 존재 여부를 안 드러내려고 둘 다 동일한 에러 유지, 구분은 우리끼리 보는 로그에만 존재. 브루트포스(같은 계정에 비번만 계속 틀림)와 계정 나열 공격(존재하지 않는 아이디를 계속 시도)을 로그로 구분해서 보기 위함.
- ✅ **`Admin.status`(ACTIVE/DELETED) 도입 — 삭제를 소프트 삭제로 전환.** 목표 DDL 갱신본에 `admin.status`/`deleted_at`이 새로 추가되면서("이력 표 다섯이 admin_id를 참조해 하드 삭제가 불가능하다"), 예전의 `adminRepository.deleteById()` 하드 삭제를 `target.delete()`(status=DELETED, deletedAt=now)로 바꿨다. `login()`도 `status=DELETED`인 계정을 "계정 없음"과 동일한 `INVALID_PASSWORD`로 거부하도록(로그에만 원인 구분) 추가 — DDL 예외사항의 "비활성 계정 로그인 불가"를 "실패 사유 미노출" 원칙과 함께 만족시킨다.
- ✅ **관리자 삭제 예외 가드 추가.** 요구사항의 "본인 및 마지막 최고관리자는 비활성화 불가"를 반영 — 자기 자신을 대상으로 하는 삭제 요청은 `CANNOT_DELETE_SELF`로 거부하고, 대상이 `SUPER_ADMIN`이면 삭제 후에도 ACTIVE한 `SUPER_ADMIN`이 최소 1명 남는지(`AdminRepository.countByRoleAndStatus`) 확인해 마지막 1명이면 `LAST_SUPER_ADMIN_CANNOT_BE_DELETED`로 거부한다.
- ✅ **관리자 삭제 시 AT 즉시 무효화 추가.** 예전엔 `refreshTokenRepository.delete()`(RT만)만 호출해서, 삭제 직후에도 이미 발급된 accessToken이 자연 만료(최대 1시간)까지 유효했다 — `MemberWithdrawalService`와 동일하게 `accessTokenValidAfterRepository.invalidateBefore()`를 같이 호출하도록 고쳤다.
- ✅ **임시 비밀번호 발급(`TempPasswordGenerator`) + 본인 비밀번호 변경(`PATCH /admin/me/password`).** "강제"는 빼기로 했다 — 플래그 없이 로그인마다 "아직 임시 비밀번호를 안 바꿨는지"를 판정할 방법이 없어서(아래 참고), 강제 대신 "발급 시점에 1회 평문으로 보여준다(그 이후로는 절대 조회 불가) + 언제든 바꿀 수 있는 API를 둔다"로 범위를 좁혔다. `AdminRegisterRequest`에서 `password` 필드를 없애고 서버가 `SecureRandom` 기반으로 생성(`identifier-strategy-guideline.md`의 SecureRandom 원칙과 동일)해 `AdminRegisterResponse.temporaryPassword`에 딱 한 번 담아 응답한다. `PATCH /admin/me/password`는 현재 비밀번호 확인 후 교체하고, 요구사항의 "변경 시 토큰 전량 폐기"대로 RT/AT를 모두 무효화해 재로그인을 요구한다.
  - **필드 추가 없이 "강제"가 가능한가?** 등록 직후 1회성 안내(응답에 임시비밀번호가 실리는 순간 자체가 신호)는 필드 없이 가능하지만, "아직 안 바꿨으면 로그인할 때마다 계속 알려준다"는 지속적 신호는 서버가 그 상태를 어딘가에 저장해야 해서 필드 없이는 불가능하다 — `updated_at`이 바뀌었는지로 유추하는 방법도 있지만, 다른 이유(이름 변경 등)로도 바뀔 수 있어 신뢰할 수 없다. 필요해지면 `must_change_password` 컬럼 추가가 정공법.
- ⬜ **로그인 실패 5회 시 30분 잠금.** 미구현. Redis(카운터+TTL, DB 스키마 변경 없음, 이 프로젝트에서 로그인은 hot path가 아니라 성능 이점은 크지 않음) vs DB(`admin`에 `failed_login_attempts`/`locked_until` 컬럼 추가, 어차피 fail-open을 받아들일 거면 Redis 장애 시나리오에서 취약해지는 이점 없는 리스크를 감수할 이유가 없고, 이미 `findByLoginId`로 admin row를 읽는 시점에 같이 읽을 수 있어 추가 네트워크 왕복도 없음) 사이에서 재논의 필요 — 상세 트레이드오프는 대화 기록 참고, 현재는 DB 쪽이 더 설득력 있다는 결론에 가까움.

## 6. 배송지(Address)

- ✅ CRUD 전체(`GET/POST /addresses`, `PUT/DELETE /addresses/{id}`).
- ✅ 기본 배송지 1개 보장 — 서비스 레이어(`AddressService`)에서 트랜잭션으로 "기존 기본 해제 후 새로 지정" 처리.
- ⬜ 목표 DDL의 `GENERATED ALWAYS AS` 컬럼 기반 단일 기본값 강제. Hibernate `ddl-auto: update`로 이 패턴을 깔끔하게 재현하기 어려워 서비스 레이어 로직으로 대체 — 정식 스키마(Flyway/Liquibase 등으로 DDL 직접 관리) 전환 시 재검토 대상.

## 7. API 응답 규약

- ✅ `ApiResponse<T>`로 성공/실패 응답 통일. 컨트롤러 전부 `ResponseEntity<ApiResponse<T>>` 반환(카카오 웹훅 컨트롤러만 외부 계약이라 예외).
- ⬜→🚫 `SuccessCode` enum. 처음엔 후보로 거론됐으나 "성공 응답은 코드 분기가 거의 필요 없다"는 이유로 기각 — 대신 `ApiResponse.of(T data)`가 고정된 `"OK"` 사용.
- ✅ `ErrorCode` 체계(HttpStatus + 메시지). Address 관련(`ADDRESS_NOT_FOUND`) 추가됨.
- ✅ `MemberPublicResponse` 삭제. 사용처가 없는 죽은 코드였음을 확인 — "남에게 보여주는 프로필" 기능이 실제로 생기면 그때 다시 만들기로 함(`PiiMasker`의 `maskEmail()` 등은 그대로 남아있어 재사용 가능).

## 8. 로깅 & PII

- ✅ `HttpBodyLoggingFilter`의 자동 마스킹. password/token류에 더해 phone/address/recipient/zipcode/roadAddress/detailAddress 같은 키도 통째로 REDACTED 처리하도록 확장(원래는 email만 부분 마스킹, 전화번호/주소는 커버리지가 없던 실제 구멍이었음). 이메일에 더해 전화번호도 키 이름과 무관하게 본문 전체에서 패턴으로 찾는 catch-all 추가.
- ✅ `HttpBodyLoggingFilter` 상태코드 기반 로그 분리. 정상 응답(2xx/3xx)은 상태코드+소요시간만 INFO로 남기고(로그 볼륨 관리), 에러 응답만 바디까지 남기되 `GlobalExceptionHandler`와 같은 관례로 4xx=WARN/5xx=ERROR. DEBUG 레벨이면 정상 응답도 바디까지 남겨서, 운영 중 `/actuator/loggers`로 재배포 없이 임시로 바디를 볼 수 있게 함.
- ✅ `PiiMasker` 유틸(email/phone/name/generic/redact) — 기존 구현, 어노테이션이 아니라 DTO `from()` 팩토리에서 명시적으로 호출하는 방식.
- ✅ `PiiMasker.maskProviderId()` 추가. `KakaoUnlinkClient`/`KakaoLogoutClient`/`KakaoUnlinkWebhookController`에서 평문으로 찍히던 `kakaoUserId`/`provider_user_id`를 마스킹.
- ✅(원칙 확인) `log.debug`로 엔티티를 통째로 찍는 건 지양 — 코드베이스에 실제 사례는 없었고, 원칙만 재확인.
- ✅ `MdcLoggingFilter`의 `requestId`를 `traceId`로 개명 + `TraceIdExchangeFilter`로 아웃바운드 WebClient 호출(`kakaoApiWebClient`)에 전파. 정식 분산 추적(OpenTelemetry 등)이 아니라 문자열 하나를 그대로 복사해서 넘기는 간이 구현이고, MSA로 쪼개지기 전까지는(카카오는 이 규약을 모르니) 사실상 효과가 없다는 점을 클래스 주석에 명시. 지금 모놀리스 안에서도 `traceId` 자체는 HTTP 계층 로그와 비즈니스 계층 로그를 잇는 용도로 값어치가 있어 유지.
- ✅ `logback-spring.xml` 추가. `MdcLoggingFilter`가 채워두던 MDC(requestId/method/uri/clientIp)가 실제로는 로깅 패턴에 안 걸려 있어서 콘솔에 전혀 안 보이던 걸 발견 — local/dev 프로필은 사람이 읽기 좋은 콘솔 패턴에 MDC를 노출, prod 프로필은 `logstash-logback-encoder`로 MDC 필드가 JSON 키로 자동 포함되는 구조화 로그 + `AsyncAppender`(요청 스레드 블로킹 방지, WARN/ERROR는 큐 포화해도 안 버림). `net.logstash.logback:logstash-logback-encoder:8.0` 의존성 추가 필요.
- ✅ **비즈니스 로그는 원칙적으로 id만 출력.** 회원가입/주소추가 등 API 성공 로그는 `memberId`/`addressId` 같은 식별자만 남기고, DTO나 엔티티를 통째로 찍지 않는다.
- ✅ **엔티티 `toString()` 마스킹 오버라이드.** 위 원칙에도 불구하고 실수로 `log.info("{}", member)`처럼 엔티티 객체 자체를 로그에 넘기는 실수를 막는 마지막 방어선으로, `Member`/`Admin`/`Address`의 `toString()`을 오버라이드했다. `Admin.passwordHash`, `Member`/`Admin`의 `refreshTokenHash`(refreshToken 해시)처럼 유출되면 바로 사고로 이어지는 값은 아예 필드 자체를 뺐고, email/name처럼 식별에 필요한 값은 `PiiMasker`로 부분 마스킹만 해서 남겼다.
- ✅ **외부 API 호출 공통 로깅.** `ExternalApiLoggingExchangeFilter`(WebClient `ExchangeFilterFunction`)를 `kakaoApiWebClient`에 붙여서 method/URL/상태코드/소요시간을 자동으로 남김 — 클라이언트 클래스마다 로깅 코드를 안 짜도 되고, 나중에 외부 API가 늘어나도 같은 필터를 붙이기만 하면 됨. `KakaoUnlinkClient` 등이 남기는 비즈니스 맥락 로그(`event=KAKAO_UNLINK_FAILED` 등, kakaoUserId 포함)와는 역할이 달라서 서로 대체하지 않고 같이 남는다. 원래는 실패 케이스만 로그가 있고 성공 케이스는 로그가 아예 없던 구멍이었는데, 이걸로 해결됨.
- ✅ **스케줄러 공통 로깅(`SchedulerLoggingAspect`).** `@Scheduled` 메서드 실행마다 시작/종료(또는 실패)/소요시간을 AOP로 자동 로깅(`spring-boot-starter-aop` 추가) — 개별 스케줄러가 "처리 건수 0이면 로그를 아예 안 남기는" 방식이어도 "이 시간에 스케줄러가 정상적으로 돌긴 한 건지"를 공통으로 확인할 수 있게 하는 목적. 새 스케줄러가 추가되면 자동 적용됨. 단, 지금은 프로젝트에 `@Scheduled` 메서드가 하나도 없어(RT DB 백업을 `RefreshTokenCleanupScheduler` 방식에서 `member`/`admin` 컬럼 방식으로 옮기며 그 스케줄러가 없어짐) 이 Aspect가 실제로 적용되는 곳은 현재 없음 — 향후 대비 인프라로 유지.
- ✅ 로컬 콘솔 로그 패턴에 `%F:%line`(파일명:라인번호) 추가. caller data 계산 비용 때문에 prod(`AsyncAppender`, `includeCallerData=false`)에는 일부러 안 넣음 — 로컬은 트래픽이 적고 동기 로깅이라 부담이 작고, IntelliJ 콘솔이 클릭 가능한 링크로 인식해줘서 디버깅 편의성이 큼.
- ✅ **회원(카카오 OAuth2) 로그인 실패 로그(`MEMBER_LOGIN_FAILED`).** 예전엔 이 흐름에서 실패해도 로그가 전혀 없었다 — `GlobalExceptionHandler`는 `DispatcherServlet`이 컨트롤러를 호출하는 과정에서 터진 예외만 잡는데, OAuth2 로그인 실패는 그보다 앞단인 스프링 시큐리티 필터 안에서 끝나버려서 도달하지 않았기 때문. `OAuth2LoginFailureHandler`(`SecurityConfig`의 `.oauth2Login(...).failureHandler(...)`)를 새로 붙여서 원인이 어디서 터지든(카카오 쪽 동의 거부, id_token 검증 실패, `CustomOidcUserService`의 예외 등) 최종적으로 여기서 다 잡히게 함. `CustomOidcUserService`의 두 예외 발생 지점에는 이 핸들러가 못 담는 구체적 맥락(지원 안 하는 registrationId, 가입 race 재조회 실패)을 추가로 로깅.

## 9. 목표 스키마(DDL) 정합성

두 종류의 DDL 초안과 비교·분석했지만, "정식 스키마로 넘어갈 때" 다루기로 하고 지금 fresh-demo에는 반영 보류한 항목들.

**상세 DDL**(member_grade/member/address/admin, `public_id BINARY(16)`, generated column 포함)
- ✅ `active_provider_key` — 이번에 fresh-demo에 실제 반영 완료.
- ✅(최종 결정) PK 전략. 처음엔 `Member`/`Admin`/`Address`/`RefreshTokenBackup`은 UUID(v7) 유지, `member_grade`처럼 새로 추가하는 내부 전용 참조 테이블만 Long PK로 쓰는 절충안이었으나, 이후 전면적으로 Long(AUTO_INCREMENT) PK로 통일하기로 결정하고 전체 코드베이스를 마이그레이션했다. `UuidBaseEntity`/`ImmutableBaseEntity`/`MutableBaseEntity`는 삭제하고 `LongMutableBaseEntity` 하나로 합쳤으며, 모든 엔티티/리포지토리/JWT sub/URL 경로/DTO가 이제 Long id를 쓴다. 목표 DDL의 "내부 PK(BIGINT) + 외부노출용 public_id(BINARY(16)) 분리" 구조는 도입하지 않기로 했다 — Long id를 그대로 JWT sub/URL/응답 body에 노출한다. 순차 증가값이라 enumeration(계정 수 추측 등) 리스크가 있음을 인지하고도, 지금 프로젝트 규모에서는 단순함을 우선한 의도적 트레이드오프다(`LongMutableBaseEntity` Javadoc 참고). 나중에 필요해지면 이 Long PK는 내부용으로만 남기고 별도 public_id를 추가하는 방향으로 갈 수 있다.
- ✅ `member_grade` 테이블 — 엔티티(`MemberGrade`)+레포지토리 구현 완료(name/promotionRule/isDefault). `discountRate`는 원래 있었는데, 목표 DDL엔 없고(등급 혜택은 아직 미정) 아무도 안 읽는 값이라 DDL에 맞춰 제거했다. `Member.memberGradeId`로 NOT NULL FK도 연결 완료 — 신규 회원은 `isDefault=true`인 등급을 자동 배정받는다(`CustomOidcUserService` 참고). 이 등급이 하나도 없으면 가입 자체가 막히므로, `DefaultMemberGradeInitializer`(기동 시 1회 확인 후 없으면 시드)를 추가해뒀다.
- ✅ `nickname` vs `name` 필드 역할 구분 — 목표 DDL대로 분리했다. `nickname`(카카오 제공 별칭)과 `name`(폼 입력 실명)은 서로 다른 필드이고, `name`은 닉네임과 함께 온보딩 필수 항목이다(`MemberOnboardingRequest`).
- ✅ `is_marketing_agreed` — `Member.marketingAgreed`로 구현. 필수 약관동의(`termsAgreedAt`)와 달리 선택 항목이라 `MemberOnboardingRequest`에 검증 없이 추가, 온보딩 API(`PATCH /members/me/onboarding`)에서 같이 받음.
- ✅(값만) `BLOCKED` 상태 — `MemberStatus`에 값은 추가했다. 다만 "누가/언제/어떤 기준으로 BLOCKED로 전환하는지" 플로우는 여전히 없다 — 이 값을 세팅하는 코드는 아직 어디에도 없고, 목표 DDL이 이 값을 기대하고 있다는 사실만 먼저 반영한 상태다(10번 항목 참고).
- ✅(하이브리드) `member.refresh_token_hash`/`refresh_token_expires_at` — DDL은 refreshToken을 Redis 없이 이 컬럼만으로 저장하는 구조지만, Redis(1차 저장소)를 그대로 유지하고 DB 백업 대상만 옮기는 하이브리드로 반영했다(별도 `RefreshTokenBackup` 테이블 → 소유 엔티티 컬럼). DDL의 `admin` 테이블엔 이 컬럼이 없지만, `RefreshTokenRepository`가 Member/Admin 세션을 공용으로 다뤄서 `Admin`에도 동일하게 추가했다(1번 항목 상세 참고). AT 즉시 무효화(`AccessTokenValidAfterRepository`)는 이 RT 저장 방식과 무관하게 완전히 별개 컴포넌트로 그대로 유지된다 — DDL은 relational schema일 뿐이라 Redis/스케줄러/AT 무효화 인프라 자체를 막지 않는다.

**단순화된 ERD 초안**(cart/member_grade/member/address, PK만)
- ⬜ 팀플 발표용 초안이라 fresh-demo에 반영 안 함. 비교 중 발견된 불일치만 기록: email/nickname NOT NULL 여부 충돌, 정체불명의 `password_hash` 컬럼(카카오 전용인데 왜 있는지 불명확), 약관동의 필드 누락, `DEFAULT BOOLEAN` 오타.
- ⬜ `cart` 테이블 — fresh-demo는 인증 데모 범위라 주문/장바구니 도메인 자체가 없음.

## 10. 앞으로 더 설계하면 좋을 것들

- Grace period / token family 기반 탈취 탐지 — 지금은 CAS 실패를 전부 "재사용 의심"으로 간주해 무조건 세션을 끊는 단순한 방식. 정상적인 동시 요청까지 로그아웃되는 게 UX상 거슬리면, 직전 토큰을 잠깐 봐주는 grace window나 토큰 계보(family) 기반 탐지로 정교화 — 실서비스 전환 시점에 필요성 재평가.
- `Member`/`Admin`을 목표 DDL처럼 "내부 PK(BIGINT) + 외부노출용 public_id" 구조로 바꿀지 — PK 자체는 이미 전부 Long으로 통일했고(9번 항목), 지금은 그 Long id를 그대로 외부에 노출하기로 결정한 상태. enumeration 리스크가 실제로 문제 되면 이 시점에 public_id 분리를 재검토.
- `MemberGrade` 등급별 혜택을 실제로 어디서 적용할지 — FK 연결과 기본 등급 자동배정은 끝났지만(9번 항목), 혜택 자체(할인율 등)는 여전히 미정이고 적용할 도메인(주문)도 없음.
- `BLOCKED` 상태 도입 — 값은 추가됐지만 "누가/언제/어떤 기준으로 회원을 차단하는지" 플로우 설계가 먼저 필요. 관리자 API, 자동 정책 여부 등.
- 주문 도메인이 생기면 `Member.withdraw()`의 "진행 중 주문 있으면 탈퇴 차단" TODO 실제 구현.
- 카카오 unlink 웹훅을 SSF(Shared Signals Framework) 기반 "계정 상태 변경 웹훅"으로 이전 — admin key 헤더 검증보다 더 강한 RS256 서명 검증(JWKS)이 가능.
- 운영 전환 체크리스트: `JWT_SECRET` 관리 방식(시크릿 매니저 도입 여부) — 지금은 Redis 이중화 대신 `member`/`admin` 컬럼 기반 DB write-through+폴백으로 장애 대비를 해결하기로 결정 완료(1번 항목 참고). (`jwt.cookie.secure`는 `application-local.yaml`/`application-prod.yaml` 프로필 분리로 이미 반영됨 — 배포 시 `SPRING_PROFILES_ACTIVE=prod` 지정 필요.)
- ✅ `Address`의 기본 배송지 강제 — 서비스 레이어 로직은 유지하면서, 목표 DDL과 같은 generated column(`is_default_key`) + UNIQUE를 추가로 걸어 DB 레벨 안전망을 얹었다. 다만 이 프로젝트는 Flyway가 없어(ddl-auto:update) 기존 테이블에 생성 컬럼을 추가하는 ALTER가 실제로 깨끗하게 먹히는지는 로컬에서 검증이 필요하다 — 안 먹으면 테이블을 드롭하고 재기동하거나 수동 ALTER TABLE이 필요하다(`Address` Javadoc 참고).
- ✅→해결 `RefreshTokenBackup` 테이블의 row 증가/정리 문제 — 별도 테이블을 없애고 `member`/`admin`의 컬럼으로 옮기면서 자연히 해소됐다(정리할 별도 row 자체가 없어짐).
- ✅→해결 관리자 액션 감사 로그를 별도 감사 테이블로 옮기는 문제 — `V1__init_schema.sql`의 `audit_log` 테이블을 그대로 반영해 해결(11번 항목 참고). 콘솔/JSON 로그는 대체되지 않고 그대로 유지(역할이 다름).
- `AccessTokenValidAfterRepository`의 fail-open 정책 재검토 — 지금은 Redis 장애 시 이 방어선을 그냥 건너뛰는데, 실서비스 규모에서 Redis 가용성이 충분히 보장되면(다중화 등) fail-closed로 바꾸는 게 나을 수도 있음. 비밀번호 변경/회원 차단 기능이 생기면 그 시점에도 `invalidateBefore()`를 호출하는 코드를 추가해야 함(지금은 훅만 없고 호출부는 없음).
- `HttpBodyLoggingFilter`의 `PHONE_PATTERN`은 국내 휴대폰 번호 형식만 커버 — 해외 진출 시 국제전화번호 형식도 같이 잡게 확장 필요.
- `ExternalApiLoggingExchangeFilter`가 URL을 그대로 로그에 남김 — 지금 쓰는 카카오 API는 쿼리 파라미터에 민감정보가 없어서 괜찮지만, 나중에 쿼리 파라미터에 토큰/키가 실리는 외부 API(일부 결제 API 등)를 추가하면 URL 마스킹을 추가해야 함.

## 11. 스키마 정합화 — V1__init_schema.sql(실제 목표 DDL) 반영

팀 공통 DDL(Flyway 마이그레이션 파일 `V1__init_schema.sql`)을 실제로 전달받아, 회원/관리자/배송지/
회원등급 범위(`member`, `admin`, `address`, `member_grade`)만 이 DDL과 대조해 반영했다. 상품/주문/
쿠폰 등 다른 도메인 테이블은 이번 범위 밖(각 담당자가 자기 도메인에서 반영).

- ✅ `Member` 컬럼명을 DDL과 일치시켰다 — `social_type`/`social_type_id` → `provider`/`provider_user_id`
  (Java 필드명도 함께 변경, `OAuthAttributes`/`CustomOidcUserService`/`AuthController`/
  `MemberWithdrawalService`/`KakaoLogoutClient`/`KakaoUnlinkClient` 전부 갱신). `provider_user_id`
  길이도 45 → 100으로 늘렸다(카카오 sub가 최대 100자까지 허용되는데 45자로는 잘릴 위험이 있었음 —
  실사용 값 길이상 지금까지 문제가 안 됐을 뿐인 잠재 버그였다).
- ✅ `Member.activeProviderKey`를 애플리케이션이 직접 값을 넣고 비우던 일반 컬럼에서 DDL대로
  `deleted_at IS NULL` 기준의 DB `GENERATED` 컬럼으로 전환했다(`Address.isDefaultKey`와 동일 기법).
  `withdraw()`가 더 이상 이 필드를 직접 비우지 않고, `deletedAt`을 채우는 순간 DB가 자동으로
  재계산한다.
- ✅ `Member.nickname` 길이를 DDL(`VARCHAR(50)`)에 맞춰 20 → 50으로 늘렸다(`MemberOnboardingRequest`/
  `MemberProfileUpdateRequest`의 `@Size(max=20)`도 50으로 함께 수정). `unique=true`는 DDL엔 없는
  애플리케이션 자체 제약으로 그대로 유지 — 동시성 레이스 이슈는 알려진 채로 이번 라운드 수정 대상이
  아니다(테스트 스위트 백업 유실로 회귀 테스트도 아직 없음).
- ✅ `member.status`/`admin.role`/`admin.status`에 DDL이 요구하는 `COLLATE utf8mb4_0900_as_cs`를
  `columnDefinition`으로 추가했다 — 서버 기본 콜레이션(대소문자 미구분)이면 `Enum.valueOf`와 DB의
  판단이 어긋날 수 있다는 DDL 코멘트의 경고를 반영.
- ✅ `member.refresh_token_hash`/`admin.refresh_token_hash`를 `VARCHAR(64)`에서 DDL대로 `CHAR(64)`로
  바꿨다.
- ✅ `chk_member_status`/`chk_member_refresh_token`/`chk_member_withdrawn`,
  `chk_admin_role`/`chk_admin_status`/`chk_admin_deleted`/`chk_admin_refresh_token` — DDL의 CHECK
  제약을 Hibernate `@Check`로 옮겼다. 예전엔 "Flyway가 없어 코드만으로 지킨다"고 문서화돼 있었는데,
  이제 애플리케이션 로직(정상 경로)에 더해 DB 레벨 마지막 방어선도 생겼다.
- ✅ `MemberGrade`에 `is_default_key` 생성 컬럼 + UNIQUE를 추가해 "기본 등급 최대 1개"를 DB가
  강제하게 했다(`Address`엔 이미 있었는데 `MemberGrade`만 빠져 있던 안전장치).
- ✅ `Address.detailAddress`를 `nullable=false`에서 DDL대로 `nullable=true`로 고쳤다 — `AddressRequest`가
  선택 필드로 취급하는 것과 어긋나 있던 실제 버그(상세주소 없이 등록하면 NOT NULL 위반 가능성)였다.
- ✅ `audit_log` 테이블(`AuditLog`/`AuditLogRepository`, `common.audit` 패키지)을 새로 반영했다.
  상품/주문 등 다른 도메인 액션도 같이 쌓는 공용 테이블이라 도메인 패키지가 아니라 `common` 아래에
  뒀고, 이번 범위에서는 `AdminService.register()`/`deleteAdmin()`(관리자 등록/삭제) 두 액션만
  `action=ADMIN_REGISTER`/`ADMIN_DELETE`로 기록한다. 콘솔/JSON 로그(`event=ADMIN_REGISTERED` 등)는
  대체하지 않고 그대로 유지 — 실시간 관찰용과 영속 감사 기록용으로 역할이 다르다. 다른 도메인
  액션(`PRODUCT_DELETE` 등)은 해당 도메인이 생길 때 같은 테이블/엔티티를 재사용하면 된다.
- ✅ 죽은 코드 정리 — `ErrorCode.KAKAO_WEBHOOK_INVALID` 제거(카카오 웹훅은 검증 실패 시에도 항상
  200을 줘야 해서 애초에 쓰일 수 없었던 코드), `docs/API.md` 삭제(최신 코드와 어긋난 채 방치돼 있었고
  — PK가 Long인데 `uuid`로 표기, `PATCH /admin/me/password` 누락 등 — 지금 시점엔 불필요 판단).
- ⬜ 관리자 로그인 5회 실패 시 30분 잠금 — 요구사항 예외사항의 일부지만 이번 라운드는 보류(5번 항목
  결론 그대로 미해결).
- ⬜ `Member.nickname` 유니크 제약의 동시성 레이스(`existsByNickname` 선조회 방식) — 알려진 이슈,
  이번 라운드 수정 대상 아님. `AdminService.register()`는 이미 `saveAndFlush()` +
  `DataIntegrityViolationException` catch로 이 패턴을 올바르게 처리하고 있어 참고할 선례가 있다.
- 이번 세션은 이 SQL 파일을 대상으로 gradle 컴파일 검증을 하지 못했다 — 샌드박스가 JDK 11만
  갖고 있고(`build.gradle`은 JDK 21 요구) Gradle 배포판 다운로드도 네트워크가 막혀 있었다. 코드는
  grep 기반으로 이전 필드명(`socialType`/`socialTypeId`) 잔존 여부를 전수 확인했지만, 실제 컴파일·
  `ddl-auto:update`가 기존 테이블에 이 변경들(특히 GENERATED 컬럼·CHECK 제약)을 깨끗하게 반영하는지는
  로컬에서 별도 검증이 필요하다.
