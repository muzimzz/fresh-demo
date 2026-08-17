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
- 관리자 액션 감사 로그를 지금의 콘솔/JSON 로그 라인 수준이 아니라 별도 감사 테이블(누가 조회해도 위변조 어려운 append-only 저장소)로 옮길지 — 지금은 `logback-spring.xml`이 찍는 로그가 사실상 유일한 기록. (`V1__init_schema.sql`에 `audit_log` 테이블이 정의되어 있으나, 이번 라운드에서는 반영하지 않기로 함 — 필요해지면 재검토.)
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
- ⬜ `audit_log` 테이블 — DDL엔 정의되어 있으나 이번 라운드에서는 반영하지 않기로 했다(한 차례
  `AuditLog`/`AuditLogRepository`로 구현해 `AdminService.register()`/`deleteAdmin()`에 연결했다가,
  이번 세션에서 다시 제거함). 상품/주문 등 다른 도메인 액션도 같이 쌓는 공용 테이블이라, 필요해지면
  그때 다시 다룬다.
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

## 12. LG-fm 컨벤션 리팩토링 — 패키지 구조/생성 패턴/응답 체계

**중요: LG-fm 저장소는 이번 작업에서 전혀 건드리지 않았다.** 목적은 fresh-demo(이 저장소)의 v1
브랜치 자체를 나중에 LG-fm으로 옮길 때 그대로 가져다 쓸 수 있는 모양으로 미리 맞춰두는 것이다 —
"이식 코드를 짜서 LG-fm에 저장"이 아니라 "LG-fm 컨벤션을 이 저장소 안에서 미리 실천"하는 방향으로
진행했다. 패키지명(`com.example.freshdemo`)은 그대로 유지했다 — 실제 이식 시점에 한 번에
바꾸는 편이 낫다고 판단.

- ✅ **패키지 구조 재배치.** LG-fm의 domain-package-boundary 규칙(도메인 루트는 API 컨트롤러+DTO+
  예외만, 엔티티/리포지토리/서비스는 `domain.entity`/`domain.repository`/`domain.service` 하위로)을
  따라 `member`/`admin`/`address`/`membergrade` 4개 도메인 전부 재배치했다. 컨트롤러는 각 도메인의
  루트 패키지로 옮겼다(예: `member.controller.MemberController` → `member.MemberController`).
  OAuth 관련 클래스(`CustomOidcUserService`/`OAuth2LoginSuccessHandler`/`OAuth2LoginFailureHandler`/
  `OAuthAttributes`/`CustomOidcUser`)는 `member.domain.oauth`라는 별도 하위 패키지로 묶었다 — 인증
  어댑터라 `domain.service`와 성격이 달라서다. LG-fm 빌드 게이트를 그대로 가져온다면 커버리지 측정
  대상(`*.domain.service.*`)에서 이 패키지가 빠진다는 뜻이라 이식 시점에 재논의 필요.
- ✅ **Address를 독립 도메인으로 유지/명확화.** 원래도 `@ManyToOne` 없이 `memberId`만 드는 구조라
  member 하위로 합칠 이유가 없었다 — 이번에 `domain.entity/repository/service` 재배치를 하며 위치를
  다시 확인만 했다.
- ✅ **인증 인프라(JWT/Redis)를 `common.auth`로 재배치.** `auth.jwt.*`/`auth.CustomUserDetails`를
  `common.auth.jwt.*`/`common.auth.CustomUserDetails`로 옮겼고, member/admin 공용이었던
  `member.controller.AuthController`(재발급/로그아웃)도 `common.auth.AuthController`로 옮겼다.
  **다만 이 재배치는 구조적 긴장을 남긴다** — `common.auth.jwt.RefreshTokenRepository`와
  `common.auth.AuthController`가 `member.domain.repository.MemberRepository`/
  `admin.domain.repository.AdminRepository`를 직접 참조한다. "common은 도메인을 몰라야 한다"는
  원칙과 부딪히는 지점이라, 실제 LG-fm 이식 시점에 포트-인터페이스로 역전시킬지, `auth`를 `common`이
  아닌 독립 도메인으로 승격할지 다시 판단해야 한다.
- ✅ **엔티티 생성 패턴 전환.** `Member`/`Admin`/`Address`/`MemberGrade` 전부 `@Builder`만 붙어있던
  생성자를 `@Builder(access = AccessLevel.PRIVATE)`로 바꾸고, 이름 있는 정적 팩토리(`register()`)를
  별도로 뒀다 — 이전엔 생성자가 `private`이어도 Lombok이 기본으로 `public` builder를 만들어줘서
  `Xxx.builder()...build()`가 외부에 그대로 노출됐고 필수값 누락이 컴파일 타임에 안 걸렸다. `Member`/
  `Admin` 생성자의 `Objects.requireNonNull` 검증 범위도 넓혔다(전에는 일부 필드만 검증).
  `OAuthAttributes.toEntity()`/`DefaultMemberGradeInitializer`는 그대로 새 팩토리를 호출하도록
  갱신했고, `AdminRegisterRequest.toEntity()`/`AddressRequest.toEntity()`는 아예 삭제하고 조립
  책임을 각각 `AdminService.register()`/`AddressService.create()`로 옮겼다(DTO가 엔티티를 직접
  조립하지 않는 방향).
- ✅ **응답 봉투 교체.** `ApiResponse<T>(boolean success, String code, String message, T data, Instant
  timestamp)`를 삭제하고 `ResponseEnvelope<T>(String code, String message, T data)`로 바꿨다 —
  success 불리언 없이 `code`가 `"SUCCESS"`인지로 성공을 판별한다(불리언과 code가 어긋나는 응답을
  구조적으로 막기 위함). 모든 컨트롤러의 반환 타입을 `ResponseEntity<ApiResponse<T>>` →
  `ResponseEntity<ResponseEnvelope<T>>`로 바꿨다(카카오 웹훅 컨트롤러는 여전히 예외 — 외부 계약이라
  순수 `ResponseEntity<Void>` 유지).
- ✅ **에러코드 체계를 도메인별로 분리.** 단일 flat `ErrorCode` enum(`ResponseCode` 인터페이스 구현)을
  없애고, `ErrorCode` 인터페이스(`getHttpStatus/getCode/getMessage`) + 공통 9개(`CommonErrorCode`,
  `COMMON-001~009`) + 도메인별 enum(`MemberErrorCode` 5개/`AdminErrorCode` 8개/`AddressErrorCode`
  1개)으로 나눴다. 기존 `INVALID_PARAMETER`/`NOT_FOUND`/`UNAUTHORIZED`/`FORBIDDEN` 등 도메인
  무관 코드는 `CommonErrorCode`의 동급 항목으로 흡수했다. `BusinessException`도 `ErrorCode`를 드는
  추상 클래스로 바뀌었고, 도메인마다 `MemberException`/`AdminException`/`AddressException`이 이를
  상속해 던진다.
- ✅ **`GlobalExceptionHandler` 전면 교체.** 기존엔 `ResponseEntityExceptionHandler`를 상속해
  일부 예외만 오버라이드했는데, `NoResourceFoundException`/`HttpRequestMethodNotSupportedException`/
  `MaxUploadSizeExceededException`/`HttpMediaTypeNotSupportedException`/`AuthenticationException`/
  `AccessDeniedException`까지 포함한 plain `@RestControllerAdvice` + 명시적 `@ExceptionHandler`
  목록으로 바꿨다. `AuthenticationException`/`AccessDeniedException`을 여기서 같이 처리하게 되면서,
  `SecurityConfig`가 필터 단계 예외를 `HandlerExceptionResolver`로 이 핸들러에 위임하도록 바뀌었고,
  기존에 직접 JSON을 쓰던 `JwtAuthenticationEntryPoint`/`JwtAccessDeniedHandler`는 중복이 되어
  삭제했다.
- ✅ **`AuthController`(재발급/로그아웃)의 예외 판단 변경.** 기존 `BusinessException(ErrorCode.
  UNAUTHORIZED)` 대신, "인증 자체가 안 된" 상황(리프레시 토큰 무효/재사용 의심)은 도메인 정책
  위반이 아니라고 보고 `BadCredentialsException`(Spring Security `AuthenticationException` 하위)을
  던지도록 바꿨다 — `GlobalExceptionHandler`가 이미 `AuthenticationException`을
  `CommonErrorCode.UNAUTHENTICATED`로 처리하므로 별도 예외 클래스가 필요 없어진다. 이 판단이 맞는지는
  재검토 대상 — "인증 실패"와 "도메인 예외"의 경계를 어디로 그을지는 참고 문서에도 명시적 답이 없었다.
- ✅ **Base entity 2단 분리.** `common.jpa.LongMutableBaseEntity`(단일 클래스)를
  `common.entity.BaseMutableTimeEntity`(id+createdAt+updatedAt, 기존과 필드 동일)와
  `common.entity.BaseImmutableTimeEntity`(id+createdAt만, 아직 상속하는 엔티티는 없음 — 향후
  이력/로그성 테이블 대비)로 나눴다.
- ✅ `PageResponse<T>`(오프셋 방식 목록 응답 공통 포맷)를 미리 추가해뒀다 — 지금 당장 쓰는 목록
  API는 없지만, 관리자용 목록 조회가 생길 때 바로 쓸 수 있게 컨벤션만 맞춰둠.
- ⬜ **API 버저닝(`/v1/...`)은 이번 라운드에 반영하지 않았다.** 컨트롤러 경로는 원래 그대로
  (`/members`, `/admin`, `/addresses`, `/auth`, `/webhook/kakao/unlink`) — 실제 이식 시점에
  라우팅을 다시 설계할 때 함께 검토.
- ⬜ **JaCoCo 100%/ArchUnit/SonarQube 게이트는 이번 라운드에 적용하지 않았다.** 새로 재배치된
  서비스 클래스에 대한 단위 테스트가 없어 그 게이트들을 그대로 가져오면 즉시 실패한다 — 사용자가
  이번 라운드에서 명시적으로 범위 밖으로 뒀다.
- 이번 리팩토링도 실제 컴파일 검증은 못 했다(위 §11의 환경 제약과 동일 — JDK 11/네트워크 제한).
  패키지 재배치 후 남은 참조는 `grep`으로 전수 확인했다(구 패키지 경로, `ApiResponse`/`ResponseCode`
  잔존, `LongMutableBaseEntity` 상속 잔존 등 전부 클린 확인). 로컬에서 `./gradlew compileJava`를
  가장 먼저 돌려볼 것.

## 13. LG-fm 2차 pull 이후 — 도메인 경계 강제(ArchUnit) 대응

fm-backend를 다시 받은 뒤(조직/저장소가 `LGU-2/backend` → `fresh-market/fm-backend`로
개명됨, 코드 영향 없음) 확인해보니 팀원 도메인 코드는 아직 없고(`common`/`config`만 존재),
대신 `ArchitectureTest.java`(ArchUnit)가 새로 추가되어 `./gradlew check`에 물렸다. 이 테스트가
강제하는 `domain-package-boundary-guideline.md`의 규칙 자체는 이전 pull 시점에도 이미 문서로
존재했지만(§12 작성 당시엔 "도메인 루트 = 컨트롤러+DTO+예외"로 잘못 단순화해서 반영함), 이제
빌드 게이트로 자동 강제되므로 fresh-demo v1의 구조가 실제로 이 규칙을 지키는지 다시 대조하고
아래 3곳을 고쳤다.

**핵심 원칙 재확인**: 도메인 루트에는 `~Api` 인터페이스(공개 창구) + 공개 DTO(record) + 공개
예외만 둔다. 그 외 전부(엔티티/리포지토리/서비스/**컨트롤러**/내부 DTO/내부 예외)는 `domain`
하위로 내린다. 다른 도메인은 오직 이 루트의 `~Api`를 통해서만 서로를 호출할 수 있다
(ArchUnit `도메인_내부는_다른_도메인에_닫혀_있다`). `common`/`config`는 어떤 도메인의
`domain` 패키지도 알아서는 안 된다(`common_은_도메인을_모른다`).

- ✅ **Controller를 도메인 루트 → `domain.controller`로 재이동, `public` → package-private.**
  `MemberController`/`MemberWithdrawalController`/`KakaoUnlinkWebhookController`/
  `AdminController`/`AddressController` 5개 전부 이동. 컨트롤러는 어떤 계층에서도 호출되지
  않는 진입점(`layeredArchitecture().whereLayer("Controller").mayNotBeAccessedByAnyLayer()`)이라
  "다른 도메인에 공개하는 계약"이 아니고, `rootIsContractOnly` 규칙상 도메인 루트에는
  interface/record/예외만 있어야 해서 애초에 루트에 있으면 안 되는 클래스였다.
  `common.auth.AuthController`는 domain-package-boundary 규칙 적용 대상이 아닌 `common` 소속이라
  위치는 그대로 두고 `public`도 유지했다(공용 인프라 컨트롤러라 특정 도메인 소유가 아님).
- ✅ **`membergrade.MemberGradeApi` 신설.** `member.domain.oauth.CustomOidcUserService`가
  회원가입 시 기본 등급을 찾으려고 `membergrade.domain.repository.MemberGradeRepository`를
  직접 참조하던 것을 도메인 경계 위반으로 판단, 도메인 루트에 `MemberGradeApi`
  (`findDefaultGradeId(): Optional<Long>`) 인터페이스와 `membergrade.domain.MemberGradeApiImpl`
  (package-private) 구현체를 추가하고 `CustomOidcUserService`가 이걸 경유하도록 바꿨다.
- ✅ **`common.auth` → `member`/`admin` 직접 의존 제거.** `common.auth.jwt.RefreshTokenRepository`와
  `common.auth.AuthController`가 `MemberRepository`/`AdminRepository`(그리고 `Member`/`Admin`
  엔티티, `MemberException`/`AdminException`, `KakaoLogoutClient`)를 직접 참조하던 것을
  `member.MemberAuthApi`/`admin.AdminAuthApi` 경유로 바꿨다.
  - `MemberAuthApi`: `findAuthInfo`(id/role/withdrawn만 담은 `MemberAuthInfo` record 반환),
    `updateRefreshToken`/`clearRefreshToken`/`compareAndSetRefreshToken`,
    `logoutExternalSession`(카카오 로그아웃 포함).
  - `AdminAuthApi`: `findAuthInfo`(id/authority만 담은 `AdminAuthInfo` record 반환),
    리프레시 토큰 3종 동일.
  - `AuthController`의 `reissueMemberRole`/`reissueAdminRole`에서 회원·관리자를 못 찾은 경우
    기존엔 `MemberException(MEMBER_NOT_FOUND)`/`AdminException(ADMIN_NOT_FOUND)`를 던졌는데,
    이 컨트롤러가 이미 다른 실패(토큰 무효/재사용 의심)에 `BadCredentialsException`을 쓰고
    있어 일관성 있게 여기도 `BadCredentialsException`으로 통일했다 — "리프레시 토큰이 가리키는
    주체가 사라짐"은 도메인 정책 위반이라기보다 인증 실패로 보는 게 이 클래스의 기존 철학과
    맞는다고 판단. 부수효과로 `common`이 `member.exception`/`admin.exception`도 더는
    참조하지 않게 됐다.
  - **트랜잭션 경계**: ArchUnit이 `~ApiImpl`에 `@Transactional`을 금지하므로(`ApiImpl_에_
    트랜잭션이_없다`) `MemberAuthApiImpl`/`AdminAuthApiImpl`은 트랜잭션을 직접 열지 않는다.
    대신 호출자인 `RefreshTokenRepository`의 `save`/`delete`/`compareAndSave`가 그대로
    `@Transactional`을 유지하고 있어서, 그 경계 안에서 Api 메서드가 호출되며 내부
    `MemberRepository`/`AdminRepository`의 `@Modifying` 쿼리가 자연스럽게 합류한다. 별도
    `domain.service` 클래스를 새로 만들지 않은 이유이기도 하다(JaCoCo 100% 게이트 대상을
    늘리지 않기 위함 — 어차피 이번 라운드엔 그 게이트를 적용 안 하기로 했지만).
- ✅ **`config.SecurityConfig` → `member.domain.oauth.*` 직접 참조 제거.** 처음엔
  `common.auth.oauth`로 이전 3개 클래스를 통째로 옮기고 `CustomOidcUser`가 든 `Member` 엔티티를
  DTO로 바꾸는 안을 검토했는데, 그건 로그인 흐름의 데이터 모델까지 바꾸는 과한 수정이라 접었다.
  실제 위반 지점은 "`config`가 `CustomOidcUserService`/`OAuth2LoginSuccessHandler`/
  `OAuth2LoginFailureHandler` 3개를 필터체인에 조립해 꽂아야 한다"는 것 하나뿐이고, 이 3개
  클래스 자체는 `member.domain.oauth` 안에 있는 게 맞다(같은 도메인 내부에서 `Member` 엔티티를
  쓰는 것도 정상). 그래서 "조립하는 책임"만 `member.MemberOAuth2LoginConfigurer`(도메인 루트
  인터페이스, 메서드 하나: `configure(OAuth2LoginConfigurer<HttpSecurity>)`)로 뽑고
  `member.domain.MemberOAuth2LoginConfigurerImpl`(package-private)이 기존 3개 필드를 그대로
  들고 조립하게 했다. `SecurityConfig`는 이제 `MemberOAuth2LoginConfigurer` 하나만 주입받고
  `.oauth2Login(memberOAuth2LoginConfigurer::configure)` 한 줄로 끝난다. 로그인 로직 자체는
  전혀 안 바뀌었다 — `MemberAuthApi`/`AdminAuthApi`/`MemberGradeApi`와 같은 패턴(도메인 루트=
  인터페이스, `domain` 안=package-private 구현체)을 "OAuth2 필터체인 설정"에도 그대로 적용한
  것뿐이다.
- 이번에도 `./gradlew compileJava`/`check`는 실행하지 못했다 — 이 세션의 샌드박스가
  `services.gradle.org`에 접근할 네트워크 경로가 없어 Gradle wrapper가 배포판을 못 받는다.
  대신 아래를 grep으로 전수 확인했다: (1) 옛 컨트롤러 경로(`member.MemberController` 등) 잔존
  참조 없음, (2) `member`/`admin`/`address` 도메인 루트에 인터페이스/record 외의 클래스 없음,
  (3) `common`이 실제 코드에서 `*.domain.*`을 참조하는 곳 없음(SecurityConfig의 `config` 소속
  참조는 위에 별도 기재), (4) `membergrade.domain.*`를 membergrade 밖에서 참조하는 곳 없음,
  (5) 이동된 컨트롤러 5개 전부 package-private 확인. 로컬 환경에서 `./gradlew compileJava`와
  (팀 저장소에 이 구조를 반영한 뒤) `./gradlew check`를 가장 먼저 돌려볼 것.

## 14. 실제 로컬 빌드/테스트 이후 — `서비스_이름` / `순환_의존이_없다` 위반 대응

§13을 반영한 뒤 사용자 로컬 환경(자체 MySQL, `application-secret.yaml`)에서 처음으로
`./gradlew build`가 실제로 통과했다. 이어서 `ArchitectureTest`를 가져와 `./gradlew test`를
직접 돌려보니 §13에서 grep만으로는 못 잡은 위반 2개가 추가로 드러났다.

### 14.1 `서비스_이름` — 도메인 루트로 이동한 클래스 5개의 위치 정정

`서비스_이름` 규칙(`domain.service` 하위 클래스는 이름이 `~Service`로 끝나야 함)을
`AdminRegistrationResult`, `TempPasswordGenerator`, `KakaoUnlinkEventListener`,
`MemberWithdrawalEvent`, `DefaultMemberGradeInitializer` 5개가 위반했다 — 전부
`domain.service` 패키지에 있었지만 `~Service`로 안 끝나는 이름들이었다(각각 결과 DTO, 유틸,
이벤트 리스너, 이벤트, 초기화 컴포넌트). 5개 전부 한 단계 위 `domain` 루트로 옮겼다(예:
`admin.domain.service.AdminRegistrationResult` → `admin.domain.AdminRegistrationResult`).
`TempPasswordGenerator`는 `AdminService`(여전히 `domain.service`에 있음)가 다른 패키지에서
호출해야 해서 `public`으로 가시성을 넓혔다.

### 14.2 `순환_의존이_없다` — `common`↔`member`/`admin` 양방향 의존 해소

**증상**: ArchUnit의 `slices()`가 최상위 패키지(`common`, `member`, `admin`, …) 단위로
슬라이스를 나누는데, `common`이 `MemberAuthApi`/`AdminAuthApi`를 경유해 `member`/`admin`을
참조하는 동시에(§13에서 만든 구조), `member`/`admin`의 여러 서비스가 로그인/재발급/로그아웃/
탈퇴/비밀번호변경 때 `common.auth.jwt.*`를 직접 호출하고 있어서 양방향 엣지가 생겼다 —
`beFreeOfCycles()`가 이걸 사이클로 잡는다.

**검토한 대안 3가지**(사용자 승인 전까지 파일 수정 없이 먼저 제시):
1. ArchUnit 예외로 인정하고 넘어간다 — 가장 쉽지만 "인증 인프라는 어차피 여러 도메인이 같이
   써야 한다"는 근본 긴장을 안 풀고 덮기만 함.
2. 포트-어댑터(의존성 역전) — `common`이 도메인을 향해 인터페이스를 만들고 어댑터를 어디에
   두느냐에 따라 완전히 안 풀릴 수도 있음이 검토 중에 드러남(아래 참고).
3. **이벤트 기반** — 가장 근본적이지만 변경 범위가 가장 큼.

**처음에 놓쳤던 것**: 옵션 2를 "새 어댑터 인터페이스 2개만 추가하면 된다"고 처음 제안했는데,
실제 호출부 3곳(`MemberWithdrawalService`, `OAuth2LoginSuccessHandler`, `AdminService`)을
전부 읽어본 뒤 `common.auth.AuthController`/`RefreshTokenRepository` 자체가 회원·관리자
공용 재발급/로그아웃 로직을 갖고 있는 한 그 클래스들이 `MemberAuthApi`/`AdminAuthApi`(또는
이름만 바뀐 어떤 인터페이스든)를 계속 참조해야 해서, 호출부 3곳을 고쳐도 `common→member`/
`common→admin` 엣지가 그대로 남는다는 걸 파일을 다시 읽고서야 발견했다. 그래서 구현 전에
사용자에게 이 점을 정정해서 다시 설명하고 승인을 받았다.

**최종 채택안 — `AuthController` 자체를 도메인별로 분리**:

| | 이전(§13) | 이후(§14) |
|---|---|---|
| 재발급/로그아웃 엔드포인트 | `common.auth.AuthController`(회원/관리자 공용, `/auth/reissue`·`/auth/logout`) | `member.domain.controller.MemberAuthController`(`/members/reissue`·`/members/logout`) + `admin.domain.controller.AdminAuthController`(`/admin/reissue`·`/admin/logout`) — 완전히 분리 |
| `RefreshTokenRepository` | Redis 1차 저장 + `MemberAuthApi`/`AdminAuthApi` 경유 DB 백업까지 오케스트레이션 | **순수 Redis 저장소**(`save`/`delete`/`compareAndSave`만, `role`/`id` 기반 키). `Member`/`Admin`을 전혀 모름 |
| DB 백업(write-through)/CAS 폴백 오케스트레이션 | `RefreshTokenRepository` 내부(`MemberAuthApi.updateRefreshToken` 등 경유) | `member.domain.service.MemberTokenService` / `admin.domain.service.AdminTokenService`(신설) — 각자 자기 도메인의 `MemberRepository`/`AdminRepository`를 직접 사용(같은 도메인이라 Api 불필요) |
| `MemberAuthApi`/`AdminAuthApi`(+`MemberAuthInfo`/`AdminAuthInfo`, `~ApiImpl`) | `common` 전용으로 신설(§13) | **완전히 삭제** — `common`이 이제 `member`/`admin`을 아예 몰라서 다리 역할의 Api가 필요 없어짐 |
| 회원 로그인(카카오)/탈퇴/관리자 로그인/비밀번호변경/계정삭제의 토큰 발급·폐기 | 각 서비스가 `JwtTokenProvider`/`RefreshTokenRepository`/`AccessTokenValidAfterRepository`/`AuthCookieFactory`를 직접 호출 | `MemberTokenService.issue/reissue/revoke`, `AdminTokenService.issue/reissue/revoke` 경유로 통일 |

결과적으로 `common`은 `member`/`admin` 어디도 참조하지 않고(순수 유틸: `JwtTokenProvider`,
`RefreshTokenRepository`, `AccessTokenValidAfterRepository`, `AuthCookieFactory`,
`CustomUserDetails`, `TokenHasher`, `TokenType` 등), `member`→`common`·`admin`→`common`
단방향만 남아 `beFreeOfCycles()`를 만족한다. Redis 장애 시 DB CAS 폴백 등 기존 복원력
로직은 전부 `~TokenService`로 그대로 옮겨서 동작은 바뀌지 않았다(호출 위치만 이동).

**API 경로 변경(주의)**: `/auth/reissue`·`/auth/logout`이 `/members/reissue`·
`/members/logout`(회원), `/admin/reissue`·`/admin/logout`(관리자)로 갈라졌다. 프론트/클라
연동 시 반드시 반영해야 한다. `SecurityConfig`의 permitAll 목록도
`HttpMethod.POST`로 좁혀서 `/members/reissue`·`/admin/reissue`·`/admin/login`만 인증 없이
허용하도록 정리했다(기존 `/auth/reissue`는 HTTP 메서드 제한이 없었는데, 실제로 컨트롤러가
`POST`만 노출하므로 더 좁혀도 안전).

**fresh-market 본 프로젝트 이식 시 참고**: 팀 내에서 회원/관리자 로그인 구현을 다른 사람이
맡기로 해서, 실제 이식 때는 **회원(`member`) 쪽만 가져가고 관리자(`admin`) 쪽은 제외**할
예정이다. 즉 `AdminTokenService`/`AdminAuthController`/`AdminService`의 토큰 처리 부분은
fresh-demo 자체의 로컬 빌드/테스트 완결성을 위해서만 유지하는 것이고, fm-backend에는
`MemberTokenService`/`MemberAuthController`(그리고 `RefreshTokenRepository`가 순수
Redis 유틸이 됐다는 설계)만 반영하면 된다 — 관리자 인증을 실제로 어떻게 구현할지는
그 담당자의 몫이다.

- `서비스_이름`/`순환_의존이_없다` 모두 이번 라운드로 해소했다고 "설계상" 판단했지만, 이번에도
  `./gradlew test`를 이 세션에서 직접 실행하지 못했다(네트워크 제약으로 Gradle wrapper가
  배포판을 못 받음, §13과 동일한 사유) — grep으로 (1) 삭제된 클래스(`AuthController`,
  `MemberAuthApi`/`AdminAuthApi`/`~Info`/`~Impl`) 잔존 참조 없음, (2) `common` 패키지 전체에
  `member.*`/`admin.*` import 없음, (3) `RefreshTokenRepository`의 새 시그니처(`role, id, ...`,
  `TokenType` 파라미터 없음)를 호출부 전부가 따르고 있음을 확인했다. 실제로 로컬에서
  `./gradlew test`를 돌려 `서비스_이름`/`순환_의존이_없다`/`contextLoads()` 12개 전부 통과 확인함.

### 14.3 실제 카카오 로그인 테스트 중 발견한 후속 버그 — refreshToken 쿠키 path

`AuthCookieFactory.refreshTokenCookie()`/`expiredRefreshTokenCookie()`가 `path("/api/auth")`로
고정돼 있었는데, 이건 §14에서 지운 `common.auth.AuthController`(`/api/auth/**`) 시절 값이
그대로 남은 것이었다 — 그 경로 자체가 이제 존재하지 않아서, 브라우저가 실제 엔드포인트인
`/api/members/reissue`·`/api/admin/reissue`로 refreshToken 쿠키를 자동으로 안 실어 보내는
문제였다. `path("/api")`로 넓혀서 고쳤다(이 앱은 `server.servlet.context-path=/api`라 사실상
accessToken의 `path("/")`와 스코프가 비슷해졌지만, 이름 그대로 "인증 관련 요청에만 보낸다"는
의도는 유지). §14 작업의 연장선이라 별도 라운드로 안 나누고 여기 기록.
