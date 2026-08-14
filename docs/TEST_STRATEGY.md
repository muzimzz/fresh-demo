# fresh-demo 테스트 전략

`src/main/java` 전체(75개 파일)를 처음부터 다시 훑어서 만든 문서. 이전 버전은 "이번 세션에 읽은
파일 안에서 눈에 띄는 분기를 골라 넣은" 수준이었고, 실제로 검증해보니 온보딩 서비스의 예외 분기
3개와 요청 DTO 검증 어노테이션 전체를 빠뜨리고 있었다. 이번엔 그 문제를 반복하지 않기 위해 방법론
자체를 바꿨다.

## 0. 방법론 — 어떤 기준을 썼고, 어디서 가져왔나

사용자가 공유한 `test-quality-reviewer` 에이전트 정의(diff 리뷰용 체크리스트)와, 별도로 공유한
"TDD/뮤테이션체크/구조적 사각지대" 섹션을 검토했다. 결론: **원문 그대로는 못 쓴다. 이 문서는
"이미 테스트가 있고 diff가 그 테스트를 약화시켰는지"를 감사하는 도구인데, fresh-demo엔 테스트가
`FreshDemoApplicationTests.contextLoads()` 하나뿐이라 감사할 diff 자체가 없다.** 대신 그 체크리스트
항목들을 "지금부터 뭘 테스트해야 하는가"를 뽑아내는 렌즈로 거꾸로 적용했다. 항목별로 이 프로젝트에
적용한 결과:

| 원문 항목 | 이 프로젝트에 적용한 결과 |
|---|---|
| 케이스 매트릭스 완전성 (정상/에러/경계/상태) | **그대로 채택.** 아래 3번 섹션 전체가 이 방식으로 다시 작성됨. |
| 권한/인가 매트릭스 | **그대로 채택 — 그리고 실제로 문제를 하나 찾음.** 2번 섹션 참고. |
| Mock 경계 맹점 (DB가 강제하는 불변식) | **그대로 채택 — 실제로 레이스 컨디션 3개를 찾음.** 4번 섹션 참고. |
| 구조적으로 안 보이는 결함(동시성/크로스플로우/미요구사항) | **그대로 채택.** 5번 섹션에 체크리스트로 분리(테스트가 아니라). |
| 능력 도달 범위(권한 하나로 뭘 할 수 있나) | **그대로 채택 — 2번 섹션의 발견의 근거가 된 질문.** |
| 트랜잭션 컨텍스트 정직성(OSIV lazy-loading 함정) | **현재는 구조적으로 해당 없음.** `open-in-view: false`인데, 이 프로젝트의 모든 엔티티가 `@ManyToOne`/`@OneToMany` 연관관계를 아예 안 씀(Address 등 모두 FK를 Long 필드로만 들고 있음) — 지연 로딩 자체가 없어서 이 함정이 원천적으로 발생 안 한다. `MemberGrade`↔`Member` 연관관계를 나중에 실제로 걸면(DESIGN_NOTES 10번) 그때 다시 챙겨야 한다. |
| 다차원 검증기(스킴/호스트/포트 등) | **해당 없음.** 이런 성격의 검증기 자체가 코드베이스에 없음. |
| 뮤테이션 저항성 | **원칙만 수동으로 채택, 도구는 안 씀.** PIT 같은 뮤테이션 테스트 도구 도입은 이 프로젝트 규모에 비해 과함 — 대신 6번 섹션에서 핵심 테스트 몇 개를 골라 "이 테스트가 진짜 뭘 잡아내는지"를 손으로 검토했다. |
| 약화된 테스트 탐지 / 불안정성(flaky) 탐지 | **아직 해당 없음.** 기존 테스트가 없으니 "약화"될 대상이 없다. 테스트 스위트가 생긴 뒤, PR마다 이 체크리스트로 diff를 감사하는 용도로는 그대로 유용할 것 — 그때 `test-quality-reviewer`를 실제로 호출하면 됨. |

즉 이 리뷰 규칙 문서 자체는 아주 적합했다 — 다만 "지금 당장 통째로 실행하는 도구"가 아니라
"테스트 리스트를 뽑을 때 놓치는 각도를 없애는 체크리스트"로 쓰는 게 이 프로젝트 단계에 맞는
사용법이었다.

### 실제로 새로 찾아낸 것들 (이 방법론이 아니었으면 놓쳤을 것)

- **권한 매트릭스를 그려보니**, `/addresses/**`와 `/members/me/onboarding`, `DELETE /members/me`가
  `authenticated()`로만 막혀 있고 `type=MEMBER`를 확인하지 않는다는 걸 발견했다. 대부분의 경우
  ADMIN 토큰으로 이 엔드포인트를 호출하면 `memberRepository.findById(adminId)`가 못 찾아서
  자연스럽게 400으로 막히지만, **`POST /addresses`는 다르다** — `AddressService.create()`가 회원
  존재 여부를 확인하지 않고 JWT의 `sub`(여기선 관리자 id)를 그대로 `memberId`로 써서 배송지
  row를 만들어버린다. 즉 관리자 계정으로 로그인한 뒤 `/addresses`를 호출하면 실제 회원이 아닌
  admin id를 주인으로 하는 배송지가 조용히 생성된다. 2번 섹션 참고.
- **Mock 경계 맹점을 적용해보니**, `Admin.loginId`/`Member.nickname`의 유니크 제약이 애플리케이션의
  "먼저 조회해서 있으면 막기"(`existsByLoginId`/`existsByNickname`) 패턴에만 의존하고 있어서
  동시 요청 시 레이스가 가능하다는 걸 확인했다. 반면 `Member.activeProviderKey`는 이미
  `saveAndFlush()` + `DataIntegrityViolationException` 캐치로 DB 제약을 직접 근거로 삼는 올바른
  패턴을 쓰고 있다 — 같은 코드베이스 안에 올바른 예시와 위험한 예시가 공존한다. 4번 섹션 참고.
- **구조적 사각지대 체크를 적용해보니**, `AuthController.reissue()`(RT 재발급)와
  `MemberWithdrawalService.withdraw()`(탈퇴)가 동시에 실행되는 경로에 대한 어떤 안전장치도 없다는
  걸 발견했다 — 순서에 따라 탈퇴 처리 *직후*에 재발급이 성공해서 죽은 계정에 새 세션이 발급될 수
  있다. 5번 섹션 참고.

---

## 1. 우선순위 정의 (기존과 동일하게 유지)

- **1순위** — 외부 의존성 없이 순수 로직만으로, 또는 mock으로 완전히 대체해서 지금 바로 작성 가능.
- **2순위** — 이미 확정된 인프라 컴포넌트를 실제 협력 객체(Redis/DB/필터체인)까지 붙여서 검증. 도메인 엔티티 변경과 무관.
- **3순위** — 비즈니스 로직/통합/외부 API 테스트. 엔티티·API가 계속 안정적인 지금은 사실상 바로 시작 가능하지만, 앞으로 도메인(주문 등)이 늘어날 때마다 이 계층이 계속 커진다.

---

## 2. 권한/인가 매트릭스

행위자(익명 / MEMBER 토큰 / 관리자 ADMIN 토큰 / 관리자 SUPER_ADMIN 토큰) × 엔드포인트로 정리.
"✅허용"은 정상적으로 의도된 동작, "⚠️허용(의도?)"은 이번에 발견한, 막아야 할지 검토가 필요한 지점.

| 엔드포인트 | 익명 | MEMBER | ADMIN | SUPER_ADMIN |
|---|---|---|---|---|
| `POST /admin/login` | ✅허용 | ✅허용(무의미) | ✅허용(무의미) | ✅허용(무의미) |
| `POST /admin` (계정발급) | 401 | 403 | 403 | ✅허용 |
| `DELETE /admin/{id}` | 401 | 403 | 403 | ✅허용 |
| `POST /auth/reissue` | ✅허용(쿠키기반) | ✅허용 | ✅허용 | ✅허용 |
| `POST /auth/logout` | 401 | ✅허용 | ✅허용(자기 세션만) | ✅허용(자기 세션만) |
| `GET/POST /webhook/kakao/unlink` | ✅허용(자체 서명 검증) | - | - | - |
| `PATCH /members/me/onboarding` | 401 | ✅허용 | ⚠️허용 — `MEMBER_NOT_FOUND`(400)로 끝나긴 함, 하지만 `type` 체크가 없어서 통과되는 건 우연 | ⚠️허용 — 위와 동일 |
| `DELETE /members/me` | 401 | ✅허용 | ⚠️허용 — `MEMBER_NOT_FOUND`(400)로 끝남, 위와 동일 이유 | ⚠️허용 |
| `GET/POST/PUT/DELETE /addresses/**` | 401 | ✅허용(자기 것만) | 🔴**실제로 성공함** — `POST /addresses`는 회원 존재 여부를 안 봐서, 관리자 id를 `memberId`로 하는 배송지가 그냥 생성됨 | 🔴동일 |

**정리:** `SecurityConfig`는 `/admin/**`만 role로 막고, `/members/**`·`/addresses/**`는 "인증만 되면
누구나"(`authenticated()`)로 열려 있다. 회원 전용 API에 관리자 토큰이 들어오는 걸 URL 레벨에서
막지 않고, 대신 "그 id로 회원을 찾을 수 있는가"라는 우연한 부작용에 기대고 있다. `AddressService`는
그 안전장치조차 없어서 실제로 뚫린다.

(참고: 이 문서 작성 시점엔 PK가 UUID(v7)였으나, 이후 프로젝트 전체가 Long PK로 마이그레이션됐다 —
아래 케이스 매트릭스의 구체적 예시는 이 변경을 반영해 갱신했고, 발견된 문제의 본질(type 미검증)은
PK 타입과 무관하게 동일하다.)

**권한 매트릭스 테스트 (신규, 2순위·통합, `@WebMvcTest`+`@WithMockUser` 또는 실제 필터체인):**
- MEMBER 토큰으로 `/admin/**` 전부 403
- ADMIN/SUPER_ADMIN 토큰으로 `/members/me/onboarding`, `DELETE /members/me` 호출 시 400(`MEMBER_NOT_FOUND`)으로 끝나는지 — **지금 동작을 고정하는 회귀 테스트**로 일단 추가
- **ADMIN 토큰으로 `POST /addresses` 호출 시 실제로 무슨 일이 일어나는지 — 지금 코드로는 201이 나오고 admin id를 memberId로 하는 row가 생긴다. 이걸 그대로 "허용된 동작"으로 문서화하고 테스트로 고정할지, 아니면 `SecurityConfig`/`AddressService`에 `type=MEMBER` 체크를 추가해서 403/400으로 막을지는 설계 판단이 필요함 — 지금은 테스트만 추가해서 현재 동작을 명시적으로 드러내는 걸 권장(고쳐야 한다고 판단되면 그때 테스트를 반대로 뒤집으면 됨).**

  (덧붙임: `SecurityConfig`에는 이후 `TYPE_MEMBER` synthetic authority 기반 매처가 실제로 추가되어 이 구멍은 막혔다 — `/addresses/**`, `/members/**`가 `hasAuthority("TYPE_MEMBER")`로 보호된다. 이 매트릭스는 "찾아낸 문제"의 기록으로 그대로 남겨두고, 실제 수정 여부는 코드 기준으로 확인할 것.)

---

## 3. 컴포넌트별 케이스 매트릭스

`[정상]` `[에러]` `[경계]` `[상태]`로 나눠서 나열. 셀이 없으면 그 축이 이 컴포넌트에 해당 없다는 뜻.

### 3.1 JwtTokenProvider — **[1순위·단위]**
- [정상] `createRefreshToken`이 `jti`/`iat`/`remember` 클레임을 담음, `createAccessToken`엔 `jti` 없음
- [정상] `getIssuedAt`이 `iat`을 정확히 변환
- [경계] `getJti`: RT엔 값, AT엔 `null`
- [경계] `getRemember`: 클레임 없는 토큰(예전 형식)은 `false`
- [에러] `validateToken`: 서명 위조 / 만료 / 형식 오류 토큰 전부 `false`
- **[신규 발견 — 미요구사항]** AT의 `role` 클레임이 실제로 그 토큰의 `type`(MEMBER/ADMIN)과 항상 짝이 맞는지 보장하는 코드가 없다 — 지금은 호출부(AdminService/OAuth2LoginSuccessHandler)가 항상 올바른 조합으로만 호출해서 우연히 안전하다. "AT 발급 시 type/role 조합이 항상 유효한 조합인가"를 고정하는 테스트를 추가해두면, 나중에 새 호출부가 실수로 잘못된 조합을 넣었을 때 잡아낼 수 있다.

### 3.2 AccessTokenValidAfterRepository — **[1순위·단위]** (`StringRedisTemplate` mock)
- [정상] 커트라인 없으면 `isValidAfter` 항상 `true`
- [경계] `iat`이 커트라인과 정확히 같은 순간(`isBefore` 기준 — 같으면 유효 처리되는지 확인)
- [상태] `iat`이 커트라인 이전/이후 각각

### 3.3 TokenHasher — **[1순위·단위]** (순수 함수)
- [정상] 같은 입력 → 같은 해시
- [경계] 다른 입력 → 다른 해시 (원문 노출 없이 검증)

### 3.4 RefreshTokenRepository — **[2순위·통합]** (실제/테스트 Redis + DB, `TokenType.MEMBER`/`ADMIN` 각각)
- [정상] `save` 후 Redis엔 해시만 저장(원문 없음), DB 백업(`member`/`admin`의 `refresh_token_hash`)도 같은 해시로 반영
- [정상] `compareAndSave`: old값 일치 시 교체 성공, 성공 시에만 DB 백업도 새 해시로 갱신
- [상태] `compareAndSave`: 이미 교체된 경우 실패(재사용 의심 시나리오 — CAS 실패)
- [에러] Redis 강제 장애 시 `save`/`compareAndSave` 전부 DB(`updateRefreshToken`/`compareAndSetRefreshToken`)로 폴백
- [에러] Redis도 DB도 둘 다 실패 — 지금 코드는 예외를 삼키고 로그만 남기는데(`trySaveBackup`), `compareAndSave`의 DB CAS 실패는 삼키지 않고 그대로 `false` 반환 — 이 비대칭이 의도한 것인지 확인하는 테스트
- [정상] `delete`: Redis·DB 백업(`clearRefreshToken`) 둘 다 삭제
- [정상] `TokenType.MEMBER`/`TokenType.ADMIN` 각각 올바른 리포지토리(`MemberRepository`/`AdminRepository`)로 라우팅되는지 — 잘못된 타입으로 엉뚱한 테이블이 갱신되면 안 됨

### 3.5 MemberRepository / AdminRepository — refreshToken 백업 메서드 — **[2순위·통합]** (`@DataJpaTest`)
목표 DDL대로 별도 `refresh_token_backup` 테이블 대신 `member`/`admin`의 `refresh_token_hash`/`refresh_token_expires_at` 컬럼을 직접 갱신하는 벌크 `@Modifying @Query` 3종(`updateRefreshToken`/`clearRefreshToken`/`compareAndSetRefreshToken`) — 예전에 있던 `RefreshTokenBackupRepository`/`RefreshTokenCleanupScheduler`(별도 테이블 + 만료 정리 배치)는 이 컬럼 이전으로 완전히 대체되어 삭제됨(정리할 별도 row 자체가 없어짐).
- [정상] `updateRefreshToken`: 대상 id의 hash/expiresAt 갱신, 영향 row 수 반환
- [경계] `updateRefreshToken`: 존재하지 않는 id → 영향 row 0건(예외 아님)
- [정상] `clearRefreshToken`: hash/expiresAt을 둘 다 null로
- [정상] `compareAndSetRefreshToken`: oldHash 일치 시 1건 업데이트
- [에러] `compareAndSetRefreshToken`: oldHash 불일치 시 0건 업데이트(예외 아님 — row count로만 판단)
- **[신규 발견 — 확인 필요]** `ddl-auto: update`가 기존 `member`/`admin` 테이블에 새 컬럼(`refresh_token_hash`/`refresh_token_expires_at`)을 실제로 깨끗하게 추가해주는지 로컬 검증 필요 — 안 먹으면 테이블 드롭 후 재기동 또는 수동 `ALTER TABLE` 필요(`Member`/`Admin` 엔티티 주석 참고)

### 3.6 JwtAuthenticationFilter — **[1순위·단위]** (의존성 mock + `MockHttpServletRequest/Response`)
- [정상] 정상 토큰 → `SecurityContext`에 인증 세팅
- [에러] 토큰 없음 → 인증 없이 통과
- [에러] `type`/`role` 클레임 없는 토큰 → 인증 없이 통과
- [상태] 커트라인 이전 발급 토큰 → 인증 없이 통과
- [에러] `AccessTokenValidAfterRepository`가 `DataAccessException` → fail-open으로 통과(경고 로그만)

### 3.7 인가 규칙(SecurityConfig) — **[2순위·통합]** (`@WebMvcTest`+`@WithMockUser`)
→ 2번 섹션의 권한 매트릭스 표 전체가 이 카테고리의 케이스 목록.

### 3.8 GlobalExceptionHandler — **[2순위·통합, 신규 추가]** (더미 컨트롤러 + `MockMvc`, 또는 핸들러 메서드 직접 호출)
이전 목록엔 아예 빠져 있던 컴포넌트 — 모든 API 응답의 에러 포맷을 결정하는 공유 인프라라 우선순위가 낮지 않다.
- [정상] `BusinessException` → 해당 `ErrorCode`의 status/message로 응답
- [정상] `ConstraintViolationException` → 400 + 필드별 `ValidationError` 목록. **확인 필요**: 이 예외는 `@Validated` + 파라미터 제약이 있어야 발생하는데, 지금 컨트롤러 어디에도 `@Validated`가 안 보여서 이 핸들러가 실제로 도달 가능한 경로인지 불확실 — 도달 불가능하면 죽은 코드
- [정상] `MethodArgumentNotValidException`(`@RequestBody @Valid` 검증 실패) → field/global 에러 병합
- [정상] `MethodArgumentTypeMismatchException`(`@PathVariable Long`에 숫자 아닌 값) → 400
- [정상] `MissingServletRequestParameterException` → 400
- [에러] 알 수 없는 `Exception` → 500 + `INTERNAL_ERROR`
- [경계] **`response.isCommitted()`가 이미 true인 상태에서 예외 발생** → `null` 반환하고 재작성 안 함(스트리밍 응답 등에서만 재현 가능 — 지금 SSE가 없어서 사실상 도달 불가능한 방어 코드, 우선순위 낮음)
- [정상] 4xx는 WARN(스택트레이스 없음), 5xx는 ERROR(스택트레이스 포함) 로그 레벨 분기
- [정상] `mapToErrorCode`: 405→`METHOD_NOT_ALLOWED`, 404→`NOT_FOUND`, 그 외 4xx→`INVALID_PARAMETER`, 그 외→`INTERNAL_ERROR`

### 3.9 로그인/감사 로그 (AdminService, CustomOidcUserService) — **[2순위·통합]**
- [정상] 로그인 성공 → `ADMIN_LOGIN_SUCCESS`
- [에러] 계정 없음 → `ADMIN_LOGIN_FAILED reason=NO_SUCH_ACCOUNT`(loginId), 응답은 계정 있음/없음 구분 안 됨(`INVALID_PASSWORD`로 동일)
- [에러] 비번 틀림 → `ADMIN_LOGIN_FAILED reason=WRONG_PASSWORD`(adminId), 응답은 위와 동일 코드
- [정상] `register`/`deleteAdmin` 성공 시 `ADMIN_REGISTERED`/`ADMIN_DELETED`(actorId+targetId)
- [에러] `register`/`deleteAdmin`을 SUPER_ADMIN 아닌 요청자가 시도 → `NOT_SUPER_ADMIN` 예외. **지금은 이 시도 자체가 로그로 안 남는다** — 감사 목적상 "권한 없는 시도"도 남겨야 하는지 검토 필요(권한 상승 시도 탐지 관점에서 원래는 남겨야 할 이벤트)
- [에러] `register`: 중복 `loginId` → `DUPLICATE_LOGIN_ID` (단, 이 체크는 `existsByLoginId()` 선조회 방식 — 4번 섹션의 레이스 참고)
- [에러] 지원 안 하는 `registrationId` → `MEMBER_LOGIN_FAILED reason=UNSUPPORTED_REGISTRATION_ID`
- [에러] 가입 race 재조회 실패 → `MEMBER_LOGIN_FAILED reason=SIGNUP_RACE_UNRESOLVED`
- [정상] `activeProviderKey`로 기존 회원 찾으면 `email`만 갱신하고 재사용

### 3.10 PII 마스킹 & HTTP 접근 로그 — **[1순위·단위]** + **[2순위·통합]**
- [경계] `PiiMasker.maskEmail`/`maskPhone`/`maskName`: 2자 이하 이름, 7자 미만 전화번호, `@` 없는 이메일
- [경계] `redact`: null/blank는 그대로 통과, 값 있으면 REDACTED
- [정상] `maskProviderId`: 앞2·뒤2만 남김
- [정상] `HttpBodyLoggingFilter.mask`: `password`/`token`/`phone`/`address` 등 키는 통째로 REDACTED, 빈 문자열 값은 REDACTED 안 됨
- [정상] 본문 아무 데나 있는 이메일/전화번호 catch-all 부분 마스킹
- [상태] 2xx/3xx는 상태코드+시간만 INFO / 4xx는 WARN+바디 / 5xx는 ERROR+바디 / DEBUG 켜지면 정상 응답도 바디 포함

### 3.11 AuthCookieFactory — **[1순위·단위]** (순수 객체)
- [상태] `persistent=true`면 `Max-Age` 설정, `false`면 세션 쿠키(Max-Age 없음)
- [정상] `expiredAccessTokenCookie`/`expiredRefreshTokenCookie`: `Max-Age=0`
- [정상] `httpOnly`/`sameSite`/`path` 고정값(accessToken=`/`, refreshToken=`/api/auth`)

### 3.12 RememberMeRequestFilter / OAuth2LoginSuccessHandler / OAuth2LoginFailureHandler — **[2순위·통합]**
- [정상] `?rememberMe=true`로 카카오 인가 요청 시작 시 `remember_me` 쿠키 세팅(10분 TTL)
- [경계] `rememberMe` 파라미터 없거나 `false`, 또는 인가 요청 경로가 아닌 URI → 쿠키 안 세팅
- [정상] 로그인 성공 시 `remember_me` 쿠키 값 읽어서 AT/RT 쿠키의 영속 여부 결정, 사용 후 `remember_me` 쿠키 삭제
- [경계] `remember_me` 쿠키 자체가 없는 상태로 콜백 도달 → `false`(기본값)로 처리
- [정상] 성공 후 `pendingProfile` 쿼리파라미터로 온보딩 필요 여부 전달
- [에러] principal이 `CustomOidcUser`가 아닌 예상 밖 타입 → `IllegalStateException` (현재 구조상 도달 불가능에 가까움, 우선순위 낮음)
- [정상] 로그인 실패 시 `MEMBER_LOGIN_FAILED` 로그 + `loginFailed=true`로 리다이렉트

### 3.13 JwtAccessDeniedHandler / JwtAuthenticationEntryPoint — **[1순위·단위]** (mock request/response)
- [정상] 403/401 JSON 바디가 각각 `ErrorCode.FORBIDDEN`/`UNAUTHORIZED` 포맷과 일치

(덧붙임: 이 둘은 이후 `member.oauth.error`에서 `auth.jwt`로 패키지 이동됨 — `SecurityConfig`의 전역
`exceptionHandling`에 등록돼서 member/admin 요청 공용으로 쓰이는데, 옛 위치가 "member 로그인 전용"처럼
보여서 다른 JWT 인프라(`JwtAuthenticationFilter` 등)와 같은 패키지로 옮겼다. 동작 변화는 없음.)

### 3.14 회원 온보딩(MemberOnboardingService) — **[3순위·통합]**
- [정상] 온보딩 완료 후 이름/이메일/닉네임/약관동의/마케팅동의 반영, `PENDING_PROFILE`→`ACTIVE`
- [상태] 이미 `ACTIVE`인 회원이 재호출(정보 변경) — 상태 전이는 없고 값만 갱신됨(에러 아님)
- [에러] 탈퇴한 회원이 호출 → `MEMBER_ALREADY_WITHDRAWN`
- [에러] 다른 사람이 쓰는 닉네임으로 변경 시도 → `DUPLICATE_NICKNAME`
- [경계] **본인이 원래 쓰던 닉네임 그대로 재호출** → 중복으로 안 침(현재 코드의 명시적 예외 처리) — 이전 목록에서 가장 크게 빠졌던 케이스
- [경계] email은 중복 검사 없음(닉네임과 달리 유니크 제약이 없는 순수 연락처 정보) — 같은 이메일로 여러 회원이 등록돼도 정상 동작하는지 확인하는 회귀 테스트
- [경계] 요청 DTO 검증: `name`/`email`/`nickname` blank/형식 오류/길이초과 → 400(`@NotBlank`/`@Email`/`@Size`), `termsAgreed=false` → 400(`@AssertTrue`)
- **[신규 발견 — 회귀 확인용]** 카카오 재로그인 시 email이 더 이상 갱신되지 않는지 — `CustomOidcUserService`가 기존 회원을 찾으면 아무 필드도 덮어쓰지 않고 그대로 반환하는지 확인(예전엔 `member.update(attrs.email())`로 매번 덮어썼던 동작이 사라졌음)
- **[신규]** 신규 회원 생성 시점(`CustomOidcUserService`)에 `MemberGrade.isDefault=true`인 행이 없으면 `DEFAULT_MEMBER_GRADE_NOT_FOUND`로 가입 자체가 실패하는지 — `DefaultMemberGradeInitializer`가 기동 시 시드를 넣어주지만, 그 시드가 실패했거나 나중에 지워진 상태를 가정한 회귀 테스트

### 3.15 회원 탈퇴(MemberWithdrawalService, KakaoUnlinkEventListener) — **[3순위·통합]**
- [정상] `withdraw()`: RT 삭제 + AT 커트라인 등록 + 트랜잭션 커밋 후에만 카카오 unlink 이벤트 발행
- [에러] 이미 탈퇴한 회원 → `MEMBER_ALREADY_WITHDRAWN`
- [정상] `withdrawByKakaoWebhook()`: 존재하지 않는 회원(또는 이미 탈퇴)이어도 예외 없이 조용히 종료(웹훅 200 스펙)
- [상태] 트랜잭션이 롤백되면 `AFTER_COMMIT` 리스너(카카오 unlink)가 아예 실행 안 되는지 — `@DataJpaTest`만으론 확인 안 되고 `@SpringBootTest` + 실제 트랜잭션 커밋/롤백 필요
- [에러] `KakaoUnlinkClient.unlink()`가 `WebClientResponseException`(4xx/404 등, "이미 카카오 쪽에서 끊김")이면 실패로 안 치고 WARN만
- [에러] 그 외 예외면 `KAKAO_UNLINK_FAILED` 로그 + `BusinessException` — 단, 이 예외가 어디서도 안 잡히면 이벤트 리스너 스레드에서 조용히 사라질 뿐 탈퇴 자체(이미 커밋됨)엔 영향 없음 — 이 "영향 없음"을 명시적으로 검증하는 테스트

### 3.16 Address — **[3순위·통합]**
- [정상] 첫 배송지는 요청값과 무관하게 무조건 기본
- [상태] 기존 기본이 있는 상태에서 새로 기본 지정 → 기존 것 해제
- [상태] 기본이 아닌 배송지를 기본으로 변경 요청 → 기존 기본 해제 + 이번 것 승격
- [상태] 이미 기본인 배송지를 다시 기본으로 요청(`isDefault=true`인데 이미 `true`) → `clearDefaultForMember` 호출 안 됨(코드의 `!address.isDefault()` 조건) — 불필요한 UPDATE를 피하는 최적화가 의도대로 동작하는지
- [상태] 기본 배송지 삭제 → 남은 것 중 가장 최근 것이 자동 기본 승격
- [경계] 마지막 남은 배송지(1개)를 삭제 → 승격 대상 없음, 예외 없이 종료
- [에러] 본인 소유가 아닌 `addressId`로 조회/수정/삭제 → `ADDRESS_NOT_FOUND`(존재 자체를 숨김 — 403 아니고 404 성격의 400)
- [경계] 요청 DTO 검증: `recipient`/`phone`/`zipcode`/`roadAddress` blank → 400(`detailAddress`는 검증 없음 — 의도적으로 선택 항목인지 확인)

### 3.17 관리자 계정 관리(AdminController/AdminService) — **[2순위·통합]**
- [정상] SUPER_ADMIN이 계정 발급 → 항상 `role=ADMIN`으로 생성(요청 DTO에 role 필드 자체가 없어서 승격 요청 자체가 불가능 — 별도 테스트 불필요할 만큼 구조적으로 막힘), `status=ACTIVE`로 시작
- [에러] SUPER_ADMIN이 아닌 요청자가 발급/삭제 시도 → `NOT_SUPER_ADMIN`(2번 섹션 권한 매트릭스와 별개로, 서비스 레이어 자체도 이 검사를 하므로 **URL 레벨 검사가 뚫려도 여기서 한 번 더 막힌다** — 이중 방어이므로 두 레이어 각각 테스트 필요, 하나가 다른 하나를 대신할 수 없음)
- [에러] 삭제 대상 `adminId`가 없음 → `ADMIN_NOT_FOUND`
- [정상] 관리자 삭제 = 소프트 삭제 — `status=DELETED`/`deletedAt` 세팅, 실제 row는 남음(하드 삭제 아님). `login_id`는 재사용 안 됨(DDL UNIQUE 유지) → 같은 `loginId`로 재등록 시도 시 `DUPLICATE_LOGIN_ID`
- [정상] 관리자 삭제 시 그 관리자의 refreshToken도 같이 삭제(재발급으로 계속 살아있지 못하게) + `accessTokenValidAfterRepository.invalidateBefore()`로 이미 발급된 AT도 즉시 무효화(RT만 지우면 AT는 자연 만료까지 유효했던 예전 갭 — 회귀 테스트로 고정)
- [에러] SUPER_ADMIN이 **자기 자신**을 삭제 시도 → `CANNOT_DELETE_SELF`(요구사항 "본인 비활성화 불가" 반영 — 이전엔 막는 코드가 없었던 갭)
- [에러] 삭제 대상이 **마지막 남은 ACTIVE SUPER_ADMIN**인 경우 → `LAST_SUPER_ADMIN_CANNOT_BE_DELETED`(요구사항 "최고관리자 1명 이상 유지" 반영). **경계**: SUPER_ADMIN이 2명 이상일 때 그중 1명 삭제는 허용되는지 함께 확인
- [에러] 이미 `DELETED` 상태인 관리자를 다시 삭제 시도 → `ADMIN_ALREADY_DELETED`
- [에러] `status=DELETED`인 계정으로 로그인 시도 → `INVALID_PASSWORD`(계정 없음과 동일한 응답 — 응답으로는 구분 안 됨, 로그의 `reason=DELETED_ACCOUNT`로만 구분되는지 확인)
- [정상] 계정 발급 시 요청에 비밀번호 필드가 없음(`AdminRegisterRequest`) — 서버가 `TempPasswordGenerator`로 생성, 응답(`AdminRegisterResponse.temporaryPassword`)에 평문 1회만 실림. 생성된 비밀번호로 실제 로그인이 되는지(인코딩/디코딩 왕복 확인)
- [정상] `PATCH /admin/me/password` — 현재 비밀번호 일치 시 변경 성공, 변경 후 RT/AT 모두 무효화(재로그인 필요) 확인
- [에러] `PATCH /admin/me/password` — 현재 비밀번호 불일치 시 `CURRENT_PASSWORD_MISMATCH`, 비밀번호는 변경되지 않음
- [경계] 요청 DTO 검증: `newPassword` 8자 미만 → 400
- **[미구현 — 설계만 있음]** 로그인 실패 5회 시 30분 잠금 — 아직 코드 없음, Redis/DB 중 어느 쪽으로 할지도 미정(DESIGN_NOTES.md §5 참고)

### 3.20 회원 정보 관리(MemberController.updateProfile/MemberProfileUpdateService) — **[3순위·통합, 신규 추가]**
- [정상] name/email/nickname/phone/address 갱신, 응답에 반영
- [경계] 본인이 원래 쓰던 닉네임 그대로 재제출 → 중복으로 안 침(온보딩과 동일 패턴)
- [에러] 다른 사람이 쓰는 닉네임으로 변경 시도 → `DUPLICATE_NICKNAME`
- [에러] 탈퇴한 회원이 호출 → `MEMBER_ALREADY_WITHDRAWN`
- [상태] `phone`/`address`를 요청에서 생략(null) → 기존 값 유지, 빈 문자열("")로 보냄 → `null`로 지워짐(PATCH 부분수정 시맨틱 — 이 둘을 구분하는지가 핵심 케이스)
- [경계] 요청 DTO 검증: `name`/`email`/`nickname` blank/형식 오류/길이초과 → 400

### 3.18 카카오 연동 — **[2순위]** (웹훅 보안 검증) / **[3순위]** (실제 외부 API 클라이언트)
- [에러] `app_id` 불일치 → 200 반환하되 탈퇴 처리 안 됨(`event=KAKAO_UNLINK_WEBHOOK_APP_ID_MISMATCH`)
- [에러] `Authorization: KakaoAK {adminKey}` 불일치 → 200 반환하되 탈퇴 처리 안 됨
- [정상] 둘 다 일치 → `withdrawByKakaoWebhook()` 호출
- [에러] `withdrawByKakaoWebhook()`이 예외를 던져도 웹훅 응답은 항상 200(스펙 준수)
- [경계] `referrer_type` 파라미터 없음(옵션) → 로그에 null로 남되 처리 자체는 진행
- [정상/에러] `KakaoUnlinkClient`/`KakaoLogoutClient`: 성공/4xx(non-fatal)/기타예외 3가지 분기 각각(`MockWebServer` 또는 `WebClient` 자체를 mock)

### 3.19 스케줄러 & 외부 API 공통 로깅 — **[2순위·통합, 현재 스케줄러 없음]**
- [정상] `SchedulerLoggingAspect`: 정상 종료 시 `SCHEDULER_START`/`SCHEDULER_END`(durationMs), 예외 시 `SCHEDULER_FAILED` + 예외 그대로 재던짐 — **테스트하려면 `@Scheduled` 더미 빈을 하나 만들어야 함**, 지금은 프로젝트에 `@Scheduled` 메서드가 하나도 없다(`RefreshTokenCleanupScheduler` 삭제 이후). 이 Aspect 자체는 향후 대비로 유지 중이라 테스트 우선순위는 낮음.
- [정상] `ExternalApiLoggingExchangeFilter`: 성공/실패 각각 `EXTERNAL_API_CALL`/`EXTERNAL_API_CALL_FAILED`에 method/url/status/durationMs

(덧붙임: `MdcLoggingFilter`/`TraceIdExchangeFilter`/`ExternalApiLoggingExchangeFilter`는 이후
`common.filter`에서 `common.logging`으로 통합 이동됨 — `common.filter`엔 이 셋만, `common.logging`엔
`HttpBodyLoggingFilter`/`PiiMasker`/`SchedulerLoggingAspect`가 있어서 "필터냐 로깅이냐" 기준이 섞여
있던 걸 "전부 로깅 인프라"라는 하나의 기준으로 통일했다. 동작 변화는 없음.)

### 3.21 로컬 콘솔 로그 포맷(logback-spring.xml) — **[2순위·통합, 신규 추가]**
이번 세션에서 실제로 겪은 버그 — 로컬 콘솔 로그가 전부 한 줄에 붙어서 출력되는 증상이 있었다.
원인 두 가지가 겹쳐 있었다:
1. `local` 프로필 패턴에서 `%cyan(%logger{36})` 바로 뒤에 리터럴 괄호 `(%F:%line)`가 이어지면서,
   Logback의 PatternParser가 그 여는 괄호를 또 다른 컴포지트 컨버터(`%word(...)`)의 시작으로 오인 —
   `- %msg%n` 전체가 파싱 실패로 통째로 사라졌다. IntelliJ 콘솔 렌더링 문제로 의심했었으나,
   `--console=plain`으로 raw 파일에 직접 캡처해서 실제로 로그 자체가 깨져 있다는 걸 확인했다(개행이
   없는 게 아니라 " - %msg%n" 부분 자체가 아예 안 찍히고 있었음).
2. `%X{traceId:-}`처럼 `%X{}`(MDC 컨버터) 안에 `:-` 기본값 문법을 썼는데, 이건 Logback도 Log4j2도
   `%X{}` 자체에서는 지원하지 않는 문법이다(Log4j2에 `:-`가 있긴 하지만 그건 `%X{}`가 아니라
   `${ctx:key:-default}` 같은 완전히 다른 Lookup 치환 문법). `"traceId:-"`라는 존재하지 않는 키를
   찾은 셈이라 traceId/method/uri/clientIp가 항상 빈 문자열로 찍히고 있었다.

리터럴 괄호 제거 + `%X{traceId:-}` → `%X{traceId}` 교정으로 해결.

- [정상] `local` 프로필 콘솔 출력이 로그 이벤트마다 정상적으로 줄바꿈되는지 — 자동화 단위테스트보단
  부팅 후 콘솔을 육안으로 확인하는 게 현실적이다. 굳이 자동화하려면 Logback `ListAppender`를 테스트에
  붙여서, 포맷된 문자열 안에 `%n`에 해당하는 개행이 실제로 들어가는지 assert하는 방법이 있다.
- [정상] MDC에 실제 값이 채워진 요청(`MdcLoggingFilter` 통과 후)은 콘솔 로그의 `[traceId]`/`method`/
  `uri`/`clientIp` 자리에도 그 값이 그대로 찍히는지 — 이전엔 항상 빈 값이었던 것에 대한 회귀 확인
- [경계] MDC 값이 없는 로그(요청 컨텍스트 밖 — 부팅 로그 등)에서도 패턴이 깨지지 않고 정상 출력되는지
- `prod` 프로필(JSON/`LogstashEncoder`)은 이 버그의 영향을 받지 않았다 — 문제였던 리터럴 괄호가
  `local` 프로필 패턴에만 있었기 때문에 재확인 불필요.

---

## 4. Managed 의존성(DB/Redis) 불변식 — Mock으로 못 보는 것

DB(MySQL)와 Redis는 우리가 소유하고 통제하는 "관리 의존성"이라, 이 둘이 실제로 강제하는 제약은
반드시 **진짜 DB/Redis에 대고** 검증해야 한다. 서비스 레이어를 mock repository로 테스트하는 것만으론
아래 제약이 실제로 걸려 있는지 전혀 증명이 안 된다.

| 불변식 | 강제 위치 | 지금 애플리케이션 코드의 방어 | 레이스 가능? | 필요한 테스트 |
|---|---|---|---|---|
| `admin.login_id` UNIQUE | DB 제약 | `existsByLoginId()` 선조회 후 `save()` | **가능** — 두 요청이 동시에 같은 loginId로 발급 시도하면 둘 다 `existsByLoginId()`에서 false를 볼 수 있음 | 실제 DB에 동시 insert 시도(또는 최소한 제약 위반 시 `DataIntegrityViolationException`이 나는지) 확인하는 테스트. 지금 이 예외를 잡는 코드가 `AdminService`에 없어서, 레이스가 실제로 발생하면 500으로 새어나갈 것 — 이것도 같이 확인 |
| `member.nickname` UNIQUE | DB 제약 | `existsByNickname()` 선조회 후 저장 | **가능** — 동일 패턴 | 위와 동일 |
| `member.active_provider_key` UNIQUE | DB 제약 | `saveAndFlush()` 시도 → `DataIntegrityViolationException` 캐치 → 재조회 | **이미 올바르게 처리됨** — DB 제약을 신뢰하고 실패를 캐치하는 정석 패턴 | 이 패턴이 계속 유지되는지 회귀 테스트만 있으면 됨(이미 3.9에 포함) |
| `member`/`admin`의 `refresh_token_hash` 동시 갱신 | 별도 DB 제약 없음(각 행 자체의 PK가 이미 유일) | `compareAndSetRefreshToken()`의 `WHERE id = :id AND refresh_token_hash = :oldHash` 조건부 UPDATE | 이론상 가능하지만 실제로는 `compareAndSave()`의 Redis Lua CAS(또는 DB CAS `compareAndSetRefreshToken`)가 상위에서 이미 동시성을 막고 있어서 같은 행에 대한 동시 갱신이 레이스로 깨질 상황 자체가 드묾 — 낮은 우선순위. 별도 테이블(`refresh_token_backup`)일 때 있던 `(role, owner_id)` UNIQUE 제약은 컬럼이 소유 엔티티(`member`/`admin`) 자신의 행으로 옮겨가면서 더 이상 필요 없어졌다(그 행의 PK 자체가 이미 유일성을 보장). | Redis/DB CAS가 정상 동작하는 한 문제없다는 전제를 문서로 남기고, CAS 테스트(3.4)로 사실상 커버 |
| 배송지 "회원당 기본 1개" | **DB 제약 추가됨**(`address.is_default_key` 생성 컬럼 + UNIQUE, 목표 DDL과 동일 기법) | `isFirstAddress`/`clearDefaultForMember`(1차) + DB UNIQUE(2차 안전망) | 애플리케이션 로직 레이스는 여전히 가능하지만, DB가 최종적으로 2개 이상 존재하는 걸 막아준다 — 레이스가 나면 앱 로직이 아니라 `DataIntegrityViolationException`으로 드러남(지금 `AddressService`는 이 예외를 아직 안 잡음, 500으로 샐 수 있음) | 동시 요청으로 실제 DB에 두 번째 기본 배송지가 들어가려 할 때 `DataIntegrityViolationException`이 나는지, 그리고 그게 지금처럼 500으로 새는 게 맞는지(Admin/Member 유니크 제약처럼 캐치해서 409 등으로 바꿀지) 확인하는 테스트. `ddl-auto:update`가 기존 테이블에 생성 컬럼을 실제로 만들어주는지부터 로컬에서 검증 필요(Address 엔티티 Javadoc 참고) |

---

## 5. 구조적으로 테스트가 못 보는 것 — 체크리스트로 대체

아래는 "순차적인 단위/통합 테스트로는 원천적으로 못 잡고, 코드 리뷰 시 사람이 체크리스트로
확인하거나 설계로 막아야 하는" 항목이다. 테스트 리스트에 억지로 넣지 않는다.

**동시성/TOCTOU:**
- Admin 계정 발급 동시 요청 시 `login_id` 중복 (위 4번 섹션)
- 회원 닉네임 변경 동시 요청 시 `nickname` 중복 (위 4번 섹션)
- 배송지 동시 생성/삭제 시 기본 배송지가 0개 또는 2개 이상이 되는 경우 (위 4번 섹션 — "2개 이상"은 이제 DB UNIQUE가 막아주지만, "0개"가 되는 경우는 여전히 DB가 못 막는다 — 이 UNIQUE는 "최대 1개"만 강제하지 "최소 1개"는 강제 안 함)

**여러 흐름이 합쳐질 때만 나타나는 결함:**
- **RT 재발급(`AuthController.reissue()`)과 회원탈퇴(`MemberWithdrawalService.withdraw()`)가 거의 동시에 실행되는 경우.** `withdraw()`가 커밋되기 직전에 `reissue()`가 옛 상태(`isWithdrawn()=false`)를 읽고 `compareAndSave()`로 새 RT를 정상 발급해버리면, 탈퇴 처리 완료 직후에도 새로 발급된 RT/AT로 계속 인증된 요청을 보낼 수 있는 창이 생긴다. 이건 어느 한쪽의 단위 테스트로도 안 잡힌다 — 두 유스케이스가 같은 계정에 동시에 오는 시나리오 자체가 테스트 매트릭스 밖이다. (완화책 후보: `withdraw()`가 비관적 락으로 회원 row를 잠그거나, `reissue()`가 `compareAndSave()' 성공 직후 다시 한번 회원 상태를 확인하는 것 — 지금은 둘 다 없음.)
- Admin 계정 삭제와 그 관리자가 진행 중인 요청(refreshToken 재발급 등)이 겹치는 경우 — 회원 탈퇴와 같은 유형이지만 관리자 쪽엔 아직 명시적으로 짚은 기록이 없었음.

**아무도 요구하지 않은 요구사항:**
- "관리자 역할(ADMIN→SUPER_ADMIN)이 바뀌면 이미 발급된 세션은 어떻게 되나?" — 지금은 역할 변경
  기능 자체가 없어서 해당 없음. 나중에 추가되면, RT는 키에 role이 들어가 자연 무효화되지만 AT는
  `AccessTokenValidAfterRepository.invalidateBefore()` 훅이 없다는 걸 미리 알아둬야 한다(비밀번호
  변경과 같은 부류 — DESIGN_NOTES 10번에 이미 기록됨, 이번에 관리자 역할변경도 같은 목록에 들어가야
  한다는 것만 새로 확인).
- "SUPER_ADMIN이 마지막 한 명일 때 자기 자신을 지우면?" — 위 3.17에서 짚었듯 막는 코드가 없다. 아무도 이 질문을 명시적으로 던진 적이 없어서 테스트도 없다.

---

## 6. 뮤테이션 체크 (도구 없이 수동으로 해본 결과)

자동화된 뮤테이션 테스트 도구는 안 썼지만, 핵심 테스트 후보 3개를 놓고 "구현이 이렇게 깨지면 이
테스트가 정말 빨간불이 되는가"를 직접 따져봤다.

1. **`RefreshTokenRepository.compareAndSave`가 성공 여부를 항상 `true`로 반환하도록 뮤테이션됐다면?**
   → `matches()`나 `save()`만 테스트하는 스위트는 못 잡는다. `compareAndSave`의 반환값을
   `assertFalse`로 직접 검증하는 테스트가 있어야만 잡힌다 — 3.4에 이미 명시.
2. **`AddressService.create()`에서 `shouldBeDefault` 계산이 `isFirstAddress || request.isDefault()`가
   아니라 `request.isDefault()`만 보도록(첫 배송지 자동 기본 로직이 삭제되도록) 뮤테이션됐다면?**
   → "첫 배송지는 무조건 기본"이라는 케이스를 **`request.isDefault()=false`로 명시적으로 보내서**
   테스트하지 않으면 안 잡힌다. `request.isDefault()=true`로만 테스트하면 두 로직이 우연히 같은
   결과를 내서 뮤턴트가 살아남는다 — 3.16 테스트를 작성할 때 이 조합을 반드시 넣어야 함.
3. **`PiiMasker.redact()`가 blank 체크 없이 항상 REDACTED를 반환하도록 뮤테이션됐다면?**
   → 3.10에 "null/blank는 그대로 통과"라는 케이스가 이미 있어서 잡힌다.

이 세 개 중 2번이 유일하게 "지금 계획대로 테스트를 짜도 놓칠 수 있는" 경우였다 — 문서에도
명시적으로 반영해뒀다(3.16 테스트 작성 시 주의사항으로).

한계: 이 수동 점검은 내가 이미 알고 있는 뮤턴트만 확인한 것이라, 아예 안 써본 분기가 빠졌다는 걸
증명하진 못한다(뮤테이션 테스트 자체의 근본적 한계 — 존재하는 코드만 교란 가능).

---

## 7. 우선순위 요약

| 순위 | 포함 항목 |
|---|---|
| 1순위 | 3.1(JwtTokenProvider 단위) · 3.2 · 3.3 · 3.6(필터 단위) · 3.10(마스킹 단위) · 3.11 · 3.13 |
| 2순위 | 2번(권한 매트릭스) · 3.4 · 3.5 · 3.7 · 3.8(GlobalExceptionHandler, 신규) · 3.9 · 3.10(HTTP 로그 통합) · 3.12 · 3.17 · 3.18(웹훅 보안 검증) · 3.19 · 3.21(로컬 콘솔 로그 포맷, 신규) · 4번(DB 불변식 — Admin/Member 유니크 레이스) |
| 3순위 | 3.14 · 3.15 · 3.16 · 3.18(카카오 클라이언트) |
| 테스트 아님(체크리스트) | 5번 전체 |

이전 버전보다 늘어난 항목: `GlobalExceptionHandler`(3.8, 완전히 빠져 있었음), 권한 매트릭스의
admin→member 엔드포인트 접근 문제(2번), DB 유니크 제약 레이스 3건(4번), RT재발급/탈퇴 크로스플로우
레이스(5번), 온보딩의 예외 분기 3개(3.14, 이전 세션에서 사용자가 직접 지적), 로컬 콘솔 로그 포맷이
깨지던 실제 버그와 그 회귀 테스트(3.21, 이번 세션에서 직접 겪고 고침).
