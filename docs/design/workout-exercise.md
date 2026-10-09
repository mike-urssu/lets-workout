# 운동 선택·세트 기록 설계 문서

- 문서 버전: v0.8
- 작성일: 2026-10-05
- 상태: 초안
- 요구사항: `docs/requirements/workout-exercise.md` (v0.7), 공통 `docs/requirements/workout-common.md` (v0.7)
- 공통 설계: `docs/design/architecture.md`, `docs/design/workout-common.md`
- Figma: 3행 `exercise-list`(4:77), `workout-recording`(4:164), `이전 기록 덮어쓰기 확인`(83:201), `workout-recording-prev-loaded`(15:6), `today-workout-popup`(30:10)
- 운동 기록 설계 묶음 (설계 ID와 요구사항 ID를 함께 쓴다, 색인은 `workout-common.md` 부록 D):
  - `workout-common.md` 공통 — 아키텍처, 세션 API 공통 앞단, 상태, 데이터, 보안, 에러, 비기능, 컴포넌트
  - `workout-session.md` 홈·운동 진행 (Figma 2행)
  - `workout-exercise.md` 운동 선택·세트 기록 (Figma 3행)
  - `workout-history.md` 운동 기록 달력 (Figma 4행)
- 변경 이력:
  - v0.2 ~ v0.7: `workout-common.md` 변경 이력 참고 (분리 전 `workout-record.md`)
  - v0.8 (2026-10-09) — `workout-record.md` 설계 v0.7을 요구사항 분리(v0.7)에 맞춰 `workout-common.md`, `workout-session.md`, `workout-exercise.md`, `workout-history.md`로 나눔. 설계 ID와 내용은 바꾸지 않았다

---

## 1. 설계 개요

### 1.1 목적
부위·운동 목록 조회, 세션에 운동 추가·삭제, 세트 추가·수정·삭제, 이전 기록 불러오기를 API 9개로 구현하는 방법을 확정한다. 세션 응답 모델(WorkoutSessionResponse)과 진행 중 현황은 workout-session에 있다.

### 1.2 설계 범위
- **포함:** REQ-EXERCISE-001, 002, REQ-SET-001 ~ 003, REQ-SET-005, 화면 EX-001 ~ EX-003
- **보류:** 없음
- **제외:** 요구사항 workout-common 8.1의 범위 밖 항목과 폐기된 요구사항.

### 1.3 대상 시스템
- Backend API: 도메인 `exercise`(부위·운동 목록, 이전 기록), `session`(세션 운동·세트)
- Mobile App: 화면 EX-001 ~ EX-003

### 1.4 기술 스택
workout-common 1.4를 따른다. 부위 이미지는 공개 정적 파일로 제공한다(DEC-WORKOUT-008).

### 1.5 설계 원칙
workout-common 1.5를 따른다.

### 1.6 요구사항 ↔ 설계 추적표
이 문서의 요구사항 ID만 둔다. 공통 ID(BR-001 등, DATA, NFR)는 workout-common 1.6에 있다.

| 요구사항 ID | 요구사항 | 설계 반영 위치 | 구현 | 테스트 |
|------------|---------|--------------|-----|-------|
| REQ-EXERCISE-001 | 세션에 운동 추가 | 3.2, API-EXERCISE-001, 002, 004, exercise_category, exercise, workout_session_exercise | | |
| REQ-EXERCISE-002 | 세션에서 운동 삭제 | 3.2, API-EXERCISE-003 | | |
| REQ-SET-001 | 세트 기록 추가 | 3.2, API-SET-001, workout_set | | |
| REQ-SET-002 | 세트 기록 수정 | 3.2, API-SET-002 | | |
| REQ-SET-003 | 세트 기록 삭제 | 3.2, API-SET-003 | | |
| REQ-SET-005 | 이전 기록 불러오기 | 3.2, API-SET-004, API-SET-005, 4.2 EX-002 | | |
| BR-003 | 반복 횟수 1~1,000 정수 | 3.5, 5.3 Validation, ck_workout_set_repetitions | | |
| BR-005 | 중량 kg, 0~1,000, 소수 둘째 자리 | 3.5, 5.3 Validation, workout_set.weight 소수(6,2), ck_workout_set_weight | | |
| BR-007 | 세트 번호 1부터 빈 번호 없이 | 3.5, DEC-WORKOUT-002 | | |
| BR-016 | 불러오기는 최근 완료 세션에서 | 3.5, API-SET-004 | | |
| BR-017 | 불러온 값은 적용 전 세트가 아님 | 3.5, 4.2 EX-002 (불러온 값은 앱에만 있다) | | |
| BR-021 | 불러오기는 기존 세트를 바꿈 | 3.5, API-SET-005, 4.2 EX-002 | | |
| ERR-002 | 존재하지 않는 운동 추가 | 8.2 EXERCISE_NOT_FOUND | | |
| ERR-005 | 세트 값 범위·형식 오류 | 8.2 VALIDATION_FAILED | | |
| ERR-010 | 필수 입력 누락 | 8.2 VALIDATION_FAILED | | |
| ERR-013 | 불러올 이전 기록 없음 | API-SET-004 204 (8.2) | | |
| IF-EXERCISE-001 | 부위별 운동 목록과 최근 수행일 | API-EXERCISE-001 | | |
| IF-EXERCISE-002 | 세션에 운동 추가·삭제 | API-EXERCISE-002, API-EXERCISE-003 | | |
| IF-EXERCISE-003 | 부위 목록과 부위별 종목 수 | API-EXERCISE-004 | | |
| IF-SET-001 | 세트 추가·수정·삭제 | API-SET-001, 002, 003 | | |
| IF-SET-003 | 종목의 최근 완료 기록 세트 | API-SET-004 | | |
| EX-001 | 운동 선택 | 4.2 | | |
| EX-002 | 운동 기록 | 4.2 | | |
| EX-003 | 오늘 한 운동 | 4.2 | | |

---

## 2. 시스템 아키텍처
workout-common 2장을 따른다.

---

## 3. 기능 설계

### 3.1 기능 목록
| 요구사항 ID | 기능 | API | 주요 컴포넌트 |
|------------|-----|-----|-------------|
| REQ-EXERCISE-001 | 부위 목록 | API-EXERCISE-004 | ExerciseQueryRepository.findCategories |
| REQ-EXERCISE-001 | 부위의 운동 목록 | API-EXERCISE-001 | ExerciseQueryRepository.findByCategory |
| REQ-EXERCISE-001 | 세션에 운동 추가 | API-EXERCISE-002 | WorkoutSessionService.addExercise |
| REQ-EXERCISE-002 | 세션에서 운동 삭제 | API-EXERCISE-003 | WorkoutSessionService.removeExercise |
| REQ-SET-001 | 세트 추가 | API-SET-001 | WorkoutSessionService.addSet |
| REQ-SET-002 | 세트 수정 | API-SET-002 | WorkoutSessionService.updateSet |
| REQ-SET-003 | 세트 삭제 | API-SET-003 | WorkoutSessionService.deleteSet |
| REQ-SET-005 | 이전 기록 조회 | API-SET-004 | WorkoutSessionQueryRepository.findLastRecord |
| REQ-SET-005 | 불러오기 전 종목의 세트 모두 삭제 | API-SET-005 | WorkoutSessionService.clearSets |

### 3.2 기능별 처리 흐름
모든 흐름의 공통 앞단 (A) 인증, (B) 방치된 세션 정리, (E) 편집 가능 세션 확보는 workout-common 3.2를 따른다.

#### REQ-EXERCISE-001 부위 목록 (API-EXERCISE-004)
1. (A).
2. 조회 저장소에서 한 쿼리로 `exercise_category`를 `sort_order` 순으로 조회하고, 부위별 `exercise` 개수를 붙인다.
3. 200과 부위 목록.

#### REQ-EXERCISE-001 부위의 운동 목록 (API-EXERCISE-001)
1. (A), (B).
2. `categoryId`(필수, UUID)를 검증한다. 없거나 형식이 틀리면 400. 없는 부위면 404 `EXERCISE_CATEGORY_NOT_FOUND`.
3. 조회 저장소에서 한 쿼리로 그 부위의 `exercise`를 `sort_order` 순으로 조회하고, 사용자의 **완료된** 세션에서 세트를 1개 이상 기록한 운동별 가장 최근 `performed_date`를 붙인다 (DEC-WORKOUT-012). 세트 없이 추가만 한 운동은 세지 않는다.
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

### 3.3 주요 시나리오
해당 없음 — 종목 선택부터 세트 기록·불러오기까지의 순서는 3.2와 4.2 EX-002에 있다. 시작부터 완료까지의 흐름은 workout-session 3.3.

### 3.4 상태 변화
workout-common 3.4를 따른다.

### 3.5 비즈니스 규칙 구현
이 문서의 규칙만 둔다. 공통 규칙은 workout-common 3.5에 있다.

| BR ID | 규칙 | 강제 위치 | 방법 | 위반 시 |
|-------|-----|----------|-----|--------|
| BR-003 | 반복 횟수 1~1,000 정수 | 요청 검증, DB | 요청 검증(필수, 정수 — 소수 거부는 공통 5장, 1~1,000) + 조건 검사 `ck_workout_set_repetitions` | 400 VALIDATION_FAILED |
| BR-005 | 중량 0~1,000, 소수 둘째 자리 | 요청 검증, DB | 요청 검증(필수, 0~1,000, 소수 2자리 이내) + 논리 타입 소수(6,2) + 조건 검사 `ck_workout_set_weight` | 400 VALIDATION_FAILED |
| BR-007 | 세트 번호 1부터 연속 | 조회 계산 | 저장하지 않고 운동별 추가 순서(`created_at`, 같으면 `id`)로 번호 계산 (DEC-WORKOUT-002) | 위반이 생길 수 없음 |
| BR-016 | 불러오기는 그 종목의 가장 최근 완료 세션 | 조회 저장소 | API-SET-004가 완료된 세션만 최근순으로 고른다 | — |
| BR-017 | 불러온 값은 적용 전 세트가 아님 | 앱, API 설계 | 불러온 값은 앱 화면에만 있다. 서버에는 적용(API-SET-001)한 세트만 `workout_set`으로 저장되므로 요약·완료 조건·통계에 들어갈 수 없다 (DEC-WORKOUT-022) | — |
| BR-021 | 불러오기는 기존 세트를 모두 바꿈, 확인 필요 | 앱, 서비스 | 앱이 확인 팝업 뒤 API-SET-005(종목의 세트 모두 삭제)를 부르고 불러온 값을 보여준다. 다시 불러오기도 같은 순서 | — |

### 3.6 기능 간 의존관계
- 세트 추가(REQ-SET-001)는 세션 운동(REQ-EXERCISE-001)이 있어야 한다.
- 세션 운동 추가는 운동 목록(`exercise`) 데이터가 있어야 한다. 초기 목록은 스키마 변경 2가 넣는다(workout-common 6.5).
- 공통 의존관계는 workout-common 3.6에 있다.

---

## 4. 화면 / API 연계 설계

### 4.1 화면-API 매핑
| 화면 ID | 화면 | 사용자 행동 | API |
|--------|-----|-----------|-----|
| EX-001 | 운동 선택 | 진입 | API-EXERCISE-001 `GET /api/v1/exercises?categoryId=` |
| EX-001 | 운동 선택 | 종목 선택 | (세션 없으면 API-WORKOUT-001) → API-EXERCISE-002 `POST /api/v1/workout-sessions/{sessionId}/exercises` |
| EX-002 | 운동 기록 | 진입 | API-WORKOUT-002 (그 종목의 `exercises[]` 항목) |
| EX-002 | 운동 기록 | 세트 추가 / 수정 / 삭제, 종목 빼기 | API-SET-001 / 002 / 003, API-EXERCISE-003 |
| EX-002 | 운동 기록 | 진입 (이전 기록이 있는지) | API-SET-004 `GET /api/v1/exercises/{exerciseId}/last-record` |
| EX-002 | 운동 기록 | 이전 기록 불러오기 → 덮어쓰기 확인 | API-SET-005 `DELETE /api/v1/workout-sessions/{sessionId}/exercises/{sessionExerciseId}/sets` |
| EX-002 | 운동 기록 | 불러온 값 적용 | API-SET-001 |
| EX-003 | 오늘 한 운동 | 진입 | API-WORKOUT-002 (`setCount` > 0인 `exercises[]`) |

### 4.2 화면별 연계 상세
공통 응답 처리(401·403·404·400·409·500·503)는 공통 설계 4장을 따른다. 아래는 화면별로 추가되는 처리다.

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

---

## 5. API 설계
URL·필드·날짜·페이지 규칙은 공통 설계 5장을 따른다. 운동 기록 API 전체 목록은 workout-common 5.1에 있다.

### 5.1 API 목록
| API ID | Method | URL | 인증 | 설명 | 관련 요구사항 |
|--------|--------|-----|-----|-----|-------------|
| API-EXERCISE-001 | GET | /api/v1/exercises?categoryId= | 필요 | 부위의 운동 목록 (v0.6: 검색 대신 부위) | REQ-EXERCISE-001, IF-EXERCISE-001 |
| API-EXERCISE-002 | POST | /api/v1/workout-sessions/{sessionId}/exercises | 필요 | 세션에 운동 추가 (v0.6: 이미 있으면 200) | REQ-EXERCISE-001, IF-EXERCISE-002 |
| API-EXERCISE-003 | DELETE | /api/v1/workout-sessions/{sessionId}/exercises/{sessionExerciseId} | 필요 | 세션에서 운동 삭제 | REQ-EXERCISE-002, IF-EXERCISE-002 |
| API-EXERCISE-004 | GET | /api/v1/exercise-categories | 필요 | 부위 목록과 종목 수 | REQ-EXERCISE-001, IF-EXERCISE-003 |
| API-SET-001 | POST | /api/v1/workout-sessions/{sessionId}/exercises/{sessionExerciseId}/sets | 필요 | 세트 추가 | REQ-SET-001, IF-SET-001 |
| API-SET-002 | PUT | /api/v1/workout-sessions/{sessionId}/exercises/{sessionExerciseId}/sets/{setId} | 필요 | 세트 수정 | REQ-SET-002, IF-SET-001 |
| API-SET-003 | DELETE | /api/v1/workout-sessions/{sessionId}/exercises/{sessionExerciseId}/sets/{setId} | 필요 | 세트 삭제 | REQ-SET-003, IF-SET-001 |
| API-SET-004 | GET | /api/v1/exercises/{exerciseId}/last-record | 필요 | 종목의 이전 기록(가장 최근 완료 세션의 세트) | REQ-SET-005, IF-SET-003 |
| API-SET-005 | DELETE | /api/v1/workout-sessions/{sessionId}/exercises/{sessionExerciseId}/sets | 필요 | 종목의 세트 모두 삭제(불러오기 전 덮어쓰기) | REQ-SET-005, BR-021 |

### 5.2 응답 모델
WorkoutSessionResponse는 workout-session 5.2를 따른다. 운동 추가 응답은 그 `exercises[]` 항목 하나다.

**WorkoutSetResponse** (API-SET-001, 002)
```json
{ "id": "0199b2df-5b94-7f85-8247-8b0c2d4e6a57", "setNumber": 3, "weight": 80.00, "repetitions": 8, "createdAt": "2026-09-27T05:30:44Z" }
```

### 5.3 API 상세

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
- `lastPerformedDate`: 사용자의 완료된 세션에서 세트를 1개 이상 기록한 날 기준, 한 번도 안 했으면 null (DEC-WORKOUT-012). 앱이 "최근 3일 전 완료" / "기록 없음"으로 바꿔 보여준다.
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

---

## 6. 데이터 설계
workout-common 6장을 따른다. 이 문서의 기능은 `exercise_category`, `exercise`, `workout_session_exercise`, `workout_set`을 쓰고, 이전 기록은 `workout_session`의 완료된 세션에서 읽는다.

---

## 7. 인증 / 인가 및 보안 설계
workout-common 7장을 따른다.

---

## 8. 예외 / 에러 처리 설계
응답 형식·상태 코드 정책은 공통 설계 8장, 공통 에러 코드(ERR-001, 003, 007, 009)는 workout-common 8.2를 따른다.

### 8.1 에러 응답 예
workout-common 8.1을 따른다.

### 8.2 이 기능의 에러 코드
| 요구사항 ERR | 에러 코드 | HTTP | 메시지 | 발생 위치 |
|-------------|----------|------|-------|----------|
| ERR-002 | EXERCISE_NOT_FOUND | 404 | 운동을 찾을 수 없습니다. | WorkoutSessionService.addExercise |
| ERR-005 | VALIDATION_FAILED (공통) | 400 | 입력값이 올바르지 않습니다. (`errors[]`에 필드별 사유) | 요청 검증 |
| ERR-010 | VALIDATION_FAILED (공통) | 400 | 입력값이 올바르지 않습니다. | 요청 검증, 시간대 헤더 해석 |
| ERR-013 | (에러 코드 없음) | 204 | — | API-SET-004: 이전 기록이 없음을 내용 없음으로 알린다 (DEC-WORKOUT-021) |

---

## 9. 비기능 요구사항 설계
workout-common 9장을 따른다.

---

## 10. 구현 구조 및 개발 전략

### 10.1 컴포넌트 구성
workout-common 10.1을 따른다.

### 10.2 구현 순서
| 순서 | 작업 | 관련 요구사항 | 완료 기준 |
|-----|-----|-------------|----------|
| 2 | 부위 목록 API, 운동 목록 API 변경(검색·이미지 제거, `categoryId`) | REQ-EXERCISE-001, IF-EXERCISE-001, IF-EXERCISE-003 | 종목 수 5·6·5·8, 부록 A 순서, 없는 부위 404 테스트 |
| 6 | 운동 추가 중복 처리 | REQ-EXERCISE-001 | 같은 운동 두 번째 추가 200·행 1개 테스트 |
| 8 | 이전 기록 조회, 종목 세트 모두 삭제 | REQ-SET-005, BR-016, BR-021, ERR-013 | 가장 최근 완료 세션의 세트, 진행 중 세션 제외, 없으면 204, 삭제 후 세트 0건 테스트 |

모두 구현되었다(2026-10-09).

### 10.3 테스트 포인트
- BR-003·BR-005 경계값: 반복 0/1/1000/1001/8.5, 중량 -0.01/0/1000/1000.01/62.555.
- BR-007: 세트 3개 중 2번째 삭제 후 현황을 조회하면 번호가 1, 2.
- REQ-EXERCISE-001: 같은 운동을 두 번 추가하면 두 번째는 200, 세션 운동은 하나.
- BR-016: 같은 종목을 9/20(완료), 9/24(완료), 오늘(진행 중)에 했으면 이전 기록은 9/24의 세트.

---

## 부록 A. 설계 결정 기록
| ID | 결정 | 이유 | 버린 대안 |
|----|-----|-----|----------|
| DEC-WORKOUT-008 | 부위 이미지는 애플리케이션과 함께 배포되는 공개 정적 파일로 `/images/exercise-categories/**`에서 제공하고, `exercise_category.image_url`에 URL 경로를 저장 (v0.6: 종목 이미지에서 부위 이미지로) | 서비스가 준비하는 고정 이미지 4개이고 사용자 업로드가 아니다. 외부 저장소가 필요 없다. **한계:** 이미지를 바꾸려면 배포가 필요하다 | 오브젝트 저장소: 사용자 파일용 비공개 저장소라 공개 이미지를 위해 공개 경로를 따로 열어야 함 |
| DEC-WORKOUT-009 | 존재하지 않는 운동을 추가하면 404 `EXERCISE_NOT_FOUND` | 공통 정책 "대상 없음 = 404"와 일관 | 400: 경로·본문 참조에 따라 상태 코드가 달라져 앱 처리가 복잡 |
| DEC-WORKOUT-011 | 운동 목록 API는 페이지 없이 부위의 전체를 반환 | 부위당 5~8개(요구사항 부록 A) | 페이지: 앱에 불필요한 복잡도 |
| DEC-WORKOUT-012 | 운동의 최근 수행일은 완료된 세션 기준 | 진행 중 세션은 아직 확정되지 않은 기록이다. 통계(workout-stats BR-002)와 달력(BR-018)도 완료된 세션만 쓴다 | 진행 중 포함: 지금 하고 있는 운동이 "최근 수행"으로 보임 |
| DEC-WORKOUT-018 | 같은 운동을 다시 추가하면 새로 만들지 않고 기존 세션 운동을 200으로 돌려준다. 유일 제약으로 보장 (v0.6) | 요구사항 REQ-EXERCISE-001("다시 추가하지 않고 그 종목의 기록 화면으로"). 앱은 응답의 `sessionExerciseId`로 바로 그 화면으로 간다. 오류가 아니라 정상 흐름이다 | 409: 앱이 오류를 받아 다시 조회해야 함 |
| DEC-WORKOUT-021 | 이전 기록이 없을 때(ERR-013) 204 | 운동 기록 화면에 들어올 때마다 부르는 조회라 "없음"이 정상 상황이다. 진행 중 세션 조회(API-WORKOUT-002)와 같은 방식 | 404 에러 코드: 정상 흐름에서 오류 로그·처리가 생김 |
| DEC-WORKOUT-022 | 불러온 값은 서버에 저장하지 않는다. 불러오기 = 이전 기록 조회(읽기) + 덮어쓰기면 종목 세트 삭제 + 적용할 때마다 세트 추가 | BR-017(적용 전에는 세트가 아님)이 저장 구조로 보장된다. 새 테이블이나 "적용 전" 상태 컬럼이 필요 없다. **한계:** 앱을 껐다 켜면 적용하지 않은 값은 사라진다(다시 불러오면 된다) | 서버에 "불러온 값" 상태 저장: 테이블·상태가 늘고 요약·통계에서 빼는 조건이 모든 쿼리에 필요 |

## 부록 B. 설계 미결정 사항
없음. 결정된 항목은 workout-common 부록 B에 있다.

## 부록 C. 요구사항 피드백
번호는 분리 전 `workout-record.md` 설계 부록 C의 번호다.

3. ~~**같은 운동 중복 추가**~~ **해결 (v0.6):** 요구사항 REQ-EXERCISE-001이 "다시 추가하지 않는다"로 정했다 (DEC-WORKOUT-018).
4. ~~**EX-001 최근 수행일**~~ **해결:** 요구사항 TODO-024·통계 TODO-002와 같이 완료된 세션 기준 (DEC-WORKOUT-012).
6. ~~**운동 검색 규칙**~~ **해결 (v0.6):** 요구사항 TODO-017로 검색을 하지 않는다.
9. **이전 기록 불러오기 버튼 상태:** Figma `workout-recording-prev-loaded`는 불러온 뒤 버튼이 "불러오기 완료"로 바뀌어 다시 누를 수 없지만, 요구사항 BR-021은 여러 번 다시 불러올 수 있다고 한다. (설계: 요구사항대로 "다시 불러오기". 디자인 변경 필요)
