# 운동 기록 기능 설계 문서

- 문서 버전: v0.7
- 작성일: 2026-10-05
- 상태: 초안
- 요구사항: `docs/requirements/workout-record.md` (v0.6)
- 공통 설계: `docs/design/architecture.md` (v0.10)
- 관련 설계: `docs/design/auth.md` (인증 필터, users, 홈의 로그아웃), `docs/design/workout-media.md` (운동 완료 시 사진·동영상)
- Figma: 2행 `home-workout-start`(24:2), `home-muscle-selection`(4:8), `workout-complete-popup`(18:4), `로그아웃 확인 팝업`(83:104) / 3행 `exercise-list`(4:77), `workout-recording`(4:164), `이전 기록 덮어쓰기 확인`(83:201), `workout-recording-prev-loaded`(15:6), `today-workout-popup`(30:10) / 4행 `workout-history`(5:31), `media-viewer-popup`(18:168)
- 변경 이력:
  - v0.2 — 기술 중립 용어로 다시 씀. 기술 대응은 공통 설계 10.1을 따른다
  - v0.3 — 모든 PK를 UUIDv7로 변경(공통 DEC-ARCH-010). 세트·운동 순서와 목록 정렬을 ID 대신 시각 컬럼 기준으로 변경
  - v0.4 — 인증 설계 반영: `workout_session.user_id`를 참조(함께 삭제)로 변경(auth BR-012, DEC-WORKOUT-014), 스키마 변경 순서를 인증 스키마 뒤로, 사용자 ID 출처를 인증 필터로
  - v0.5 — 구현 반영: 방치 세션 정리의 기준 시각을 DB 시각 대신 주입한 시계로(공통 10.1, 테스트에서 시간 이동), 스키마 변경 번호를 인증 V1 통합에 맞춰 2·3으로
  - v0.6 (2026-10-09) — 요구사항 v0.5와 Figma 2행(홈, 운동 완료 팝업)에 맞춤.
    - 추가: 부위 목록(API-EXERCISE-004, `exercise_category`, 초기 운동 목록 24개), 진행 중 세션 현황의 부위별·종목별 볼륨(REQ-WORKOUT-009), 운동 취소(REQ-WORKOUT-010, API-WORKOUT-006을 진행 중 세션 전용으로), 완료 시 사진·동영상(`mediaIds`, workout-media), 방치된 세션 정리 때 파일 삭제
    - 요구사항에서 빠진 것 정리: 메모(TODO-020), 운동 검색(TODO-017), 종목 이미지(TODO-018, BR-014 폐기), 목록·상세 조회 API-WORKOUT-004·005 폐기(REQ-WORKOUT-003·004 폐기), 같은 종목 중복 추가 금지(REQ-EXERCISE-001)
    - 운영 사용 전이라 스키마 변경 2·3을 다시 만든다(사용자 결정, DEC-WORKOUT-016)
    - Figma 3·4행 기능(이전 기록 불러오기, 운동 기록 달력, 날짜별 기록·삭제)은 보류(D-TODO-WORKOUT-004)
  - v0.7 (2026-10-09) — Figma 3·4행 설계(D-TODO-WORKOUT-004 결정): 이전 기록 조회(API-SET-004)와 종목 세트 모두 삭제(API-SET-005), 월별 운동 달력(API-WORKOUT-007), 날짜별 기록(API-WORKOUT-008), 날짜 단위 삭제(API-WORKOUT-009), 화면 EX-001 ~ EX-003·WO-003 연계. v0.6 설계는 코드에 반영됨(공통 v0.9, workout-media v0.1과 함께)

---

## 1. 설계 개요

### 1.1 목적
운동 세션 시작부터 세트 기록(이전 기록 불러오기 포함), 완료(사진·동영상 첨부 포함), 취소까지와, 홈이 쓰는 부위 목록·진행 중 현황, 운동 기록 달력·날짜별 기록·삭제를 REST API 16개와 테이블 5개로 구현하는 방법을 확정한다. 운동 세션을 중심 단위로 두고, 세션 안의 운동과 세트는 세션의 하위 리소스로 다룬다. 지난 기록은 사용자에게 날짜 단위로 보이므로 "운동한 날"(`workout-days`)을 조회·삭제의 단위로 둔다(DEC-WORKOUT-019).

### 1.2 설계 범위
- **Figma 2행 (홈, 운동 완료 팝업):** REQ-WORKOUT-001, 002, 006, 009, 010, 부위 목록(IF-EXERCISE-003), DATA-006과 초기 운동 목록(요구사항 부록 A) — v0.6에서 설계, 코드 반영됨
- **Figma 3행 (운동 선택, 운동 기록, 오늘 한 운동):** REQ-EXERCISE-001, 002, REQ-SET-001 ~ 003, REQ-SET-005 이전 기록 불러오기(v0.7), 화면 EX-001 ~ EX-003
- **Figma 4행 (운동 기록 달력):** REQ-WORKOUT-005 날짜 단위 삭제, REQ-WORKOUT-007 월별 달력, REQ-WORKOUT-008 날짜별 기록(v0.7), 화면 WO-003. 사진·동영상 목록·뷰어는 workout-media 설계
- **보류:** 없음
- **제외:** 요구사항 8.1의 범위 밖 항목. 폐기된 요구사항 REQ-SET-004, REQ-WORKOUT-003, REQ-WORKOUT-004, BR-006, BR-014, ERR-006, IF-SET-002, IF-WORKOUT-004, IF-WORKOUT-005, 화면 WO-004는 설계하지 않는다.

### 1.3 대상 시스템
- Backend API: 도메인 `exercise`(부위·운동 목록), `session`(운동 세션·세션 운동·세트)
- Database: `exercise_category`(신규), `exercise`, `workout_session`, `workout_session_exercise`, `workout_set` (+ workout-media의 `workout_media`)
- Mobile App: 화면 WO-001, WO-002(이 문서에서 상세), EX-001 ~ EX-003(유지·매핑만)

### 1.4 기술 스택
공통 설계 1.4를 따른다. 이 기능에 새로 필요한 기술 능력:
| 필요한 능력 | 용도 | 이유 |
|-----------|-----|-----|
| 공개 정적 파일 제공 | 부위 이미지 | 서비스가 준비하는 고정 이미지 4개라 외부 저장소가 필요 없다 (DEC-WORKOUT-008) |

방치된 세션 자동 완료(REQ-WORKOUT-006)는 주기 작업 없이 처리하므로 그 능력은 필요 없다 (DEC-WORKOUT-004). 사진·동영상의 파일 저장은 workout-media 설계를 따른다.

### 1.5 설계 원칙
1. **세션이 중심:** 운동·세트 변경은 모두 소속 세션을 통해 접근하고, 세션 하나의 상태·소유자 확인을 거친다.
2. **계산할 수 있는 값은 저장하지 않는다:** 세트 번호, 운동 순서, 세션 요약, 부위별·종목별 볼륨은 저장하지 않고 조회 때 계산한다. 저장하면 값이 서로 어긋날 수 있다(DEC-WORKOUT-002, 003).
3. **변경 요청은 세션에 변경 잠금을 건다:** 완료와 세트 변경이 동시에 들어와도 완료된 세션에 세트가 추가되지 않게 한다(DEC-WORKOUT-005).
4. **세션을 지우는 모든 경로는 파일도 지운다:** 운동 취소, 방치된 세션 정리는 workout-media 6.4의 파일 삭제를 커밋 후 작업으로 부른다.

### 1.6 요구사항 ↔ 설계 추적표
| 요구사항 ID | 요구사항 | 설계 반영 위치 | 구현 | 테스트 |
|------------|---------|--------------|-----|-------|
| REQ-WORKOUT-001 | 운동 세션 시작 | 3.2, API-WORKOUT-001, workout_session | | |
| REQ-WORKOUT-002 | 운동 세션 완료 | 3.2, API-WORKOUT-003, workout-media 3.2 | | |
| REQ-WORKOUT-005 | 운동 기록 삭제(날짜 단위) | 3.2, API-WORKOUT-009 | | |
| REQ-WORKOUT-006 | 방치된 세션 자동 완료 | 3.2, ExpiredSessionCleaner, DEC-WORKOUT-004 | | |
| REQ-WORKOUT-007 | 월별 운동 달력 | 3.2, API-WORKOUT-007 | | |
| REQ-WORKOUT-008 | 날짜별 운동 기록 | 3.2, API-WORKOUT-008, 5.2 WorkoutDayResponse | | |
| REQ-WORKOUT-009 | 진행 중인 세션 현황 | 3.2, API-WORKOUT-002, 5.2 WorkoutSessionResponse | | |
| REQ-WORKOUT-010 | 진행 중인 운동 취소 | 3.2, API-WORKOUT-006 | | |
| REQ-EXERCISE-001 | 세션에 운동 추가 | 3.2, API-EXERCISE-001, 002, 004, exercise_category, exercise, workout_session_exercise | | |
| REQ-EXERCISE-002 | 세션에서 운동 삭제 | 3.2, API-EXERCISE-003 | | |
| REQ-SET-001 | 세트 기록 추가 | 3.2, API-SET-001, workout_set | | |
| REQ-SET-002 | 세트 기록 수정 | 3.2, API-SET-002 | | |
| REQ-SET-003 | 세트 기록 삭제 | 3.2, API-SET-003 | | |
| REQ-SET-005 | 이전 기록 불러오기 | 3.2, API-SET-004, API-SET-005, 4.2 EX-002 | | |
| BR-001 | 본인 기록만 | 3.5, 7.2 | | |
| BR-002 | 완료된 세션 수정 불가 | 3.4, 3.5, WORKOUT_SESSION_NOT_EDITABLE | | |
| BR-003 | 반복 횟수 1~1,000 정수 | 3.5, 5.3 Validation, ck_workout_set_repetitions | | |
| BR-004 | 수행 날짜 = 시작 시각의 현지 날짜 | 3.2, 3.5, API-WORKOUT-001 `X-Time-Zone` | | |
| BR-005 | 중량 kg, 0~1,000, 소수 둘째 자리 | 3.5, 5.3 Validation, workout_set.weight 소수(6,2), ck_workout_set_weight | | |
| BR-007 | 세트 번호 1부터 빈 번호 없이 | 3.5, DEC-WORKOUT-002 | | |
| BR-008 | 종료 시각 ≥ 시작 시각 | 3.5, ck_workout_session_ended | | |
| BR-009 | 제공 목록의 운동만 추가 | 3.5, 참조 workout_session_exercise.exercise_id | | |
| BR-010 | 요약은 모든 세트로 계산 | 3.5, DEC-WORKOUT-003, 5.2 summary | | |
| BR-011 | 진행 중 세션은 하나 | 3.5, ux_workout_session_user_in_progress | | |
| BR-012 | 세트가 있어야 완료 | 3.5, API-WORKOUT-003 | | |
| BR-013 | 6시간 지나면 시스템이 정리 | 3.2, 3.5, ExpiredSessionCleaner | | |
| BR-015 | 부위별·종목별 볼륨·세트 수 | 3.5, 5.2 `exercises[].volume`, `categories[]` | | |
| BR-016 | 불러오기는 최근 완료 세션에서 | 3.5, API-SET-004 | | |
| BR-017 | 불러온 값은 적용 전 세트가 아님 | 3.5, 4.2 EX-002 (불러온 값은 앱에만 있다) | | |
| BR-018 | 달력에 운동한 부위, 완료 세션만 | 3.5, API-WORKOUT-007 | | |
| BR-019 | 세션 없이 종목을 고르면 자동 시작 | 3.2, 4.2 EX-001 (앱이 시작 후 추가) | | |
| BR-020 | 하루 여러 세션은 합치고 날짜 단위로 삭제 | 3.5, API-WORKOUT-008, API-WORKOUT-009, DEC-WORKOUT-020 | | |
| BR-021 | 불러오기는 기존 세트를 바꿈 | 3.5, API-SET-005, 4.2 EX-002 | | |
| ERR-001 | 비로그인 요청 | 8.2 UNAUTHORIZED | | |
| ERR-002 | 존재하지 않는 운동 추가 | 8.2 EXERCISE_NOT_FOUND | | |
| ERR-003 | 다른 사용자 데이터 접근 | 7.2, 8.2 FORBIDDEN | | |
| ERR-004 | 그날 기록 없음·이미 삭제 | 8.2 WORKOUT_DAY_NOT_FOUND | | |
| ERR-005 | 세트 값 범위·형식 오류 | 8.2 VALIDATION_FAILED | | |
| ERR-007 | 완료된 세션 변경 | 8.2 WORKOUT_SESSION_NOT_EDITABLE | | |
| ERR-008 | 완료된 세션 재완료·취소 | 8.2 WORKOUT_SESSION_ALREADY_COMPLETED | | |
| ERR-009 | 없는 세션·운동·세트 | 8.2 WORKOUT_SESSION_NOT_FOUND, SESSION_EXERCISE_NOT_FOUND, WORKOUT_SET_NOT_FOUND | | |
| ERR-010 | 필수 입력 누락 | 8.2 VALIDATION_FAILED | | |
| ERR-011 | 진행 중 세션이 있는데 시작 | 8.2 WORKOUT_SESSION_ALREADY_IN_PROGRESS | | |
| ERR-012 | 세트 없는 세션 완료 | 8.2 WORKOUT_SESSION_HAS_NO_SETS | | |
| ERR-013 | 불러올 이전 기록 없음 | API-SET-004 204 (8.2) | | |
| ERR-014 | 달력 연·월 형식 오류 | 8.2 VALIDATION_FAILED | | |
| ERR-015 | 날짜 형식 오류 | 8.2 VALIDATION_FAILED | | |
| NFR-PERF-001 | p95 500ms | 9장 | | |
| NFR-PERF-002 | 기록이 많아도 달력·날짜별·불러오기 성능 유지 | 9장, 6.3 | | |
| NFR-PERF-003 | 동시 1,000명 | 9장 | | |
| NFR-SEC-001 | 인증된 사용자만 | 7.1 | | |
| NFR-SEC-002 | 본인 데이터만 | 7.2 | | |
| NFR-AVAIL-001 | 오류 반환, 부분 저장 없음 | 9장, 3.2 트랜잭션 | | |
| NFR-INTEG-001 | 없는 사용자의 기록 생성 불가 | 6.2 참조 workout_session.user_id | | |
| NFR-INTEG-002 | 삭제 시 하위 데이터 남지 않음 | 6.4 참조(함께 삭제), workout-media 6.4 | | |
| NFR-LOG-001 | 주요 행위·자동 처리·오류 추적 | 9장 로그 이벤트 | | |
| IF-WORKOUT-001 | 세션 시작 | API-WORKOUT-001 | | |
| IF-WORKOUT-002 | 진행 중 세션과 현황 조회 | API-WORKOUT-002 | | |
| IF-WORKOUT-003 | 세션 완료 | API-WORKOUT-003 | | |
| IF-WORKOUT-006 | 날짜의 기록 삭제 | API-WORKOUT-009 | | |
| IF-WORKOUT-007 | 월별 운동 날짜·부위 | API-WORKOUT-007 | | |
| IF-WORKOUT-008 | 날짜의 기록 조회 | API-WORKOUT-008 | | |
| IF-WORKOUT-009 | 진행 중 세션 취소 | API-WORKOUT-006 | | |
| IF-EXERCISE-001 | 부위별 운동 목록과 최근 수행일 | API-EXERCISE-001 | | |
| IF-EXERCISE-002 | 세션에 운동 추가·삭제 | API-EXERCISE-002, API-EXERCISE-003 | | |
| IF-EXERCISE-003 | 부위 목록과 부위별 종목 수 | API-EXERCISE-004 | | |
| IF-SET-001 | 세트 추가·수정·삭제 | API-SET-001, 002, 003 | | |
| IF-SET-003 | 종목의 최근 완료 기록 세트 | API-SET-004 | | |
| DATA-001 | 운동 세션 | 6.2 workout_session | | |
| DATA-002 | 운동(종목) | 6.2 exercise, 6.5 초기 목록 | | |
| DATA-003 | 세션 내 운동 | 6.2 workout_session_exercise | | |
| DATA-004 | 세트 | 6.2 workout_set | | |
| DATA-005 | 세션 요약 | DEC-WORKOUT-003, 5.2 summary·categories | | |
| DATA-006 | 운동 카테고리(부위) | 6.2 exercise_category, 6.5 초기 목록 | | |
| WO-001 | 홈 | 4.2 | | |
| WO-002 | 운동 완료 팝업 | 4.2, workout-media 4.2 | | |
| EX-001 | 운동 선택 | 4.2 | | |
| EX-002 | 운동 기록 | 4.2 | | |
| EX-003 | 오늘 한 운동 | 4.2 | | |
| WO-003 | 운동 기록 (달력) | 4.2 | | |

---

## 2. 시스템 아키텍처
공통 설계 2장을 따른다. 이 기능에서 추가되는 것:

- 추가 컴포넌트: **ExpiredSessionCleaner**(서비스) — 시작 후 6시간이 지난 진행 중 세션을 정리한다(REQ-WORKOUT-006). 주기 작업이 아니라, 세션 관련 API가 처리 전에 **요청한 사용자의 세션만** 정리한다. **독립 트랜잭션**으로 실행해, 뒤이은 요청 처리가 실패해도 정리 결과는 남는다(DEC-WORKOUT-004). 정리한 세션의 임시 사진·동영상 파일은 커밋 후 작업으로 지운다(workout-media 6.4).
- 공개 정적 파일: 부위 이미지를 URL `/images/exercise-categories/**`로 제공한다(DEC-WORKOUT-008).

```
요청 ─▶ 인증 필터(userId) ─▶ WorkoutSessionController / ExerciseController (API 진입점)
                               │
                               ├─▶ ExpiredSessionCleaner.cleanUp(userId)   [독립 트랜잭션, 조건부 일괄 갱신, 커밋 후 파일 삭제]
                               │
                               └─▶ WorkoutSessionService [트랜잭션]
                                      ├─ 쓰기·단건 조회: 저장소 (세션에 변경 잠금)
                                      ├─ 세션 현황·이전 기록: WorkoutSessionQueryRepository (조회 저장소)
                                      └─ 파일 삭제 요청: WorkoutMediaService (커밋 후 작업)

요청 ─▶ 인증 필터(userId) ─▶ WorkoutDayController (API 진입점, 운동한 날)
                               ├─▶ ExpiredSessionCleaner.cleanUp(userId)
                               └─▶ WorkoutDayService [트랜잭션]
                                      ├─ 달력·날짜별 기록: WorkoutDayQueryRepository (조회 저장소)
                                      └─ 날짜 단위 삭제: 조건부 일괄 갱신 → 파일 삭제 요청(커밋 후)
```

---

## 3. 기능 설계

### 3.1 기능 목록
| 요구사항 ID | 기능 | API | 주요 컴포넌트 |
|------------|-----|-----|-------------|
| REQ-WORKOUT-001 | 운동 세션 시작 | API-WORKOUT-001 | WorkoutSessionService.start |
| REQ-WORKOUT-009 | 진행 중 세션 현황 | API-WORKOUT-002 | WorkoutSessionService.getInProgress |
| REQ-WORKOUT-002 | 운동 세션 완료 (사진·동영상 포함) | API-WORKOUT-003 | WorkoutSessionService.complete |
| REQ-WORKOUT-010 | 진행 중인 운동 취소 | API-WORKOUT-006 | WorkoutSessionService.cancel |
| REQ-WORKOUT-006 | 방치된 세션 자동 완료 | (모든 세션·운동 API 앞단) | ExpiredSessionCleaner.cleanUp |
| REQ-EXERCISE-001 | 부위 목록 | API-EXERCISE-004 | ExerciseQueryRepository.findCategories |
| REQ-EXERCISE-001 | 부위의 운동 목록 | API-EXERCISE-001 | ExerciseQueryRepository.findByCategory |
| REQ-EXERCISE-001 | 세션에 운동 추가 | API-EXERCISE-002 | WorkoutSessionService.addExercise |
| REQ-EXERCISE-002 | 세션에서 운동 삭제 | API-EXERCISE-003 | WorkoutSessionService.removeExercise |
| REQ-SET-001 | 세트 추가 | API-SET-001 | WorkoutSessionService.addSet |
| REQ-SET-002 | 세트 수정 | API-SET-002 | WorkoutSessionService.updateSet |
| REQ-SET-003 | 세트 삭제 | API-SET-003 | WorkoutSessionService.deleteSet |
| REQ-SET-005 | 이전 기록 조회 | API-SET-004 | WorkoutSessionQueryRepository.findLastRecord |
| REQ-SET-005 | 불러오기 전 종목의 세트 모두 삭제 | API-SET-005 | WorkoutSessionService.clearSets |
| REQ-WORKOUT-007 | 월별 운동 달력 | API-WORKOUT-007 | WorkoutDayQueryRepository.findMonth |
| REQ-WORKOUT-008 | 날짜별 운동 기록 | API-WORKOUT-008 | WorkoutDayService.get |
| REQ-WORKOUT-005 | 날짜 단위 기록 삭제 | API-WORKOUT-009 | WorkoutDayService.delete |

### 3.2 기능별 처리 흐름
모든 흐름의 공통 앞단:
- (A) 인증 필터에서 userId를 얻는다. 없거나 잘못되면 401 `UNAUTHORIZED` (ERR-001).
- (B) `ExpiredSessionCleaner.cleanUp(userId)` 실행 (REQ-WORKOUT-006 흐름 참고). 운동 목록 조회(API-EXERCISE-001)도 최근 수행일이 정리 결과를 반영하도록 실행한다. 부위 목록(API-EXERCISE-004)은 세션과 관계없으므로 실행하지 않는다.

**편집 가능 세션 확보** (운동·세트 변경, 완료, 취소에서 공통으로 쓰는 단계, 이하 "(E)"):
1. 세션을 **변경 잠금**으로 조회한다. 없으면 404 `WORKOUT_SESSION_NOT_FOUND` (ERR-009).
2. `user_id`가 userId와 다르면 403 `FORBIDDEN` (BR-001, ERR-003).
3. 상태가 `COMPLETED`면 409 `WORKOUT_SESSION_NOT_EDITABLE` (BR-002, ERR-007). 완료·취소 API에서는 대신 409 `WORKOUT_SESSION_ALREADY_COMPLETED` (ERR-008).

#### REQ-WORKOUT-001 운동 세션 시작 (API-WORKOUT-001)
1. (A), (B).
2. `X-Time-Zone` 헤더를 IANA 시간대로 해석한다. 없거나 잘못되면 400 `VALIDATION_FAILED` (ERR-010).
3. 트랜잭션 시작. 사용자의 `IN_PROGRESS` 세션이 있으면 409 `WORKOUT_SESSION_ALREADY_IN_PROGRESS` (BR-011, ERR-011).
4. `started_at` = 현재 시각(UTC), `performed_date` = `started_at`을 헤더 시간대로 바꾼 날짜 (BR-004), `status` = `IN_PROGRESS`로 저장한다.
5. 3을 동시에 통과한 두 요청 중 하나는 조건부 유일 제약 `ux_workout_session_user_in_progress` 위반이 난다. **제약 위반 변환**으로 409 `WORKOUT_SESSION_ALREADY_IN_PROGRESS`를 반환한다 (BR-011).
6. 커밋, 로그 `workout_session.started`, 201과 WorkoutSessionResponse(운동 없음).
- **BR-019 자동 시작:** 별도 API를 두지 않는다. 진행 중 세션 없이 운동 선택 화면에서 종목을 고르면, 앱이 이 API를 부른 뒤 API-EXERCISE-002를 부른다. 이 API가 409를 주면(다른 기기에서 이미 시작) 앱은 API-WORKOUT-002로 세션 ID를 얻어 추가한다 (DEC-WORKOUT-017).

#### REQ-WORKOUT-009 진행 중 세션 현황 (API-WORKOUT-002)
1. (A), (B).
2. 사용자의 `IN_PROGRESS` 세션을 찾는다. 없으면 204 (오류 아님).
3. 조회 저장소에서 한 쿼리로 세션의 운동(`workout_session_exercise.created_at`, `id` 순), 운동·부위 정보, 세트(`workout_set.created_at`, `id` 순)를 가져온다.
4. 서비스에서 계산한다 (BR-007, BR-010, BR-015, DEC-WORKOUT-003):
   - 운동별: 세트 번호(1부터), `setCount`, `totalRepetitions`, `volume` = Σ(중량 × 반복 횟수), `firstSetAt` = 가장 먼저 추가된 세트의 `created_at`(세트가 없으면 null)
   - 부위별(`categories[]`): 세트가 1개 이상인 운동만 부위로 묶어 `setCount`, `volume`, 그 부위 운동의 `sessionExerciseIds`(추가 순). 부위 순서는 `exercise_category.sort_order`
   - 합계(`summary`): `exerciseCount`(세트가 1개 이상인 운동 수), `totalSets`, `totalRepetitions`, `totalVolume`, `durationSeconds`(진행 중이면 null)
5. 200과 WorkoutSessionResponse. 불러왔지만 적용하지 않은 값은 서버에 없으므로 들어가지 않는다(BR-017, DEC-WORKOUT-022).

#### REQ-WORKOUT-002 운동 세션 완료 (API-WORKOUT-003)
1. (A), (B).
2. 요청 본문 `mediaIds`를 검증한다(생략 시 빈 목록, 10개 이하, 중복 없음). 위반 시 400 `VALIDATION_FAILED` (workout-media BR-005).
3. 트랜잭션 시작, (E) — 상태가 `COMPLETED`면 409 `WORKOUT_SESSION_ALREADY_COMPLETED` (ERR-008).
4. 세션의 세트 수를 센다. 0이면 409 `WORKOUT_SESSION_HAS_NO_SETS` (BR-012, ERR-012).
5. 사진·동영상을 붙인다: workout-media 3.2 (2) — `mediaIds`가 모두 이 세션의 미디어인지 확인(아니면 400), 순서 저장, 고르지 않은 임시 미디어 행 삭제와 그 파일의 커밋 후 삭제 등록.
6. `status` = `COMPLETED`, `ended_at` = 현재 시각(저장하기·건너뛰기를 누른 시각). `ended_at ≥ started_at`은 항상 참이고 조건 검사 제약으로도 보장한다 (BR-008).
7. 커밋, 로그 `workout_session.completed`(사진·동영상 개수 포함), 200과 WorkoutSessionResponse.
- 운동 완료 팝업을 닫는 것은 API를 부르지 않는다. 세션은 진행 중으로 남는다.

#### REQ-WORKOUT-010 진행 중인 운동 취소 (API-WORKOUT-006)
1. (A), (B).
2. 트랜잭션 시작, (E) — 상태가 `COMPLETED`면 409 `WORKOUT_SESSION_ALREADY_COMPLETED` (ERR-008). 완료된 기록은 날짜 단위 삭제(REQ-WORKOUT-005, API-WORKOUT-009)로만 지운다.
3. 세션을 삭제한다. 하위 운동·세트·미디어 행은 참조(함께 삭제)로 같은 트랜잭션에서 삭제된다 (NFR-INTEG-002).
4. 세션 접두어의 파일 삭제를 커밋 후 작업으로 등록한다 (workout-media 6.4).
5. 커밋, 로그 `workout_session.cancelled`, 204.

#### REQ-WORKOUT-006 방치된 세션 자동 완료 (ExpiredSessionCleaner.cleanUp)
독립 트랜잭션에서 조회 저장소의 **조건부 일괄 갱신**으로 처리한다. 기준 시각은 애플리케이션의 현재 시각(공통 10.1)이다.
1. 완료 처리 — 대상: `user_id = userId`, `status = IN_PROGRESS`, `started_at < 현재 시각 - 6시간`, 세트가 1개 이상인 세션. 변경: `status = COMPLETED`, `ended_at` = 그 세션의 세트 중 가장 늦은 `created_at`, `updated_at` = 현재 시각 (BR-013). 처리한 세션 ID를 돌려받는다.
2. 1에서 완료한 세션의 미디어 행(모두 임시 파일이다)을 삭제하고 키를 돌려받는다. 사용자가 "저장하기"를 누르지 않았으므로 붙인 파일이 없다 (workout-media 6.4).
3. 삭제 처리 — 대상: 같은 조건이면서 세트가 하나도 없는 세션. 삭제한다 (BR-013, BR-012). 하위 운동·미디어 행은 참조(함께 삭제)로 삭제된다. 처리한 세션 ID를 돌려받는다.
4. 2의 미디어 파일과 3의 세션 접두어 파일 삭제를 커밋 후 작업으로 등록한다.
5. 처리한 세션마다 로그 `workout_session.auto_completed` / `workout_session.auto_deleted` (NFR-LOG-001).
6. 조건부 일괄 갱신이라 여러 요청이 동시에 실행해도 결과가 같다(멱등).

#### REQ-EXERCISE-001 부위 목록 (API-EXERCISE-004)
1. (A).
2. 조회 저장소에서 한 쿼리로 `exercise_category`를 `sort_order` 순으로 조회하고, 부위별 `exercise` 개수를 붙인다.
3. 200과 부위 목록.

#### REQ-EXERCISE-001 부위의 운동 목록 (API-EXERCISE-001)
1. (A), (B).
2. `categoryId`(필수, UUID)를 검증한다. 없거나 형식이 틀리면 400. 없는 부위면 404 `EXERCISE_CATEGORY_NOT_FOUND`.
3. 조회 저장소에서 한 쿼리로 그 부위의 `exercise`를 `sort_order` 순으로 조회하고, 사용자의 **완료된** 세션 기준 운동별 가장 최근 `performed_date`를 붙인다 (DEC-WORKOUT-012).
4. 200과 운동 목록(페이지 없음, DEC-WORKOUT-011). 운동 검색은 하지 않는다(요구사항 TODO-017).

#### REQ-EXERCISE-001 세션에 운동 추가 (API-EXERCISE-002)
1. (A), (B), (E).
2. 본문 `exerciseId` 필수 검증. 위반 시 400 (ERR-010).
3. `exercise`가 없으면 404 `EXERCISE_NOT_FOUND` (BR-009, ERR-002).
4. 이 세션에 같은 운동이 이미 있으면 새로 만들지 않고 그 세션 운동을 200으로 돌려준다(요구사항 REQ-EXERCISE-001 "다시 추가하지 않고", DEC-WORKOUT-018).
5. 없으면 `workout_session_exercise`를 저장한다. 순서는 추가한 시각(`created_at`)으로 정해진다 (DEC-WORKOUT-002). 201과 추가된 세션 운동(세트 없음).
6. 세션 변경 잠금 안이라 같은 운동을 동시에 추가해도 하나만 생긴다. 유일 제약 `ux_workout_session_exercise_session_exercise`가 마지막 보장이다.

#### REQ-EXERCISE-002 세션에서 운동 삭제 (API-EXERCISE-003)
1. (A), (B), (E).
2. 경로의 `sessionExerciseId`가 이 세션에 속하지 않으면 404 `SESSION_EXERCISE_NOT_FOUND` (ERR-009).
3. 삭제한다. 그 운동의 세트는 참조(함께 삭제)로 함께 삭제 (NFR-INTEG-002).
4. 204.

#### REQ-SET-001 세트 추가 (API-SET-001)
1. (A), (B), (E).
2. 본문 검증: `weight` 필수, 0~1,000, 소수 둘째 자리까지 (BR-005) / `repetitions` 필수, 1~1,000 정수 (BR-003). 위반 시 400 `VALIDATION_FAILED` (ERR-005, ERR-010).
3. `sessionExerciseId`가 이 세션에 속하지 않으면 404 `SESSION_EXERCISE_NOT_FOUND`.
4. `workout_set`을 저장한다. `created_at` = 추가한 시각 (REQ-WORKOUT-006의 종료 시각 기준, EX-002의 세트 시각).
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

#### REQ-SET-005 이전 기록 조회 (API-SET-004)
1. (A), (B).
2. 경로의 `exerciseId`가 없는 운동이면 404 `EXERCISE_NOT_FOUND`.
3. 조회 저장소에서 한 쿼리로, 사용자의 **완료된** 세션 중 이 운동이 있는 가장 최근 세션(`performed_date` 내림차순, `started_at` 내림차순, `id` 내림차순)을 고르고, 그 세션에서 이 운동의 세트를 추가 순서로 가져온다 (BR-016). 진행 중인 세션은 대상이 아니다.
4. 있으면 200과 그 세션의 수행 날짜, 세트(세트 번호, 중량, 반복 횟수). 없으면 204 (ERR-013 — 오류가 아니라 "없음"으로 알린다, DEC-WORKOUT-021).
- 이 API는 아무것도 바꾸지 않는다. 불러온 값은 앱 화면에만 있고, 사용자가 값을 누를 때마다 앱이 API-SET-001로 세트를 추가한다 (BR-017, DEC-WORKOUT-022).

#### REQ-SET-005 불러오기 전 종목의 세트 모두 삭제 (API-SET-005)
1. (A), (B), (E).
2. `sessionExerciseId`가 이 세션에 속하지 않으면 404 `SESSION_EXERCISE_NOT_FOUND`.
3. 그 세션 운동의 세트를 모두 삭제한다 (BR-021). 세트가 없어도 성공이다(멱등).
4. 204.
- 앱은 기록한 세트가 있는 종목에서 불러오기를 누르면 "이전 기록으로 덮어쓸까요?" 확인 후 이 API를 부른다. 기록한 세트가 없으면 부르지 않는다.

#### REQ-WORKOUT-007 월별 운동 달력 (API-WORKOUT-007)
1. (A), (B).
2. `month`(선택, `YYYY-MM`)를 검증한다. 형식이 틀리면 400 `VALIDATION_FAILED` (ERR-014).
3. `month`가 없으면 사용자의 완료된 세션 중 가장 늦은 `performed_date`가 속한 달로 정한다. 완료된 세션이 없으면 `month` = null, 빈 목록으로 응답한다(앱은 기기의 이번 달을 보여준다).
4. 조회 저장소에서 한 쿼리로 그 달의 **완료된** 세션에서 세트가 있는 운동의 부위를 날짜별로 모은다(중복 없이, 부위 순서). 진행 중인 세션은 넣지 않는다 (BR-018, TODO-024 결정).
5. 200과 `month`, `latestDate`(사용자의 가장 늦은 운동한 날, 처음 들어올 때 고를 날), 날짜 목록.

#### REQ-WORKOUT-008 날짜별 운동 기록 (API-WORKOUT-008)
1. (A), (B).
2. 경로의 `date`가 `YYYY-MM-DD`가 아니면 400 `VALIDATION_FAILED` (ERR-015).
3. 사용자의 그날 **완료된** 세션을 시작 시각 순으로 조회한다. 없으면 204 (운동하지 않은 날, 오류 아님).
4. 조회 저장소에서 한 쿼리로 그 세션들의 운동·부위·세트를 가져와 하나로 합친다 (BR-020, DEC-WORKOUT-020):
   - 같은 운동은 한 항목으로 합치고, 세트는 추가 시각 순으로 1부터 번호를 매긴다. 운동 순서는 그 운동이 처음 추가된 시각 순
   - 운동별·부위별·합계는 REQ-WORKOUT-009와 같은 계산 (BR-010, BR-015)
   - 운동 시간 = 세션마다 (종료 시각 − 시작 시각)의 합
5. 그 세션들의 붙은 사진·동영상을 세션 시작 순, 붙은 순서로 붙인다(workout-media REQ-MEDIA-002). 각 항목에 미리보기·원본 주소를 준다.
6. 200과 WorkoutDayResponse.

#### REQ-WORKOUT-005 날짜 단위 기록 삭제 (API-WORKOUT-009)
1. (A), (B).
2. 경로의 `date` 형식을 검증한다 (ERR-015).
3. 트랜잭션에서 조건부 일괄 갱신으로 사용자의 그날 **완료된** 세션을 모두 삭제하고 삭제한 세션 ID를 돌려받는다 (BR-020). 하위 운동·세트·미디어 행은 참조(함께 삭제)로 삭제된다 (NFR-INTEG-002). 진행 중인 세션은 지우지 않는다(운동 취소로 지운다).
4. 삭제한 세션이 없으면 404 `WORKOUT_DAY_NOT_FOUND` (ERR-004).
5. 삭제한 세션들의 파일을 커밋 후 작업으로 지운다 (workout-media 6.4).
6. 커밋, 세션마다 로그 `workout_session.deleted`, 204.

### 3.3 주요 시나리오 — 홈에서 운동을 시작해 완료하기 (Figma 2행)
```
App                              API                                    DB
 │ GET exercise-categories         │ 부위 4개 + 종목 수                      │
 │ GET in-progress                 │ cleanUp(userId) → 진행 중 없음           │
 │◀───────── 204 ─────────────────│                                        │
 │ [운동 시작] POST workout-sessions │ workout_session 저장 (IN_PROGRESS)     │
 │ X-Time-Zone: Asia/Seoul         │────────────────────────────────────────▶│
 │◀───────── 201 ─────────────────│                                        │
 │ (부위 → 종목 → 세트 기록: 3행 화면, API-EXERCISE-001·002, API-SET-001)        │
 │ [홈] GET in-progress            │ 세션 현황(운동별 세트 수·볼륨, 부위별)       │
 │◀───────── 200 ─────────────────│                                        │
 │ [운동 종료] → 완료 팝업 (같은 응답의 summary·categories 사용)                    │
 │ [저장하기] POST .../media × n    │ (workout-media)                        │
 │ POST .../complete {mediaIds}    │ 세션 변경 잠금 → 세트 수 확인 → 미디어 붙임 → 완료 │
 │◀───────── 200 ─────────────────│                                        │
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
              └──DELETE (운동 취소)──▶ (삭제)   (삭제) ◀──DELETE /workout-days/{date}─┘
```

| 작업 | IN_PROGRESS | COMPLETED |
|-----|-------------|-----------|
| 현황 조회(API-WORKOUT-002) | ○ | — (진행 중 세션만 대상) |
| 이전 기록(API-SET-004)·달력·날짜별 기록의 대상 | ✕ | ○ |
| 운동 추가·삭제, 세트 추가·수정·삭제, 사진·동영상 임시 올리기 | ○ | ✕ 409 WORKOUT_SESSION_NOT_EDITABLE |
| 완료 | ○ (세트 ≥ 1) | ✕ 409 WORKOUT_SESSION_ALREADY_COMPLETED |
| 취소(API-WORKOUT-006) | ○ | ✕ 409 WORKOUT_SESSION_ALREADY_COMPLETED |
| 날짜 단위 삭제(API-WORKOUT-009) | — (지우지 않는다) | ○ 그날 완료된 세션 모두 |

### 3.5 비즈니스 규칙 구현
| BR ID | 규칙 | 강제 위치 | 방법 | 위반 시 |
|-------|-----|----------|-----|--------|
| BR-001 | 본인 기록만 | 서비스, 조회 조건 | 단건: 조회 후 `user_id` 비교 / 목록·집계: 조회 조건에 `user_id = userId` | 403 FORBIDDEN |
| BR-002 | 완료된 세션 수정 불가 | 서비스 (E) | 세션 변경 잠금 후 상태 확인 | 409 WORKOUT_SESSION_NOT_EDITABLE |
| BR-003 | 반복 횟수 1~1,000 정수 | 요청 검증, DB | 요청 검증(필수, 정수 — 소수 거부는 공통 5장, 1~1,000) + 조건 검사 `ck_workout_set_repetitions` | 400 VALIDATION_FAILED |
| BR-004 | 수행 날짜 = 시작 시각의 현지 날짜 | 서비스 | `X-Time-Zone`으로 `started_at`의 날짜 계산, 입력으로 받지 않음 | 헤더 오류 시 400 |
| BR-005 | 중량 0~1,000, 소수 둘째 자리 | 요청 검증, DB | 요청 검증(필수, 0~1,000, 소수 2자리 이내) + 논리 타입 소수(6,2) + 조건 검사 `ck_workout_set_weight` | 400 VALIDATION_FAILED |
| BR-007 | 세트 번호 1부터 연속 | 조회 계산 | 저장하지 않고 운동별 추가 순서(`created_at`, 같으면 `id`)로 번호 계산 (DEC-WORKOUT-002) | 위반이 생길 수 없음 |
| BR-008 | 종료 ≥ 시작 | 서비스, DB | 종료 시각은 항상 현재 시각 또는 시작 이후 추가된 세트 시각, 조건 검사 `ck_workout_session_ended` | 정상 흐름에서 발생 불가 → 500 |
| BR-009 | 제공 목록의 운동만 | 서비스, DB | `exercise` 존재 확인 + 참조(삭제 금지) `exercise_id → exercise.id`. 운동·부위는 스키마 변경 스크립트로만 넣는다 | 404 EXERCISE_NOT_FOUND |
| BR-010 | 요약은 모든 세트로 | 조회 계산 | 현황 조회 결과로 합계 계산 (DEC-WORKOUT-003) | — |
| BR-011 | 진행 중 세션 하나 | 서비스, DB | 시작 전 조회 + 조건부 유일 `ux_workout_session_user_in_progress`, 위반을 제약 위반 변환으로 409 | 409 WORKOUT_SESSION_ALREADY_IN_PROGRESS |
| BR-012 | 세트가 있어야 완료 | 서비스 | 완료 전 세트 수 확인 | 409 WORKOUT_SESSION_HAS_NO_SETS |
| BR-013 | 6시간 지나면 정리 | ExpiredSessionCleaner | 세션 API 앞단에서 독립 트랜잭션 + 조건부 일괄 갱신 (DEC-WORKOUT-004) | — |
| BR-015 | 부위별·종목별 볼륨·세트 수 | 조회 계산 | 3.2 REQ-WORKOUT-009의 4. BR-010과 같은 세트로 계산 | — |
| BR-016 | 불러오기는 그 종목의 가장 최근 완료 세션 | 조회 저장소 | API-SET-004가 완료된 세션만 최근순으로 고른다 | — |
| BR-017 | 불러온 값은 적용 전 세트가 아님 | 앱, API 설계 | 불러온 값은 앱 화면에만 있다. 서버에는 적용(API-SET-001)한 세트만 `workout_set`으로 저장되므로 요약·완료 조건·통계에 들어갈 수 없다 (DEC-WORKOUT-022) | — |
| BR-018 | 달력은 완료 세션의 운동한 부위 | 조회 저장소 | API-WORKOUT-007이 완료된 세션, 세트가 있는 운동의 부위만 모은다 | — |
| BR-019 | 세션 없이 종목을 고르면 자동 시작 | 앱 | 앱이 API-WORKOUT-001 → API-EXERCISE-002 순으로 호출. 시작이 409면 진행 중 세션을 조회해 그 세션에 추가 (DEC-WORKOUT-017) | — |
| BR-020 | 하루 여러 세션은 합치고 날짜 단위로 삭제 | 서비스, 조회 저장소 | 날짜별 기록은 그날 완료된 세션을 합쳐 계산(DEC-WORKOUT-020), 삭제는 그날 완료된 세션 전부를 조건부 일괄 삭제 | 404 WORKOUT_DAY_NOT_FOUND |
| BR-021 | 불러오기는 기존 세트를 모두 바꿈, 확인 필요 | 앱, 서비스 | 앱이 확인 팝업 뒤 API-SET-005(종목의 세트 모두 삭제)를 부르고 불러온 값을 보여준다. 다시 불러오기도 같은 순서 | — |

### 3.6 기능 간 의존관계
- 세트 추가(REQ-SET-001)는 세션 운동(REQ-EXERCISE-001)이 있어야 한다.
- 세션 운동 추가는 운동 목록(`exercise`) 데이터가 있어야 한다. 초기 목록은 스키마 변경 2가 넣는다(6.5).
- 완료(REQ-WORKOUT-002)와 시작의 응답은 현황 조회(REQ-WORKOUT-009)와 같은 형태를 재사용한다.
- 완료의 사진·동영상(`mediaIds`)과 취소·정리의 파일 삭제는 workout-media 설계에 의존한다.
- 모든 세션 API와 운동 목록·이전 기록·달력·날짜별 기록 API는 ExpiredSessionCleaner(REQ-WORKOUT-006)에 의존한다(6시간 지난 세션이 정리된 결과를 보여야 한다).
- 날짜별 기록의 사진·동영상 주소와 내려받기는 workout-media API-MEDIA-002·003에 의존한다.

---

## 4. 화면 / API 연계 설계
요구사항 3장의 화면 정의와 Figma 2·3·4행을 근거로 한다.

### 4.1 화면-API 매핑
| 화면 ID | 화면 | 사용자 행동 | API |
|--------|-----|-----------|-----|
| WO-001 | 홈 | 진입 | API-EXERCISE-004 `GET /api/v1/exercise-categories`, API-WORKOUT-002 `GET /api/v1/workout-sessions/in-progress` |
| WO-001 | 홈 | 운동 시작 | API-WORKOUT-001 `POST /api/v1/workout-sessions` |
| WO-001 | 홈 | 운동 취소 (진행 중 카드) | API-WORKOUT-006 `DELETE /api/v1/workout-sessions/{sessionId}` |
| WO-001 | 홈 | 운동 종료 | (호출 없음) API-WORKOUT-002 응답으로 WO-002를 연다 |
| WO-001 | 홈 | 로그아웃 | auth API-AUTH-002 (auth 설계 4.2) |
| WO-002 | 운동 완료 팝업 | 저장하기 / 건너뛰기 | workout-media API-MEDIA-001(파일마다), API-WORKOUT-003 `POST /api/v1/workout-sessions/{sessionId}/complete` |
| EX-001 | 운동 선택 | 진입 | API-EXERCISE-001 `GET /api/v1/exercises?categoryId=` |
| EX-001 | 운동 선택 | 종목 선택 | (세션 없으면 API-WORKOUT-001) → API-EXERCISE-002 `POST /api/v1/workout-sessions/{sessionId}/exercises` |
| EX-002 | 운동 기록 | 진입 | API-WORKOUT-002 (그 종목의 `exercises[]` 항목) |
| EX-002 | 운동 기록 | 세트 추가 / 수정 / 삭제, 종목 빼기 | API-SET-001 / 002 / 003, API-EXERCISE-003 |
| EX-002 | 운동 기록 | 진입 (이전 기록이 있는지) | API-SET-004 `GET /api/v1/exercises/{exerciseId}/last-record` |
| EX-002 | 운동 기록 | 이전 기록 불러오기 → 덮어쓰기 확인 | API-SET-005 `DELETE /api/v1/workout-sessions/{sessionId}/exercises/{sessionExerciseId}/sets` |
| EX-002 | 운동 기록 | 불러온 값 적용 | API-SET-001 |
| EX-003 | 오늘 한 운동 | 진입 | API-WORKOUT-002 (`setCount` > 0인 `exercises[]`) |
| WO-003 | 운동 기록 (달력) | 진입, 이전 달·다음 달 | API-WORKOUT-007 `GET /api/v1/workout-days?month=` |
| WO-003 | 운동 기록 (달력) | 날짜 선택 | API-WORKOUT-008 `GET /api/v1/workout-days/{date}` |
| WO-003 | 운동 기록 (달력) | 그날 기록 삭제 | API-WORKOUT-009 `DELETE /api/v1/workout-days/{date}` |
| WO-003 | 운동 기록 (달력) | 사진·동영상 미리보기, 누르면 뷰어 | workout-media API-MEDIA-002·003 (workout-media 4.2) |

### 4.2 화면별 연계 상세
공통 응답 처리(401·403·404·400·409·500·503)는 공통 설계 4장을 따른다. 아래는 화면별로 추가되는 처리다.

#### WO-001 홈 (Figma `home-workout-start`, `home-muscle-selection`)
- 진입 조건: 로그인 완료, 하단 탭 "홈"
- 필요 데이터:
  - 오늘 날짜: 기기 날짜
  - 부위 카드: API-EXERCISE-004 → `name`, `imageUrl`, `exerciseCount` ("12개 운동")
  - 진행 중 카드: API-WORKOUT-002 → `startedAt`(경과 시간은 앱이 1초마다 계산), `exercises[]` 중 `setCount` > 0인 것의 `name`, `setCount` ("3세트 완료")
- 사용자 입력: 없음
- API 호출: 진입할 때마다 API-WORKOUT-002(다른 화면에서 돌아올 때 최신 현황). API-EXERCISE-004는 앱 실행 중 한 번(기준 데이터라 잘 바뀌지 않는다). "운동 시작" 시 API-WORKOUT-001(`X-Time-Zone` = 기기 시간대). "운동 취소"는 확인 후 API-WORKOUT-006.
- 성공 처리:
  - API-WORKOUT-002 200 → 진행 중 카드와 "운동 종료" 버튼 / 204 → "운동 시작" 버튼
  - 시작 201 → 홈에 머물며 진행 중 카드 표시(경과 00:00:00). 부위 카드를 눌러 EX-001
  - 취소 204 → 진행 중 카드를 없애고 "운동 시작" 버튼
  - 부위 카드 → EX-001 (`categoryId` 전달). 세션이 없어도 갈 수 있다(BR-019)
  - "운동 종료" → 지금 가진 API-WORKOUT-002 응답으로 WO-002를 연다
- 실패 처리:
  - 시작 409 `WORKOUT_SESSION_ALREADY_IN_PROGRESS` → API-WORKOUT-002 다시 호출해 진행 중 카드 표시
  - 취소 409 `WORKOUT_SESSION_ALREADY_COMPLETED` → "이미 완료된 운동이에요"(6시간 경과로 자동 완료) 후 API-WORKOUT-002 다시 호출 / 404 → 이미 취소됨, 다시 호출
- 로딩 상태: 진입 조회 중 시작·종료 버튼 비활성
- 빈 상태: 204 → 진행 중 카드 없음. 진행 중인데 세트가 하나도 없음 → "기록된 운동" 아래 비어 있음 안내, "운동 종료"를 누르면 WO-002 대신 "세트를 하나 이상 기록하거나 운동을 취소하세요" 안내(BR-012, ERR-012)
- Figma와 다른 점: "운동 취소" 버튼이 아직 디자인에 없다(요구사항 8.2, TODO-019 결정). 위치는 진행 중 카드 안으로 둔다

#### WO-002 운동 완료 팝업 (Figma `workout-complete-popup`)
- 진입 조건: 홈에서 "운동 종료", 진행 중 세션에 세트 ≥ 1
- 필요 데이터 (API-WORKOUT-002 응답, 추가 호출 없음):
  - 날짜: `performedDate` (예: 2026년 9월 27일 (일))
  - 운동 시간 배지: 앱이 `startedAt`부터 팝업을 연 시각까지 계산 (예: 47분 23초). 저장 후 `summary.durationSeconds`가 확정값
  - 총 볼륨: `summary.totalVolume`
  - 부위 카드: `categories[]`의 `name`, `volume`, `setCount` ("4,320kg · 6세트"). 펼치면 `sessionExerciseIds`로 `exercises[]`를 찾아 `name`, `volume`, `setCount` ("2,340kg · 3세트")
- 사용자 입력: 오운완 인증 사진·동영상 (workout-media 4.2)
- API 호출: 저장하기·건너뛰기 → workout-media 4.2의 순서로 API-MEDIA-001, API-WORKOUT-003
- 성공 처리: 완료 200 → 팝업 닫고 홈, 진행 중 카드 사라짐
- 실패 처리: 409 `WORKOUT_SESSION_ALREADY_COMPLETED` → 자동 완료 안내 후 홈 / 409 `WORKOUT_SESSION_HAS_NO_SETS` → 다른 기기에서 세트를 지운 경우, 안내 후 팝업 닫기 / 404 → 다른 기기에서 취소됨, 홈 / 사진·동영상 오류는 workout-media 4.2
- 로딩 상태: 업로드·완료 중 버튼 비활성
- 빈 상태: 해당 없음 (세트 ≥ 1일 때만 열린다)
- 팝업을 닫으면 API 호출 없이 홈으로, 세션은 진행 중

#### EX-001 운동 선택 (Figma `exercise-list`)
- 진입 조건: 홈에서 부위 카드 선택 (`categoryId`, 부위명). 진행 중 세션이 없어도 된다(BR-019)
- 필요 데이터: API-EXERCISE-001 → `name`, `nameEn`, `lastPerformedDate`(앱이 "최근 3일 전 완료" / "기록 없음"으로 바꾼다)
- 사용자 입력: 종목 선택
- API 호출: 진입 시 API-EXERCISE-001. 종목 선택 시 진행 중 세션이 없으면 API-WORKOUT-001(시작) 뒤 API-EXERCISE-002, 있으면 바로 API-EXERCISE-002
- 성공 처리: 201·200 → 응답의 `sessionExerciseId`로 EX-002 (200이면 이미 있던 종목, DEC-WORKOUT-018)
- 실패 처리: 시작 409 → API-WORKOUT-002로 세션 ID를 얻어 추가(DEC-WORKOUT-017) / 추가 409 `WORKOUT_SESSION_NOT_EDITABLE`(6시간 경과로 자동 완료) → 안내 후 홈 / 404 `EXERCISE_CATEGORY_NOT_FOUND` → 홈
- 로딩 상태: 목록 조회 중 표시, 종목을 누른 뒤 응답까지 다른 종목 선택 막기
- 빈 상태: 해당 없음(부위마다 종목이 있다)
- 문구: 부제 "…추천 루틴"은 쓰지 않는다(요구사항 TODO-023). 디자인에서 바꾼다

#### EX-002 운동 기록 (Figma `workout-recording`, `이전 기록 덮어쓰기 확인`, `workout-recording-prev-loaded`)
- 진입 조건: EX-001에서 종목 선택 (`sessionExerciseId`, `exerciseId`)
- 필요 데이터:
  - 머리글: API-WORKOUT-002의 그 종목 항목 → `name`, `category.name`, `target`
  - 기록 중·경과 시간: `startedAt`부터 앱이 계산
  - 세트 표: `sets[]`의 `setNumber`, `createdAt`(현지 시각 "14:23"), `weight`, `repetitions`
  - 이전 기록 버튼: API-SET-004 → 200이면 "이전 기록 불러오기", 204면 "이전 기록 없음"(비활성)
- 사용자 입력: 중량(0~1,000, 소수 둘째 자리), 반복 횟수(1~1,000 정수). 앱에서도 같은 범위로 미리 검증
- API 호출:
  - 진입 시 API-WORKOUT-002(이미 있으면 재사용)와 API-SET-004
  - "세트 추가" → 화면에 빈 행을 만든다. 중량 칸에는 직전 세트의 중량을 미리 채우고 반복 횟수는 비운다(요구사항 EX-002). 두 값이 다 채워지면 API-SET-001로 저장(그때 `createdAt`이 정해진다)
  - 저장된 세트의 값을 고치면 API-SET-002, ✕를 누르면 API-SET-003(삭제 뒤 번호가 바뀌므로 API-WORKOUT-002 다시 조회)
  - 종목 빼기 → 확인 후 API-EXERCISE-003, EX-001로 돌아감
  - **이전 기록 불러오기:** 이 종목에 저장된 세트가 있으면 "이전 기록으로 덮어쓸까요?" 팝업 → "덮어쓰기"면 API-SET-005, "취소"면 아무것도 하지 않음. 세트가 없으면 팝업 없이 진행. 그다음 API-SET-004의 세트를 **적용 전 값**(흐리게)으로 보여주고 "이전 기록 (9월 24일) 세트가 로드됨" 안내를 띄운다. 적용 전 값을 누르거나 고치면 그 값으로 API-SET-001 (BR-017, BR-021)
  - 다시 불러오기도 같은 순서다. 화면을 떠나면 적용하지 않은 값은 버린다(서버에 없다)
- 성공 처리: 각 API 응답으로 표를 갱신. "오늘 한 운동 보기" → EX-003
- 실패 처리: 400 → 해당 칸에 `errors[].reason` / 409 `WORKOUT_SESSION_NOT_EDITABLE` → 자동 완료 안내 후 홈 / 404 `SESSION_EXERCISE_NOT_FOUND` → 다른 기기에서 종목을 뺀 경우, EX-001
- 로딩 상태: 저장 중 그 행 입력 막기(중복 요청 방지)
- 빈 상태: 세트 없음 → 빈 행 하나
- Figma와 다른 점: 불러온 뒤 버튼이 "불러오기 완료"로 바뀌지만 요구사항은 다시 불러오기를 허용한다(BR-021). 버튼은 "다시 불러오기"로 둔다. 종목 빼기 버튼 위치는 디자인에 없다(요구사항 TODO-019)

#### EX-003 오늘 한 운동 (Figma `today-workout-popup`)
- 진입 조건: EX-002에서 "오늘 한 운동 보기"
- 필요 데이터: API-WORKOUT-002 → `setCount` > 0인 `exercises[]`의 `name`, `category.name`, `firstSetAt`(현지 시각), `setCount`. 지금 EX-002에서 보고 있는 종목은 앱이 "진행 중"으로 표시. 아래 합계는 `summary.exerciseCount`, `summary.totalVolume`, `summary.totalSets`
- 사용자 입력: 닫기
- API 호출: 열 때 API-WORKOUT-002
- 성공 처리·실패 처리: 공통
- 빈 상태: 세트가 있는 종목 없음 → "아직 기록한 운동이 없어요"
- 문구: 제목 "오늘의 루틴"은 쓰지 않는다(요구사항 TODO-023)

#### WO-003 운동 기록 (달력) (Figma `workout-history`)
- 진입 조건: 하단 탭 "기록"
- 필요 데이터:
  - 달력: API-WORKOUT-007 → `month`, `days[]`의 `date`, `categories[].name`(날짜 칸 아래 부위명)
  - 고른 날짜: API-WORKOUT-008 → `durationSeconds`("47분 23초"), `summary.totalVolume`, `categories[]`(부위별 볼륨·세트 수), 펼치면 `exercises[]`의 `volume`, `setCount`, `sets[]`("1세트 50kg × 12회"), `media[]`(오운완 인증)
- 사용자 입력: 이전 달·다음 달, 날짜 선택, 부위 펼치기, 그날 기록 삭제(확인 후), 사진·동영상 선택
- API 호출:
  - 처음 들어올 때 API-WORKOUT-007(`month` 없이) → 응답의 `month`를 보여주고 `latestDate`를 골라 API-WORKOUT-008. `month`가 null이면 기기의 이번 달을 빈 달력으로
  - 달 이동 → API-WORKOUT-007 `month=YYYY-MM`. 날짜 선택 → API-WORKOUT-008
  - 삭제 → 확인 후 API-WORKOUT-009
- 성공 처리: 삭제 204 → 그 달을 다시 조회하고 날짜 선택을 해제 / 사진·동영상 → MEDIA-001 (workout-media 4.2)
- 실패 처리: 삭제 404 `WORKOUT_DAY_NOT_FOUND` → 이미 지워짐, 다시 조회 / 400 → 앱 버그(형식), 이번 달로
- 로딩 상태: 달·날짜 조회 중 표시
- 빈 상태: 날짜별 기록 204 → "이 날의 운동 기록이 없어요". 달력에 운동한 날이 없으면 빈 달력
- Figma와 다른 점: 그날 기록 삭제 버튼이 디자인에 없다(요구사항 TODO-019)

---

## 5. API 설계
URL·필드·날짜·페이지 규칙은 공통 설계 5장을 따른다.

### 5.1 API 목록
| API ID | Method | URL | 인증 | 설명 | 관련 요구사항 |
|--------|--------|-----|-----|-----|-------------|
| API-WORKOUT-001 | POST | /api/v1/workout-sessions | 필요 | 세션 시작 | REQ-WORKOUT-001, IF-WORKOUT-001 |
| API-WORKOUT-002 | GET | /api/v1/workout-sessions/in-progress | 필요 | 진행 중 세션과 현황 | REQ-WORKOUT-009, IF-WORKOUT-002 |
| API-WORKOUT-003 | POST | /api/v1/workout-sessions/{sessionId}/complete | 필요 | 세션 완료(사진·동영상 붙임) | REQ-WORKOUT-002, IF-WORKOUT-003, workout-media IF-MEDIA-001 |
| ~~API-WORKOUT-004~~ | ~~GET~~ | ~~/api/v1/workout-sessions~~ | — | **폐기 (v0.6)** — REQ-WORKOUT-003 폐기. 달력·날짜별 조회로 바뀐다(보류) | — |
| ~~API-WORKOUT-005~~ | ~~GET~~ | ~~/api/v1/workout-sessions/{sessionId}~~ | — | **폐기 (v0.6)** — REQ-WORKOUT-004 폐기. 진행 중 세션은 API-WORKOUT-002로 본다 | — |
| API-WORKOUT-006 | DELETE | /api/v1/workout-sessions/{sessionId} | 필요 | 진행 중인 운동 취소 (v0.6: 진행 중 세션만) | REQ-WORKOUT-010, IF-WORKOUT-009 |
| API-EXERCISE-001 | GET | /api/v1/exercises?categoryId= | 필요 | 부위의 운동 목록 (v0.6: 검색 대신 부위) | REQ-EXERCISE-001, IF-EXERCISE-001 |
| API-EXERCISE-002 | POST | /api/v1/workout-sessions/{sessionId}/exercises | 필요 | 세션에 운동 추가 (v0.6: 이미 있으면 200) | REQ-EXERCISE-001, IF-EXERCISE-002 |
| API-EXERCISE-003 | DELETE | /api/v1/workout-sessions/{sessionId}/exercises/{sessionExerciseId} | 필요 | 세션에서 운동 삭제 | REQ-EXERCISE-002, IF-EXERCISE-002 |
| API-EXERCISE-004 | GET | /api/v1/exercise-categories | 필요 | 부위 목록과 종목 수 | REQ-EXERCISE-001, IF-EXERCISE-003 |
| API-SET-001 | POST | /api/v1/workout-sessions/{sessionId}/exercises/{sessionExerciseId}/sets | 필요 | 세트 추가 | REQ-SET-001, IF-SET-001 |
| API-SET-002 | PUT | /api/v1/workout-sessions/{sessionId}/exercises/{sessionExerciseId}/sets/{setId} | 필요 | 세트 수정 | REQ-SET-002, IF-SET-001 |
| API-SET-003 | DELETE | /api/v1/workout-sessions/{sessionId}/exercises/{sessionExerciseId}/sets/{setId} | 필요 | 세트 삭제 | REQ-SET-003, IF-SET-001 |
| API-SET-004 | GET | /api/v1/exercises/{exerciseId}/last-record | 필요 | 종목의 이전 기록(가장 최근 완료 세션의 세트) | REQ-SET-005, IF-SET-003 |
| API-SET-005 | DELETE | /api/v1/workout-sessions/{sessionId}/exercises/{sessionExerciseId}/sets | 필요 | 종목의 세트 모두 삭제(불러오기 전 덮어쓰기) | REQ-SET-005, BR-021 |
| API-WORKOUT-007 | GET | /api/v1/workout-days?month=YYYY-MM | 필요 | 월별 운동한 날과 부위 | REQ-WORKOUT-007, IF-WORKOUT-007 |
| API-WORKOUT-008 | GET | /api/v1/workout-days/{date} | 필요 | 날짜별 운동 기록 | REQ-WORKOUT-008, IF-WORKOUT-008 |
| API-WORKOUT-009 | DELETE | /api/v1/workout-days/{date} | 필요 | 그날 완료된 기록 삭제 | REQ-WORKOUT-005, IF-WORKOUT-006 |

(살아 있는 API 16개)

### 5.2 공통 응답 모델

**WorkoutSessionResponse** (API-WORKOUT-001, 002, 003) — v0.6에서 WorkoutSessionDetailResponse를 바꿈(DEC-WORKOUT-015)
```json
{
  "id": "0199b2d4-6c40-7a3e-8f21-3c5d7e9a1b02",
  "status": "IN_PROGRESS",
  "performedDate": "2026-09-27",
  "startedAt": "2026-09-27T05:00:00Z",
  "endedAt": null,
  "exercises": [
    {
      "sessionExerciseId": "0199b2d5-0a11-7b42-9c03-4d6e8f0a2c13",
      "exerciseId": "0199a0f1-2b30-7c55-8d14-5e7f9a1b3d24",
      "name": "벤치프레스",
      "nameEn": "Bench Press",
      "target": "가슴 중부 타겟",
      "category": { "id": "0199a0f0-1a20-7b44-8c03-4d6e8f0a2c01", "name": "가슴" },
      "sets": [
        { "id": "0199b2d6-1f22-7d63-a025-6f8a0b2c4e35", "setNumber": 1, "weight": 60.00, "repetitions": 12, "createdAt": "2026-09-27T05:23:10Z" },
        { "id": "0199b2da-3e83-7e74-b136-7a9b1c3d5f46", "setNumber": 2, "weight": 70.00, "repetitions": 10, "createdAt": "2026-09-27T05:26:02Z" }
      ],
      "setCount": 2,
      "totalRepetitions": 22,
      "volume": 1420.00,
      "firstSetAt": "2026-09-27T05:23:10Z"
    }
  ],
  "categories": [
    { "id": "0199a0f0-1a20-7b44-8c03-4d6e8f0a2c01", "name": "가슴", "setCount": 2, "volume": 1420.00,
      "sessionExerciseIds": ["0199b2d5-0a11-7b42-9c03-4d6e8f0a2c13"] }
  ],
  "summary": {
    "durationSeconds": null,
    "exerciseCount": 1,
    "totalSets": 2,
    "totalRepetitions": 22,
    "totalVolume": 1420.00
  }
}
```
| 필드 | 설명 | 근거 |
|-----|-----|-----|
| `status` | `IN_PROGRESS` / `COMPLETED` | DATA-001 |
| `endedAt` | 진행 중이면 null | DATA-001 |
| `exercises` | 추가한 순서. 세트가 없는 운동도 들어간다(EX-002). 홈·오늘 한 운동은 `setCount` > 0만 보여준다 | REQ-EXERCISE-001, REQ-WORKOUT-009 |
| `exercises[].nameEn`, `target` | 영문명, 타깃 설명 (EX-002 머리글) | DATA-002 |
| `exercises[].sets` | 추가한 순서, `setNumber`는 1부터 연속, `createdAt`은 추가 시각(EX-002 세트 시각) | BR-007, DATA-004 |
| `exercises[].volume` | Σ(중량 × 반복 횟수), 소수 둘째 자리 | BR-015 |
| `exercises[].firstSetAt` | 첫 세트의 추가 시각(EX-003). 세트가 없으면 null | REQ-WORKOUT-009 |
| `categories` | 세트가 1개 이상인 운동을 부위로 묶은 것. 부위 순서(`exercise_category.sort_order`) | BR-015, WO-002 |
| `summary.durationSeconds` | `endedAt - startedAt` 초. 진행 중이면 null | DATA-005 |
| `summary.exerciseCount` | 세트가 1개 이상인 운동 수 | BR-010 |
| `summary.totalSets` / `totalRepetitions` / `totalVolume` | 모든 세트 수 / 반복 횟수 합 / Σ(중량 × 반복 횟수) | BR-010 |
| ID 필드 | UUID 문자열 (공통 5장) | DEC-ARCH-010 |

**WorkoutDayResponse** (API-WORKOUT-008) — 그날 완료된 세션을 합친 기록 (BR-020, DEC-WORKOUT-020)
```json
{
  "date": "2026-09-27",
  "durationSeconds": 2843,
  "exercises": [
    {
      "exerciseId": "0199a0f1-2b30-7c55-8d14-5e7f9a1b3d24",
      "name": "랫풀다운", "nameEn": "Lat Pulldown", "target": "광배근 타겟",
      "category": { "id": "0199a0f0-1a21-7c55-9d14-5e7f9a1b3d02", "name": "등" },
      "sets": [ { "id": "0199b2d6-1f22-7d63-a025-6f8a0b2c4e35", "setNumber": 1, "weight": 50.00, "repetitions": 12, "createdAt": "2026-09-27T05:23:10Z" } ],
      "setCount": 1, "totalRepetitions": 12, "volume": 600.00
    }
  ],
  "categories": [
    { "id": "0199a0f0-1a21-7c55-9d14-5e7f9a1b3d02", "name": "등", "setCount": 1, "volume": 600.00,
      "exerciseIds": ["0199a0f1-2b30-7c55-8d14-5e7f9a1b3d24"] }
  ],
  "summary": { "exerciseCount": 1, "totalSets": 1, "totalRepetitions": 12, "totalVolume": 600.00 },
  "media": [
    { "id": "019a1c2e-5f00-7d11-9a3b-2c4d6e8f0a12", "mediaType": "PHOTO", "contentType": "image/jpeg",
      "previewUrl": "/api/v1/media/019a1c2e-5f00-7d11-9a3b-2c4d6e8f0a12/preview",
      "originalUrl": "/api/v1/media/019a1c2e-5f00-7d11-9a3b-2c4d6e8f0a12/original" }
  ]
}
```
| 필드 | 설명 | 근거 |
|-----|-----|-----|
| `durationSeconds` | 그날 완료된 세션마다 (종료 − 시작)의 합 | BR-020 |
| `exercises` | 같은 운동은 하나로 합침. 운동 순서는 처음 추가된 시각 순, 세트는 추가 시각 순으로 1부터 번호 | BR-007, BR-020 |
| `categories` | 부위 순서. 그 부위 운동의 `exerciseIds` | BR-015 |
| `media` | 세션 시작 순, 붙은 순서. 주소는 workout-media API-MEDIA-002·003 | workout-media REQ-MEDIA-002 |

**WorkoutSetResponse** (API-SET-001, 002)
```json
{ "id": "0199b2df-5b94-7f85-8247-8b0c2d4e6a57", "setNumber": 3, "weight": 80.00, "repetitions": 8, "createdAt": "2026-09-27T05:30:44Z" }
```

### 5.3 API 상세

#### API-WORKOUT-001 운동 세션 시작
- 목적: 진행 중 운동 세션을 만든다.
- Method / URL: `POST /api/v1/workout-sessions`
- 인증: 필요
- 관련 요구사항: REQ-WORKOUT-001, IF-WORKOUT-001, BR-004, BR-011, BR-019

Request
- Header: `X-Time-Zone: Asia/Seoul` (필수)
- Body: 없음

Response `201 Created` — WorkoutSessionResponse (`status` = `IN_PROGRESS`, `exercises` = [], `categories` = [], `summary`의 합계 0, `durationSeconds` null). `Location: /api/v1/workout-sessions/in-progress`

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

#### API-WORKOUT-002 진행 중 세션과 현황
- 목적: 홈·오늘 한 운동·운동 완료 팝업·운동 기록 화면에 진행 중 세션과 현황을 준다.
- Method / URL: `GET /api/v1/workout-sessions/in-progress`
- 인증: 필요
- 관련 요구사항: REQ-WORKOUT-009, IF-WORKOUT-002, BR-010, BR-011, BR-015

Request: 없음

Response `200 OK` — WorkoutSessionResponse / `204 No Content` — 진행 중 세션 없음

Validation: 해당 없음

Errors
| HTTP | 에러 코드 | 조건 | 관련 |
|------|----------|-----|-----|
| 401 | UNAUTHORIZED | 인증 없음 | ERR-001 |

#### API-WORKOUT-003 운동 세션 완료
- 목적: 진행 중 세션을 완료해 기록으로 확정하고, 고른 사진·동영상을 붙인다.
- Method / URL: `POST /api/v1/workout-sessions/{sessionId}/complete`
- 인증: 필요
- 관련 요구사항: REQ-WORKOUT-002, IF-WORKOUT-003, BR-002, BR-008, BR-010, BR-012, workout-media REQ-MEDIA-001, BR-003, BR-005, BR-007

Request (본문 생략 가능 — 생략하면 `mediaIds` = [])
```json
{ "mediaIds": ["019a1c2e-5f00-7d11-9a3b-2c4d6e8f0a12", "019a1c2f-0b10-7e22-8b4c-3d5e7f9a1b23"] }
```

Response `200 OK` — WorkoutSessionResponse (`status` = `COMPLETED`)

Validation
| 필드 | 타입 | 필수 | 규칙 | 근거 |
|-----|-----|-----|-----|-----|
| sessionId (경로) | UUID | Y | UUID 형식 | 공통 5장 |
| mediaIds | UUID 배열 | N | 0~10개, 중복 없음, 모두 이 세션의 미디어 | workout-media BR-005, BR-002 |

메모(`memo`)는 받지 않는다 (v0.6, 요구사항 TODO-020).

Errors
| HTTP | 에러 코드 | 조건 | 관련 |
|------|----------|-----|-----|
| 400 | VALIDATION_FAILED | `mediaIds` 11개 이상·중복·이 세션의 미디어가 아님 | workout-media BR-005 |
| 401 | UNAUTHORIZED | 인증 없음 | ERR-001 |
| 403 | FORBIDDEN | 다른 사용자의 세션 | ERR-003 |
| 404 | WORKOUT_SESSION_NOT_FOUND | 세션 없음 | ERR-009 |
| 409 | WORKOUT_SESSION_ALREADY_COMPLETED | 이미 완료됨 | ERR-008 |
| 409 | WORKOUT_SESSION_HAS_NO_SETS | 세트가 하나도 없음 | ERR-012 |

#### API-WORKOUT-006 진행 중인 운동 취소
- 목적: 진행 중 세션과 그 운동·세트·임시 사진·동영상을 지운다.
- Method / URL: `DELETE /api/v1/workout-sessions/{sessionId}`
- 인증: 필요
- 관련 요구사항: REQ-WORKOUT-010, IF-WORKOUT-009, BR-001, NFR-INTEG-002

Response `204 No Content`

Validation: 경로 변수가 UUID 형식이 아니면 400

Errors
| HTTP | 에러 코드 | 조건 | 관련 |
|------|----------|-----|-----|
| 401 | UNAUTHORIZED | 인증 없음 | ERR-001 |
| 403 | FORBIDDEN | 다른 사용자의 세션 | ERR-003 |
| 404 | WORKOUT_SESSION_NOT_FOUND | 세션 없음·이미 취소됨 | ERR-009 |
| 409 | WORKOUT_SESSION_ALREADY_COMPLETED | 완료된 세션 (v0.6: 완료된 기록은 이 API로 지우지 않는다) | ERR-008 |

#### API-EXERCISE-004 부위 목록
- 목적: 홈의 부위 카드(이름, 이미지, 종목 수)를 준다.
- Method / URL: `GET /api/v1/exercise-categories`
- 인증: 필요
- 관련 요구사항: REQ-EXERCISE-001, IF-EXERCISE-003, DATA-006

Response `200 OK`
```json
[
  { "id": "0199a0f0-1a20-7b44-8c03-4d6e8f0a2c01", "name": "가슴", "imageUrl": "/images/exercise-categories/chest.jpg", "exerciseCount": 5 },
  { "id": "0199a0f0-1a21-7c55-9d14-5e7f9a1b3d02", "name": "등", "imageUrl": "/images/exercise-categories/back.jpg", "exerciseCount": 6 }
]
```
- 정렬: `exercise_category.sort_order`. 페이지 없음(4개).
- `imageUrl`: 공개 정적 파일의 URL 경로. 앱은 API 주소에 붙여 불러온다.

Validation: 해당 없음

Errors
| HTTP | 에러 코드 | 조건 | 관련 |
|------|----------|-----|-----|
| 401 | UNAUTHORIZED | 인증 없음 | ERR-001 |

#### API-EXERCISE-001 부위의 운동 목록 (v0.6 변경)
- 목적: 운동 선택 화면에 한 부위의 종목과 사용자의 최근 수행일을 준다.
- Method / URL: `GET /api/v1/exercises?categoryId={categoryId}`
- 인증: 필요
- 관련 요구사항: REQ-EXERCISE-001, IF-EXERCISE-001, BR-009

Response `200 OK`
```json
[
  { "id": "0199a0f1-2b30-7c55-8d14-5e7f9a1b3d24", "name": "벤치프레스", "nameEn": "Bench Press", "target": "가슴 중부 타겟", "lastPerformedDate": "2026-09-24" }
]
```
- 정렬: `exercise.sort_order`(요구사항 부록 A의 순서). 페이지 없음 (DEC-WORKOUT-011).
- `lastPerformedDate`: 사용자의 완료된 세션 기준, 한 번도 안 했으면 null (DEC-WORKOUT-012). 앱이 "최근 3일 전 완료" / "기록 없음"으로 바꿔 보여준다.
- v0.5의 `keyword`, `category`(문자열), `imageUrl`은 없앤다.

Validation
| 필드 | 타입 | 필수 | 규칙 | 근거 |
|-----|-----|-----|-----|-----|
| categoryId | UUID | Y | UUID 형식 | DATA-006 |

Errors
| HTTP | 에러 코드 | 조건 | 관련 |
|------|----------|-----|-----|
| 400 | VALIDATION_FAILED | categoryId 없음·형식 오류 | ERR-010 |
| 401 | UNAUTHORIZED | 인증 없음 | ERR-001 |
| 404 | EXERCISE_CATEGORY_NOT_FOUND | 없는 부위 | ERR-009 |

#### API-EXERCISE-002 세션에 운동 추가 (v0.6 변경)
- 목적: 진행 중 세션에 운동을 추가한다. 이미 있으면 그 운동을 돌려준다.
- Method / URL: `POST /api/v1/workout-sessions/{sessionId}/exercises`
- 인증: 필요
- 관련 요구사항: REQ-EXERCISE-001, IF-EXERCISE-002, BR-001, BR-002, BR-009

Request
```json
{ "exerciseId": "0199a0f1-2b30-7c55-8d14-5e7f9a1b3d24" }
```

Response `201 Created` — 새로 추가 / `200 OK` — 이미 이 세션에 있음. 본문은 WorkoutSessionResponse의 `exercises[]` 항목 하나(새로 추가면 `sets` = [])

Validation
| 필드 | 타입 | 필수 | 규칙 | 근거 |
|-----|-----|-----|-----|-----|
| exerciseId | UUID | Y | UUID 형식 | ERR-010 |

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
변경 없음 (v0.5와 같다).
- Method / URL: `DELETE /api/v1/workout-sessions/{sessionId}/exercises/{sessionExerciseId}`, 204
- Errors: 401 UNAUTHORIZED(ERR-001), 403 FORBIDDEN(ERR-003), 404 WORKOUT_SESSION_NOT_FOUND·SESSION_EXERCISE_NOT_FOUND(ERR-009), 409 WORKOUT_SESSION_NOT_EDITABLE(ERR-007), 경로 UUID 형식 오류 400

#### API-SET-001 세트 추가
- Method / URL: `POST /api/v1/workout-sessions/{sessionId}/exercises/{sessionExerciseId}/sets`
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
| 400 | VALIDATION_FAILED | 범위·형식 위반 / 필수값 누락 | ERR-005, ERR-010 |
| 401 | UNAUTHORIZED | 인증 없음 | ERR-001 |
| 403 | FORBIDDEN | 다른 사용자의 세션 | ERR-003 |
| 404 | WORKOUT_SESSION_NOT_FOUND / SESSION_EXERCISE_NOT_FOUND | 세션 없음 / 이 세션에 그 운동 없음 | ERR-009 |
| 409 | WORKOUT_SESSION_NOT_EDITABLE | 완료된 세션 | ERR-007 |

#### API-SET-002 세트 수정
- Method / URL: `PUT /api/v1/workout-sessions/{sessionId}/exercises/{sessionExerciseId}/sets/{setId}`
- Request / Validation: API-SET-001과 같다.
- Response `200 OK` — WorkoutSetResponse (`setNumber`, `createdAt` 변경 없음)
- Errors: API-SET-001과 같고, 추가로 404 `WORKOUT_SET_NOT_FOUND`(그 세션 운동에 세트 없음, ERR-009)

#### API-SET-003 세트 삭제
- Method / URL: `DELETE /api/v1/workout-sessions/{sessionId}/exercises/{sessionExerciseId}/sets/{setId}`, 204
- Errors: 401(ERR-001), 403(ERR-003), 404 WORKOUT_SESSION_NOT_FOUND·SESSION_EXERCISE_NOT_FOUND·WORKOUT_SET_NOT_FOUND(ERR-009), 409 WORKOUT_SESSION_NOT_EDITABLE(ERR-007), 경로 UUID 형식 오류 400

#### API-SET-004 이전 기록 조회
- 목적: 운동 기록 화면에서 이 종목을 마지막으로 했을 때의 세트를 불러온다.
- Method / URL: `GET /api/v1/exercises/{exerciseId}/last-record`
- 인증: 필요
- 관련 요구사항: REQ-SET-005, IF-SET-003, BR-016, ERR-013

Response `200 OK`
```json
{
  "performedDate": "2026-09-24",
  "sets": [
    { "setNumber": 1, "weight": 60.00, "repetitions": 12 },
    { "setNumber": 2, "weight": 65.00, "repetitions": 10 }
  ]
}
```
Response `204 No Content` — 이 종목을 완료한 기록이 없음 (ERR-013)

Validation: 경로 변수가 UUID 형식이 아니면 400

Errors
| HTTP | 에러 코드 | 조건 | 관련 |
|------|----------|-----|-----|
| 401 | UNAUTHORIZED | 인증 없음 | ERR-001 |
| 404 | EXERCISE_NOT_FOUND | 없는 운동 | ERR-002 |

#### API-SET-005 종목의 세트 모두 삭제
- 목적: 이전 기록을 불러오기 전에 이 종목에 기록한 세트를 모두 지운다(덮어쓰기).
- Method / URL: `DELETE /api/v1/workout-sessions/{sessionId}/exercises/{sessionExerciseId}/sets`
- 인증: 필요
- 관련 요구사항: REQ-SET-005, BR-021, BR-002

Response `204 No Content` (세트가 없어도 204)

Validation: 경로 변수가 UUID 형식이 아니면 400

Errors
| HTTP | 에러 코드 | 조건 | 관련 |
|------|----------|-----|-----|
| 401 | UNAUTHORIZED | 인증 없음 | ERR-001 |
| 403 | FORBIDDEN | 다른 사용자의 세션 | ERR-003 |
| 404 | WORKOUT_SESSION_NOT_FOUND / SESSION_EXERCISE_NOT_FOUND | 세션 없음 / 이 세션에 그 운동 없음 | ERR-009 |
| 409 | WORKOUT_SESSION_NOT_EDITABLE | 완료된 세션 | ERR-007 |

#### API-WORKOUT-007 월별 운동 달력
- 목적: 운동 기록 화면의 달력에 운동한 날과 그날의 부위를 보여준다.
- Method / URL: `GET /api/v1/workout-days?month=2026-09` (`month` 생략 가능)
- 인증: 필요
- 관련 요구사항: REQ-WORKOUT-007, IF-WORKOUT-007, BR-001, BR-018, TODO-024

Response `200 OK`
```json
{
  "month": "2026-09",
  "latestDate": "2026-09-27",
  "days": [
    { "date": "2026-09-01", "categories": [ { "id": "0199a0f0-1a20-7b44-8c03-4d6e8f0a2c01", "name": "가슴" } ] },
    { "date": "2026-09-05", "categories": [ { "id": "…", "name": "어깨" }, { "id": "…", "name": "하체" } ] }
  ]
}
```
- `month`를 생략하면 `latestDate`가 속한 달. 완료된 기록이 하나도 없으면 `{ "month": null, "latestDate": null, "days": [] }`
- `days`: 날짜 오름차순, 운동한 날만. `categories`: 부위 순서, 중복 없음

Validation
| 필드 | 타입 | 필수 | 규칙 | 근거 |
|-----|-----|-----|-----|-----|
| month | string | N | `YYYY-MM` | ERR-014 |

Errors
| HTTP | 에러 코드 | 조건 | 관련 |
|------|----------|-----|-----|
| 400 | VALIDATION_FAILED | month 형식 오류 | ERR-014 |
| 401 | UNAUTHORIZED | 인증 없음 | ERR-001 |

#### API-WORKOUT-008 날짜별 운동 기록
- 목적: 고른 날의 운동 기록을 세트와 사진·동영상까지 보여준다.
- Method / URL: `GET /api/v1/workout-days/{date}` (예: `/api/v1/workout-days/2026-09-27`)
- 인증: 필요
- 관련 요구사항: REQ-WORKOUT-008, IF-WORKOUT-008, BR-001, BR-010, BR-015, BR-020, workout-media REQ-MEDIA-002

Response `200 OK` — WorkoutDayResponse / `204 No Content` — 그날 완료된 기록 없음

Validation
| 필드 | 타입 | 필수 | 규칙 | 근거 |
|-----|-----|-----|-----|-----|
| date (경로) | string | Y | `YYYY-MM-DD` | ERR-015 |

Errors
| HTTP | 에러 코드 | 조건 | 관련 |
|------|----------|-----|-----|
| 400 | VALIDATION_FAILED | 날짜 형식 오류 | ERR-015 |
| 401 | UNAUTHORIZED | 인증 없음 | ERR-001 |

#### API-WORKOUT-009 날짜 단위 기록 삭제
- 목적: 그날 완료된 운동 기록을 모두(운동·세트·사진·동영상 포함) 지운다.
- Method / URL: `DELETE /api/v1/workout-days/{date}`
- 인증: 필요
- 관련 요구사항: REQ-WORKOUT-005, IF-WORKOUT-006, BR-020, NFR-INTEG-002, workout-media BR-004

Response `204 No Content`

Validation: `date` 형식 (`YYYY-MM-DD`)

Errors
| HTTP | 에러 코드 | 조건 | 관련 |
|------|----------|-----|-----|
| 400 | VALIDATION_FAILED | 날짜 형식 오류 | ERR-015 |
| 401 | UNAUTHORIZED | 인증 없음 | ERR-001 |
| 404 | WORKOUT_DAY_NOT_FOUND | 그날 완료된 기록 없음·이미 삭제 | ERR-004 |

다른 사용자의 기록은 조회 조건(`user_id`)에 걸리지 않으므로 "없음"과 같다. 날짜는 사용자마다 따로라 403이 생기지 않는다.

---

## 6. 데이터 설계
이름 규칙·논리 타입·제약 종류·`users` 테이블은 공통 설계 6장을 따른다.

### 6.1 ERD
```
exercise_category 1 ──── N exercise
                              1
                              │
                              N
users 1 ──── N workout_session 1 ──── N workout_session_exercise 1 ──── N workout_set
                    1
                    └──── N workout_media   (workout-media 설계)
```

### 6.2 테이블 정의

#### exercise_category — 근거: DATA-006 (v0.6 신규)
| 컬럼 | 타입 | Null | 기본값 | 설명 | 근거 |
|-----|-----|------|-------|-----|-----|
| id | ID | N | DB 생성 (공통 DEC-ARCH-018) | PK | DEC-ARCH-010 |
| name | 문자열(20) | N | | 부위명 (가슴, 등, 어깨, 하체) | DATA-006 |
| image_url | 문자열(300) | N | | 부위 이미지의 URL 경로. 예: `/images/exercise-categories/chest.jpg` | DATA-006, DEC-WORKOUT-008 |
| sort_order | 정수 | N | | 화면 순서(1부터) | DATA-006 |
| created_at | 시각 | N | 현재 시각 | | 공통 |

- PK: `id`
- 유일 `ux_exercise_category_name`: `name`
- 유일 `ux_exercise_category_sort_order`: `sort_order`
- 사용자는 이 테이블을 바꾸지 않는다(BR-009). 행은 스키마 변경 스크립트로만 넣고 고친다. 수정이 스크립트뿐이라 `updated_at`을 두지 않는다.

#### exercise — 근거: DATA-002 (v0.6 변경: `category`·`image_url` 대신 부위 참조, 영문명·타깃·순서 추가)
| 컬럼 | 타입 | Null | 기본값 | 설명 | 근거 |
|-----|-----|------|-------|-----|-----|
| id | ID | N | DB 생성 (공통 DEC-ARCH-018) | PK | DEC-ARCH-010 |
| exercise_category_id | ID | N | | 부위 | DATA-002, DATA-006 |
| name | 문자열(100) | N | | 운동명 | DATA-002 |
| name_en | 문자열(100) | N | | 영문 운동명 | DATA-002 |
| target | 문자열(50) | N | | 타깃 설명 (예: 가슴 중부 타겟) | DATA-002 |
| sort_order | 정수 | N | | 부위 안 순서(1부터) | DATA-002 |
| created_at | 시각 | N | 현재 시각 | | 공통 |
| updated_at | 시각 | N | 현재 시각 | | 공통 |

- PK: `id`
- 참조(삭제 금지): `exercise_category_id → exercise_category.id`
- 유일 `ux_exercise_name`: `name` — 같은 운동이 다른 행으로 중복되지 않게 (요구사항 TODO-002 결정 이유)
- 유일 `ux_exercise_category_order`: `(exercise_category_id, sort_order)`
- 종목 이미지는 두지 않는다 (요구사항 TODO-018, BR-014 폐기)

#### workout_session — 근거: DATA-001 (v0.6 변경: `memo` 제거)
| 컬럼 | 타입 | Null | 기본값 | 설명 | 근거 |
|-----|-----|------|-------|-----|-----|
| id | ID | N | 애플리케이션 생성 | PK | DEC-ARCH-010 |
| user_id | ID | N | | 소유자 | BR-001, NFR-INTEG-001 |
| status | 열거(IN_PROGRESS, COMPLETED) | N | | 세션 상태 | DATA-001 |
| performed_date | 날짜 | N | | 수행 날짜(사용자 현지) | BR-004 |
| started_at | 시각 | N | | 시작 시각 | DATA-001 |
| ended_at | 시각 | Y | | 종료 시각, 진행 중이면 Null | DATA-001, BR-008 |
| created_at | 시각 | N | 현재 시각 | | 공통 |
| updated_at | 시각 | N | 현재 시각 | | 공통 |

- PK: `id`
- 참조(함께 삭제): `user_id → users.id` — 운영자가 계정을 삭제하면 그 사용자의 운동 기록도 모두 삭제 (auth BR-012, 공통 6.1, DEC-WORKOUT-014)
- 조건 검사 `ck_workout_session_ended`: `(status = IN_PROGRESS 이고 ended_at 없음) 또는 (status = COMPLETED 이고 ended_at 있음 이고 ended_at >= started_at)` — BR-008
- 조건부 유일 `ux_workout_session_user_in_progress`: `status = IN_PROGRESS`인 행 사이에서 `user_id` 유일 — BR-011. 위반은 제약 위반 변환으로 409

#### workout_session_exercise — 근거: DATA-003 (v0.6 변경: 같은 운동 중복 금지)
| 컬럼 | 타입 | Null | 기본값 | 설명 | 근거 |
|-----|-----|------|-------|-----|-----|
| id | ID | N | 애플리케이션 생성 | PK | DEC-ARCH-010 |
| workout_session_id | ID | N | | 소속 세션 | DATA-003 |
| exercise_id | ID | N | | 운동 | DATA-003, BR-009 |
| created_at | 시각 | N | 현재 시각 | 추가한 시각. 세션 안 운동 순서의 기준 | DATA-003, DEC-WORKOUT-002 |

- PK: `id`
- 참조(함께 삭제): `workout_session_id → workout_session.id` (NFR-INTEG-002)
- 참조(삭제 금지): `exercise_id → exercise.id`
- 유일 `ux_workout_session_exercise_session_exercise`: `(workout_session_id, exercise_id)` — 한 세션에 같은 운동은 하나 (REQ-EXERCISE-001, DEC-WORKOUT-018)
- 수정되지 않는 행이라 `updated_at`이 없다.

#### workout_set — 근거: DATA-004 (변경 없음)
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
| ux_exercise_category_name | exercise_category | (name), 유일 | 중복 방지 | DATA-006 |
| ux_exercise_category_sort_order | exercise_category | (sort_order), 유일 | 부위 목록 정렬 | API-EXERCISE-004 |
| ux_exercise_name | exercise | (name), 유일 | 중복 방지 | DATA-002 |
| ux_exercise_category_order | exercise | (exercise_category_id, sort_order), 유일 | 부위의 운동 목록 정렬, 부위별 종목 수 | API-EXERCISE-001, API-EXERCISE-004 |
| ux_workout_session_user_in_progress | workout_session | (user_id), 조건부 유일: status = IN_PROGRESS | 진행 중 세션 조회(API-WORKOUT-002), 시작 시 확인, cleanUp 대상 조회 | BR-011, BR-013 |
| idx_workout_session_user_performed | workout_session | (user_id, performed_date 내림차순, started_at 내림차순, id 내림차순) | 운동별 최근 수행일, 이전 기록(가장 최근 완료 세션), 월별 달력(날짜 범위), 날짜별 기록·삭제(같은 날짜), 가장 늦은 운동한 날 | REQ-EXERCISE-001, REQ-SET-005, REQ-WORKOUT-005, 007, 008, NFR-PERF-002 |
| ux_workout_session_exercise_session_exercise | workout_session_exercise | (workout_session_id, exercise_id), 유일 | 같은 운동 중복 확인, 세션별 운동 조회, 함께 삭제 | REQ-EXERCISE-001 |
| idx_workout_session_exercise_session | workout_session_exercise | (workout_session_id, created_at, id) | 세션 현황의 운동을 추가 순서로 조회 | REQ-WORKOUT-009 |
| idx_workout_set_session_exercise | workout_set | (workout_session_exercise_id, created_at, id) | 운동별 세트를 추가 순서로 조회, 세트 번호 계산, cleanUp의 세트 존재 확인, 함께 삭제 | REQ-WORKOUT-009, BR-013 |

`workout_session_exercise.exercise_id`에는 따로 인덱스를 두지 않는다. 운동별 최근 수행일은 사용자 세션에서 출발한다(idx_workout_session_user_performed → ux_workout_session_exercise_session_exercise). `exercise` 행은 지우지 않으므로 참조 확인용 인덱스도 필요 없다.

### 6.4 삭제 정책
- 요구사항 TODO-001 결정대로 실제 삭제한다. 보관 컬럼(`deleted_at`)은 두지 않는다.
- `workout_session` 삭제(운동 취소, 방치된 세션 자동 삭제) → `workout_session_exercise` → `workout_set`, 그리고 `workout_media`가 참조(함께 삭제)로 같은 트랜잭션에서 삭제된다 (NFR-INTEG-002). 파일은 커밋 후 작업으로 지운다 (workout-media 6.4).
- 계정(`users`) 삭제 → 그 사용자의 `workout_session`과 하위 데이터가 모두 참조(함께 삭제)로 삭제된다 (auth BR-012). 파일은 공통 10.7 절차.
- `exercise`, `exercise_category`는 삭제하지 않는다(사용 중이면 참조(삭제 금지)가 막는다).

### 6.5 스키마 변경 목록
인증 설계의 스키마 변경 1(users, login_session) 다음 순서로 적용한다. 스크립트 파일 규칙은 공통 설계 10.1을 따른다.

**v0.6: 운영 사용 전이라 2·3을 고쳐 다시 만든다 (사용자 결정, DEC-WORKOUT-016).** 배포 전에 운영 DB에서 스키마 변경 2·3의 적용 기록과 그 테이블을 지워야 한다(10.2 순서 0).

| 순서 | 변경 | 내용 |
|-----|-----|-----|
| 2 | 운동 부위·종목 생성과 초기 목록 | `exercise_category`, `exercise`(6.2), 6.3의 인덱스. 초기 데이터: 부위 4개(가슴 1, 등 2, 어깨 3, 하체 4, 이미지 `/images/exercise-categories/{chest,back,shoulders,legs}.jpg`)와 요구사항 부록 A의 종목 24개(순서·영문명·타깃 그대로) (요구사항 TODO-014 결정, D-TODO-WORKOUT-001 결정) |
| 3 | 운동 세션 생성 | `workout_session`(메모 없음), `workout_session_exercise`(중복 금지 유일 제약), `workout_set`과 6.2의 제약, 6.3의 인덱스 |
| 4 | workout_media 생성 | workout-media 설계 6.5 |

부위 이미지 파일 4개는 공개 정적 파일로 함께 배포한다. 원본은 앱 저장소의 부위 이미지(`assets/images/groups/`)를 쓴다.

---

## 7. 인증 / 인가 및 보안 설계
인증 흐름·토큰·CORS·CSRF는 공통 설계 7장을 따른다.

### 7.1 API별 인증
- 5.1의 살아 있는 API 16개는 모두 인증 필요 (NFR-SEC-001).
- 공개 정적 파일 `GET /images/exercise-categories/**`는 인증 없이 허용한다. 부위 이미지만 있고 사용자 데이터가 없다 (DEC-WORKOUT-008). v0.5의 `/images/exercises/**`는 없앤다.

### 7.2 사용자별 데이터 접근 제한
- userId는 인증 필터가 확인한 로그인에서만 얻는다 (auth 설계 3.2).
- 경로의 `sessionId`: 세션 조회 → 없으면 404 → `user_id` 불일치면 403 `FORBIDDEN` (BR-001, ERR-003, DEC-WORKOUT-007).
- 경로의 `sessionExerciseId`, `setId`: 상위 리소스에 속하는지 확인한다(`workout_session_exercise.workout_session_id = sessionId`, `workout_set.workout_session_exercise_id = sessionExerciseId`). 속하지 않으면 404.
- 진행 중 세션 현황·운동별 최근 수행일·이전 기록·달력·날짜별 기록·날짜 단위 삭제·cleanUp은 모두 `user_id = userId` 조건을 가진다 (NFR-SEC-002). 날짜로 접근하는 API는 다른 사용자의 기록에 닿을 수 없다.
- 부위·운동 목록은 모든 사용자에게 같은 기준 데이터다(최근 수행일만 사용자별).

### 7.3 민감 데이터 / 로그
- 로그에는 이벤트 이름, userId, sessionId만 남긴다. 사진·동영상은 workout-media 7.3.

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
| ERR-004 | WORKOUT_DAY_NOT_FOUND (v0.7 신규) | 404 | 그날의 운동 기록을 찾을 수 없습니다. | WorkoutDayService.delete |
| ERR-005 | VALIDATION_FAILED (공통) | 400 | 입력값이 올바르지 않습니다. (`errors[]`에 필드별 사유) | 요청 검증 |
| ERR-007 | WORKOUT_SESSION_NOT_EDITABLE | 409 | 완료된 운동 기록은 수정할 수 없습니다. | WorkoutSessionService (E) |
| ERR-008 | WORKOUT_SESSION_ALREADY_COMPLETED | 409 | 이미 완료된 운동입니다. | WorkoutSessionService.complete, cancel |
| ERR-009 | WORKOUT_SESSION_NOT_FOUND | 404 | 운동 기록을 찾을 수 없습니다. | WorkoutSessionService (E) |
| ERR-009 | SESSION_EXERCISE_NOT_FOUND | 404 | 운동 기록에서 해당 운동을 찾을 수 없습니다. | WorkoutSessionService |
| ERR-009 | WORKOUT_SET_NOT_FOUND | 404 | 세트를 찾을 수 없습니다. | WorkoutSessionService |
| ERR-009 | EXERCISE_CATEGORY_NOT_FOUND (v0.6 신규) | 404 | 운동 부위를 찾을 수 없습니다. | ExerciseService (API-EXERCISE-001) |
| ERR-010 | VALIDATION_FAILED (공통) | 400 | 입력값이 올바르지 않습니다. | 요청 검증, 시간대 헤더 해석 |
| ERR-011 | WORKOUT_SESSION_ALREADY_IN_PROGRESS | 409 | 이미 진행 중인 운동이 있습니다. | WorkoutSessionService.start (조회 확인 + 제약 위반 변환) |
| ERR-012 | WORKOUT_SESSION_HAS_NO_SETS | 409 | 세트를 하나 이상 기록해야 운동을 완료할 수 있습니다. | WorkoutSessionService.complete |
| ERR-013 | (에러 코드 없음) | 204 | — | API-SET-004: 이전 기록이 없음을 내용 없음으로 알린다 (DEC-WORKOUT-021) |
| ERR-014 | VALIDATION_FAILED (공통) | 400 | 입력값이 올바르지 않습니다. | API-WORKOUT-007 `month` 해석 |
| ERR-015 | VALIDATION_FAILED (공통) | 400 | 입력값이 올바르지 않습니다. | API-WORKOUT-008·009 `date` 해석 |

제약 위반 변환 대상: `ux_workout_session_user_in_progress` → 409 `WORKOUT_SESSION_ALREADY_IN_PROGRESS`만 지정한다. `ux_workout_session_exercise_session_exercise`는 세션 변경 잠금 안에서 먼저 확인하므로 정상 흐름에서 위반이 나지 않는다. 그 밖의 DB 제약 위반은 버그이므로 500으로 두고 ERROR 로그를 남긴다(공통 8.4).

---

## 9. 비기능 요구사항 설계
| NFR ID | 요구사항 | 설계 대응 | 확인 방법 |
|--------|---------|----------|----------|
| NFR-PERF-001 | 요청의 95%가 500ms 이내 | 요청당 쿼리 수 고정: 현황 1회(세션·운동·부위·세트 조인), 부위 목록 1회, 운동 목록 1회, cleanUp 3회(완료·미디어 정리·삭제). 모든 조회 조건에 6.3 인덱스 | 부하 테스트 p95 측정 (공통 D-TODO-ARCH-004) |
| NFR-PERF-002 | 기록이 많아도 성능 유지 | 최근 수행일·이전 기록·달력·날짜별 기록·날짜 삭제는 모두 `idx_workout_session_user_performed`의 사용자·날짜 범위로 좁힌 뒤 조인한다. 달력은 한 달, 날짜별은 하루만 읽는다. 요청당 쿼리: 이전 기록 1회, 달력 2회(가장 늦은 날 + 그 달), 날짜별 2회(운동·세트 + 사진·동영상) | 사용자 1명에 세션 1,000건을 넣고 각 API 응답 시간 측정 |
| NFR-PERF-003 | 동시 1,000명 | 무상태 서버(공통 9장). 변경 잠금은 사용자 자신의 세션에만 걸려 사용자 간 경합이 없다 | 부하 테스트 (공통 D-TODO-ARCH-004) |
| NFR-SEC-001 | 인증된 사용자만 | 7.1, 모든 API 인증 필요 | 토큰 없는 요청이 API마다 401인지 테스트 |
| NFR-SEC-002 | 본인 데이터만 | 7.2 소유자 확인, 상위 리소스 소속 확인, 조회 조건 `user_id` | 다른 사용자 세션·세트에 대해 API마다 403/404 테스트 |
| NFR-AVAIL-001 | 장애 시 오류 반환, 부분 저장 없음 | 요청 하나 = 트랜잭션 하나, 삭제는 참조(함께 삭제)로 한 번에, 파일은 공통 2.6 순서, 오류는 공통 8장 형식 | 강제 예외 시 500 JSON과 데이터 무변경 테스트 |
| NFR-INTEG-001 | 없는 사용자의 기록 생성 불가 | 참조 `workout_session.user_id → users.id` | 없는 userId로 세션 생성 시 실패 테스트 |
| NFR-INTEG-002 | 삭제 시 하위 데이터 남지 않음 | 참조(함께 삭제) 3단 + 파일 커밋 후 삭제 | 취소 후 운동·세트·미디어 행 0건, 저장소 파일 없음 테스트 |
| NFR-LOG-001 | 주요 행위·자동 처리·오류 추적 | INFO 이벤트: `workout_session.started`, `.completed`, `.cancelled`, `.auto_completed`, `.auto_deleted` (userId, sessionId). 처리하지 못한 예외는 ERROR | 로그 출력 확인 테스트 |

---

## 10. 구현 구조 및 개발 전략

### 10.1 컴포넌트 구성
역할은 공통 설계 2.2, 패키지·파일 배치와 구현 기술은 공통 설계 10장을 따른다.

| 도메인 | 컴포넌트 | 역할 | 책임 |
|-------|---------|-----|-----|
| exercise | ExerciseController | API 진입점 | API-EXERCISE-001, API-EXERCISE-004, API-SET-004(종목 아래 경로) |
| exercise | ExerciseService | 서비스 | 부위 존재 확인, 목록 조회 |
| exercise | ExerciseRepository | 저장소 | 운동 단건·존재 확인 (BR-009) |
| exercise | ExerciseQueryRepository | 조회 저장소 | 부위 목록 + 종목 수, 부위의 운동 목록 + 사용자별 최근 수행일 |
| session | WorkoutSessionController | API 진입점 | `/workout-sessions/**` API 10개(세션·운동·세트) |
| session | WorkoutSessionService | 서비스 | 세션 묶음의 모든 쓰기와 규칙: 소유자·상태 확인, 변경 잠금, 완료 조건, 미디어 붙이기 호출, 취소, 현황 계산 |
| session | ExpiredSessionCleaner | 서비스 (독립 트랜잭션) | REQ-WORKOUT-006 정리, 정리한 세션의 파일 삭제 등록 |
| session | WorkoutSessionRepository, WorkoutSessionExerciseRepository, WorkoutSetRepository | 저장소 | 행 저장·수정·삭제, 단건 조회, 세션 변경 잠금 조회 |
| session | WorkoutSessionQueryRepository | 조회 저장소 | 세션 현황 조회, 이전 기록 조회, cleanUp 조건부 일괄 갱신 |
| session | WorkoutDayController | API 진입점 | `/workout-days/**` API 3개 |
| session | WorkoutDayService | 서비스 | 날짜별 기록 합치기, 날짜 단위 삭제와 파일 삭제 요청 |
| session | WorkoutDayQueryRepository | 조회 저장소 | 월별 운동한 날·부위, 날짜의 세션·운동·세트, 날짜 단위 조건부 일괄 삭제 |

### 10.2 구현 순서
v0.5까지 구현된 코드에서 바꾸는 순서다.

| 순서 | 작업 | 관련 요구사항 | 완료 기준 |
|-----|-----|-------------|----------|
| 0 | 운영 DB 정리: 스키마 변경 2·3의 적용 기록과 `exercise`·`workout_*` 테이블 삭제 (DEC-WORKOUT-016) | — | 배포 시 새 2·3·4가 오류 없이 적용 |
| 1 | 스키마 변경 2·3 다시 쓰기 + 부위 이미지 정적 파일 | DATA-001 ~ 004, DATA-006 | 적용 성공, 부위 4개·종목 24개, 중복 운동 추가가 DB에서 거절 |
| 2 | 부위 목록 API, 운동 목록 API 변경(검색·이미지 제거, `categoryId`) | REQ-EXERCISE-001, IF-EXERCISE-001, IF-EXERCISE-003 | 종목 수 5·6·5·8, 부록 A 순서, 없는 부위 404 테스트 |
| 3 | 응답 모델 변경(WorkoutSessionResponse: `categories`, `volume`, `firstSetAt`, 세트 `createdAt`) | REQ-WORKOUT-009, BR-015 | 부위별·종목별 볼륨·세트 수 계산 테스트 |
| 4 | 목록·상세 API(API-WORKOUT-004·005)와 메모 제거 | 폐기 | 해당 경로 404, `memo` 무시 테스트 |
| 5 | 운동 취소(API-WORKOUT-006을 진행 중 전용으로) | REQ-WORKOUT-010, ERR-008 | 진행 중 204·하위 행 0건, 완료된 세션 409 테스트 |
| 6 | 운동 추가 중복 처리 | REQ-EXERCISE-001 | 같은 운동 두 번째 추가 200·행 1개 테스트 |
| 7 | 완료의 `mediaIds`, 취소·cleanUp의 파일 삭제 (workout-media 10.2와 함께) | REQ-WORKOUT-002, REQ-WORKOUT-006, workout-media BR-007 | workout-media 10.2 완료 기준 |
| 8 | 이전 기록 조회, 종목 세트 모두 삭제 | REQ-SET-005, BR-016, BR-021, ERR-013 | 가장 최근 완료 세션의 세트, 진행 중 세션 제외, 없으면 204, 삭제 후 세트 0건 테스트 |
| 9 | 월별 달력 | REQ-WORKOUT-007, BR-018, ERR-014 | 완료 세션 부위만, 진행 중 제외, `month` 생략 시 가장 늦은 달, 형식 오류 400 테스트 |
| 10 | 날짜별 기록 | REQ-WORKOUT-008, BR-020, ERR-015 | 하루 두 세션 합치기(같은 운동 하나로, 운동 시간 합), 사진·동영상 순서, 없는 날 204 테스트 |
| 11 | 날짜 단위 삭제 | REQ-WORKOUT-005, BR-020, ERR-004 | 그날 완료 세션 모두 삭제·진행 중은 남음, 파일 삭제, 다시 삭제 404 테스트 |

순서 0 ~ 7은 v0.6 반영으로 끝났다(2026-10-09).

### 10.3 테스트 포인트
- BR-011: 같은 사용자가 동시에 두 번 시작해도 진행 중 세션은 하나이고, 하나는 409.
- BR-013: `started_at`을 6시간 전으로 만든 세션 — 세트가 있으면 다음 요청에서 `COMPLETED`이고 `ended_at` = 마지막 세트의 `created_at`, 임시 미디어 행·파일 삭제. 세트가 없으면 삭제됨. 정리 직후 새 세션을 시작할 수 있다.
- BR-013 + BR-002: 6시간 지난 세션에 세트를 추가하면 409 `WORKOUT_SESSION_NOT_EDITABLE`, 취소하면 409 `WORKOUT_SESSION_ALREADY_COMPLETED`.
- BR-004: `X-Time-Zone: Asia/Seoul`로 UTC 15:30(현지 다음 날 00:30)에 시작하면 `performedDate`가 현지 날짜.
- BR-003·BR-005 경계값: 반복 0/1/1000/1001/8.5, 중량 -0.01/0/1000/1000.01/62.555.
- BR-007: 세트 3개 중 2번째 삭제 후 현황을 조회하면 번호가 1, 2.
- BR-010·BR-015: 가슴 2종목·등 1종목에 세트를 넣으면 `categories`가 가슴→등 순서, 부위별 `volume` = 그 부위 세트의 Σ(중량×반복), `summary.totalVolume` = 부위 합. 세트 없는 운동은 `categories`와 `exerciseCount`에서 빠짐.
- BR-012 + 동시성: 완료 요청과 세트 추가가 동시에 와도 완료된 세션에 세트가 생기지 않는다.
- REQ-EXERCISE-001: 같은 운동을 두 번 추가하면 두 번째는 200, 세션 운동은 하나.
- NFR-SEC-002: 다른 사용자의 세션 ID로 모든 세션 API를 호출하면 403. 다른 사용자의 세트 ID를 자기 세션 경로에 넣으면 404.
- BR-016: 같은 종목을 9/20(완료), 9/24(완료), 오늘(진행 중)에 했으면 이전 기록은 9/24의 세트.
- BR-020: 같은 날 두 세션(둘 다 벤치프레스)을 완료하면 날짜별 기록의 벤치프레스는 하나이고 세트 번호가 이어지며, 운동 시간은 두 세션의 합. 그날 삭제하면 두 세션 모두 사라지고 진행 중 세션은 남는다.
- BR-018·TODO-024: 진행 중 세션의 날짜·부위는 달력에 나오지 않는다.

---

## 부록 A. 설계 결정 기록
| ID | 결정 | 이유 | 버린 대안 |
|----|-----|-----|----------|
| DEC-WORKOUT-001 | 세션·세션 운동·세트를 하나의 묶음으로 보고 `/workout-sessions/**` 아래 중첩 경로, API 진입점·서비스 각 1개로 다룬다 | 모든 변경이 세션 상태·소유자 확인을 거쳐야 한다. 확인 로직이 한 곳에 모인다 | 운동·세트별 컴포넌트: 같은 세션 확인을 여러 곳에서 반복 |
| DEC-WORKOUT-002 | 세트 번호와 세션 안 운동 순서를 저장하지 않고 추가한 시각(`created_at` 오름차순, 같으면 `id`)으로 계산 (v0.3: `id` 순서에서 변경, DEC-ARCH-010) | BR-007(빈 번호 없음)이 자동으로 지켜지고, 삭제 시 번호를 다시 쓰는 갱신이 없다. 변경 요청은 세션 변경 잠금으로 하나씩 처리되므로 같은 운동의 세트끼리 추가 시각이 겹치지 않는다 | 번호 컬럼 저장: 삭제할 때마다 재정렬 갱신, 동시 변경 시 중복 위험 |
| DEC-WORKOUT-003 | 세션 요약, 부위별·종목별 볼륨(DATA-005, BR-015)을 저장하지 않고 조회 결과로 계산 (v0.6: 부위별 추가) | 세트가 바뀔 때 요약을 함께 갱신할 필요가 없어 값이 어긋날 수 없다. 세션당 세트 수가 적어 계산 비용이 작다 | 요약 컬럼 저장: 세트 변경마다 갱신 필요 |
| DEC-WORKOUT-004 | 방치된 세션 정리(REQ-WORKOUT-006)를 주기 작업 없이, 세션 관련 API 처리 전에 요청한 사용자 것만 독립 트랜잭션으로 실행 | 사용자가 결과를 보는 시점(다음 요청)에 항상 정리되어 있다. 주기 작업 실행 환경과 여러 서버 간 중복 실행 문제가 없다. **한계:** 사용자가 다시 접속하지 않으면 6시간 넘은 `IN_PROGRESS` 행과 그 임시 파일이 남는다. 통계도 요청한 사용자 것만 보므로(workout-stats BR-001) 영향이 없다 | 주기 작업: 실행 간격만큼 늦게 정리되어 결국 요청 시 확인도 필요하고, 여러 서버 실행 대비가 필요 |
| DEC-WORKOUT-005 | 세션 변경 요청(운동·세트 변경, 완료, 취소)은 세션을 변경 잠금으로 조회 | 완료와 세트 추가가 동시에 와도 완료된 세션에 세트가 생기지 않는다. 잠금은 사용자 자신의 세션에만 걸려 경합이 거의 없다 | 낙관적 잠금(버전 비교): 충돌 시 재시도 처리가 앱까지 필요 |
| DEC-WORKOUT-006 | BR-011은 조회 확인 + 조건부 유일 제약, 위반을 409로 변환 | 조회 확인만으로는 동시 요청을 막지 못한다 | 사용자 행에 변경 잠금: users 테이블에 기능이 의존하게 됨 |
| DEC-WORKOUT-007 | 다른 사용자의 데이터는 403 | 요구사항 ERR-003("권한 오류")을 따른다. 존재 노출 문제는 부록 C-5 | 404로 숨김: 요구사항과 다름 |
| DEC-WORKOUT-008 | 부위 이미지는 애플리케이션과 함께 배포되는 공개 정적 파일로 `/images/exercise-categories/**`에서 제공하고, `exercise_category.image_url`에 URL 경로를 저장 (v0.6: 종목 이미지에서 부위 이미지로) | 서비스가 준비하는 고정 이미지 4개이고 사용자 업로드가 아니다. 외부 저장소가 필요 없다. **한계:** 이미지를 바꾸려면 배포가 필요하다 | 오브젝트 저장소: 사용자 파일용 비공개 저장소라 공개 이미지를 위해 공개 경로를 따로 열어야 함 |
| DEC-WORKOUT-009 | 존재하지 않는 운동을 추가하면 404 `EXERCISE_NOT_FOUND` | 공통 정책 "대상 없음 = 404"와 일관 | 400: 경로·본문 참조에 따라 상태 코드가 달라져 앱 처리가 복잡 |
| DEC-WORKOUT-010 | ~~JPA 엔티티 간 연관관계 매핑 없이 참조 ID만~~ **폐기 (v0.2)** — 공통 설계 DEC-ARCH-008로 옮김 | — | — |
| DEC-WORKOUT-011 | 운동 목록 API는 페이지 없이 부위의 전체를 반환 | 부위당 5~8개(요구사항 부록 A) | 페이지: 앱에 불필요한 복잡도 |
| DEC-WORKOUT-012 | 운동의 최근 수행일은 완료된 세션 기준 | 진행 중 세션은 아직 확정되지 않은 기록이다. 통계(workout-stats BR-002)와 달력(BR-018)도 완료된 세션만 쓴다 | 진행 중 포함: 지금 하고 있는 운동이 "최근 수행"으로 보임 |
| DEC-WORKOUT-013 | ~~반복 횟수에 소수가 오면 400~~ **폐기 (v0.2)** — 공통 설계 5장으로 옮김 | — | — |
| DEC-WORKOUT-014 | `workout_session.user_id`를 참조(함께 삭제)로 둔다 (v0.4) | 계정을 삭제하면 그 사용자의 운동 기록도 모두 삭제해야 한다(auth BR-012) | 삭제 금지 유지 + 운영자가 운동 기록부터 삭제: 절차가 길고 빠뜨리면 계정 삭제가 실패 |
| DEC-WORKOUT-015 | 세션 응답을 WorkoutSessionResponse 하나로 두고, 현황(부위별 `categories`, 종목별 `volume`·`firstSetAt`, 세트 `createdAt`)을 함께 담는다. 볼륨 이름을 `volume`/`totalVolume`으로 통일 (v0.6) | 홈·오늘 한 운동·완료 팝업·운동 기록 화면이 같은 세션의 다른 부분을 보여주므로 API 하나로 충분하다(요청 수 감소). 화면과 요구사항이 "볼륨"이라 부르므로 이름을 맞춘다(v0.5의 `totalWeight`, `totalSets`(운동별)는 바꾼다) | 화면마다 다른 응답: API가 늘고 계산이 흩어짐 |
| DEC-WORKOUT-016 | 스키마 변경 2·3을 고쳐 다시 만든다(운영 DB 초기화) (v0.6) | 사용자 결정. 운영에 지킬 운동 기록이 없다. 메모 제거·부위 테이블·초기 목록을 처음부터 맞는 스키마로 둔다 (공통 v0.8의 인증 V1 통합과 같은 판단) | 스키마 변경 4 이후로 추가: 쓰지 않는 단계와 기존 데이터 이전 작업이 남음 |
| DEC-WORKOUT-017 | BR-019(세션 없이 종목 선택 시 자동 시작)는 별도 API 없이 앱이 시작 → 추가 순으로 호출 | 기존 API 두 개로 된다. 서버에 "시작하면서 추가" 같은 겹치는 경로를 두지 않는다 | 운동 추가 API가 세션이 없으면 자동 생성: 경로에 세션 ID가 있어 맞지 않고, 시간대 헤더가 추가 API에도 필요해짐 |
| DEC-WORKOUT-018 | 같은 운동을 다시 추가하면 새로 만들지 않고 기존 세션 운동을 200으로 돌려준다. 유일 제약으로 보장 (v0.6) | 요구사항 REQ-EXERCISE-001("다시 추가하지 않고 그 종목의 기록 화면으로"). 앱은 응답의 `sessionExerciseId`로 바로 그 화면으로 간다. 오류가 아니라 정상 흐름이다 | 409: 앱이 오류를 받아 다시 조회해야 함 |
| DEC-WORKOUT-019 | 지난 기록의 조회·삭제 단위를 "운동한 날"(`/workout-days/{date}`)로 둔다 | 화면(WO-003)과 요구사항(BR-020)이 날짜 단위다. 세션 ID를 앱에 노출해 여러 번 부르게 하지 않는다 | 세션 단위 API + 앱이 합치기: 합치는 규칙이 앱에 흩어지고 요청 수가 늘어남 |
| DEC-WORKOUT-020 | 하루 여러 세션을 합칠 때 같은 운동은 한 항목으로 합치고 세트를 추가 시각 순으로 이어 번호를 매긴다 | 화면에서 같은 운동이 두 번 보이지 않는다. 사용자가 보는 것은 "그날 무엇을 했는가"이다 | 세션별로 따로 나열: 요구사항 TODO-021(합쳐서 하나로)과 다름 |
| DEC-WORKOUT-021 | 이전 기록이 없을 때(ERR-013) 204 | 운동 기록 화면에 들어올 때마다 부르는 조회라 "없음"이 정상 상황이다. 진행 중 세션 조회(API-WORKOUT-002)와 같은 방식 | 404 에러 코드: 정상 흐름에서 오류 로그·처리가 생김 |
| DEC-WORKOUT-022 | 불러온 값은 서버에 저장하지 않는다. 불러오기 = 이전 기록 조회(읽기) + 덮어쓰기면 종목 세트 삭제 + 적용할 때마다 세트 추가 | BR-017(적용 전에는 세트가 아님)이 저장 구조로 보장된다. 새 테이블이나 "적용 전" 상태 컬럼이 필요 없다. **한계:** 앱을 껐다 켜면 적용하지 않은 값은 사라진다(다시 불러오면 된다) | 서버에 "불러온 값" 상태 저장: 테이블·상태가 늘고 요약·통계에서 빼는 조건이 모든 쿼리에 필요 |

## 부록 B. 설계 미결정 사항
- **D-TODO-WORKOUT-001** ~~초기 운동 목록 투입과 이미지 파일~~ **결정됨 (v0.6):** 요구사항 TODO-014 결정(부록 A, 24개). 스키마 변경 2에 넣는다. 종목 이미지는 두지 않고 부위 이미지 4개만 둔다 (6.5)
- **D-TODO-WORKOUT-002** ~~운동 목록 페이지 나누기 필요 여부~~ **결정됨 (v0.6):** 부위당 5~8개라 페이지 없음 (DEC-WORKOUT-011)
- **D-TODO-WORKOUT-003** ~~Figma 화면과 4장 대조~~ **결정됨 (v0.7):** 2·3·4행 모두 4.2에 반영. Figma와 다른 점은 화면별 "Figma와 다른 점"
- **D-TODO-WORKOUT-004** ~~Figma 3·4행 기능의 설계~~ **결정됨 (v0.7):** 3.2, 4.2, 5장의 API-SET-004·005, API-WORKOUT-007·008·009

## 부록 C. 요구사항 피드백
설계하면서 요구사항에 정해지지 않았거나 확인이 필요한 점이다. 설계는 괄호의 안으로 진행했으며, 요구사항을 고치면 설계도 따라 고친다.

1. **BR-004 시간대 출처:** "사용자가 있는 지역의 날짜"를 알려면 시스템이 사용자 시간대를 알아야 하는데, 요구사항에 출처가 없다. (설계: 앱이 `X-Time-Zone` 헤더로 기기 시간대를 보낸다. DEC-ARCH-007)
2. ~~**메모 길이**~~ **해결 (v0.6):** 요구사항 TODO-020으로 메모를 두지 않는다.
3. ~~**같은 운동 중복 추가**~~ **해결 (v0.6):** 요구사항 REQ-EXERCISE-001이 "다시 추가하지 않는다"로 정했다 (DEC-WORKOUT-018).
4. ~~**EX-001 최근 수행일**~~ **해결:** 요구사항 TODO-024·통계 TODO-002와 같이 완료된 세션 기준 (DEC-WORKOUT-012).
5. **ERR-003과 2.2의 충돌 가능성:** 2.2는 "다른 사용자의 기록은 존재 여부와 관계없이 볼 수 없다"고 하는데, ERR-003의 403 응답은 그 ID의 기록이 **존재한다는 사실**을 드러낸다. 존재 자체를 숨기려면 다른 사용자의 기록도 404로 응답해야 한다. (설계: ERR-003대로 403. DEC-WORKOUT-007. workout-media 부록 C-1도 같은 문제)
6. ~~**운동 검색 규칙**~~ **해결 (v0.6):** 요구사항 TODO-017로 검색을 하지 않는다.
7. **운동 취소 버튼 위치:** 요구사항 TODO-019는 "홈의 진행 중인 운동"에서 취소한다고 했고 Figma 2행에는 아직 버튼이 없다. (설계: 진행 중 카드 안에 둔다. 디자인 추가 필요)
8. **홈의 부위별 종목 수:** Figma 홈은 "12개 운동"처럼 디자인 시안 숫자를 보여준다. 실제로는 요구사항 부록 A의 수(가슴 5, 등 6, 어깨 5, 하체 8)가 나온다. (설계: DB의 종목 수)
9. **이전 기록 불러오기 버튼 상태:** Figma `workout-recording-prev-loaded`는 불러온 뒤 버튼이 "불러오기 완료"로 바뀌어 다시 누를 수 없지만, 요구사항 BR-021은 여러 번 다시 불러올 수 있다고 한다. (설계: 요구사항대로 "다시 불러오기". 디자인 변경 필요)
