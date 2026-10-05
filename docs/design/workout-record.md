# 운동 기록 기능 설계 문서

- 문서 버전: v0.4
- 작성일: 2026-10-05
- 상태: 초안
- 요구사항: `docs/requirements/workout-record.md` (v0.1)
- 공통 설계: `docs/design/architecture.md` (v0.4)
- 관련 설계: `docs/design/auth.md` (인증 필터, users)
- 변경 이력:
  - v0.2 — 기술 중립 용어로 다시 씀. 기술 대응은 공통 설계 10.1을 따른다
  - v0.3 — 모든 PK를 UUIDv7로 변경(공통 DEC-ARCH-010). 세트·운동 순서와 목록 정렬을 ID 대신 시각 컬럼 기준으로 변경
  - v0.4 — 인증 설계 반영: `workout_session.user_id`를 참조(함께 삭제)로 변경(auth BR-012, DEC-WORKOUT-014), 스키마 변경 순서를 인증 스키마 뒤로, 사용자 ID 출처를 인증 필터로

---

## 1. 설계 개요

### 1.1 목적
운동 세션 시작부터 세트 기록, 완료, 지난 기록 조회·삭제까지의 요구사항을 REST API 12개와 테이블 3개(+ 운동 목록 테이블 1개)로 구현하는 방법을 확정한다. 운동 세션을 중심 단위로 두고, 세션 안의 운동과 세트는 세션의 하위 리소스로 다룬다.

### 1.2 설계 범위
- 포함: REQ-WORKOUT-001 ~ 006, REQ-EXERCISE-001 ~ 002, REQ-SET-001 ~ 003
- 보류: 초기 운동 목록 데이터와 이미지 파일 (요구사항 TODO-014 미결정 → D-TODO-WORKOUT-001). 테이블과 조회 API는 설계하지만 실제 운동 데이터 투입은 보류한다.
- 제외: 요구사항 8.1의 범위 밖 항목. 폐기된 요구사항 REQ-SET-004, BR-006, ERR-006, IF-SET-002는 설계하지 않는다.

### 1.3 대상 시스템
- Backend API: 신규 도메인 `exercise`(운동 목록), `session`(운동 세션·세션 운동·세트)
- Database: 신규 테이블 `exercise`, `workout_session`, `workout_session_exercise`, `workout_set`
- Mobile App: 화면 WO-001 ~ WO-004, EX-001 ~ EX-002가 이 API를 호출

### 1.4 기술 스택
공통 설계 1.4를 따른다. 이 기능에 새로 필요한 기술 능력:
| 필요한 능력 | 용도 | 이유 |
|-----------|-----|-----|
| 공개 정적 파일 제공 | 운동 이미지 | 운영팀이 준비하는 고정 이미지라 사용자 업로드·외부 저장소가 필요 없다 (DEC-WORKOUT-008) |

방치된 세션 자동 완료(REQ-WORKOUT-006)는 주기 작업 없이 처리하므로 그 능력은 필요 없다 (DEC-WORKOUT-004).

### 1.5 설계 원칙
1. **세션이 중심:** 운동·세트 변경은 모두 소속 세션을 통해 접근하고, 세션 하나의 상태·소유자 확인을 거친다.
2. **계산할 수 있는 값은 저장하지 않는다:** 세트 번호, 운동 순서, 세션 요약은 저장하지 않고 조회 때 계산한다. 저장하면 값이 서로 어긋날 수 있다(DEC-WORKOUT-002, 003).
3. **변경 요청은 세션에 변경 잠금을 건다:** 완료와 세트 변경이 동시에 들어와도 완료된 세션에 세트가 추가되지 않게 한다(DEC-WORKOUT-005).

### 1.6 요구사항 ↔ 설계 추적표
| 요구사항 ID | 요구사항 | 설계 반영 위치 | 구현 | 테스트 |
|------------|---------|--------------|-----|-------|
| REQ-WORKOUT-001 | 운동 세션 시작 | 3.2, API-WORKOUT-001, API-WORKOUT-002, workout_session | | |
| REQ-WORKOUT-002 | 운동 세션 완료 | 3.2, API-WORKOUT-003 | | |
| REQ-WORKOUT-003 | 운동 기록 목록 조회 | 3.2, API-WORKOUT-004, idx_workout_session_user_performed | | |
| REQ-WORKOUT-004 | 운동 기록 상세 조회 | 3.2, API-WORKOUT-005 | | |
| REQ-WORKOUT-005 | 운동 기록 삭제 | 3.2, API-WORKOUT-006, 6.4 | | |
| REQ-WORKOUT-006 | 방치된 세션 자동 완료 | 3.2, ExpiredSessionCleaner, DEC-WORKOUT-004 | | |
| REQ-EXERCISE-001 | 세션에 운동 추가 | 3.2, API-EXERCISE-001, API-EXERCISE-002, exercise, workout_session_exercise | | |
| REQ-EXERCISE-002 | 세션에서 운동 삭제 | 3.2, API-EXERCISE-003 | | |
| REQ-SET-001 | 세트 기록 추가 | 3.2, API-SET-001, workout_set | | |
| REQ-SET-002 | 세트 기록 수정 | 3.2, API-SET-002 | | |
| REQ-SET-003 | 세트 기록 삭제 | 3.2, API-SET-003 | | |
| BR-001 | 본인 기록만 조회·수정·삭제 | 3.5, 7.2 | | |
| BR-002 | 완료된 세션 수정 불가 | 3.4, 3.5, ERR 코드 WORKOUT_SESSION_NOT_EDITABLE | | |
| BR-003 | 반복 횟수 1~1,000 정수 | 3.5, 5.3 Validation, ck_workout_set_repetitions | | |
| BR-004 | 수행 날짜 = 시작 시각의 현지 날짜 | 3.2, 3.5, API-WORKOUT-001 `X-Time-Zone` | | |
| BR-005 | 중량 kg, 0~1,000, 소수 둘째 자리 | 3.5, 5.3 Validation, workout_set.weight 소수(6,2), ck_workout_set_weight | | |
| BR-007 | 세트 번호 1부터 빈 번호 없이 | 3.5, DEC-WORKOUT-002 | | |
| BR-008 | 종료 시각 ≥ 시작 시각 | 3.5, ck_workout_session_ended | | |
| BR-009 | 제공 목록의 운동만 추가 | 3.5, 참조 workout_session_exercise.exercise_id | | |
| BR-010 | 요약은 모든 세트로 계산 | 3.5, DEC-WORKOUT-003, 5.2 summary | | |
| BR-011 | 진행 중 세션은 하나 | 3.5, 조건부 유일 ux_workout_session_user_in_progress | | |
| BR-012 | 세트가 있어야 완료 | 3.5, API-WORKOUT-003 | | |
| BR-013 | 6시간 지나면 시스템이 정리 | 3.2, 3.5, ExpiredSessionCleaner | | |
| BR-014 | 이미지 선택, 없으면 기본 이미지 | 3.5, exercise.image_url Null 허용, 4.2 EX-001 | | |
| ERR-001 | 비로그인 요청 | 8.2 UNAUTHORIZED | | |
| ERR-002 | 존재하지 않는 운동 추가 | 8.2 EXERCISE_NOT_FOUND | | |
| ERR-003 | 다른 사용자 데이터 접근 | 7.2, 8.2 FORBIDDEN | | |
| ERR-004 | 삭제된 기록 재삭제 | 8.2 WORKOUT_SESSION_NOT_FOUND | | |
| ERR-005 | 세트 값 범위·형식 오류 | 8.2 VALIDATION_FAILED | | |
| ERR-007 | 완료된 세션 변경 | 8.2 WORKOUT_SESSION_NOT_EDITABLE | | |
| ERR-008 | 완료된 세션 재완료 | 8.2 WORKOUT_SESSION_ALREADY_COMPLETED | | |
| ERR-009 | 없는 세션·운동·세트 | 8.2 WORKOUT_SESSION_NOT_FOUND, SESSION_EXERCISE_NOT_FOUND, WORKOUT_SET_NOT_FOUND | | |
| ERR-010 | 필수 입력 누락 | 8.2 VALIDATION_FAILED | | |
| ERR-011 | 진행 중 세션이 있는데 시작 | 8.2 WORKOUT_SESSION_ALREADY_IN_PROGRESS | | |
| ERR-012 | 세트 없는 세션 완료 | 8.2 WORKOUT_SESSION_HAS_NO_SETS | | |
| NFR-PERF-001 | p95 500ms | 9장 | | |
| NFR-PERF-002 | 기록이 많아도 목록 성능 유지 | 9장, 6.3 | | |
| NFR-PERF-003 | 동시 1,000명 | 9장 | | |
| NFR-SEC-001 | 인증된 사용자만 | 7.1 | | |
| NFR-SEC-002 | 본인 데이터만 | 7.2 | | |
| NFR-AVAIL-001 | 오류 반환, 부분 저장 없음 | 9장, 3.2 트랜잭션 | | |
| NFR-INTEG-001 | 없는 사용자의 기록 생성 불가 | 6.2 참조 workout_session.user_id | | |
| NFR-INTEG-002 | 삭제 시 하위 데이터 남지 않음 | 6.4 참조(함께 삭제) | | |
| NFR-LOG-001 | 주요 행위·자동 처리·오류 추적 | 9장 로그 이벤트 | | |
| IF-WORKOUT-001 | 세션 시작 | API-WORKOUT-001 | | |
| IF-WORKOUT-002 | 진행 중 세션 조회 | API-WORKOUT-002 | | |
| IF-WORKOUT-003 | 세션 완료 | API-WORKOUT-003 | | |
| IF-WORKOUT-004 | 기록 목록 조회 | API-WORKOUT-004 | | |
| IF-WORKOUT-005 | 기록 상세 조회 | API-WORKOUT-005 | | |
| IF-WORKOUT-006 | 기록 삭제 | API-WORKOUT-006 | | |
| IF-EXERCISE-001 | 운동 목록 조회·검색 | API-EXERCISE-001 | | |
| IF-EXERCISE-002 | 세션에 운동 추가·삭제 | API-EXERCISE-002, API-EXERCISE-003 | | |
| IF-SET-001 | 세트 추가·수정·삭제 | API-SET-001, API-SET-002, API-SET-003 | | |
| DATA-001 | 운동 세션 | 6.2 workout_session | | |
| DATA-002 | 운동(종목) | 6.2 exercise | | |
| DATA-003 | 세션 내 운동 | 6.2 workout_session_exercise | | |
| DATA-004 | 세트 | 6.2 workout_set | | |
| DATA-005 | 세션 요약 | DEC-WORKOUT-003, 5.2 summary | | |
| WO-001 | 홈 | 4.2 | | |
| WO-002 | 운동 결과 | 4.2 | | |
| WO-003 | 운동 기록 목록 | 4.2 | | |
| WO-004 | 운동 기록 상세 | 4.2 | | |
| EX-001 | 운동 선택 | 4.2 | | |
| EX-002 | 운동 기록 | 4.2 | | |

---

## 2. 시스템 아키텍처
공통 설계 2장을 따른다. 이 기능에서 추가되는 것:

- 추가 컴포넌트: **ExpiredSessionCleaner**(서비스) — 시작 후 6시간이 지난 진행 중 세션을 정리한다(REQ-WORKOUT-006). 주기 작업이 아니라, 세션 관련 API가 처리 전에 **요청한 사용자의 세션만** 정리한다. **독립 트랜잭션**으로 실행해, 뒤이은 요청 처리가 실패해도 정리 결과는 남는다(DEC-WORKOUT-004).
- 공개 정적 파일: 운동 이미지를 URL `/images/exercises/**`로 제공한다(DEC-WORKOUT-008).

```
요청 ─▶ 인증 필터(userId) ─▶ WorkoutSessionController (API 진입점)
                               │
                               ├─▶ ExpiredSessionCleaner.cleanUp(userId)   [독립 트랜잭션, 조건부 일괄 갱신]
                               │
                               └─▶ WorkoutSessionService [트랜잭션]
                                      ├─ 쓰기·단건 조회: 저장소 (세션에 변경 잠금)
                                      └─ 상세·목록: WorkoutSessionQueryRepository (조회 저장소)
```

---

## 3. 기능 설계

### 3.1 기능 목록
| 요구사항 ID | 기능 | API | 주요 컴포넌트 |
|------------|-----|-----|-------------|
| REQ-WORKOUT-001 | 운동 세션 시작 | API-WORKOUT-001 | WorkoutSessionService.start |
| REQ-WORKOUT-001 (WO-001) | 진행 중 세션 조회 | API-WORKOUT-002 | WorkoutSessionService.getInProgress |
| REQ-WORKOUT-002 | 운동 세션 완료 | API-WORKOUT-003 | WorkoutSessionService.complete |
| REQ-WORKOUT-003 | 운동 기록 목록 | API-WORKOUT-004 | WorkoutSessionQueryRepository.findPage |
| REQ-WORKOUT-004 | 운동 기록 상세 | API-WORKOUT-005 | WorkoutSessionQueryRepository.findDetail |
| REQ-WORKOUT-005 | 운동 기록 삭제 | API-WORKOUT-006 | WorkoutSessionService.delete |
| REQ-WORKOUT-006 | 방치된 세션 자동 완료 | (모든 세션 API 앞단) | ExpiredSessionCleaner.cleanUp |
| REQ-EXERCISE-001 | 운동 목록 조회 | API-EXERCISE-001 | ExerciseService.search |
| REQ-EXERCISE-001 | 세션에 운동 추가 | API-EXERCISE-002 | WorkoutSessionService.addExercise |
| REQ-EXERCISE-002 | 세션에서 운동 삭제 | API-EXERCISE-003 | WorkoutSessionService.removeExercise |
| REQ-SET-001 | 세트 추가 | API-SET-001 | WorkoutSessionService.addSet |
| REQ-SET-002 | 세트 수정 | API-SET-002 | WorkoutSessionService.updateSet |
| REQ-SET-003 | 세트 삭제 | API-SET-003 | WorkoutSessionService.deleteSet |

### 3.2 기능별 처리 흐름
모든 흐름의 공통 앞단:
- (A) 인증 필터에서 userId를 얻는다. 없거나 잘못되면 401 `UNAUTHORIZED` (ERR-001).
- (B) `ExpiredSessionCleaner.cleanUp(userId)` 실행 (REQ-WORKOUT-006 흐름 참고). 운동 목록 조회(API-EXERCISE-001)도 최근 수행일 계산이 정리 결과를 반영하도록 실행한다.

**편집 가능 세션 확보** (운동·세트 변경, 완료에서 공통으로 쓰는 단계, 이하 "(E)"):
1. 세션을 **변경 잠금**으로 조회한다. 없으면 404 `WORKOUT_SESSION_NOT_FOUND` (ERR-009).
2. `user_id`가 userId와 다르면 403 `FORBIDDEN` (BR-001, ERR-003).
3. 상태가 `COMPLETED`면 409 `WORKOUT_SESSION_NOT_EDITABLE` (BR-002, ERR-007). 완료 API에서는 대신 409 `WORKOUT_SESSION_ALREADY_COMPLETED` (ERR-008).

#### REQ-WORKOUT-001 운동 세션 시작 (API-WORKOUT-001)
1. (A), (B).
2. `X-Time-Zone` 헤더를 IANA 시간대로 해석한다. 없거나 잘못되면 400 `VALIDATION_FAILED` (ERR-010).
3. 트랜잭션 시작. 사용자의 `IN_PROGRESS` 세션이 있으면 409 `WORKOUT_SESSION_ALREADY_IN_PROGRESS` (BR-011, ERR-011).
4. `started_at` = 현재 시각(UTC), `performed_date` = `started_at`을 헤더 시간대로 바꾼 날짜 (BR-004), `status` = `IN_PROGRESS`로 저장한다.
5. 3을 동시에 통과한 두 요청 중 하나는 조건부 유일 제약 `ux_workout_session_user_in_progress` 위반이 난다. **제약 위반 변환**으로 409 `WORKOUT_SESSION_ALREADY_IN_PROGRESS`를 반환한다 (BR-011).
6. 커밋, 로그 `workout_session.started`, 201과 세션 상세(운동 없음)를 반환한다.

#### REQ-WORKOUT-001 / WO-001 진행 중 세션 조회 (API-WORKOUT-002)
1. (A), (B).
2. 사용자의 `IN_PROGRESS` 세션을 찾는다. 없으면 204.
3. 있으면 상세 조회(API-WORKOUT-005와 같은 응답)를 200으로 반환한다.

#### REQ-WORKOUT-002 운동 세션 완료 (API-WORKOUT-003)
1. (A), (B), (E) — 상태가 `COMPLETED`면 409 `WORKOUT_SESSION_ALREADY_COMPLETED` (ERR-008).
2. 요청 본문의 `memo`를 검증한다(0~500자, 생략 가능). 위반 시 400.
3. 세션의 세트 수를 센다. 0이면 409 `WORKOUT_SESSION_HAS_NO_SETS` (BR-012, ERR-012).
4. `status` = `COMPLETED`, `ended_at` = 현재 시각, `memo` 저장. `ended_at ≥ started_at`은 항상 참이고 조건 검사 제약으로도 보장한다 (BR-008).
5. 커밋, 로그 `workout_session.completed`, 200과 세션 상세(요약 포함)를 반환한다.

#### REQ-WORKOUT-003 운동 기록 목록 (API-WORKOUT-004)
1. (A), (B).
2. `page`, `size`를 검증한다(공통 설계 5장). 위반 시 400.
3. 조회 저장소에서 한 쿼리로 `user_id = userId` 세션을 `performed_date` 내림차순, `started_at` 내림차순, `id` 내림차순(동점 처리)으로 페이지 조회하고, 각 세션의 운동명 목록과 세트 수를 함께 집계한다. 전체 개수는 별도 개수 쿼리 1회.
4. 200과 페이지 응답. 기록이 없으면 빈 `content` (오류 아님).

#### REQ-WORKOUT-004 운동 기록 상세 (API-WORKOUT-005)
1. (A), (B).
2. 세션을 조회한다. 없으면 404 `WORKOUT_SESSION_NOT_FOUND` (ERR-009), 다른 사용자 것이면 403 `FORBIDDEN` (ERR-003).
3. 조회 저장소에서 한 쿼리로 세션의 운동(`workout_session_exercise.created_at`, `id` 순)과 운동 정보, 세트(`workout_set.created_at`, `id` 순)를 가져온다.
4. 서비스에서 세트 번호(운동별 1부터), 운동별 합계, 세션 요약을 계산한다 (BR-007, BR-010).
5. 200과 세션 상세.

#### REQ-WORKOUT-005 운동 기록 삭제 (API-WORKOUT-006)
1. (A), (B).
2. 세션을 변경 잠금으로 조회한다. 없으면 404 `WORKOUT_SESSION_NOT_FOUND` (ERR-004 — 이미 삭제된 경우 포함). 다른 사용자 것이면 403.
3. 상태와 관계없이 삭제한다. 하위 운동·세트는 참조(함께 삭제)로 같은 트랜잭션에서 삭제된다 (NFR-INTEG-002).
4. 커밋, 로그 `workout_session.deleted`, 204.

#### REQ-WORKOUT-006 방치된 세션 자동 완료 (ExpiredSessionCleaner.cleanUp)
독립 트랜잭션에서 조회 저장소의 **조건부 일괄 갱신** 두 번으로 처리한다. 기준 시각은 DB의 현재 시각이다.
1. 완료 처리 — 대상: `user_id = userId`, `status = IN_PROGRESS`, `started_at < 현재 시각 - 6시간`, 세트가 1개 이상인 세션. 변경: `status = COMPLETED`, `ended_at` = 그 세션의 세트 중 가장 늦은 `created_at`, `updated_at` = 현재 시각 (BR-013). 처리한 세션 ID를 돌려받는다.
2. 삭제 처리 — 대상: 같은 조건이면서 세트가 하나도 없는 세션. 삭제한다 (BR-013, BR-012). 하위 운동은 참조(함께 삭제)로 삭제된다. 처리한 세션 ID를 돌려받는다.
3. 처리한 세션마다 로그 `workout_session.auto_completed` / `workout_session.auto_deleted` (NFR-LOG-001).
4. 조건부 일괄 갱신이라 여러 요청이 동시에 실행해도 결과가 같다(멱등).

#### REQ-EXERCISE-001 운동 목록 조회 (API-EXERCISE-001)
1. (A), (B).
2. `keyword`를 앞뒤 공백 제거 후 검증한다(생략 가능, 최대 50자). 위반 시 400.
3. 조회 저장소에서 한 쿼리로 `exercise` 전체(또는 운동명 부분 일치, 대소문자 무시. 검색어의 와일드카드 문자는 일반 문자로 취급)를 `category`, `name` 순으로 조회하고, 사용자의 **완료된** 세션 기준 운동별 가장 최근 `performed_date`를 붙인다 (DEC-WORKOUT-012).
4. 200과 운동 목록(페이지 없음, DEC-WORKOUT-011).

#### REQ-EXERCISE-001 세션에 운동 추가 (API-EXERCISE-002)
1. (A), (B), (E).
2. 본문 `exerciseId` 필수 검증. 위반 시 400 (ERR-010).
3. `exercise`가 없으면 404 `EXERCISE_NOT_FOUND` (BR-009, ERR-002).
4. `workout_session_exercise`를 저장한다. 순서는 추가한 시각(`created_at`)으로 정해진다 (DEC-WORKOUT-002).
5. 201과 추가된 세션 운동(세트 없음).

#### REQ-EXERCISE-002 세션에서 운동 삭제 (API-EXERCISE-003)
1. (A), (B), (E).
2. 경로의 `sessionExerciseId`가 이 세션에 속하지 않으면 404 `SESSION_EXERCISE_NOT_FOUND` (ERR-009).
3. 삭제한다. 그 운동의 세트는 참조(함께 삭제)로 함께 삭제 (NFR-INTEG-002).
4. 204.

#### REQ-SET-001 세트 추가 (API-SET-001)
1. (A), (B), (E).
2. 본문 검증: `weight` 필수, 0~1,000, 소수 둘째 자리까지 (BR-005) / `repetitions` 필수, 1~1,000 정수 (BR-003). 위반 시 400 `VALIDATION_FAILED` (ERR-005, ERR-010).
3. `sessionExerciseId`가 이 세션에 속하지 않으면 404 `SESSION_EXERCISE_NOT_FOUND`.
4. `workout_set`을 저장한다. `created_at` = 추가한 시각 (REQ-WORKOUT-006의 종료 시각 기준).
5. 201과 세트(세트 번호 = 그 운동에서 이 세트보다 먼저 추가된 세트 수 + 1. 순서 기준은 `created_at`, 같으면 `id`).

#### REQ-SET-002 세트 수정 (API-SET-002)
1. (A), (B), (E).
2. 본문 검증은 REQ-SET-001과 같다.
3. 세션 운동이 세션에 속하지 않으면 404 `SESSION_EXERCISE_NOT_FOUND`. 세트가 그 세션 운동에 속하지 않으면 404 `WORKOUT_SET_NOT_FOUND` (ERR-009).
4. `weight`, `repetitions`, `updated_at`만 바꾼다. `created_at`과 세트 번호는 바뀌지 않는다.
5. 200과 세트.

#### REQ-SET-003 세트 삭제 (API-SET-003)
1. (A), (B), (E).
2. 소속 확인은 REQ-SET-002와 같다.
3. 삭제한다. 세트 번호는 저장하지 않으므로 다시 매기는 쓰기가 없다. 다음 조회부터 남은 세트가 1부터 다시 번호를 가진다 (BR-007).
4. 204.

### 3.3 주요 시나리오 — 운동을 시작해서 완료하기
```
App                         API                                    DB
 │ GET in-progress           │ cleanUp(userId)                       │
 │──────────────────────────▶│──────────────────────────────────────▶│
 │◀───────── 204 ────────────│ (진행 중 없음)                          │
 │ POST workout-sessions     │                                        │
 │ X-Time-Zone: Asia/Seoul   │ workout_session 저장 (IN_PROGRESS)     │
 │──────────────────────────▶│──────────────────────────────────────▶│
 │◀───────── 201 ────────────│                                        │
 │ GET exercises?keyword=벤치 │ 운동 목록 + 최근 수행일 조회             │
 │──────────────────────────▶│──────────────────────────────────────▶│
 │ POST .../exercises        │ 세션 변경 잠금 → 세션 운동 저장          │
 │──────────────────────────▶│──────────────────────────────────────▶│
 │ POST .../sets (반복)       │ 세션 변경 잠금 → 세트 저장               │
 │──────────────────────────▶│──────────────────────────────────────▶│
 │ POST .../complete         │ 세션 변경 잠금 → 세트 수 확인 → 완료 저장 │
 │──────────────────────────▶│──────────────────────────────────────▶│
 │◀── 200 세션 상세+요약 ──────│                                        │
```

### 3.4 상태 변화
```
           POST /workout-sessions
                    │
                    ▼
            [IN_PROGRESS] ──POST /complete (세트 ≥ 1)──────────────▶ [COMPLETED]
              │      │                                                │
              │      └──6시간 경과 + 세트 ≥ 1 (cleanUp)──────────────▶ │
              │                                                       │
              ├──6시간 경과 + 세트 0 (cleanUp)──▶ (삭제)                │
              └──DELETE──▶ (삭제)                      (삭제) ◀──DELETE─┘
```

| 작업 | IN_PROGRESS | COMPLETED |
|-----|-------------|-----------|
| 상세·목록 조회 | ○ | ○ |
| 운동 추가·삭제, 세트 추가·수정·삭제 | ○ | ✕ 409 WORKOUT_SESSION_NOT_EDITABLE |
| 완료 | ○ (세트 ≥ 1) | ✕ 409 WORKOUT_SESSION_ALREADY_COMPLETED |
| 삭제 | ○ | ○ |

### 3.5 비즈니스 규칙 구현
| BR ID | 규칙 | 강제 위치 | 방법 | 위반 시 |
|-------|-----|----------|-----|--------|
| BR-001 | 본인 기록만 | 서비스, 조회 조건 | 단건: 조회 후 `user_id` 비교 / 목록: 조회 조건에 `user_id = userId` | 403 FORBIDDEN |
| BR-002 | 완료된 세션 수정 불가 | 서비스 (E) | 세션 변경 잠금 후 상태 확인 | 409 WORKOUT_SESSION_NOT_EDITABLE |
| BR-003 | 반복 횟수 1~1,000 정수 | 요청 검증, DB | 요청 검증(필수, 정수 — 소수 거부는 공통 5장, 1~1,000) + 조건 검사 `ck_workout_set_repetitions` | 400 VALIDATION_FAILED |
| BR-004 | 수행 날짜 = 시작 시각의 현지 날짜 | 서비스 | `X-Time-Zone`으로 `started_at`의 날짜 계산, 입력으로 받지 않음 | 헤더 오류 시 400 |
| BR-005 | 중량 0~1,000, 소수 둘째 자리 | 요청 검증, DB | 요청 검증(필수, 0~1,000, 소수 2자리 이내) + 논리 타입 소수(6,2) + 조건 검사 `ck_workout_set_weight` | 400 VALIDATION_FAILED |
| BR-007 | 세트 번호 1부터 연속 | 조회 계산 | 저장하지 않고 운동별 추가 순서(`created_at`, 같으면 `id`)로 번호 계산 (DEC-WORKOUT-002) | 위반이 생길 수 없음 |
| BR-008 | 종료 ≥ 시작 | 서비스, DB | 종료 시각은 항상 현재 시각 또는 시작 이후 추가된 세트 시각, 조건 검사 `ck_workout_session_ended` | 정상 흐름에서 발생 불가 → 500 |
| BR-009 | 제공 목록의 운동만 | 서비스, DB | `exercise` 존재 확인 + 참조(삭제 금지) `exercise_id → exercise.id` | 404 EXERCISE_NOT_FOUND |
| BR-010 | 요약은 모든 세트로 | 조회 계산 | 상세 조회 결과로 합계 계산 (DEC-WORKOUT-003) | — |
| BR-011 | 진행 중 세션 하나 | 서비스, DB | 시작 전 조회 + 조건부 유일 `ux_workout_session_user_in_progress`, 위반을 제약 위반 변환으로 409 | 409 WORKOUT_SESSION_ALREADY_IN_PROGRESS |
| BR-012 | 세트가 있어야 완료 | 서비스 | 완료 전 세트 수 확인 | 409 WORKOUT_SESSION_HAS_NO_SETS |
| BR-013 | 6시간 지나면 정리 | ExpiredSessionCleaner | 세션 API 앞단에서 독립 트랜잭션 + 조건부 일괄 갱신 (DEC-WORKOUT-004) | — |
| BR-014 | 이미지 선택 | DB, 앱 | `image_url` Null 허용, 앱이 null·로드 실패 시 기본 이미지 표시 | — |

### 3.6 기능 간 의존관계
- 세트 추가(REQ-SET-001)는 세션 운동(REQ-EXERCISE-001)이 있어야 한다.
- 세션 운동 추가는 운동 목록(`exercise` 데이터)이 있어야 한다. 실제 데이터는 TODO-014 이후 투입(D-TODO-WORKOUT-001). 그 전까지 테스트는 직접 넣은 운동 데이터로 한다.
- 완료(REQ-WORKOUT-002)와 진행 중 조회의 응답은 상세 조회(REQ-WORKOUT-004)를 재사용한다.
- 모든 세션 API는 ExpiredSessionCleaner(REQ-WORKOUT-006)에 의존한다.

---

## 4. 화면 / API 연계 설계
요구사항 3장의 화면 정의를 근거로 한다. Figma와의 대조는 아직 하지 않았다 (D-TODO-WORKOUT-003).

### 4.1 화면-API 매핑
| 화면 ID | 화면 | 사용자 행동 | API |
|--------|-----|-----------|-----|
| WO-001 | 홈 | 진입 | API-WORKOUT-002 `GET /api/v1/workout-sessions/in-progress` |
| WO-001 | 홈 | 운동 시작 | API-WORKOUT-001 `POST /api/v1/workout-sessions` |
| EX-001 | 운동 선택 | 진입, 검색 | API-EXERCISE-001 `GET /api/v1/exercises` |
| EX-001 | 운동 선택 | 운동 선택 | API-EXERCISE-002 `POST /api/v1/workout-sessions/{sessionId}/exercises` |
| EX-002 | 운동 기록 | 진입 | API-WORKOUT-005 `GET /api/v1/workout-sessions/{sessionId}` |
| EX-002 | 운동 기록 | 세트 추가 / 수정 / 삭제 | API-SET-001 / 002 / 003 |
| EX-002 | 운동 기록 | 운동 삭제 | API-EXERCISE-003 |
| EX-002 | 운동 기록 | 운동 완료 | API-WORKOUT-003 `POST /api/v1/workout-sessions/{sessionId}/complete` |
| WO-002 | 운동 결과 | 진입 | API-WORKOUT-003 응답 사용 (재진입 시 API-WORKOUT-005) |
| WO-003 | 운동 기록 목록 | 진입, 더 보기 | API-WORKOUT-004 `GET /api/v1/workout-sessions?page=&size=` |
| WO-004 | 운동 기록 상세 | 진입 | API-WORKOUT-005 |
| WO-004 | 운동 기록 상세 | 삭제 | API-WORKOUT-006 `DELETE /api/v1/workout-sessions/{sessionId}` |

### 4.2 화면별 연계 상세
공통 응답 처리(401·403·404·400·409·500)는 공통 설계 4장을 따른다. 아래는 화면별로 추가되는 처리다.

#### WO-001 홈
- 진입 조건: 로그인 완료
- 필요 데이터: API-WORKOUT-002 → `startedAt`, `exercises[].name`, `summary.totalSets`
- 사용자 입력: 없음
- API 호출: 진입 시 API-WORKOUT-002. "운동 시작" 시 API-WORKOUT-001 (`X-Time-Zone` = 기기 시간대)
- 성공 처리: 200 → "이어서 하기"와 진행 중 정보 표시 / 204 → "운동 시작" 표시 / 시작 201 → EX-001로 이동
- 실패 처리: 409 `WORKOUT_SESSION_ALREADY_IN_PROGRESS` → API-WORKOUT-002 재호출 후 EX-002로 이동
- 로딩 상태: 진입 조회 중 버튼 비활성
- 빈 상태: 204 → 진행 중 세션 없음, "운동 시작" 버튼만

#### EX-001 운동 선택
- 진입 조건: 진행 중 세션 있음
- 필요 데이터: API-EXERCISE-001 → `id`, `name`, `category`, `imageUrl`, `lastPerformedDate`
- 사용자 입력: 검색어(최대 50자)
- API 호출: 진입 시 검색어 없이 1회, 검색어 입력 시 재호출. 운동 선택 시 API-EXERCISE-002
- 성공 처리: 추가 201 → EX-002로 이동
- 실패 처리: 404 `EXERCISE_NOT_FOUND` → 목록 새로고침 / 409 `WORKOUT_SESSION_NOT_EDITABLE`(6시간 경과로 자동 완료됨) → 안내 후 WO-001
- 로딩 상태: 목록 조회 중 표시
- 빈 상태: 검색 결과 없음 → "검색 결과가 없습니다" / `imageUrl`이 null이거나 로드 실패 → 기본 이미지 (BR-014)

#### EX-002 운동 기록
- 진입 조건: 진행 중 세션에 운동 1개 이상
- 필요 데이터: API-WORKOUT-005 → `exercises[]`(`name`, `category`, `sets[]`의 `setNumber`, `weight`, `repetitions`, `totalSets`, `totalRepetitions`)
- 사용자 입력: 세트의 중량(0~1,000, 소수 둘째 자리), 반복 횟수(1~1,000 정수). 앱에서도 같은 범위로 미리 검증
- API 호출: 진입 시 API-WORKOUT-005. 세트 추가·수정·삭제, 운동 삭제, 완료 시 해당 API. 세트 삭제 후에는 번호가 바뀌므로 API-WORKOUT-005 재조회
- 성공 처리: 완료 200 → 응답을 가지고 WO-002로 이동
- 실패 처리: 400 → 해당 입력 칸에 `errors[].reason` / 409 `WORKOUT_SESSION_HAS_NO_SETS` → "세트를 하나 이상 기록하세요" / 409 `WORKOUT_SESSION_NOT_EDITABLE` → 자동 완료 안내 후 WO-004
- 로딩 상태: 저장 중 해당 버튼 비활성(중복 요청 방지)
- 빈 상태: 세트 없는 운동 → "세트를 추가하세요"

#### WO-002 운동 결과
- 진입 조건: 완료 직후
- 필요 데이터: API-WORKOUT-003 응답 → `performedDate`, `startedAt`, `endedAt`, `summary`(`durationSeconds`, `exerciseCount`, `totalSets`, `totalRepetitions`, `totalWeight`), `memo`
- 사용자 입력: 없음
- API 호출: 없음(완료 응답 사용)
- 성공 처리: 홈 버튼 → WO-001
- 실패 처리: 해당 없음
- 로딩 상태: 해당 없음
- 빈 상태: `memo` null → 메모 영역 숨김

#### WO-003 운동 기록 목록
- 진입 조건: 로그인 완료
- 필요 데이터: API-WORKOUT-004 → `content[]`(`id`, `performedDate`, `durationSeconds`, `exerciseNames`, `totalSets`, `status`), `totalPages`
- 사용자 입력: 없음
- API 호출: 진입 시 `page=0`, 스크롤 끝에서 다음 페이지
- 성공 처리: 항목 선택 → WO-004
- 실패 처리: 공통
- 로딩 상태: 다음 페이지 로딩 표시
- 빈 상태: `totalElements` = 0 → "아직 운동 기록이 없습니다" / 진행 중 세션은 `durationSeconds` null → "진행 중" 표시

#### WO-004 운동 기록 상세
- 진입 조건: WO-003에서 선택
- 필요 데이터: API-WORKOUT-005 전체
- 사용자 입력: 삭제 확인
- API 호출: 진입 시 API-WORKOUT-005, 삭제 확인 시 API-WORKOUT-006
- 성공 처리: 삭제 204 → WO-003으로 이동 후 목록 새로고침
- 실패 처리: 404 `WORKOUT_SESSION_NOT_FOUND`(이미 삭제됨) → 안내 후 WO-003
- 로딩 상태: 조회 중 표시, 삭제 중 버튼 비활성
- 빈 상태: 해당 없음

---

## 5. API 설계
URL·필드·날짜·페이지 규칙은 공통 설계 5장을 따른다.

### 5.1 API 목록
| API ID | Method | URL | 인증 | 설명 | 관련 요구사항 |
|--------|--------|-----|-----|-----|-------------|
| API-WORKOUT-001 | POST | /api/v1/workout-sessions | 필요 | 세션 시작 | REQ-WORKOUT-001, IF-WORKOUT-001 |
| API-WORKOUT-002 | GET | /api/v1/workout-sessions/in-progress | 필요 | 진행 중 세션 조회 | REQ-WORKOUT-001, IF-WORKOUT-002 |
| API-WORKOUT-003 | POST | /api/v1/workout-sessions/{sessionId}/complete | 필요 | 세션 완료 | REQ-WORKOUT-002, IF-WORKOUT-003 |
| API-WORKOUT-004 | GET | /api/v1/workout-sessions | 필요 | 기록 목록 | REQ-WORKOUT-003, IF-WORKOUT-004 |
| API-WORKOUT-005 | GET | /api/v1/workout-sessions/{sessionId} | 필요 | 기록 상세 | REQ-WORKOUT-004, IF-WORKOUT-005 |
| API-WORKOUT-006 | DELETE | /api/v1/workout-sessions/{sessionId} | 필요 | 기록 삭제 | REQ-WORKOUT-005, IF-WORKOUT-006 |
| API-EXERCISE-001 | GET | /api/v1/exercises | 필요 | 운동 목록·검색 | REQ-EXERCISE-001, IF-EXERCISE-001 |
| API-EXERCISE-002 | POST | /api/v1/workout-sessions/{sessionId}/exercises | 필요 | 세션에 운동 추가 | REQ-EXERCISE-001, IF-EXERCISE-002 |
| API-EXERCISE-003 | DELETE | /api/v1/workout-sessions/{sessionId}/exercises/{sessionExerciseId} | 필요 | 세션에서 운동 삭제 | REQ-EXERCISE-002, IF-EXERCISE-002 |
| API-SET-001 | POST | /api/v1/workout-sessions/{sessionId}/exercises/{sessionExerciseId}/sets | 필요 | 세트 추가 | REQ-SET-001, IF-SET-001 |
| API-SET-002 | PUT | /api/v1/workout-sessions/{sessionId}/exercises/{sessionExerciseId}/sets/{setId} | 필요 | 세트 수정 | REQ-SET-002, IF-SET-001 |
| API-SET-003 | DELETE | /api/v1/workout-sessions/{sessionId}/exercises/{sessionExerciseId}/sets/{setId} | 필요 | 세트 삭제 | REQ-SET-003, IF-SET-001 |

### 5.2 공통 응답 모델

**WorkoutSessionDetailResponse** (API-WORKOUT-001, 002, 003, 005)
```json
{
  "id": "0199b2d4-6c40-7a3e-8f21-3c5d7e9a1b02",
  "status": "COMPLETED",
  "performedDate": "2026-10-05",
  "startedAt": "2026-10-05T09:00:00Z",
  "endedAt": "2026-10-05T10:05:00Z",
  "memo": "하체 위주",
  "exercises": [
    {
      "sessionExerciseId": "0199b2d5-0a11-7b42-9c03-4d6e8f0a2c13",
      "exerciseId": "0199a0f1-2b30-7c55-8d14-5e7f9a1b3d24",
      "name": "벤치프레스",
      "category": "가슴",
      "imageUrl": "/images/exercises/bench-press.png",
      "sets": [
        { "id": "0199b2d6-1f22-7d63-a025-6f8a0b2c4e35", "setNumber": 1, "weight": 60.00, "repetitions": 10 },
        { "id": "0199b2da-3e83-7e74-b136-7a9b1c3d5f46", "setNumber": 2, "weight": 62.50, "repetitions": 8 }
      ],
      "totalSets": 2,
      "totalRepetitions": 18
    }
  ],
  "summary": {
    "durationSeconds": 3900,
    "exerciseCount": 1,
    "totalSets": 2,
    "totalRepetitions": 18,
    "totalWeight": 1100.00
  }
}
```
| 필드 | 설명 | 근거 |
|-----|-----|-----|
| `status` | `IN_PROGRESS` / `COMPLETED` | DATA-001 |
| `endedAt` | 진행 중이면 null | DATA-001 |
| `memo` | 없으면 null | DATA-001 |
| `id`, `sessionExerciseId`, `exerciseId`, `sets[].id` | UUID 문자열 (공통 5장) | DEC-ARCH-010 |
| `exercises` | 추가한 순서 | REQ-EXERCISE-001 |
| `imageUrl` | 없으면 null → 앱이 기본 이미지 | BR-014 |
| `sets` | 추가한 순서, `setNumber`는 1부터 연속 | BR-007 |
| `summary.durationSeconds` | `endedAt - startedAt` 초. 진행 중이면 null | DATA-005 |
| `summary.exerciseCount` | 세트가 1개 이상인 운동 수 | BR-010 |
| `summary.totalSets` / `totalRepetitions` | 모든 세트 수 / 반복 횟수 합 | BR-010 |
| `summary.totalWeight` | Σ(중량 × 반복 횟수), 소수 둘째 자리 | BR-010 |

**WorkoutSetResponse** (API-SET-001, 002)
```json
{ "id": "0199b2df-5b94-7f85-8247-8b0c2d4e6a57", "setNumber": 3, "weight": 62.50, "repetitions": 6 }
```

### 5.3 API 상세

#### API-WORKOUT-001 운동 세션 시작
- 목적: 진행 중 운동 세션을 만든다.
- Method / URL: `POST /api/v1/workout-sessions`
- 인증: 필요
- 관련 요구사항: REQ-WORKOUT-001, IF-WORKOUT-001, BR-004, BR-011

Request
- Header: `X-Time-Zone: Asia/Seoul` (필수)
- Body: 없음

Response `201 Created` — WorkoutSessionDetailResponse (`status` = `IN_PROGRESS`, `exercises` = [], `summary`의 합계 0, `durationSeconds` null). `Location: /api/v1/workout-sessions/{id}`

Validation
| 필드 | 타입 | 필수 | 규칙 | 근거 |
|-----|-----|-----|-----|-----|
| X-Time-Zone (헤더) | string | Y | IANA 시간대 데이터베이스의 이름(예: `Asia/Seoul`) | BR-004 |

Errors
| HTTP | 에러 코드 | 조건 | 관련 |
|------|----------|-----|-----|
| 400 | VALIDATION_FAILED | 헤더 없음·잘못된 시간대 | ERR-010 |
| 401 | UNAUTHORIZED | 인증 없음 | ERR-001 |
| 409 | WORKOUT_SESSION_ALREADY_IN_PROGRESS | 진행 중 세션이 이미 있음 | ERR-011 |

#### API-WORKOUT-002 진행 중 세션 조회
- 목적: 홈에서 이어서 할 세션이 있는지 확인한다.
- Method / URL: `GET /api/v1/workout-sessions/in-progress`
- 인증: 필요
- 관련 요구사항: REQ-WORKOUT-001, IF-WORKOUT-002, BR-011

Request: 없음

Response `200 OK` — WorkoutSessionDetailResponse / `204 No Content` — 진행 중 세션 없음

Validation: 해당 없음

Errors
| HTTP | 에러 코드 | 조건 | 관련 |
|------|----------|-----|-----|
| 401 | UNAUTHORIZED | 인증 없음 | ERR-001 |

#### API-WORKOUT-003 운동 세션 완료
- 목적: 진행 중 세션을 완료해 기록으로 확정한다.
- Method / URL: `POST /api/v1/workout-sessions/{sessionId}/complete`
- 인증: 필요
- 관련 요구사항: REQ-WORKOUT-002, IF-WORKOUT-003, BR-002, BR-008, BR-010, BR-012

Request (본문 생략 가능)
```json
{ "memo": "하체 위주" }
```

Response `200 OK` — WorkoutSessionDetailResponse (`status` = `COMPLETED`)

Validation
| 필드 | 타입 | 필수 | 규칙 | 근거 |
|-----|-----|-----|-----|-----|
| memo | string | N | 0~500자 | DATA-001 (길이는 부록 C-2) |

Errors
| HTTP | 에러 코드 | 조건 | 관련 |
|------|----------|-----|-----|
| 400 | VALIDATION_FAILED | memo 500자 초과 | ERR-010 |
| 401 | UNAUTHORIZED | 인증 없음 | ERR-001 |
| 403 | FORBIDDEN | 다른 사용자의 세션 | ERR-003 |
| 404 | WORKOUT_SESSION_NOT_FOUND | 세션 없음 | ERR-009 |
| 409 | WORKOUT_SESSION_ALREADY_COMPLETED | 이미 완료됨 | ERR-008 |
| 409 | WORKOUT_SESSION_HAS_NO_SETS | 세트가 하나도 없음 | ERR-012 |

#### API-WORKOUT-004 운동 기록 목록
- 목적: 본인의 운동 기록을 최근 수행일 순으로 페이지 조회한다.
- Method / URL: `GET /api/v1/workout-sessions?page=0&size=20`
- 인증: 필요
- 관련 요구사항: REQ-WORKOUT-003, IF-WORKOUT-004, BR-001, NFR-PERF-002

Response `200 OK`
```json
{
  "content": [
    {
      "id": "0199b2d4-6c40-7a3e-8f21-3c5d7e9a1b02",
      "status": "COMPLETED",
      "performedDate": "2026-10-05",
      "startedAt": "2026-10-05T09:00:00Z",
      "endedAt": "2026-10-05T10:05:00Z",
      "durationSeconds": 3900,
      "exerciseNames": ["벤치프레스", "스쿼트"],
      "totalSets": 8
    }
  ],
  "page": 0, "size": 20, "totalElements": 1, "totalPages": 1
}
```
- 정렬: `performedDate` 내림차순, 같은 날은 늦게 시작한 세션 먼저(`startedAt` 내림차순, 같으면 `id` 내림차순). 필터 없음(요구사항 TODO-012).
- `exerciseNames`: 추가한 순서, 같은 운동이 여러 번이면 한 번만.

Validation
| 필드 | 타입 | 필수 | 규칙 | 근거 |
|-----|-----|-----|-----|-----|
| page | int | N | ≥ 0, 기본 0 | 공통 5장 |
| size | int | N | 1~100, 기본 20 | 공통 5장 |

Errors
| HTTP | 에러 코드 | 조건 | 관련 |
|------|----------|-----|-----|
| 400 | VALIDATION_FAILED | page·size 범위 밖 | ERR-010 |
| 401 | UNAUTHORIZED | 인증 없음 | ERR-001 |

#### API-WORKOUT-005 운동 기록 상세
- 목적: 세션 하나의 운동·세트·요약을 조회한다.
- Method / URL: `GET /api/v1/workout-sessions/{sessionId}`
- 인증: 필요
- 관련 요구사항: REQ-WORKOUT-004, IF-WORKOUT-005, BR-001, BR-007, BR-010

Response `200 OK` — WorkoutSessionDetailResponse

Validation: 경로 변수 `sessionId`가 UUID 형식이 아니면 400

Errors
| HTTP | 에러 코드 | 조건 | 관련 |
|------|----------|-----|-----|
| 401 | UNAUTHORIZED | 인증 없음 | ERR-001 |
| 403 | FORBIDDEN | 다른 사용자의 세션 | ERR-003 |
| 404 | WORKOUT_SESSION_NOT_FOUND | 세션 없음·삭제됨 | ERR-009 |

#### API-WORKOUT-006 운동 기록 삭제
- 목적: 세션과 하위 운동·세트를 완전히 삭제한다.
- Method / URL: `DELETE /api/v1/workout-sessions/{sessionId}`
- 인증: 필요
- 관련 요구사항: REQ-WORKOUT-005, IF-WORKOUT-006, BR-001, NFR-INTEG-002

Response `204 No Content`

Validation: 경로 변수가 UUID 형식이 아니면 400

Errors
| HTTP | 에러 코드 | 조건 | 관련 |
|------|----------|-----|-----|
| 401 | UNAUTHORIZED | 인증 없음 | ERR-001 |
| 403 | FORBIDDEN | 다른 사용자의 세션 | ERR-003 |
| 404 | WORKOUT_SESSION_NOT_FOUND | 세션 없음·이미 삭제됨 | ERR-004 |

#### API-EXERCISE-001 운동 목록·검색
- 목적: 세션에 추가할 수 있는 운동 목록과 사용자의 최근 수행일을 조회한다.
- Method / URL: `GET /api/v1/exercises?keyword=벤치`
- 인증: 필요
- 관련 요구사항: REQ-EXERCISE-001, IF-EXERCISE-001, BR-009, BR-014

Response `200 OK`
```json
[
  {
    "id": "0199a0f1-2b30-7c55-8d14-5e7f9a1b3d24",
    "name": "벤치프레스",
    "category": "가슴",
    "imageUrl": "/images/exercises/bench-press.png",
    "lastPerformedDate": "2026-10-01"
  }
]
```
- 정렬: `category`, `name` 오름차순. 페이지 없음 (DEC-WORKOUT-011).
- `lastPerformedDate`: 사용자의 완료된 세션 기준, 한 번도 안 했으면 null (DEC-WORKOUT-012).
- 검색: 운동명 부분 일치, 대소문자 무시.

Validation
| 필드 | 타입 | 필수 | 규칙 | 근거 |
|-----|-----|-----|-----|-----|
| keyword | string | N | 앞뒤 공백 제거 후 0~50자. 빈 문자열은 생략과 같음 | 부록 C-6 |

Errors
| HTTP | 에러 코드 | 조건 | 관련 |
|------|----------|-----|-----|
| 400 | VALIDATION_FAILED | keyword 50자 초과 | ERR-010 |
| 401 | UNAUTHORIZED | 인증 없음 | ERR-001 |

#### API-EXERCISE-002 세션에 운동 추가
- 목적: 진행 중 세션에 운동을 추가한다.
- Method / URL: `POST /api/v1/workout-sessions/{sessionId}/exercises`
- 인증: 필요
- 관련 요구사항: REQ-EXERCISE-001, IF-EXERCISE-002, BR-001, BR-002, BR-009

Request
```json
{ "exerciseId": "0199a0f1-2b30-7c55-8d14-5e7f9a1b3d24" }
```

Response `201 Created` — 세션 운동 하나(WorkoutSessionDetailResponse의 `exercises[]` 항목과 같은 형태, `sets` = [])

Validation
| 필드 | 타입 | 필수 | 규칙 | 근거 |
|-----|-----|-----|-----|-----|
| exerciseId | string | Y | UUID 형식 | ERR-010 |

Errors
| HTTP | 에러 코드 | 조건 | 관련 |
|------|----------|-----|-----|
| 400 | VALIDATION_FAILED | exerciseId 없음·형식 오류 | ERR-010 |
| 401 | UNAUTHORIZED | 인증 없음 | ERR-001 |
| 403 | FORBIDDEN | 다른 사용자의 세션 | ERR-003 |
| 404 | WORKOUT_SESSION_NOT_FOUND | 세션 없음 | ERR-009 |
| 404 | EXERCISE_NOT_FOUND | 운동 없음 | ERR-002 |
| 409 | WORKOUT_SESSION_NOT_EDITABLE | 완료된 세션 | ERR-007 |

#### API-EXERCISE-003 세션에서 운동 삭제
- 목적: 세션에서 운동과 그 세트를 삭제한다.
- Method / URL: `DELETE /api/v1/workout-sessions/{sessionId}/exercises/{sessionExerciseId}`
- 인증: 필요
- 관련 요구사항: REQ-EXERCISE-002, IF-EXERCISE-002, BR-001, BR-002, NFR-INTEG-002

Response `204 No Content`

Validation: 경로 변수가 UUID 형식이 아니면 400

Errors
| HTTP | 에러 코드 | 조건 | 관련 |
|------|----------|-----|-----|
| 401 | UNAUTHORIZED | 인증 없음 | ERR-001 |
| 403 | FORBIDDEN | 다른 사용자의 세션 | ERR-003 |
| 404 | WORKOUT_SESSION_NOT_FOUND | 세션 없음 | ERR-009 |
| 404 | SESSION_EXERCISE_NOT_FOUND | 이 세션에 그 운동 없음 | ERR-009 |
| 409 | WORKOUT_SESSION_NOT_EDITABLE | 완료된 세션 | ERR-007 |

#### API-SET-001 세트 추가
- 목적: 세션 운동에 세트를 추가한다.
- Method / URL: `POST /api/v1/workout-sessions/{sessionId}/exercises/{sessionExerciseId}/sets`
- 인증: 필요
- 관련 요구사항: REQ-SET-001, IF-SET-001, BR-002, BR-003, BR-005, BR-007

Request
```json
{ "weight": 62.5, "repetitions": 8 }
```

Response `201 Created` — WorkoutSetResponse

Validation
| 필드 | 타입 | 필수 | 규칙 | 근거 |
|-----|-----|-----|-----|-----|
| weight | number | Y | 0 이상 1,000 이하, 소수 둘째 자리까지 | BR-005 |
| repetitions | integer | Y | 1 이상 1,000 이하. `8.5` 같은 소수는 거부(공통 5장) | BR-003 |

Errors
| HTTP | 에러 코드 | 조건 | 관련 |
|------|----------|-----|-----|
| 400 | VALIDATION_FAILED | 범위·형식 위반 | ERR-005 |
| 400 | VALIDATION_FAILED | 필수값 누락 | ERR-010 |
| 401 | UNAUTHORIZED | 인증 없음 | ERR-001 |
| 403 | FORBIDDEN | 다른 사용자의 세션 | ERR-003 |
| 404 | WORKOUT_SESSION_NOT_FOUND | 세션 없음 | ERR-009 |
| 404 | SESSION_EXERCISE_NOT_FOUND | 이 세션에 그 운동 없음 | ERR-009 |
| 409 | WORKOUT_SESSION_NOT_EDITABLE | 완료된 세션 | ERR-007 |

#### API-SET-002 세트 수정
- 목적: 세트의 중량·반복 횟수를 고친다.
- Method / URL: `PUT /api/v1/workout-sessions/{sessionId}/exercises/{sessionExerciseId}/sets/{setId}`
- 인증: 필요
- 관련 요구사항: REQ-SET-002, IF-SET-001, BR-002, BR-003, BR-005

Request / Validation: API-SET-001과 같다.

Response `200 OK` — WorkoutSetResponse (`setNumber` 변경 없음)

Errors: API-SET-001과 같고, 추가로
| HTTP | 에러 코드 | 조건 | 관련 |
|------|----------|-----|-----|
| 404 | WORKOUT_SET_NOT_FOUND | 그 세션 운동에 세트 없음 | ERR-009 |

#### API-SET-003 세트 삭제
- 목적: 세트를 삭제한다.
- Method / URL: `DELETE /api/v1/workout-sessions/{sessionId}/exercises/{sessionExerciseId}/sets/{setId}`
- 인증: 필요
- 관련 요구사항: REQ-SET-003, IF-SET-001, BR-002, BR-007

Response `204 No Content`

Validation: 경로 변수가 UUID 형식이 아니면 400

Errors
| HTTP | 에러 코드 | 조건 | 관련 |
|------|----------|-----|-----|
| 401 | UNAUTHORIZED | 인증 없음 | ERR-001 |
| 403 | FORBIDDEN | 다른 사용자의 세션 | ERR-003 |
| 404 | WORKOUT_SESSION_NOT_FOUND | 세션 없음 | ERR-009 |
| 404 | SESSION_EXERCISE_NOT_FOUND | 이 세션에 그 운동 없음 | ERR-009 |
| 404 | WORKOUT_SET_NOT_FOUND | 그 세션 운동에 세트 없음 | ERR-009 |
| 409 | WORKOUT_SESSION_NOT_EDITABLE | 완료된 세션 | ERR-007 |

---

## 6. 데이터 설계
이름 규칙·논리 타입·제약 종류·`users` 테이블은 공통 설계 6장을 따른다.

### 6.1 ERD
```
users 1 ──── N workout_session 1 ──── N workout_session_exercise 1 ──── N workout_set
                                               N
                                               │
                                               1
                                           exercise
```

### 6.2 테이블 정의

#### exercise — 근거: DATA-002
| 컬럼 | 타입 | Null | 기본값 | 설명 | 근거 |
|-----|-----|------|-------|-----|-----|
| id | ID | N | 애플리케이션 생성 | PK | DEC-ARCH-010 |
| name | 문자열(100) | N | | 운동명 | DATA-002 |
| category | 문자열(30) | N | | 카테고리(값 목록은 TODO-014) | DATA-002 |
| image_url | 문자열(500) | Y | | 이미지 URL 경로. 예: `/images/exercises/bench-press.png` | DATA-002, BR-014 |
| created_at | 시각 | N | 현재 시각 | | 공통 |
| updated_at | 시각 | N | 현재 시각 | | 공통 |

- PK: `id`
- 유일 `ux_exercise_name`: `name` — 같은 운동이 다른 행으로 중복되지 않게 한다 (요구사항 TODO-002 결정 이유)
- 사용자는 이 테이블을 변경하지 않는다 (BR-009). 데이터는 스키마 변경 스크립트로만 넣는다.

#### workout_session — 근거: DATA-001
| 컬럼 | 타입 | Null | 기본값 | 설명 | 근거 |
|-----|-----|------|-------|-----|-----|
| id | ID | N | 애플리케이션 생성 | PK | DEC-ARCH-010 |
| user_id | ID | N | | 소유자 | BR-001, NFR-INTEG-001 |
| status | 열거(IN_PROGRESS, COMPLETED) | N | | 세션 상태 | DATA-001 |
| performed_date | 날짜 | N | | 수행 날짜(사용자 현지) | BR-004 |
| started_at | 시각 | N | | 시작 시각 | DATA-001 |
| ended_at | 시각 | Y | | 종료 시각, 진행 중이면 Null | DATA-001, BR-008 |
| memo | 문자열(500) | Y | | 완료 시 메모 | DATA-001 (길이 부록 C-2) |
| created_at | 시각 | N | 현재 시각 | | 공통 |
| updated_at | 시각 | N | 현재 시각 | | 공통 |

- PK: `id`
- 참조(함께 삭제): `user_id → users.id` — 운영자가 계정을 삭제하면 그 사용자의 운동 기록도 모두 삭제 (auth BR-012, 공통 6.1, DEC-WORKOUT-014)
- 조건 검사 `ck_workout_session_ended`: `(status = IN_PROGRESS 이고 ended_at 없음) 또는 (status = COMPLETED 이고 ended_at 있음 이고 ended_at >= started_at)` — BR-008, 상태와 종료 시각의 일관성
- 조건부 유일 `ux_workout_session_user_in_progress`: `status = IN_PROGRESS`인 행 사이에서 `user_id` 유일 — BR-011을 동시 요청에서도 보장. 위반은 제약 위반 변환으로 409

#### workout_session_exercise — 근거: DATA-003
| 컬럼 | 타입 | Null | 기본값 | 설명 | 근거 |
|-----|-----|------|-------|-----|-----|
| id | ID | N | 애플리케이션 생성 | PK | DEC-ARCH-010 |
| workout_session_id | ID | N | | 소속 세션 | DATA-003 |
| exercise_id | ID | N | | 운동 | DATA-003, BR-009 |
| created_at | 시각 | N | 현재 시각 | 추가한 시각. 세션 안 운동 순서의 기준 | DATA-003, DEC-WORKOUT-002 |

- PK: `id`
- 참조(함께 삭제): `workout_session_id → workout_session.id` (NFR-INTEG-002)
- 참조(삭제 금지): `exercise_id → exercise.id` — 사용 중인 운동은 지울 수 없게
- 같은 세션에 같은 운동을 여러 번 추가하는 것을 막지 않는다 (부록 C-3)
- 수정되지 않는 행이라 `updated_at`이 없다.

#### workout_set — 근거: DATA-004
| 컬럼 | 타입 | Null | 기본값 | 설명 | 근거 |
|-----|-----|------|-------|-----|-----|
| id | ID | N | 애플리케이션 생성 | PK | DEC-ARCH-010 |
| workout_session_exercise_id | ID | N | | 소속 세션 운동 | DATA-004 |
| weight | 소수(6,2) | N | | 중량(kg) | BR-005 |
| repetitions | 정수 | N | | 반복 횟수 | BR-003 |
| created_at | 시각 | N | 현재 시각 | 추가한 시각. 세트 순서의 기준 | DATA-004, BR-007, BR-013 |
| updated_at | 시각 | N | 현재 시각 | | 공통 |

- PK: `id`
- 참조(함께 삭제): `workout_session_exercise_id → workout_session_exercise.id` (NFR-INTEG-002)
- 조건 검사 `ck_workout_set_weight`: `0 <= weight <= 1000` (BR-005)
- 조건 검사 `ck_workout_set_repetitions`: `1 <= repetitions <= 1000` (BR-003)

### 6.3 인덱스
| 인덱스 | 테이블 | 컬럼 | 대상 조회 | 근거 |
|-------|-------|-----|---------|-----|
| ux_exercise_name | exercise | (name), 유일 | 중복 방지 | DATA-002 |
| ux_workout_session_user_in_progress | workout_session | (user_id), 조건부 유일: status = IN_PROGRESS | 진행 중 세션 조회(API-WORKOUT-002), 시작 시 확인, cleanUp 대상 조회 | BR-011, BR-013 |
| idx_workout_session_user_performed | workout_session | (user_id, performed_date 내림차순, started_at 내림차순, id 내림차순) | 기록 목록 정렬·페이지, 운동별 최근 수행일 집계 | REQ-WORKOUT-003, NFR-PERF-002 |
| idx_workout_session_exercise_session | workout_session_exercise | (workout_session_id, created_at, id) | 상세·목록의 세션별 운동을 추가 순서로 조회, 함께 삭제 | REQ-WORKOUT-004, NFR-PERF-002 |
| idx_workout_set_session_exercise | workout_set | (workout_session_exercise_id, created_at, id) | 상세의 운동별 세트를 추가 순서로 조회, 세트 번호 계산, cleanUp의 세트 존재 확인, 함께 삭제 | REQ-WORKOUT-004, BR-013 |

`workout_session_exercise.exercise_id`에는 인덱스를 두지 않는다. 운동별 조회는 사용자 세션에서 출발하므로(idx_workout_session_user_performed → idx_workout_session_exercise_session) 필요 없다. `exercise` 행은 삭제하지 않으므로 참조 확인용 인덱스도 필요 없다.

### 6.4 삭제 정책
- 요구사항 TODO-001 결정대로 실제 삭제한다. 보관 컬럼(`deleted_at`)은 두지 않는다.
- `workout_session` 삭제 → `workout_session_exercise` → `workout_set`이 참조(함께 삭제)로 같은 트랜잭션에서 삭제된다 (NFR-INTEG-002).
- 계정(`users`) 삭제 → 그 사용자의 `workout_session`과 하위 데이터가 모두 참조(함께 삭제)로 삭제된다 (auth BR-012).
- `exercise`는 삭제하지 않는다(사용 중이면 참조(삭제 금지)가 막는다).

### 6.5 스키마 변경 목록
인증 설계의 스키마 변경 1·2(users, login_session) 다음 순서로 적용한다. 스크립트 파일 규칙은 공통 설계 10.1을 따른다.

| 순서 | 변경 | 내용 |
|-----|-----|-----|
| 3 | exercise 생성 | `exercise` 테이블, `ux_exercise_name` |
| 4 | 운동 세션 생성 | `workout_session`, `workout_session_exercise`, `workout_set`과 6.2의 제약, 6.3의 인덱스 |
| 5 (보류) | 초기 운동 목록 투입 | TODO-014 결정 후 (D-TODO-WORKOUT-001) |

---

## 7. 인증 / 인가 및 보안 설계
인증 흐름·토큰·CORS·CSRF는 공통 설계 7장을 따른다.

### 7.1 API별 인증
- 5.1의 API 12개는 모두 인증 필요 (NFR-SEC-001).
- 공개 정적 파일 `GET /images/exercises/**`는 인증 없이 허용한다. 공개 운동 이미지만 있고 사용자 데이터가 없다 (DEC-WORKOUT-008).

### 7.2 사용자별 데이터 접근 제한
- userId는 인증 필터가 확인한 로그인에서만 얻는다 (auth 설계 3.2).
- 경로의 `sessionId`: 세션 조회 → 없으면 404 → `user_id` 불일치면 403 `FORBIDDEN` (BR-001, ERR-003, DEC-WORKOUT-007).
- 경로의 `sessionExerciseId`, `setId`: 상위 리소스에 속하는지 확인한다(`workout_session_exercise.workout_session_id = sessionId`, `workout_set.workout_session_exercise_id = sessionExerciseId`). 속하지 않으면 404. 다른 사용자의 세트 ID를 자기 세션 경로에 넣어도 접근할 수 없다.
- 목록·운동 최근 수행일·cleanUp 조회는 모두 `user_id = userId` 조건을 가진다 (NFR-SEC-002).

### 7.3 민감 데이터 / 로그
- `memo`는 사용자가 쓴 자유 텍스트라 로그에 남기지 않는다.
- 로그에는 이벤트 이름, userId, sessionId만 남긴다.

---

## 8. 예외 / 에러 처리 설계
응답 형식·상태 코드 정책·공통 에러 코드는 공통 설계 8장을 따른다.

### 8.1 에러 응답 예
```json
{
  "code": "WORKOUT_SESSION_HAS_NO_SETS",
  "message": "세트를 하나 이상 기록해야 운동을 완료할 수 있습니다.",
  "timestamp": "2026-10-05T18:00:00Z"
}
```

### 8.2 이 기능의 에러 코드
| 요구사항 ERR | 에러 코드 | HTTP | 메시지 | 발생 위치 |
|-------------|----------|------|-------|----------|
| ERR-001 | UNAUTHORIZED (공통) | 401 | 로그인이 필요합니다. | 인증 필터 |
| ERR-002 | EXERCISE_NOT_FOUND | 404 | 운동을 찾을 수 없습니다. | WorkoutSessionService.addExercise |
| ERR-003 | FORBIDDEN (공통) | 403 | 접근할 수 없는 데이터입니다. | WorkoutSessionService 소유자 확인 |
| ERR-004 | WORKOUT_SESSION_NOT_FOUND | 404 | 운동 기록을 찾을 수 없습니다. | WorkoutSessionService.delete |
| ERR-005 | VALIDATION_FAILED (공통) | 400 | 입력값이 올바르지 않습니다. (`errors[]`에 필드별 사유) | 요청 검증 |
| ERR-007 | WORKOUT_SESSION_NOT_EDITABLE | 409 | 완료된 운동 기록은 수정할 수 없습니다. | WorkoutSessionService (E) |
| ERR-008 | WORKOUT_SESSION_ALREADY_COMPLETED | 409 | 이미 완료된 운동입니다. | WorkoutSessionService.complete |
| ERR-009 | WORKOUT_SESSION_NOT_FOUND | 404 | 운동 기록을 찾을 수 없습니다. | WorkoutSessionService (E), 상세 |
| ERR-009 | SESSION_EXERCISE_NOT_FOUND | 404 | 운동 기록에서 해당 운동을 찾을 수 없습니다. | WorkoutSessionService |
| ERR-009 | WORKOUT_SET_NOT_FOUND | 404 | 세트를 찾을 수 없습니다. | WorkoutSessionService |
| ERR-010 | VALIDATION_FAILED (공통) | 400 | 입력값이 올바르지 않습니다. | 요청 검증, 시간대 헤더 해석 |
| ERR-011 | WORKOUT_SESSION_ALREADY_IN_PROGRESS | 409 | 이미 진행 중인 운동이 있습니다. | WorkoutSessionService.start (조회 확인 + 제약 위반 변환) |
| ERR-012 | WORKOUT_SESSION_HAS_NO_SETS | 409 | 세트를 하나 이상 기록해야 운동을 완료할 수 있습니다. | WorkoutSessionService.complete |

제약 위반 변환 대상: `ux_workout_session_user_in_progress` → 409 `WORKOUT_SESSION_ALREADY_IN_PROGRESS`만 지정한다. 그 밖의 DB 제약 위반은 정상 흐름에서 나오면 안 되는 버그이므로 500으로 두고 ERROR 로그를 남긴다(공통 8.4).

---

## 9. 비기능 요구사항 설계
| NFR ID | 요구사항 | 설계 대응 | 확인 방법 |
|--------|---------|----------|----------|
| NFR-PERF-001 | 요청의 95%가 500ms 이내 | 요청당 쿼리 수 고정: 상세 1회, 목록 2회(목록+개수), 운동 목록 1회, cleanUp 2회. 모든 조회 조건에 6.3 인덱스 | 부하 테스트 p95 측정 (D-TODO-ARCH-004) |
| NFR-PERF-002 | 기록이 많아도 목록 성능 유지 | 페이지 조회 + `idx_workout_session_user_performed`. 운동명·세트 수는 페이지 대상 세션에만 집계 | 사용자 1명에 세션 1,000건을 넣고 마지막 페이지 조회 시간 측정 |
| NFR-PERF-003 | 동시 1,000명 | 무상태 서버(공통 9장). 변경 잠금은 사용자 자신의 세션에만 걸려 사용자 간 경합이 없다 | 부하 테스트 (D-TODO-ARCH-004) |
| NFR-SEC-001 | 인증된 사용자만 | 7.1, 모든 API 인증 필요 | 토큰 없는 요청이 API마다 401인지 테스트 |
| NFR-SEC-002 | 본인 데이터만 | 7.2 소유자 확인, 상위 리소스 소속 확인, 조회 조건 `user_id` | 다른 사용자 세션·세트에 대해 API마다 403/404 테스트 |
| NFR-AVAIL-001 | 장애 시 오류 반환, 부분 저장 없음 | 요청 하나 = 트랜잭션 하나, 삭제는 참조(함께 삭제)로 한 번에, 오류는 공통 8장 형식 | 강제 예외 시 500 JSON과 데이터 무변경 테스트 |
| NFR-INTEG-001 | 없는 사용자의 기록 생성 불가 | 참조 `workout_session.user_id → users.id` | 없는 userId로 세션 생성 시 실패 테스트 |
| NFR-INTEG-002 | 삭제 시 하위 데이터 남지 않음 | 참조(함께 삭제) 2단 | 세션 삭제 후 운동·세트 행 0건 테스트 |
| NFR-LOG-001 | 주요 행위·자동 처리·오류 추적 | INFO 이벤트: `workout_session.started`, `.completed`, `.deleted`, `.auto_completed`, `.auto_deleted` (userId, sessionId). 처리하지 못한 예외는 ERROR | 로그 출력 확인 테스트 |

---

## 10. 구현 구조 및 개발 전략

### 10.1 컴포넌트 구성
역할은 공통 설계 2.2, 패키지·파일 배치와 구현 기술은 공통 설계 10장을 따른다.

| 도메인 | 컴포넌트 | 역할 | 책임 |
|-------|---------|-----|-----|
| exercise | ExerciseController | API 진입점 | API-EXERCISE-001 |
| exercise | ExerciseService | 서비스 | 검색어 정리·검증, 운동 목록 조회 |
| exercise | ExerciseRepository | 저장소 | 운동 단건·존재 확인 (BR-009) |
| exercise | ExerciseQueryRepository | 조회 저장소 | 운동 목록 + 사용자별 최근 수행일 |
| session | WorkoutSessionController | API 진입점 | `/workout-sessions/**` API 11개(세션·운동·세트) |
| session | WorkoutSessionService | 서비스 | 세션 묶음(세션·운동·세트)의 모든 쓰기와 규칙: 소유자·상태 확인, 변경 잠금, 완료 조건, 요약 계산 |
| session | ExpiredSessionCleaner | 서비스 (독립 트랜잭션) | REQ-WORKOUT-006 정리 |
| session | WorkoutSessionRepository, WorkoutSessionExerciseRepository, WorkoutSetRepository | 저장소 | 행 저장·수정·삭제, 단건 조회, 세션 변경 잠금 조회 |
| session | WorkoutSessionQueryRepository | 조회 저장소 | 상세 조회, 목록 페이지, cleanUp 조건부 일괄 갱신 |

### 10.2 구현 순서
| 순서 | 작업 | 관련 요구사항 | 완료 기준 |
|-----|-----|-------------|----------|
| 0 | 공통 기반 (공통 설계 10.6) | NFR-SEC-001, NFR-AVAIL-001 | 공통 설계 10.6 완료 기준 |
| 1 | 스키마 변경 3·4 적용 (6.5, 인증 스키마 1·2 이후) | DATA-001~004 | 스키마 적용 성공, 조회 저장소에서 테이블 사용 가능 |
| 2 | 운동 목록 API | REQ-EXERCISE-001, BR-014 | API-EXERCISE-001 테스트(검색, 최근 수행일 null) |
| 3 | 세션 시작·진행 중 조회 + ExpiredSessionCleaner | REQ-WORKOUT-001, REQ-WORKOUT-006, BR-004, BR-011, BR-013 | 201, 중복 409, 동시 시작 시 1건, 6시간 경과 세션 정리 테스트 |
| 4 | 상세 조회 | REQ-WORKOUT-004, BR-007, BR-010 | 세트 번호·요약 계산 테스트 |
| 5 | 세션에 운동 추가·삭제 | REQ-EXERCISE-001, REQ-EXERCISE-002, BR-009 | 없는 운동 404, 함께 삭제 테스트 |
| 6 | 세트 추가·수정·삭제 | REQ-SET-001~003, BR-003, BR-005 | 범위 경계값, 소수 반복 횟수 거부, 삭제 후 번호 재계산 테스트 |
| 7 | 세션 완료 | REQ-WORKOUT-002, BR-002, BR-012 | 세트 0건 409, 완료 후 변경 409 테스트 |
| 8 | 기록 목록 | REQ-WORKOUT-003, NFR-PERF-002 | 정렬·페이지·다른 사용자 제외 테스트 |
| 9 | 기록 삭제 | REQ-WORKOUT-005, NFR-INTEG-002 | 204, 재삭제 404, 하위 행 0건 테스트 |

### 10.3 테스트 포인트
- BR-011: 같은 사용자가 동시에 두 번 시작해도 진행 중 세션은 하나이고, 하나는 409.
- BR-013: `started_at`을 6시간 전으로 만든 세션 — 세트가 있으면 다음 요청에서 `COMPLETED`이고 `ended_at` = 마지막 세트의 `created_at`. 세트가 없으면 삭제됨. 정리 직후 새 세션을 시작할 수 있다.
- BR-013 + BR-002: 6시간 지난 세션에 세트를 추가하면 409 `WORKOUT_SESSION_NOT_EDITABLE`이고, 세션은 완료 상태로 남는다(정리가 취소되지 않음).
- BR-004: `X-Time-Zone: Asia/Seoul`로 UTC 15:30(현지 다음 날 00:30)에 시작하면 `performedDate`가 현지 날짜.
- BR-003·BR-005 경계값: 반복 0/1/1000/1001/8.5, 중량 -0.01/0/1000/1000.01/62.555.
- BR-007: 세트 3개 중 2번째 삭제 후 상세 조회하면 번호가 1, 2.
- BR-010: 요약 `totalWeight` = Σ(중량×반복), 세트 없는 운동은 `exerciseCount`에서 빠짐.
- BR-012 + 동시성: 완료 요청과 세트 추가가 동시에 와도 완료된 세션에 세트가 생기지 않는다.
- NFR-SEC-002: 다른 사용자의 세션 ID로 모든 세션 API를 호출하면 403. 다른 사용자의 세트 ID를 자기 세션 경로에 넣으면 404.
- NFR-INTEG-002: 세션 삭제 후 운동·세트 행 0건.

---

## 부록 A. 설계 결정 기록
| ID | 결정 | 이유 | 버린 대안 |
|----|-----|-----|----------|
| DEC-WORKOUT-001 | 세션·세션 운동·세트를 하나의 묶음으로 보고 `/workout-sessions/**` 아래 중첩 경로, API 진입점·서비스 각 1개로 다룬다 | 모든 변경이 세션 상태·소유자 확인을 거쳐야 한다. 확인 로직이 한 곳에 모인다 | 운동·세트별 컴포넌트: 같은 세션 확인을 여러 곳에서 반복 |
| DEC-WORKOUT-002 | 세트 번호와 세션 안 운동 순서를 저장하지 않고 추가한 시각(`created_at` 오름차순, 같으면 `id`)으로 계산 (v0.3: `id` 순서에서 변경 — ID가 UUIDv7이라 순서 기준으로 쓰지 않는다, DEC-ARCH-010) | BR-007(빈 번호 없음)이 자동으로 지켜지고, 삭제 시 번호를 다시 쓰는 갱신이 없다. 변경 요청은 세션 변경 잠금으로 하나씩 처리되므로 같은 운동의 세트끼리 추가 시각이 겹치지 않는다 | 번호 컬럼 저장: 삭제할 때마다 재정렬 갱신, 동시 변경 시 중복 위험 |
| DEC-WORKOUT-003 | 세션 요약(DATA-005)을 저장하지 않고 상세 조회 결과로 계산, 목록의 세트 수는 조회 시 집계 | 세트가 바뀔 때 요약을 함께 갱신할 필요가 없어 값이 어긋날 수 없다. 세션당 세트 수가 적어 계산 비용이 작다 | 요약 컬럼 저장: 세트 변경마다 갱신 필요 |
| DEC-WORKOUT-004 | 방치된 세션 정리(REQ-WORKOUT-006)를 주기 작업 없이, 세션 관련 API 처리 전에 요청한 사용자 것만 독립 트랜잭션으로 실행 | 사용자가 결과를 보는 시점(다음 요청)에 항상 정리되어 있다. 주기 작업 실행 환경과 여러 서버 간 중복 실행 문제가 없다. 독립 트랜잭션이라 뒤이은 요청이 실패해도 정리는 유지된다. **한계:** 사용자가 다시 접속하지 않으면 DB에 6시간 넘은 `IN_PROGRESS` 행이 남는다. 통계처럼 다른 사용자 데이터를 모아 보는 기능이 생기면 주기 작업을 추가한다 | 주기 작업: 실행 간격만큼 늦게 정리되어 결국 요청 시 확인도 필요하고, 여러 서버 실행 대비가 필요 |
| DEC-WORKOUT-005 | 세션 변경 요청(운동·세트 변경, 완료, 삭제)은 세션을 변경 잠금으로 조회 | 완료와 세트 추가가 동시에 와도 완료된 세션에 세트가 생기지 않는다. 잠금은 사용자 자신의 세션에만 걸려 경합이 거의 없다 | 낙관적 잠금(버전 비교): 충돌 시 재시도 처리가 앱까지 필요 |
| DEC-WORKOUT-006 | BR-011은 조회 확인 + 조건부 유일 제약, 위반을 409로 변환 | 조회 확인만으로는 동시 요청을 막지 못한다 | 사용자 행에 변경 잠금: users 테이블에 기능이 의존하게 됨 |
| DEC-WORKOUT-007 | 다른 사용자의 데이터는 403 | 요구사항 ERR-003("권한 오류")을 따른다. 존재 노출 문제는 부록 C-5 | 404로 숨김: 요구사항과 다름 |
| DEC-WORKOUT-008 | 운동 이미지는 애플리케이션과 함께 배포되는 공개 정적 파일로 URL `/images/exercises/**`에서 제공하고, `exercise.image_url`에 URL 경로를 저장 | 이미지는 운영팀이 준비하는 고정 목록이고 사용자 업로드가 없다(요구사항 8.1). 외부 저장소가 필요 없다. **한계:** 이미지를 바꾸려면 배포가 필요하다. 이미지가 많아지거나 자주 바뀌면 오브젝트 스토리지로 옮긴다 | 오브젝트 스토리지: 지금 규모에 인프라·비용 추가 |
| DEC-WORKOUT-009 | 존재하지 않는 운동을 추가하면 404 `EXERCISE_NOT_FOUND` | 공통 정책 "대상 없음 = 404"와 일관 | 400: 경로·본문 참조에 따라 상태 코드가 달라져 앱 처리가 복잡 |
| DEC-WORKOUT-010 | ~~JPA 엔티티 간 연관관계 매핑 없이 참조 ID만~~ **폐기 (v0.2)** — 기술에 묶인 결정이라 공통 설계 DEC-ARCH-008로 옮김 | — | — |
| DEC-WORKOUT-011 | 운동 목록 API는 페이지 없이 전체를 반환 | 운영팀이 관리하는 작은 목록으로 본다. 규모가 TODO-014에서 정해지면 다시 본다 (D-TODO-WORKOUT-002) | 페이지: 지금은 앱에 불필요한 복잡도 |
| DEC-WORKOUT-012 | 운동의 최근 수행일은 완료된 세션 기준 | 진행 중 세션은 아직 확정되지 않은 기록이다. 부록 C-4 | 진행 중 포함: 지금 하고 있는 운동이 "최근 수행"으로 보임 |
| DEC-WORKOUT-013 | ~~반복 횟수에 소수가 오면 400~~ **폐기 (v0.2)** — 모든 정수 필드에 해당하는 규칙이라 공통 설계 5장("정수 필드")으로 옮김 | — | — |
| DEC-WORKOUT-014 | `workout_session.user_id`를 참조(함께 삭제)로 둔다 (v0.4: 삭제 금지에서 변경) | 계정을 삭제하면 그 사용자의 운동 기록도 모두 삭제해야 한다(auth BR-012). 운영자의 계정 삭제 SQL 한 문장으로 끝난다 | 삭제 금지 유지 + 운영자가 운동 기록부터 삭제: 절차가 길고 빠뜨리면 계정 삭제가 실패 |

## 부록 B. 설계 미결정 사항
- **D-TODO-WORKOUT-001** 초기 운동 목록 투입(스키마 변경 5)과 이미지 파일. 요구사항 TODO-014 결정을 기다린다. (영향: 6.5, DEC-WORKOUT-008, EX-001)
- **D-TODO-WORKOUT-002** 운동 목록 페이지 나누기 필요 여부. TODO-014에서 정한 운동 개수를 보고 정한다. (영향: API-EXERCISE-001, DEC-WORKOUT-011)
- **D-TODO-WORKOUT-003** Figma 화면과 4장 대조. Figma 링크를 받으면 화면 데이터·흐름이 요구사항 3장과 같은지 확인한다. (영향: 4장)

## 부록 C. 요구사항 피드백
설계하면서 요구사항에 정해지지 않았거나 확인이 필요한 점이다. 설계는 괄호의 안으로 진행했으며, 요구사항을 고치면 설계도 따라 고친다.

1. **BR-004 시간대 출처:** "사용자가 있는 지역의 날짜"를 알려면 시스템이 사용자 시간대를 알아야 하는데, 요구사항에 출처가 없다. (설계: 앱이 `X-Time-Zone` 헤더로 기기 시간대를 보낸다. DEC-ARCH-007)
2. **메모 길이:** DATA-001의 메모에 길이 제한이 없다. (설계: 500자 제안)
3. **같은 운동 중복 추가:** 한 세션에 같은 운동을 두 번 추가할 수 있는지 정해지지 않았다. (설계: 허용. 예를 들어 스쿼트를 처음과 끝에 두 번 할 수 있다)
4. **EX-001 최근 수행일:** 진행 중 세션을 포함하는지 정해지지 않았다. (설계: 완료된 세션만. DEC-WORKOUT-012)
5. **ERR-003과 2.2의 충돌 가능성:** 2.2는 "다른 사용자의 기록은 존재 여부와 관계없이 볼 수 없다"고 하는데, ERR-003의 403 응답은 그 ID의 기록이 **존재한다는 사실**을 드러낸다. 존재 자체를 숨기려면 다른 사용자의 기록도 404로 응답해야 한다. (설계: ERR-003대로 403. DEC-WORKOUT-007)
6. **운동 검색 규칙:** 검색어 길이와 일치 방식이 정해지지 않았다. (설계: 앞뒤 공백 제거, 최대 50자, 운동명 부분 일치, 대소문자 무시)
