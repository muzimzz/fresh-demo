# fresh-demo API 명세서

- Base URL: `http://localhost:8080/api` (context-path: `/api`)
- 인증 방식: JWT, httpOnly 쿠키(`accessToken`, `refreshToken`)로만 오간다. Authorization 헤더 사용 안 함.
- 공용/관리자 계정은 JWT의 `type` 클레임(`MEMBER` / `ADMIN`)으로 구분하고, 인가는 `role` 클레임(예: `ROLE_USER`, `ROLE_ADMIN`, `ROLE_SUPER_ADMIN`)으로 처리한다.
- 쿠키의 `Secure` 속성은 프로필별로 다르다(`application-local.yaml`은 false, `application-prod.yaml`은 true) — 운영 배포 시 `SPRING_PROFILES_ACTIVE=prod`를 반드시 지정해야 HTTPS 전용 쿠키로 내려간다.

## 공통 응답 포맷

성공/실패 모두 `ApiResponse<T>`로 감싸서 내려간다.

```json
{
  "success": true,
  "code": "OK",
  "message": "요청이 성공했습니다.",
  "data": { },
  "timestamp": "2026-08-11T09:00:00Z"
}
```

실패 시 `success: false`, `code`/`message`는 아래 에러 코드 표를 따르고 `data`는 보통 `null`이다.

예외: `GET|POST /webhook/kakao/unlink`는 카카오 서버가 호출하는 외부 웹훅 계약이라 `ApiResponse`로 감싸지 않고 바디 없이 200만 응답한다.

## 에러 코드

| code | HTTP status | message |
|---|---|---|
| INVALID_PARAMETER | 400 | 요청 파라미터가 올바르지 않습니다. |
| INTERNAL_ERROR | 500 | 서버 내부 오류가 발생했습니다. |
| METHOD_NOT_ALLOWED | 405 | 지원하지 않는 HTTP Method입니다. |
| NOT_FOUND | 404 | 요청한 리소스를 찾을 수 없습니다. |
| UNAUTHORIZED | 401 | 인증이 필요합니다. |
| FORBIDDEN | 403 | 접근 권한이 없습니다. |
| MEMBER_NOT_FOUND | 400 | 회원을 찾을 수 없습니다. |
| MEMBER_ALREADY_WITHDRAWN | 400 | 이미 탈퇴한 회원입니다. |
| DUPLICATE_NICKNAME | 409 | 이미 사용 중인 닉네임입니다. |
| KAKAO_UNLINK_FAILED | 502 | 카카오 연결 해제 요청에 실패했습니다. |
| KAKAO_WEBHOOK_INVALID | 400 | 유효하지 않은 카카오 웹훅 요청입니다. |
| ADMIN_NOT_FOUND | 400 | 관리자를 찾을 수 없습니다. |
| NOT_SUPER_ADMIN | 403 | SUPER_ADMIN만 수행할 수 있는 작업입니다. |
| DUPLICATE_LOGIN_ID | 409 | 이미 사용 중인 아이디입니다. |
| INVALID_PASSWORD | 401 | 아이디 또는 비밀번호가 올바르지 않습니다. |
| ADDRESS_NOT_FOUND | 400 | 배송지를 찾을 수 없습니다. |

---

## 1. 카카오 로그인 (회원)

### 1-1. 로그인 시작

```
GET /oauth2/authorization/kakao?rememberMe={true|false}
```

- Spring Security 표준 경로(직접 만든 컨트롤러 없음). 인증 불필요.
- `rememberMe=true`를 붙이면 짧은 쿠키(`remember_me`, 10분)에 담아뒀다가 로그인 완료 시 반영한다 — 카카오 인가~콜백 왕복 동안 세션 없이 값을 들고 다니기 위한 장치.
- 프론트는 이 URL로 브라우저를 이동시키기만 하면 된다(리다이렉트 체인은 서버가 처리).

### 1-2. 카카오 콜백 (카카오 → 서버, 프론트는 직접 호출하지 않음)

```
GET /login/oauth2/code/kakao
```

- 카카오가 인가 코드와 함께 리다이렉트하는 콜백. 처리 완료 후 서버가 프론트로 다시 리다이렉트한다.
- 신규/기존 회원 판별: `provider(social_type) + ":" + provider_user_id(sub)`로 만든 `active_provider_key`가 유니크 키. 매칭되는 활성 회원이 없으면(신규 가입이든, 탈퇴 후 재가입이든) 새 회원 행을 만든다.
- accessToken/refreshToken을 httpOnly 쿠키로 발급하고, 아래 URL로 리다이렉트:

```
{FRONTEND_CALLBACK_URL}?pendingProfile={true|false}
```

- `pendingProfile=true`면 닉네임/약관동의가 아직 안 채워진 상태 — 프론트는 온보딩 화면으로 강제 이동시켜야 한다. 로그인할 때마다 상태 기준으로 다시 계산되므로, 온보딩 중 브라우저를 닫아도 다음 로그인 때 다시 true가 내려온다.

---

## 2. 인증 공통 (회원 · 관리자 겸용)

### 2-1. 토큰 재발급

```
POST /auth/reissue
```

- 인증: 불필요(httpOnly `refreshToken` 쿠키로 판별). Refresh Token Rotation — 재발급마다 refreshToken도 새로 발급되어 쿠키가 갱신된다.
- 저장된 옛 토큰과의 비교+새 토큰 저장을 원자적 CAS(Redis Lua 스크립트, Redis 장애 시 DB 조건부 UPDATE로 폴백)로 처리한다. 비교가 실패하면(동시 요청 race 또는 이미 폐기된 토큰의 재사용) 재사용 의심으로 간주해 해당 세션을 즉시 무효화한다.
- Redis 장애 시에도 MySQL의 refreshToken 백업 테이블로 폴백해 재발급을 계속 처리한다.
- Request Body: 없음
- Response: `ApiResponse<Void>` (200) — accessToken/refreshToken 쿠키 갱신
- 실패: `UNAUTHORIZED`(refreshToken 없음/무효/저장된 값과 불일치(재사용 의심)/탈퇴한 회원)

### 2-2. 로그아웃

```
POST /auth/logout
```

- 인증: 필요(accessToken 쿠키)
- Response: `ApiResponse<Void>` (200) — 쿠키 만료 처리 + Redis refreshToken 삭제 + accessToken 블랙리스트 등록
- 회원(MEMBER)일 때만 부가로 카카오 `POST /v1/user/logout`도 호출한다(카카오 토큰 무효화, 실패해도 로그아웃 자체엔 영향 없음). 관리자는 해당 없음.

---

## 3. 회원(Member)

### 3-1. 온보딩 완료

```
PATCH /members/me/onboarding
```

- 인증: 필요(MEMBER)
- 용도: 카카오 최초 로그인 후 `PENDING_PROFILE` 상태를 필수 정보(닉네임+약관동의)로 채워 `ACTIVE`로 전환. 이미 `ACTIVE`여도 재호출하면 닉네임만 갱신된다(상태 전이는 `PENDING_PROFILE`일 때만).
- phone/address는 여기서 받지 않는다 — 선택 항목이라 첫 배송 시점에 별도로 받을 예정(주문 도메인 미구현이라 현재 진입점 없음).

Request Body

| field | type | 제약 |
|---|---|---|
| nickname | string | 필수, 최대 20자 |
| termsAgreed | boolean | 필수, true여야 함(false면 400) |

Response: `ApiResponse<MemberResponse>` (200)

```json
{
  "nickname": "string",
  "email": "string",
  "phone": "string | null",
  "address": "string | null",
  "role": "ROLE_USER",
  "status": "PENDING_PROFILE | ACTIVE | WITHDRAWN",
  "createdAt": "2026-08-11T09:00:00"
}
```

실패: `DUPLICATE_NICKNAME`, `MEMBER_NOT_FOUND`, `MEMBER_ALREADY_WITHDRAWN`

### 3-2. 회원 탈퇴

```
DELETE /members/me
```

- 인증: 필요(MEMBER)
- 처리: 소프트 삭제(`status = WITHDRAWN`). `active_provider_key`를 `null`로 비워 같은 카카오 계정으로 재가입할 수 있게 자리를 비켜준다(social_type/social_type_id는 이력 조회용으로 행에 그대로 남는다). refreshToken 삭제, accessToken 블랙리스트 등록, 쿠키 만료, 카카오 unlink 이벤트 발행(커밋 후 비동기 처리).
- Response: `ApiResponse<Void>` (200) — 쿠키 만료 처리 포함
- 실패: `MEMBER_ALREADY_WITHDRAWN`, `MEMBER_NOT_FOUND`

---

## 4. 배송지(Address)

모든 엔드포인트 인증 필요(MEMBER). 본인 소유 배송지만 조회/수정/삭제 가능(다른 회원 것 접근 시 `ADDRESS_NOT_FOUND`로 응답 — 존재 자체를 숨김).

### 4-1. 내 배송지 목록

```
GET /addresses
```

Response: `ApiResponse<List<AddressResponse>>` (200), 최신순 정렬

### 4-2. 배송지 등록

```
POST /addresses
```

Request Body

| field | type | 제약 |
|---|---|---|
| recipient | string | 필수 |
| phone | string | 필수 |
| zipcode | string | 필수 |
| roadAddress | string | 필수 |
| detailAddress | string | 선택 |
| isDefault | boolean | 선택(기본 false) |

- 첫 배송지는 `isDefault` 값과 무관하게 자동으로 기본 배송지가 된다.
- `isDefault: true`로 등록하면 기존 기본 배송지는 자동 해제된다(기본은 항상 1개).

Response: `ApiResponse<AddressResponse>` (201)

### 4-3. 배송지 수정

```
PUT /addresses/{addressId}
```

- Request Body: 등록과 동일(`AddressRequest`)
- Response: `ApiResponse<AddressResponse>` (200)
- 실패: `ADDRESS_NOT_FOUND`

### 4-4. 배송지 삭제

```
DELETE /addresses/{addressId}
```

- 삭제한 배송지가 기본이었다면 남은 것 중 최신 배송지가 자동으로 기본으로 승격된다.
- Response: `ApiResponse<Void>` (200)
- 실패: `ADDRESS_NOT_FOUND`

`AddressResponse` 형태:

```json
{
  "id": "uuid",
  "recipient": "string",
  "phone": "string",
  "zipcode": "string",
  "roadAddress": "string",
  "detailAddress": "string",
  "isDefault": true,
  "createdAt": "2026-08-11T09:00:00"
}
```

---

## 5. 관리자(Admin)

### 5-1. 관리자 로그인

```
POST /admin/login
```

- 인증: 불필요
- Request Body

| field | type |
|---|---|
| loginId | string, 필수 |
| password | string, 필수 |

Response: `ApiResponse<AdminLoginResponse>` (200) — accessToken/refreshToken 쿠키 발급(항상 영속 쿠키, "자동로그인" 개념 없음)

```json
{ "adminId": "uuid", "name": "string", "role": "ADMIN | SUPER_ADMIN" }
```

실패: `INVALID_PASSWORD` (계정 없음/비밀번호 틀림 모두 동일 코드로 응답 — 계정 존재 여부 비노출)

### 5-2. 관리자 계정 발급

```
POST /admin
```

- 인증: 필요, `SUPER_ADMIN` 전용
- Request Body

| field | type |
|---|---|
| loginId | string, 필수 |
| password | string, 필수 |
| name | string, 필수 |

- 새로 발급되는 계정의 role은 항상 `ADMIN`(SUPER_ADMIN 승격은 이 API로 불가)

Response: `ApiResponse<AdminRegisterResponse>` (201)

```json
{ "id": "uuid", "loginId": "string", "name": "string", "role": "ADMIN", "createdAt": "2026-08-11T09:00:00" }
```

실패: `NOT_SUPER_ADMIN`, `DUPLICATE_LOGIN_ID`, `ADMIN_NOT_FOUND`(요청자 조회 실패)

### 5-3. 관리자 계정 삭제

```
DELETE /admin/{adminId}
```

- 인증: 필요, `SUPER_ADMIN` 전용
- 삭제 대상의 refreshToken도 함께 제거(삭제 후 재발급으로 계속 accessToken을 받는 것 방지)
- Response: `ApiResponse<Void>` (200)
- 실패: `NOT_SUPER_ADMIN`, `ADMIN_NOT_FOUND`

---

## 6. 카카오 웹훅 (카카오 서버 전용, 프론트 무관)

```
GET|POST /webhook/kakao/unlink
```

- 인증 불필요(카카오 디벨로퍼스 콘솔에 등록된 웹훅 URL로 카카오 서버가 직접 호출).
- 사용자가 카카오 쪽에서 먼저 연결을 끊었을 때(카카오톡 설정, 카카오계정 탈퇴 등) 통보받아 우리 쪽 회원도 탈퇴 처리한다.
- Query Params: `app_id`, `user_id`, `referrer_type`(선택)
- Response: 바디 없이 항상 200 (카카오 스펙 — 실패해도 200을 줘야 재시도 폭주를 막는다)
- `ApiResponse` 규약을 따르지 않는 유일한 엔드포인트 — 외부 계약이라 우리 컨벤션 대상이 아님.

---

## 부록: `active_provider_key` 설계

- 회원 식별 유니크 키는 `social_type` 단독이 아니라 `active_provider_key = "{social_type}:{social_type_id}"` 하나에만 걸려 있다.
- 탈퇴 시 이 필드를 `null`로 비운다. `social_type`/`social_type_id` 컬럼 자체는 유니크 제약이 없어 탈퇴한 행에 그대로 남는다(이력 보존).
- 재가입은 탈퇴했던 행을 재활성화하지 않고 **새 행**을 만든다 — 신규 행이 같은 `provider:providerUserId`로 새 `active_provider_key`를 갖게 되는데, 옛 행은 이미 키가 비어 있어 유니크 충돌이 나지 않는다.
- 카카오 `sub`(회원번호)는 unlink 후 재연동해도 값이 바뀌지 않는다는 게 카카오 공식 지원 답변으로 확인된 사실이라, 이 설계가 안전하게 성립한다.
