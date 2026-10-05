# 인증 기능 설계 문서

- 문서 버전: v0.1
- 작성일: 2026-10-05
- 상태: 초안
- 요구사항: `docs/requirements/auth.md` (v0.2)
- 공통 설계: `docs/design/architecture.md` (v0.4)

---

## 1. 설계 개요

### 1.1 목적
운영자가 발급한 아이디·PIN 로그인, 한 기기 로그인, 사용할 때마다 연장되는 30일 로그인 유지, PIN 연속 실패 잠금을 API 3개와 테이블 2개로 구현하는 방법을 확정한다. 로그인 상태는 서버의 로그인 행으로 관리하고, 모든 인증 요청은 인증 필터가 이 행을 조회해 판단한다(공통 7.1).

### 1.2 설계 범위
- 포함: REQ-AUTH-001 ~ 003, 인증 필터(IF-AUTH-004), 운영자 SQL로 바뀌는 데이터에 대한 DB 보장(요구사항 8.2)
- 보류: 로그아웃 메뉴가 있는 화면과의 연계 (요구사항 TODO-005 → D-TODO-AUTH-001). 로그아웃 API는 설계한다.
- 제외: 요구사항 8.1의 범위 밖 항목(가입, PIN 변경·재설정, 운영자 화면·API, 여러 기기 동시 로그인 등). 폐기된 BR-004는 설계하지 않는다.

### 1.3 대상 시스템
- Backend API: 신규 도메인 `auth`(로그인 API, 로그인 서비스), 공통 인증 필터
- Database: `users`(계정), 신규 `login_session`(로그인), PIN 변경 시 로그인을 끝내는 DB 자동 동작
- Mobile App: 로그인 화면 AUTH-001, 앱 시작 시 로그인 유지 확인, 401 사유별 안내
- 운영자: DB에 직접 SQL로 계정 관리 (공통 7.7, 10.7)

### 1.4 기술 스택
공통 설계 1.4를 따른다. 이 기능에 새로 필요한 기술 능력:
| 필요한 능력 | 용도 | 이유 |
|-----------|-----|-----|
| 암호학적 난수 생성 | 로그인 토큰 | 추측할 수 없는 토큰이 필요하다 (NFR-SEC-001) |
| 단방향 해시 | 로그인 토큰을 서버에 해시로만 저장 | DB가 유출돼도 토큰을 쓸 수 없게 한다 |
| DB 자동 동작 | PIN 변경 시 로그인 종료 | 운영자 SQL 변경에도 BR-011을 지킨다 (DEC-AUTH-008) |

### 1.5 설계 원칙
1. **운영자 SQL도 규칙을 지키게:** 계정은 애플리케이션이 아니라 운영자 SQL로 바뀐다. 아이디·PIN 형식, 유일성, PIN 변경 시 로그인 종료, 계정 삭제 시 데이터 삭제는 DB 제약과 자동 동작으로 보장한다.
2. **실패 기록은 반드시 남긴다:** 로그인 실패 응답이어도 실패 횟수와 잠금은 확정한다(확정 후 오류 응답, DEC-AUTH-005).
3. **로그인 행 하나가 상태의 출처:** 로그인 여부·만료·종료 사유는 모두 로그인 행에서 판단한다.

### 1.6 요구사항 ↔ 설계 추적표
| 요구사항 ID | 요구사항 | 설계 반영 위치 | 구현 | 테스트 |
|------------|---------|--------------|-----|-------|
| REQ-AUTH-001 | 로그인 | 3.2, API-AUTH-001 | | |
| REQ-AUTH-002 | 로그아웃 | 3.2, API-AUTH-002 | | |
| REQ-AUTH-003 | 로그인 유지 | 3.2 인증 필터, API-AUTH-003 | | |
| BR-001 | 계정은 운영자만 생성 | 3.5, 가입 API 없음, users.id DB 생성 (DEC-AUTH-001) | | |
| BR-002 | 아이디 유일·형식·정확히 일치 | 3.5, ux_users_login_id, ck_users_login_id_format | | |
| BR-003 | PIN 숫자 6자리 | 3.5, 5.3 Validation, ck_users_pin_format | | |
| BR-005 | 한 계정 한 기기 | 3.5, ux_login_session_user_active | | |
| BR-006 | 마지막 사용 후 30일 만료, 사용 시 연장 | 3.2 인증 필터, DEC-AUTH-003, DEC-AUTH-004 | | |
| BR-007 | 5회 연속 실패 시 5분 잠금 | 3.2, 3.5, users.failed_pin_count·locked_until | | |
| BR-008 | 성공·잠금 해제 후 0부터 | 3.5, DEC-AUTH-006 | | |
| BR-009 | 잠금 중 올바른 PIN도 거절 | 3.2, 3.5 | | |
| BR-010 | 아이디·PIN 중 무엇이 틀렸는지 숨김 | 3.5, AUTH_INVALID_CREDENTIALS | | |
| BR-011 | PIN 재발급·계정 삭제 시 즉시 종료 | 3.5, trg_users_pin_revoke_login, 참조(함께 삭제) | | |
| BR-012 | 계정 삭제 시 모든 데이터 삭제 | 3.5, 6.4, 공통 6.1 사용자 소유 데이터 규칙 | | |
| ERR-001 | 아이디 또는 PIN 틀림 | 8.2 AUTH_INVALID_CREDENTIALS | | |
| ERR-002 | PIN 형식 오류 | 8.2 VALIDATION_FAILED | | |
| ERR-003 | 아이디·PIN 누락 | 8.2 VALIDATION_FAILED | | |
| ERR-004 | 잠금 상태 로그인 | 8.2 AUTH_ACCOUNT_LOCKED | | |
| ERR-005 | 만료된 로그인 | 8.2 AUTH_SESSION_EXPIRED | | |
| ERR-006 | 다른 기기 로그인으로 종료 | 8.2 AUTH_SESSION_REPLACED | | |
| ERR-007 | 로그인 없이 요청 | 8.2 UNAUTHORIZED | | |
| ERR-008 | 재발급·삭제로 종료 | 8.2 AUTH_SESSION_REVOKED, UNAUTHORIZED | | |
| NFR-SEC-001 | 요청마다 로그인 확인 | 3.2 인증 필터, 7.1 | | |
| NFR-SEC-002 | 보호된 통신 | 7.3, 공통 2.4 | | |
| NFR-SEC-003 | 기기 보관 정보 보호 | 4.2, 공통 7.1 | | |
| NFR-SEC-004 | PIN 노출 금지 | 7.3, 9장 | | |
| NFR-PERF-001 | p95 500ms | 9장, 6.3 | | |
| NFR-AVAIL-001 | 실패 횟수 일부 반영 금지 | 3.2, DEC-AUTH-005 | | |
| NFR-LOG-001 | 인증 이벤트 추적 | 9장 로그 이벤트 | | |
| IF-AUTH-001 | 아이디·PIN 로그인 | API-AUTH-001 | | |
| IF-AUTH-002 | 로그아웃 | API-AUTH-002 | | |
| IF-AUTH-003 | 로그인 확인·연장 | API-AUTH-003, 인증 필터 | | |
| IF-AUTH-004 | 요청 사용자 확인 | 3.2 인증 필터 | | |
| DATA-001 | 계정 | 6.2 users | | |
| DATA-002 | 로그인 | 6.2 login_session | | |
| AUTH-001 | 로그인 화면 | 4.2 | | |

---

## 2. 시스템 아키텍처
공통 설계 2장, 7.1을 따른다. 이 기능에서 추가되는 것:

- **인증 필터(LoginTokenFilter)**: 공개 경로를 뺀 모든 `/api/**` 요청에서 로그인 토큰으로 로그인 행을 조회·검증하고, 유지 기간을 연장한다. 조회·연장은 **독립 트랜잭션**으로 먼저 확정한다(뒤이은 요청 처리가 실패해도 "사용했다"는 사실은 남는다).
- **DB 자동 동작**: `users.pin`이 바뀌면 DB가 그 계정의 활성 로그인을 끝낸다(BR-011).
- **운영자**: DB에 SQL로 계정을 만들고 바꾼다(공통 10.7).

```
App ──login──▶ AuthController ─▶ LoginService ─▶ users(변경 잠금) / login_session
App ──API────▶ LoginTokenFilter ─▶ LoginSessionService(독립 트랜잭션: 조회·연장) ─▶ login_session
                    │ 통과(userId)
                    ▼
               각 기능의 API 진입점
운영자 ──SQL──▶ users ──(PIN 변경: DB 자동 동작)──▶ login_session.status = REVOKED
                     └─(삭제: 참조 함께 삭제)──▶ login_session, workout_session …
```

---

## 3. 기능 설계

### 3.1 기능 목록
| 요구사항 ID | 기능 | API | 주요 컴포넌트 |
|------------|-----|-----|-------------|
| REQ-AUTH-001 | 로그인 | API-AUTH-001 | LoginService.login |
| REQ-AUTH-002 | 로그아웃 | API-AUTH-002 | LoginService.logout |
| REQ-AUTH-003 | 로그인 유지 (모든 인증 요청) | (공통 인증 필터) | LoginTokenFilter, LoginSessionService.authenticate |
| REQ-AUTH-003 | 로그인 유지 확인 (앱 시작) | API-AUTH-003 | AuthController |

### 3.2 기능별 처리 흐름

#### REQ-AUTH-001 로그인 (API-AUTH-001)
1. 요청 검증: `loginId` 필수(공백 아님, 최대 100자), `pin` 필수·숫자 6자리. 위반 시 400 `VALIDATION_FAILED`. 실패 횟수에 넣지 않는다 (BR-003, ERR-002, ERR-003).
2. 트랜잭션 시작. `users`를 `login_id`가 **정확히 일치**하는 행으로 변경 잠금 조회한다 (BR-002). 같은 계정의 로그인 시도는 하나씩 처리된다 (NFR-AVAIL-001).
3. 없으면 401 `AUTH_INVALID_CREDENTIALS` (ERR-001, BR-010).
4. `locked_until`이 현재 시각보다 뒤면 401 `AUTH_ACCOUNT_LOCKED`, `details.retryAt` = `locked_until`. 실패 횟수는 바꾸지 않는다 (BR-009, ERR-004).
   `locked_until`이 현재 시각 이전이면 잠금이 풀린 것으로 보고 `locked_until`을 비운다.
5. PIN을 비교한다(걸리는 시간이 값에 따라 달라지지 않는 방식, 공통 7.5).
6. **틀리면**: `failed_pin_count`를 1 늘린다. 5가 되면 `locked_until` = 현재 시각 + 5분, `failed_pin_count` = 0 (BR-007, BR-008, DEC-AUTH-006). **확정 후 오류 응답**으로 커밋한 뒤 401 `AUTH_INVALID_CREDENTIALS` (ERR-001). 로그 `auth.login_failed`, 잠갔으면 `auth.account_locked`.
7. **맞으면**:
   1. `failed_pin_count` = 0, `locked_until` = 없음 (BR-008).
   2. 이 계정의 끝난 로그인 행 중 `ended_at`이 30일보다 오래된 것을 삭제한다 (DEC-AUTH-002).
   3. 이 계정의 `ACTIVE` 로그인 행이 있으면 끝낸다: `last_used_at` + 30일이 지났으면 `EXPIRED`, 아니면 `REPLACED`, `ended_at` = 현재 시각 (BR-005). `REPLACED`면 로그 `auth.session_replaced`.
   4. 로그인 토큰을 만들고(공통 7.1), 토큰 해시로 `ACTIVE` 로그인 행을 저장한다(`created_at` = `last_used_at` = 현재 시각).
   5. 동시에 두 로그인이 들어와도 2의 변경 잠금으로 하나씩 처리된다. 그래도 조건부 유일 제약 `ux_login_session_user_active`가 마지막 보장이다.
   6. 커밋, 로그 `auth.login_succeeded`, 200과 토큰.

#### 인증 필터 — REQ-AUTH-003 로그인 유지, IF-AUTH-004, NFR-SEC-001
공개 경로(API-AUTH-001, API-AUTH-002)를 뺀 모든 `/api/**` 요청에 적용한다.
1. `Authorization: Bearer <토큰>`이 없거나 형식이 틀리면 401 `UNAUTHORIZED` (ERR-007).
2. 독립 트랜잭션에서 토큰 해시로 `login_session`을 조회한다. 없으면 401 `UNAUTHORIZED` (ERR-007 — 로그아웃한 토큰, 계정이 삭제된 토큰 포함, 부록 C-2).
3. 상태별 판단:
   - `REPLACED` → 401 `AUTH_SESSION_REPLACED` (ERR-006)
   - `REVOKED` → 401 `AUTH_SESSION_REVOKED` (ERR-008)
   - `EXPIRED`, 또는 `ACTIVE`인데 `last_used_at` + 30일 ≤ 현재 시각 → 401 `AUTH_SESSION_EXPIRED` (ERR-005, BR-006)
4. 유효한 `ACTIVE`면 조건부 일괄 갱신으로 `last_used_at` = 현재 시각 (조건: 그 행이 아직 `ACTIVE`). 갱신된 행이 0이면(그 사이 다른 기기 로그인·재발급) 다시 조회해 3을 따른다 (BR-006 연장).
5. 확정하고, 로그인 행의 `user_id`를 요청의 사용자 ID로 정한다.

#### REQ-AUTH-003 로그인 유지 확인 (API-AUTH-003)
1. 인증 필터가 1~5를 수행한다(여기서 유지 기간도 연장된다).
2. 204를 반환한다. 앱은 이 응답으로 로그인 화면 없이 홈으로 간다.

#### REQ-AUTH-002 로그아웃 (API-AUTH-002)
1. 공개 경로다. 인증 필터를 거치지 않는다(이미 끝난 로그인으로도 로그아웃할 수 있어야 하므로).
2. `Authorization: Bearer <토큰>`이 있으면 토큰 해시로 로그인 행을 찾아 삭제한다. 삭제했으면 로그 `auth.logged_out`.
3. 토큰이 없거나, 모르는 토큰이거나, 이미 끝난 로그인이어도 204 (REQ-AUTH-002 "이미 끝난 로그인도 성공").

### 3.3 주요 시나리오 — 다른 기기 로그인으로 기존 기기가 로그아웃됨
```
기기 A                 API                                  DB
 │ (로그인 중)           │                                     │
                기기 B ─▶ POST /auth/login                      │
                        │ users 변경 잠금, PIN 확인              │
                        │ A의 로그인 행 → REPLACED ─────────────▶│
                        │ B의 로그인 행 ACTIVE 저장 ────────────▶│
                기기 B ◀─ 200 토큰                               │
 │ GET /workout-sessions │                                     │
 │──────────────────────▶│ 인증 필터: A 토큰 조회 → REPLACED ───▶│
 │◀── 401 AUTH_SESSION_REPLACED                                 │
 │ 토큰 삭제, 로그인 화면("다른 기기에서 로그인되어 로그아웃되었습니다")
```

### 3.4 상태 변화

**로그인 행 (login_session.status)**
```
            로그인 성공
                │
                ▼
            [ACTIVE] ──같은 계정이 다른 기기에서 로그인──▶ [REPLACED]
              │  │  └──운영자 PIN 변경(DB 자동 동작)──────▶ [REVOKED]
              │  └──30일 미사용 후 다음 로그인 시 정리─────▶ [EXPIRED]
              └──로그아웃──▶ (삭제)
  끝난 행(REPLACED/REVOKED/EXPIRED)은 ended_at 30일 후, 그 계정의 다음 로그인 때 삭제
  계정 삭제 시 모든 행 삭제(참조 함께 삭제)
```
`ACTIVE`이면서 `last_used_at` + 30일이 지난 행은 저장된 상태와 관계없이 만료로 판단한다(DEC-AUTH-003).

**계정 잠금 (users)**
| 상태 | 조건 | 로그인 |
|-----|-----|-------|
| 정상 | `locked_until` 없음 또는 지남 | 시도 가능 |
| 잠금 | `locked_until` > 현재 시각 | 올바른 PIN도 401 AUTH_ACCOUNT_LOCKED |

### 3.5 비즈니스 규칙 구현
| BR ID | 규칙 | 강제 위치 | 방법 | 위반 시 |
|-------|-----|----------|-----|--------|
| BR-001 | 계정은 운영자만 생성 | API 설계, DB | 가입·계정 생성 API를 두지 않는다. 애플리케이션은 `users`를 만들지 않는다(`users.id`는 DB 생성, 공통 DEC-ARCH-012) | — |
| BR-002 | 아이디 유일, `이름.성[번호]`, 정확히 일치 | DB, 서비스 | 유일 `ux_users_login_id`(대소문자 구분), 조건 검사 `ck_users_login_id_format`, 로그인 조회는 정확히 일치 비교 | 운영자 SQL 거절 / 로그인 시 일치하지 않으면 ERR-001 |
| BR-003 | PIN 숫자 6자리 | 요청 검증, DB | 요청 검증(숫자 6자리) + 조건 검사 `ck_users_pin_format` | 400 VALIDATION_FAILED / 운영자 SQL 거절 |
| BR-005 | 한 계정 한 기기 | 서비스, DB | 로그인 성공 시 기존 `ACTIVE` 행 종료 + 조건부 유일 `ux_login_session_user_active` | — |
| BR-006 | 마지막 사용 후 30일, 사용 시 연장 | 인증 필터 | `last_used_at` + 30일로 만료 판단, 인증 요청마다 `last_used_at` 갱신 | 401 AUTH_SESSION_EXPIRED |
| BR-007 | 5회 연속 실패 → 5분 잠금 | 서비스, DB | 계정 변경 잠금 후 실패 횟수 증가, 5회면 `locked_until` 설정. 조건 검사 `ck_users_failed_pin_count`(0~4) | 다음 시도부터 401 AUTH_ACCOUNT_LOCKED |
| BR-008 | 성공·잠금 해제 후 0부터 | 서비스 | 성공 시 0, 잠글 때 0으로 되돌려 저장(DEC-AUTH-006) | — |
| BR-009 | 잠금 중 올바른 PIN도 거절 | 서비스 | PIN 비교 전에 잠금 확인 | 401 AUTH_ACCOUNT_LOCKED |
| BR-010 | 무엇이 틀렸는지 숨김 | 서비스 | 없는 아이디와 틀린 PIN에 같은 에러 코드·메시지 | 401 AUTH_INVALID_CREDENTIALS |
| BR-011 | PIN 재발급·계정 삭제 시 즉시 종료 | DB | DB 자동 동작 `trg_users_pin_revoke_login`(PIN 변경 → 활성 로그인 `REVOKED`), 계정 삭제 → 참조(함께 삭제)로 로그인 행 삭제 | 401 AUTH_SESSION_REVOKED / UNAUTHORIZED |
| BR-012 | 계정 삭제 시 모든 데이터 삭제 | DB | 사용자 소유 데이터는 모두 `users.id`를 참조(함께 삭제) (공통 6.1). 운동 기록은 workout-record 설계 6.2 | — |

### 3.6 기능 간 의존관계
- 모든 기능(운동 기록 포함)의 인증은 이 문서의 인증 필터에 의존한다.
- 인증 필터는 로그인 행이 있어야 동작한다. 로그인 API(REQ-AUTH-001)보다 먼저 구현하는 경우, 테스트는 픽스처로 로그인 행을 만든다(공통 7.6).
- 운동 기록 테이블의 `user_id` 참조는 함께 삭제여야 한다(BR-012, workout-record 설계 v0.4).

---

## 4. 화면 / API 연계 설계
요구사항 3장의 화면 정의를 근거로 한다. 공통 응답 처리는 공통 설계 4장을 따른다.

### 4.1 화면-API 매핑
| 화면 ID | 화면 | 사용자 행동 | API |
|--------|-----|-----------|-----|
| (앱 시작) | — | 앱 실행 | API-AUTH-003 `GET /api/v1/auth/session` (저장된 토큰이 있을 때) |
| AUTH-001 | 로그인 | 로그인 | API-AUTH-001 `POST /api/v1/auth/login` |
| 미정 (D-TODO-AUTH-001) | — | 로그아웃 | API-AUTH-002 `POST /api/v1/auth/logout` |

### 4.2 화면별 연계 상세

#### (앱 시작) 로그인 유지 확인
- 진입 조건: 앱 실행
- 필요 데이터: 기기 보안 저장소의 토큰(공통 7.1, NFR-SEC-003)
- API 호출: 토큰이 있으면 API-AUTH-003. 없으면 호출 없이 AUTH-001
- 성공 처리: 204 → 홈(workout-record WO-001)
- 실패 처리: 401 → 토큰 삭제 후 AUTH-001로 이동하면서 에러 코드를 넘긴다(AUTH-001에서 사유 안내) / 네트워크 오류 → 재시도 안내(토큰은 지우지 않음)
- 로딩 상태: 시작 화면 유지

#### AUTH-001 로그인
- 진입 조건: 로그인 상태가 아님
- 필요 데이터: 진입 사유 에러 코드(있으면)
- 사용자 입력: 아이디(최대 100자), PIN(숫자 6자리, 화면에 숫자를 그대로 보이지 않음). 앱에서도 PIN 형식을 미리 검증
- API 호출: 로그인 시 API-AUTH-001
- 성공 처리: 200 → 토큰을 기기 보안 저장소에 저장하고 홈(workout-record WO-001)
- 실패 처리 (에러 코드별 안내):
  - `AUTH_INVALID_CREDENTIALS` → "아이디 또는 PIN이 올바르지 않습니다." PIN 칸 비우기
  - `AUTH_ACCOUNT_LOCKED` → "PIN을 5회 잘못 입력해 잠겼습니다. {details.retryAt 현지 시각} 이후 다시 시도하세요."
  - `VALIDATION_FAILED` → 해당 입력 칸에 사유
- 진입 사유 안내: `AUTH_SESSION_EXPIRED` → "오랫동안 사용하지 않아 로그아웃되었습니다." / `AUTH_SESSION_REPLACED` → "다른 기기에서 로그인되어 로그아웃되었습니다." / `AUTH_SESSION_REVOKED`, `UNAUTHORIZED` → "다시 로그인해 주세요."
- 로딩 상태: 요청 중 로그인 버튼 비활성
- 빈 상태: 해당 없음

#### 로그아웃 (화면 미정)
- API 호출: API-AUTH-002 (저장된 토큰을 헤더에 넣어)
- 처리: 응답과 관계없이(204, 네트워크 오류 포함) 기기의 토큰을 지우고 AUTH-001
- 메뉴 위치는 D-TODO-AUTH-001

---

## 5. API 설계
URL·필드 규칙은 공통 설계 5장을 따른다.

### 5.1 API 목록
| API ID | Method | URL | 인증 | 설명 | 관련 요구사항 |
|--------|--------|-----|-----|-----|-------------|
| API-AUTH-001 | POST | /api/v1/auth/login | 불필요 | 로그인 | REQ-AUTH-001, IF-AUTH-001 |
| API-AUTH-002 | POST | /api/v1/auth/logout | 불필요 (토큰이 있으면 그 로그인을 끝냄) | 로그아웃 | REQ-AUTH-002, IF-AUTH-002 |
| API-AUTH-003 | GET | /api/v1/auth/session | 필요 | 로그인 유지 확인·연장 | REQ-AUTH-003, IF-AUTH-003 |

### 5.2 공통 응답 모델
해당 없음 — 성공 응답은 API-AUTH-001의 토큰뿐이다.

### 5.3 API 상세

#### API-AUTH-001 로그인
- 목적: 아이디·PIN을 확인하고 이 기기의 로그인을 시작한다.
- Method / URL: `POST /api/v1/auth/login`
- 인증: 불필요
- 관련 요구사항: REQ-AUTH-001, IF-AUTH-001, BR-002, BR-003, BR-005, BR-007 ~ BR-010

Request
```json
{ "loginId": "joonhee.song", "pin": "123456" }
```

Response `200 OK`
```json
{ "token": "q3Vh8sK1pXz0bN7mR2tY5wA9cE4fG6jL8nP0sU3vX1y" }
```

Validation
| 필드 | 타입 | 필수 | 규칙 | 근거 |
|-----|-----|-----|-----|-----|
| loginId | string | Y | 공백 아님, 최대 100자. 형식 검사는 하지 않는다(틀린 형식은 없는 아이디와 같게 처리, BR-010) | ERR-003, BR-002 |
| pin | string | Y | 숫자 6자리(`000000`~`999999`). 숫자형 JSON이 아니라 문자열(앞자리 0 보존) | BR-003, ERR-002 |

Errors
| HTTP | 에러 코드 | 조건 | 관련 |
|------|----------|-----|-----|
| 400 | VALIDATION_FAILED | 아이디·PIN 누락, PIN 형식 오류 | ERR-002, ERR-003 |
| 401 | AUTH_INVALID_CREDENTIALS | 없는 아이디 또는 틀린 PIN | ERR-001 |
| 401 | AUTH_ACCOUNT_LOCKED | 잠금 중. `details.retryAt` | ERR-004 |

#### API-AUTH-002 로그아웃
- 목적: 이 기기의 로그인을 끝낸다.
- Method / URL: `POST /api/v1/auth/logout`
- 인증: 불필요. `Authorization: Bearer <토큰>`이 있으면 그 로그인을 끝낸다
- 관련 요구사항: REQ-AUTH-002, IF-AUTH-002

Request: Body 없음

Response `204 No Content` — 항상(토큰 없음, 모르는 토큰, 이미 끝난 로그인 포함)

Validation: 해당 없음

Errors: 없음 (500 제외)

#### API-AUTH-003 로그인 유지 확인
- 목적: 앱 시작 시 로그인이 유효한지 확인하고 유지 기간을 연장한다.
- Method / URL: `GET /api/v1/auth/session`
- 인증: 필요
- 관련 요구사항: REQ-AUTH-003, IF-AUTH-003, BR-005, BR-006, BR-011

Request: 없음

Response `204 No Content` — 유효한 로그인(유지 기간 연장됨)

Validation: 해당 없음

Errors
| HTTP | 에러 코드 | 조건 | 관련 |
|------|----------|-----|-----|
| 401 | UNAUTHORIZED | 토큰 없음·모르는 토큰(로그아웃, 계정 삭제 포함) | ERR-007, ERR-008 |
| 401 | AUTH_SESSION_EXPIRED | 마지막 사용 후 30일 경과 | ERR-005 |
| 401 | AUTH_SESSION_REPLACED | 다른 기기 로그인으로 종료 | ERR-006 |
| 401 | AUTH_SESSION_REVOKED | PIN 재발급으로 종료 | ERR-008 |

인증 필터가 쓰는 위 4개 401 응답은 인증이 필요한 **모든 API**(운동 기록 포함)에 똑같이 적용된다.

---

## 6. 데이터 설계
이름 규칙·논리 타입·제약 종류는 공통 설계 6장을 따른다.

### 6.1 ERD
```
users 1 ──── N login_session          (참조: 함께 삭제)
  1
  └──── N workout_session …           (참조: 함께 삭제, workout-record 설계)
```

### 6.2 테이블 정의

#### users — 근거: DATA-001
| 컬럼 | 타입 | Null | 기본값 | 설명 | 근거 |
|-----|-----|------|-------|-----|-----|
| id | ID | N | DB 생성 (공통 DEC-ARCH-012) | PK | BR-001 |
| login_id | 문자열(100) | N | | 아이디. 대소문자 구분 | BR-002 |
| pin | 문자열(6) | N | | PIN 평문 | BR-003, 요구사항 8.2 |
| failed_pin_count | 정수 | N | 0 | 연속 실패 횟수 | BR-007, BR-008 |
| locked_until | 시각 | Y | | 잠금이 풀리는 시각. 잠금이 아니면 없음 | BR-007, ERR-004 |
| created_at | 시각 | N | 현재 시각 | 발급 시각 | DATA-001 |
| updated_at | 시각 | N | 현재 시각 | | 공통 |

- PK: `id`
- 유일 `ux_users_login_id`: `login_id` (대소문자 구분) — BR-002
- 조건 검사 `ck_users_login_id_format`: `login_id`가 패턴 `^[A-Za-z]+\.[A-Za-z]+([2-9]|[1-9][0-9]+)?$`와 일치 — `이름.성`, 중복 번호는 2부터 (BR-002, 부록 C-3)
- 조건 검사 `ck_users_pin_format`: `pin`이 패턴 `^[0-9]{6}$`와 일치 — BR-003
- 조건 검사 `ck_users_failed_pin_count`: `0 <= failed_pin_count <= 4` — 5회째에 잠그면서 0으로 되돌리므로 5 이상은 저장되지 않는다 (BR-007, DEC-AUTH-006)
- DB 자동 동작 `trg_users_pin_revoke_login`: `pin` 값이 바뀌면, 그 사용자의 `status = ACTIVE`인 `login_session`을 `status = REVOKED`, `ended_at` = 현재 시각으로 바꾼다 (BR-011, DEC-AUTH-008)

#### login_session — 근거: DATA-002
| 컬럼 | 타입 | Null | 기본값 | 설명 | 근거 |
|-----|-----|------|-------|-----|-----|
| id | ID | N | 애플리케이션 생성 | PK | 공통 DEC-ARCH-010 |
| user_id | ID | N | | 계정 | DATA-002 |
| token_hash | 문자열(64) | N | | 로그인 토큰의 해시(공통 10.1). 토큰 원문은 저장하지 않는다 | NFR-SEC-001 |
| status | 열거(ACTIVE, REPLACED, REVOKED, EXPIRED) | N | | 로그인 상태, 끝난 사유 | ERR-005, ERR-006, ERR-008 |
| created_at | 시각 | N | 현재 시각 | 로그인한 시각 | DATA-002 |
| last_used_at | 시각 | N | 현재 시각 | 마지막 사용 시각. 만료 = 이 값 + 30일 | BR-006 |
| ended_at | 시각 | Y | | 끝난 시각. `ACTIVE`면 없음 | DEC-AUTH-002 |

- PK: `id`
- 참조(함께 삭제): `user_id → users.id` — 계정 삭제 시 로그인도 삭제 (BR-011, BR-012)
- 유일 `ux_login_session_token_hash`: `token_hash` — 토큰으로 로그인 조회
- 조건부 유일 `ux_login_session_user_active`: `status = ACTIVE`인 행 사이에서 `user_id` 유일 — BR-005를 동시 로그인에서도 보장
- 조건 검사 `ck_login_session_ended`: `(status = ACTIVE 이고 ended_at 없음) 또는 (status ≠ ACTIVE 이고 ended_at 있음)`
- 수정되는 컬럼이 `status`, `last_used_at`, `ended_at`뿐이고 각각 의미 있는 시각을 가지므로 `updated_at`을 두지 않는다.

### 6.3 인덱스
| 인덱스 | 테이블 | 컬럼 | 대상 조회 | 근거 |
|-------|-------|-----|---------|-----|
| ux_users_login_id | users | (login_id), 유일 | 로그인 시 계정 조회 | REQ-AUTH-001, BR-002 |
| ux_login_session_token_hash | login_session | (token_hash), 유일 | 인증 필터의 요청마다 로그인 조회 | NFR-SEC-001, NFR-PERF-001 |
| ux_login_session_user_active | login_session | (user_id), 조건부 유일: status = ACTIVE | 로그인 시 기존 활성 로그인 조회, DB 자동 동작의 대상 조회 | BR-005, BR-011 |
| idx_login_session_user | login_session | (user_id, ended_at) | 로그인 시 30일 지난 끝난 행 삭제, 계정 삭제 시 함께 삭제 | DEC-AUTH-002, BR-012 |

### 6.4 삭제 정책
- `users` 삭제(운영자 SQL) → `login_session`과 모든 사용자 소유 데이터(운동 기록 등)가 참조(함께 삭제)로 같은 문장 안에서 삭제된다 (BR-012). 되돌릴 수 없다.
- `login_session`: 로그아웃 시 삭제. 끝난 행은 `ended_at` 30일 뒤, 그 계정의 다음 로그인 때 삭제(DEC-AUTH-002).

### 6.5 스키마 변경 목록
운동 기록 스키마보다 먼저 적용한다(운동 기록이 `users`를 참조).

| 순서 | 변경 | 내용 |
|-----|-----|-----|
| 1 | users 생성 | (필요하면 UUIDv7 생성 함수, 공통 10.1) `users` 테이블과 6.2의 제약 |
| 2 | login_session 생성 | `login_session` 테이블, 6.2의 제약, 6.3의 인덱스, DB 자동 동작 `trg_users_pin_revoke_login` |

---

## 7. 인증 / 인가 및 보안 설계
공통 설계 7장을 따른다.

### 7.1 API별 인증
- 공개 경로(인증 필터 미적용): API-AUTH-001 로그인, API-AUTH-002 로그아웃
- 그 밖의 모든 `/api/**`는 인증 필요 (NFR-SEC-001)

### 7.2 사용자별 데이터 접근 제한
- 로그아웃은 요청한 토큰의 로그인 행만 지운다. 다른 로그인 행에는 접근하지 않는다.
- 이 기능에는 사용자 ID를 입력으로 받는 API가 없다.

### 7.3 민감 데이터 / 로그
- PIN: 요청 본문 외에는 어디에도 남기지 않는다. 로그인 요청 본문은 로그에 남기지 않고, 에러 응답·메시지에도 넣지 않는다 (NFR-SEC-004).
- 로그인 토큰: 응답 본문으로 한 번만 준다. 서버에는 해시만 저장하고 로그에 남기지 않는다.
- 통신: HTTPS만 (NFR-SEC-002, 공통 2.4).
- 평문 PIN 보완책은 공통 7.7(DB 접근 최소화, 백업 암호화).

---

## 8. 예외 / 에러 처리 설계
응답 형식·상태 코드 정책·공통 에러 코드는 공통 설계 8장을 따른다.

### 8.1 에러 응답 예
```json
{
  "code": "AUTH_ACCOUNT_LOCKED",
  "message": "PIN을 5회 잘못 입력해 로그인이 잠겼습니다.",
  "timestamp": "2026-10-05T18:00:00Z",
  "details": { "retryAt": "2026-10-05T18:05:00Z" }
}
```

### 8.2 이 기능의 에러 코드
| 요구사항 ERR | 에러 코드 | HTTP | 메시지 | details | 발생 위치 |
|-------------|----------|------|-------|--------|----------|
| ERR-001 | AUTH_INVALID_CREDENTIALS | 401 | 아이디 또는 PIN이 올바르지 않습니다. | — | LoginService.login |
| ERR-002 | VALIDATION_FAILED (공통) | 400 | 입력값이 올바르지 않습니다. | — | 요청 검증 |
| ERR-003 | VALIDATION_FAILED (공통) | 400 | 입력값이 올바르지 않습니다. | — | 요청 검증 |
| ERR-004 | AUTH_ACCOUNT_LOCKED | 401 | PIN을 5회 잘못 입력해 로그인이 잠겼습니다. | `retryAt`: 잠금이 풀리는 시각 | LoginService.login |
| ERR-005 | AUTH_SESSION_EXPIRED | 401 | 오랫동안 사용하지 않아 로그아웃되었습니다. 다시 로그인해 주세요. | — | 인증 필터 |
| ERR-006 | AUTH_SESSION_REPLACED | 401 | 다른 기기에서 로그인되어 로그아웃되었습니다. | — | 인증 필터 |
| ERR-007 | UNAUTHORIZED (공통) | 401 | 로그인이 필요합니다. | — | 인증 필터 |
| ERR-008 | AUTH_SESSION_REVOKED | 401 | 다시 로그인해 주세요. | — | 인증 필터 (PIN 재발급) |
| ERR-008 | UNAUTHORIZED (공통) | 401 | 로그인이 필요합니다. | — | 인증 필터 (계정 삭제, 부록 C-2) |

제약 위반 변환 대상: `ux_login_session_user_active` 위반은 정상 흐름에서 생기지 않는다(계정 변경 잠금으로 직렬화). 생기면 버그이므로 500으로 둔다.

---

## 9. 비기능 요구사항 설계
| NFR ID | 요구사항 | 설계 대응 | 확인 방법 |
|--------|---------|----------|----------|
| NFR-SEC-001 | 요청마다 로그인 확인 | 인증 필터가 공개 경로 외 모든 `/api/**`에 적용. 토큰은 256비트 난수, 서버에는 해시만 | 모든 인증 API에 토큰 없음·모르는 토큰·종료 토큰 401 테스트 |
| NFR-SEC-002 | 보호된 통신 | HTTPS만 (공통 2.4) | 배포 환경 점검 (D-TODO-ARCH-002) |
| NFR-SEC-003 | 기기 보관 정보 보호 | 앱은 토큰을 기기 보안 저장소에 보관 (공통 7.1) | 앱 구현 점검 |
| NFR-SEC-004 | PIN 노출 금지 | 로그인 요청 본문 로그 금지, 에러 메시지에 입력값 미포함 | 로그 출력에 PIN이 없는지 테스트 |
| NFR-PERF-001 | p95 500ms | 로그인: 계정 조회 1회 + 갱신 몇 회. 인증 필터: 유일 인덱스 조회 1회 + 갱신 1회 | 부하 테스트 (D-TODO-ARCH-004) |
| NFR-AVAIL-001 | 실패 횟수 일부 반영 금지 | 계정 변경 잠금으로 시도를 하나씩 처리, 확정 후 오류 응답(DEC-AUTH-005) | 같은 계정에 틀린 PIN 10회 동시 요청 → 잠금 1회, 실패 횟수 일관 테스트 |
| NFR-LOG-001 | 인증 이벤트 추적 | INFO 이벤트: `auth.login_succeeded`, `auth.login_failed`, `auth.account_locked`, `auth.logged_out`, `auth.session_replaced` (userId, 로그인 행 id. 없는 아이디 실패는 userId 없음) | 로그 출력 확인 테스트 |

---

## 10. 구현 구조 및 개발 전략

### 10.1 컴포넌트 구성
| 도메인 | 컴포넌트 | 역할 | 책임 |
|-------|---------|-----|-----|
| auth | AuthController | API 진입점 | API-AUTH-001 ~ 003 |
| auth | LoginService | 서비스 | 로그인(계정 변경 잠금, 잠금·실패 처리, 기존 로그인 종료, 토큰 발급), 로그아웃. 로그인 실패는 결과 값으로 돌려 확정 후 오류 응답 |
| auth | LoginSessionService | 서비스 (독립 트랜잭션) | 인증 필터의 토큰 조회·상태 판단·유지 기간 연장 |
| common | LoginTokenFilter | 인증 필터 | 토큰 추출, LoginSessionService 호출, 사용자 ID 설정, 401 응답 |
| auth | UserRepository | 저장소 | `login_id`로 계정 변경 잠금 조회, 실패 횟수·잠금 갱신 |
| auth | LoginSessionRepository | 저장소 | 로그인 행 저장·삭제, 토큰 해시로 조회, 활성 로그인 조회 |
| auth | LoginSessionQueryRepository | 조회 저장소 | `last_used_at` 조건부 갱신, 30일 지난 끝난 행 일괄 삭제 |

### 10.2 구현 순서
| 순서 | 작업 | 관련 요구사항 | 완료 기준 |
|-----|-----|-------------|----------|
| 0 | 공통 기반 (공통 설계 10.6) | NFR-AVAIL-001 | 공통 설계 10.6 완료 기준 |
| 1 | 스키마 변경 1·2 (6.5) | DATA-001, DATA-002, BR-002, BR-003, BR-011 | 운영자 SQL로 잘못된 아이디·PIN 거절, PIN 변경 시 활성 로그인 REVOKED 테스트 |
| 2 | 인증 필터 + 로그인 유지 확인 API | REQ-AUTH-003, BR-006, NFR-SEC-001 | 픽스처 토큰으로 204, 만료·REPLACED·REVOKED·모르는 토큰 401 코드별 테스트 |
| 3 | 로그인 API | REQ-AUTH-001, BR-005, BR-007 ~ BR-010 | 성공, 실패, 5회째 잠금, 잠금 중 거절, 5분 후 0부터, 두 기기 로그인 테스트 |
| 4 | 로그아웃 API | REQ-AUTH-002 | 로그아웃 후 401, 끝난 토큰·토큰 없음도 204 테스트 |
| 5 | 계정 삭제 연쇄 확인 | BR-011, BR-012 | 운영자 SQL로 계정 삭제 시 로그인·운동 기록 행 0건 테스트 (운동 기록 스키마 적용 후) |

### 10.3 테스트 포인트
- BR-007·BR-008: 틀린 PIN 4회 → 실패, 5회째 → 실패 응답 후 잠금. 잠금 중 올바른 PIN → AUTH_ACCOUNT_LOCKED(`retryAt` 확인). 5분 후 틀린 PIN 4회까지는 잠기지 않음(0부터).
- BR-010: 없는 아이디와 틀린 PIN의 응답 코드·메시지가 같다.
- BR-002: `Joonhee.song`으로 로그인하면 `joonhee.song` 계정에 로그인되지 않는다(대소문자 구분).
- BR-005·ERR-006: 기기 A 로그인 → 기기 B 로그인 → A 토큰 요청 401 AUTH_SESSION_REPLACED. A·B → C 순서로 로그인해도 A는 계속 REPLACED(30일 이내).
- BR-006·ERR-005: `last_used_at`을 30일 전으로 바꾼 행 → 401 AUTH_SESSION_EXPIRED. 29일 전이면 통과하고 `last_used_at`이 갱신됨.
- BR-011·ERR-008: SQL로 PIN 변경 → 다음 요청 401 AUTH_SESSION_REVOKED. 잠금 해제 SQL(PIN 그대로)은 로그인을 끝내지 않는다.
- BR-012: SQL로 계정 삭제 → 그 토큰 401 UNAUTHORIZED, 로그인·운동 기록 행 0건.
- NFR-AVAIL-001: 같은 계정에 틀린 PIN을 동시에 여러 번 보내도 실패 횟수가 정확하다.
- 로그아웃: 이미 REPLACED된 토큰으로 로그아웃해도 204.

---

## 부록 A. 설계 결정 기록
| ID | 결정 | 이유 | 버린 대안 |
|----|-----|-----|----------|
| DEC-AUTH-001 | 가입·계정 생성 API를 두지 않고, 애플리케이션은 `users` 행을 만들지 않는다 | BR-001, 요구사항 8.2(운영자가 SQL로 관리) | 운영자 전용 API: 요구사항 범위 밖 |
| DEC-AUTH-002 | 끝난 로그인 행을 `ended_at` 후 30일 보관하고, 그 계정의 다음 로그인 때 30일 지난 행을 삭제 | 끝난 사유(ERR-005, 006, 008)를 정확히 알려주려면 행이 남아 있어야 한다. 끝난 토큰은 30일 미사용이면 어차피 만료에 해당하므로 그 뒤에는 사유가 필요 없다. 주기 작업 없이 행 수가 계정당 30일간 로그인 횟수로 제한된다 | 끝나면 바로 삭제: 사유를 알려줄 수 없음 / 영구 보관: 행이 계속 쌓임 |
| DEC-AUTH-003 | 만료를 저장하지 않고 `last_used_at` + 30일로 판단. 만료된 `ACTIVE` 행은 다음 로그인 때 `EXPIRED`로 바꿈 | 만료 시각을 따로 두면 연장 때마다 두 값을 맞춰야 한다. 주기 작업 없이 판단할 수 있다 | 만료 시각 컬럼 + 주기적 만료 처리: 값 불일치 위험과 주기 작업 필요 |
| DEC-AUTH-004 | 인증된 요청마다 `last_used_at`을 갱신 | BR-006("사용할 때마다 연장")을 그대로 지킨다. **한계:** 요청마다 쓰기가 1회 생긴다. 부하가 문제가 되면 "마지막 갱신 후 1분 이상 지났을 때만 갱신"으로 바꾼다(만료 오차 1분) | 일정 간격으로만 갱신: 지금 규모에는 불필요한 최적화 |
| DEC-AUTH-005 | 로그인 실패는 계정 변경 잠금 안에서 실패 횟수를 바꾸고, 확정 후 오류 응답 | 오류 응답과 함께 트랜잭션이 취소되면 실패 횟수가 남지 않는다. 동시 시도도 정확히 센다 (NFR-AVAIL-001) | 독립 트랜잭션으로 실패만 기록: 잠금 확인과 기록이 다른 트랜잭션이라 동시 시도에서 어긋남 |
| DEC-AUTH-006 | 5회째 실패에서 잠그면서 `failed_pin_count`를 0으로 되돌려 저장 | BR-008("잠금이 풀린 뒤 0부터")을 잠금 해제 시점의 처리 없이 지킨다. 조건 검사(0~4)로 값 범위도 단순해진다 | 해제 시점에 0으로: 해제를 감지하는 처리가 필요 |
| DEC-AUTH-007 | 로그인 잠금도 401(`AUTH_ACCOUNT_LOCKED`) + `details.retryAt` | 인증 실패 범주라 공통 정책(401)과 맞고, 앱은 에러 코드로 구분한다 | 429/423: 공통 상태 코드 정책에 없는 코드 추가 |
| DEC-AUTH-008 | PIN 변경 시 로그인 종료는 DB 자동 동작으로 (공통 DEC-ARCH-013 적용) | 운영자 SQL은 애플리케이션을 거치지 않는다. 운영자가 종료 SQL을 빠뜨려도 BR-011이 지켜진다. 잠금 해제처럼 PIN이 바뀌지 않는 변경은 로그인을 끝내지 않는다 | 운영 절차에 종료 SQL 추가: 빠뜨리면 규칙이 깨짐 |
| DEC-AUTH-009 | 로그아웃은 공개 경로, 토큰이 있으면 그 행 삭제, 항상 204 | REQ-AUTH-002 "이미 끝난 로그인도 성공". 인증 필터를 거치면 끝난 로그인은 401이 된다 | 인증 필요 경로: 끝난 로그인으로 로그아웃하면 401 |
| DEC-AUTH-010 | 로그인 요청의 `loginId` 형식은 검사하지 않는다(필수·길이만) | 형식이 틀린 아이디도 "없는 아이디"와 같은 응답이어야 무엇이 틀렸는지 드러나지 않는다(BR-010). 형식은 발급 시 DB가 보장한다 | 형식 검사 후 400: 아이디 형식 정보를 드러내고 응답이 갈림 |
| DEC-AUTH-011 | PIN은 JSON 문자열로 받는다 | `012345`처럼 0으로 시작하는 PIN을 숫자로 받으면 앞자리가 사라진다 | 숫자: 앞자리 0 손실 |

## 부록 B. 설계 미결정 사항
- **D-TODO-AUTH-001** 로그아웃 메뉴가 있는 화면과의 연계. 요구사항 TODO-005 결정 후 4장에 추가한다. (영향: 4.1, 4.2)

## 부록 C. 요구사항 피드백
설계는 괄호 안으로 진행했다. 요구사항을 고치면 설계도 따라 고친다.

1. **BR-010과 잠금의 충돌:** 없는 아이디는 잠기지 않으므로, 어떤 아이디로 PIN을 5번 틀려 보고 `AUTH_ACCOUNT_LOCKED`가 나오면 그 아이디가 있다는 것을 알 수 있다. 잠금 안내(ERR-004)를 유지하는 한 완전히 숨길 수는 없다. 숨기려면 없는 아이디도 시도 횟수를 기록해 똑같이 잠가야 한다(범위 확대). (설계: 요구사항대로 잠금 안내를 보여준다)
2. **ERR-008 계정 삭제:** 계정을 삭제하면 로그인 기록도 함께 지워져(BR-012) 그 기기에는 "다시 로그인해 주세요"(재발급)가 아니라 "로그인이 필요합니다"(UNAUTHORIZED)가 나간다. 앱 처리는 같다(로그인 화면). (설계: UNAUTHORIZED)
3. **아이디 형식 세부:** 이름·성에 영문 외 문자(하이픈, 공백)를 허용하는지, 길이 상한이 없다. (설계: 영문자만, 최대 100자, 번호는 2부터)
4. **잠금이 걸리는 시도의 응답:** 5번째로 틀린 시도에는 "아이디 또는 PIN이 올바르지 않습니다"가 나가고, 잠긴 사실은 다음 시도에서 안내된다. 5번째 시도에서 바로 잠금을 알리려면 요구사항 ERR-001/ERR-004의 구분을 바꿔야 한다. (설계: 요구사항대로)
