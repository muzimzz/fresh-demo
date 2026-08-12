# fresh-demo 로깅 가이드 (팀 발표용)

새 기능을 붙일 때 "로그를 어떻게 채워야 하는가"에 대한 실무 규칙과, 왜 지금 이런 구조로 설계했는지에
대한 배경을 함께 정리한 문서. 실무 규칙만 빠르게 참고하려면 2번으로 바로 가면 됨.

---

## 1. 전체 그림

로그는 세 레이어로 나뉘어 있고, 이미 만들어진 공통 장치를 최대한 재사용하는 걸 원칙으로 한다.

| 레이어 | 누가 남기나 | 예시 |
|---|---|---|
| 요청 컨텍스트(MDC) | `MdcLoggingFilter` — 자동, 손댈 필요 없음 | `traceId`, `method`, `uri`, `clientIp` |
| HTTP 접근 로그 | `HttpBodyLoggingFilter` — 자동, 손댈 필요 없음 | `event=HTTP_ACCESS status=... durationMs=...` |
| 비즈니스 이벤트 로그 | 개발자가 서비스 코드에 직접 작성 | `event=ADMIN_LOGIN_FAILED reason=...` |

새 기능을 만들 때 개발자가 직접 신경 써야 하는 건 사실상 3번째 레이어뿐이다. 1·2번은 필터가 모든
요청에 자동으로 붙여준다.

외부 API를 새로 호출하는 클라이언트를 만들 때도 마찬가지로, `WebClient`에
`ExternalApiLoggingExchangeFilter.logCalls()`를 붙이면 method/URL/상태코드/소요시간이 자동으로
남는다 — 클라이언트 코드에 따로 로깅을 추가할 필요 없음(`WebClientConfig.kakaoApiWebClient()` 참고).
`@Scheduled` 메서드도 마찬가지로 `SchedulerLoggingAspect`가 시작/종료/실패를 자동으로 남기므로,
스케줄러 안에 시작/종료 로그를 직접 넣지 않아도 된다.

---

## 2. 실무 규칙

### 2.1 이벤트명 컨벤션

`event=대문자_스네이크케이스` 형식. 로그 집계 도구(그라파나 Loki, ELK 등)에서 `event="XXX"`로
필터링하는 게 전제라, 자유 텍스트 문장이 아니라 고정된 키워드로 남긴다.

현재 쓰이는 이벤트명:

```
ADMIN_LOGIN_SUCCESS / ADMIN_LOGIN_FAILED
ADMIN_REGISTERED / ADMIN_DELETED
MEMBER_LOGIN_FAILED
REFRESH_TOKEN_REUSE_SUSPECTED
HTTP_ACCESS
EXTERNAL_API_CALL / EXTERNAL_API_CALL_FAILED
SCHEDULER_START / SCHEDULER_END / SCHEDULER_FAILED
REDIS_SAVE_FAILED / REDIS_FIND_FAILED / REDIS_DELETE_FAILED / REDIS_CAS_FAILED
DB_BACKUP_SAVE_FAILED / DB_BACKUP_DELETE_FAILED
ACCESS_TOKEN_VALID_AFTER_CHECK_FAILED
```

새 이벤트를 추가할 때는 `동사_상태` 조합(`_SUCCESS`, `_FAILED`, `_SUSPECTED`)을 따르고, 비슷한
기존 이벤트가 있으면 이름 패턴을 맞춘다.

### 2.2 로그 레벨 기준

| 레벨 | 기준 | 예시 |
|---|---|---|
| INFO | 정상 흐름의 중요 마일스톤 | 로그인 성공, 관리자 액션, 스케줄러 시작/종료, 정상 HTTP 응답 |
| WARN | 비정상이지만 서비스는 계속 가능 | 로그인 실패, Redis 장애로 DB 폴백, fail-open 발동, 4xx 응답 |
| ERROR | 즉시 조사가 필요한 예외 상황 | 5xx 응답, 스케줄러 실패, DB/Redis 둘 다 실패 |
| DEBUG | 평소엔 꺼두고 필요할 때만 | 정상 응답의 요청/응답 바디 전체 |

DEBUG는 상시로 켜두지 않는다. 운영 중 특정 순간의 원인을 자세히 봐야 할 때만
`/actuator/loggers`로 해당 로거를 즉시 DEBUG로 올렸다가, 확인 끝나면 다시 내린다
(`HttpBodyLoggingFilter.logAccess()` 참고).

### 2.3 무엇을 남기고, 무엇을 남기면 안 되는가

**비즈니스 로그는 원칙적으로 id만 남긴다.** DTO나 엔티티 객체를 통째로 `log.info("{}", member)`
처럼 넘기지 않는다. `memberId=...`, `adminId=...`처럼 식별자만 남기면 충분하고, 상세 내용이
필요하면 그 id로 DB를 조회하면 된다. **이 원칙은 "PII는 마스킹하면 로그에 남겨도 된다"는 뜻이
아니다** — id 하나로 충분한 상황에서 굳이 이름/이메일/전화번호 같은 필드를 (마스킹해서든 아니든)
같이 붙이지 않는다. 마스킹은 "정말 필요해서 남기기로 한 값"을 안전하게 남기는 방법이지, 뭘 남길지
고민하지 않아도 되게 해주는 면죄부가 아니다. 새 로그를 추가할 때마다 "이 필드가 원인 추적에 정말
필요한가, 아니면 그냥 있으면 편할 것 같아서 넣는 건가"를 먼저 따져보고, 필요 없으면 마스킹 여부와
무관하게 아예 빼는 게 기본값이다.

실수로 엔티티를 통째로 로그에 넘기더라도 사고로 이어지지 않도록, `Member`/`Admin`/`Address`/
`RefreshTokenBackup`의 `toString()`을 오버라이드해서 최소 필드만(비밀번호 해시·토큰 해시 등은
아예 제외) 노출하게 만들어뒀다. 이건 마지막 방어선이고, 기본 원칙은 여전히 "애초에 엔티티를
로그에 넘기지 않는다"이다.

#### 비즈니스 이벤트 로그 예시 (데이터 타입별)

로그에 어떤 식별자를 담을지는 "이 이벤트가 누구에 대한 이야기인가"에 따라 자연스럽게 정해진다.
지금 코드베이스에 실제로 쓰이는 로그를 세 가지 패턴으로 분류하면 다음과 같다.

**패턴 1 — 자기 자신에 대한 이벤트 (단일 id).** 행위자와 대상이 같을 때 — 로그인, 가입, 탈퇴처럼
"누가 자기 자신에게 벌어진 일을 겪는" 이벤트. `memberId`/`adminId` 하나면 충분하고, 그 계정을
아직 특정하지 못한 실패 케이스(예: 존재하지 않는 아이디)는 대신 요청값(`loginId` 등)을 쓴다.

```
INFO  event=ADMIN_LOGIN_SUCCESS adminId={adminId}
WARN  event=ADMIN_LOGIN_FAILED adminId={adminId} reason=WRONG_PASSWORD
WARN  event=ADMIN_LOGIN_FAILED loginId={loginId} reason=NO_SUCH_ACCOUNT   # adminId 자체가 없는 케이스
```

이 패턴에서 ERROR는 지금 코드베이스엔 없다(로그인 실패는 비즈니스 예외지 시스템 오류가 아니라서
WARN까지만 쓴다). 나중에 이 흐름 중간에서 예상 못 한 예외(DB 커넥션 끊김 등)를 별도로 잡아 남기고
싶어지면 `event=MEMBER_WITHDRAWAL_FAILED memberId={memberId} err={...}`처럼 같은 패턴을
ERROR로 확장하면 된다.

**패턴 2 — 행위자 → 대상 액션 (actor/target 쌍).** 관리자가 다른 계정에 대해 뭔가를 하는 것처럼
"누가 누구에게" 했는지 자체가 로그의 핵심 정보인 이벤트. 요청을 보낸 쪽은 `actorId`, 그 행위의
대상은 `targetId`로 이름을 구분해서 둘 다 남긴다(둘 다 그냥 `id`로 뭉뚱그리면 "이게 누구 id인지"를
로그만 보고 알 수 없다).

```
INFO  event=ADMIN_REGISTERED actorId={requesterId} targetId={createdAdminId} targetRole={role}
INFO  event=ADMIN_DELETED actorId={requesterId} targetId={targetAdminId}
```

지금 빠져 있는 예: 권한 없는 `actor`가 시도했을 때(`NOT_SUPER_ADMIN`으로 막히는 경우) 별도
WARN 로그가 없다 — 예외만 던지고 끝난다. 감사 목적상 "누가 권한 없는 액션을 시도했는지"도 로그로
남기고 싶다면 `event=ADMIN_ACTION_DENIED actorId={actorId} action=DELETE_ADMIN
reason=NOT_SUPER_ADMIN` 같은 로그를 추가하는 게 이 패턴에 맞는 확장이다.

**패턴 3 — 외부/보안 컨텍스트 이벤트.** 단순 식별자만으론 상황 파악이 안 되고, 판단 근거(사유,
토큰 인스턴스, 외부 시스템 식별자)가 같이 필요한 이벤트. 외부 시스템 식별자(카카오 `user_id` 등)는
그 자체로 우리 서비스 밖의 개인 식별 정보라 `PiiMasker.maskProviderId()`로 마스킹해서 담는다.

```
INFO  event=KAKAO_UNLINK_WEBHOOK userId={maskedUserId} referrerType={referrerType}
WARN  event=REFRESH_TOKEN_REUSE_SUSPECTED role={role} id={id} jti={jti}
WARN  event=KAKAO_UNLINK_WEBHOOK_AUTH_MISMATCH userId={maskedUserId}
ERROR event=KAKAO_UNLINK_WEBHOOK_PROCESSING_FAILED userId={maskedUserId}   # 예외 스택트레이스 포함
```

세 패턴 중 뭘 따라야 할지 헷갈리면: "이 로그를 볼 때 내가 누구에 대한 얘기인지 바로 알 수 있는가"를
기준으로 삼는다. 주체가 하나면 패턴 1, 행위자와 대상이 다르면 패턴 2, 식별자만으론 왜 이 로그가
찍혔는지 알 수 없고 사유/외부 식별자가 꼭 필요하면 패턴 3이다.

**PII(이메일/전화번호/이름 등)를 로그에 남겨야 하면 반드시 `PiiMasker`를 거친다.** 직접 정규식을
짜거나 문자열을 잘라서 마스킹하지 않는다 — 마스킹 로직이 여러 곳에 흩어지면 나중에 정책을 바꿀 때
일부만 반영되고 놓치는 곳이 생긴다. `HttpBodyLoggingFilter`도 원래 REDACTED 문자열을
직접 하드코딩했다가, `PiiMasker.redact()` 호출로 바꿔서 이 원칙을 스스로 지키도록 정리했다.

**토큰 원문은 절대 로그에 남기지 않는다.** `accessToken`/`refreshToken` 문자열 전체(서명 포함)는
그 자체로 인증 수단이라, 로그에 한 글자라도 남으면 그 로그를 볼 수 있는 사람 전부가 그 세션을
탈취할 수 있다. 반면 `jti`(토큰 인스턴스 식별자 클레임)는 서명이 없는 순수 라벨이라 그 자체로는
아무 권한도 증명하지 못하므로 평문으로 로그에 남겨도 안전하다 — `REFRESH_TOKEN_REUSE_SUSPECTED`
로그가 `jti`를 평문으로 남기는 이유(3.2 참고).

**비밀번호는 해시값조차 로그에 남기지 않는다.** bcrypt 해시라도 로그에 남을 이유가 없다.

### 2.4 새 기능 추가 시 체크리스트

1. 이 로직이 이미 있는 공통 필터/애스펙트로 커버되는가? (HTTP 요청/응답, 외부 API 호출, 스케줄러는
   보통 별도 로깅 코드가 필요 없다.)
2. 실패할 수 있는 지점(로그인, 외부 연동, 권한 상승/회수 액션)에는 성공 로그뿐 아니라 실패 로그도
   있는가 — 실패는 원인(reason)까지 구분해서 남긴다(2.1의 `ADMIN_LOGIN_FAILED` 예시처럼).
3. 로그에 넘기는 값 중 PII/토큰/비밀번호가 섞여 있지 않은가?
4. 로그 레벨이 2.2 기준에 맞는가?

---

## 3. 왜 이렇게 설계했나

### 3.1 hash / mask / encrypt, 언제 뭘 쓰나

이 셋은 목적이 다르다.

- **hash(해시)**: 원본을 복원할 필요가 없고 "이 값이 맞는지"만 확인하면 될 때. 비밀번호(bcrypt,
  느린 해시로 브루트포스 방어)와 refreshToken(SHA-256, 서명 자체가 고엔트로피라 느린 해시 불필요 +
  매 요청 검증이라 오히려 빨라야 함)에 사용.
- **mask(마스킹)**: 원본은 평문으로 저장/보관하되, 로그나 화면에 보여줄 때만 일부를 가림. 이메일/
  전화번호/이름 등 `PiiMasker`, `HttpBodyLoggingFilter`, 엔티티 `toString()`이 여기 해당.
- **encrypt(암호화)**: 나중에 원본이 다시 필요할 때만 쓴다. 현재 fresh-demo에는 이 케이스가 없다
  (이메일/전화번호/주소는 서비스 운영상 평문 조회가 필요해서 애초에 암호화 대상이 아니고, 마스킹만
  적용된다 — DB 자체는 평문 저장, 로그/응답 노출 시에만 마스킹).

### 3.2 jti는 왜 평문으로 로그에 남겨도 되는가

`jti`는 토큰의 "인스턴스 식별자"일 뿐, 그 자체로는 서명도 권한도 없는 라벨이다. 이 값만 가지고는
로그인도, 인증도 할 수 없다. 반면 refreshToken 원문 전체는 서명까지 포함된 완전한 인증 수단이라
로그에 남으면 안 된다. 이 구분(credential vs label) 때문에, RT 재사용 의심 로그에는 jti를 평문으로
남겨서 "정확히 어떤 토큰 인스턴스가 재사용됐는지" 추적할 수 있게 했다.

### 3.3 AT 무효화 체크는 왜 fail-open인가

`AccessTokenValidAfterRepository`(회원 탈퇴/RT 탈취 의심 시 accessToken을 즉시 무효화하는 장치)는
인증이 필요한 모든 요청마다 Redis를 확인해야 한다. 이 체크에는 DB 백업을 두지 않았다 — 매 요청마다
DB까지 보게 하면 그 자체가 성능 병목이 된다. 대신 Redis 장애 시엔 이 2차 방어선을 건너뛰고 요청을
통과시킨다(WARN 로그만 남김). Redis 순간 장애 하나 때문에 인증이 필요한 API 전체가 막히는 것보다,
그 순간만 이 방어선이 비활성화되는 쪽이 서비스 가용성 관점에서 낫다고 판단했다. (반면
refreshToken 저장소는 DB write-through 백업을 둔다 — 로그인/재발급 자체가 막히는 걸 더 심각하게
봤기 때문. 두 저장소가 다른 정책을 쓰는 이유이기도 하다.)

### 3.4 로컬/운영 로그 포맷이 다른 이유

로컬은 사람이 읽기 좋은 콘솔 텍스트(`%F:%line`로 IntelliJ 클릭 이동까지 지원), 운영은
`logstash-logback-encoder`로 JSON 구조화 로그를 남긴다 — ELK/Loki/CloudWatch 같은 수집기가
`event=`, `status=` 같은 key=value 필드를 그대로 JSON 키로 파싱해서 필터링/집계할 수 있어야
하기 때문이다. 운영은 `AsyncAppender`로 로깅 I/O가 요청 스레드를 막지 않게 하고, `%line` 같은
caller data는 계산 비용 때문에 운영에서는 끈다.

### 3.5 HTTP 접근 로그를 상태코드로 나눈 이유

정상 응답(2xx/3xx)은 상태코드+소요시간만 INFO로 남기고, 에러 응답(4xx/5xx)만 바디까지 남긴다.
매 요청마다 바디를 통째로 남기면 운영 환경에서 로그 볼륨이 감당이 안 되기 때문 — 반대로 에러는
원인 파악이 중요해서 바디까지 남긴다.

---

## 4. 더 자세한 설계 배경

이 문서는 "로그를 어떻게 쓸까"에 초점을 맞췄고, 인증/토큰/스키마 등 로깅 이외의 전체 설계 결정은
`docs/DESIGN_NOTES.md`(특히 8번 "로깅 & PII" 항목)에 더 상세히 정리돼 있다.
