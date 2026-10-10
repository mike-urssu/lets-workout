# 운동 통계 설계 문서

- 문서 버전: v0.7
- 작성일: 2026-10-09
- 상태: 초안
- 요구사항: `docs/requirements/workout-stats.md` (v0.14)
- 공통 설계: `docs/design/architecture.md` (v0.13), 운동 기록 공통 `docs/design/workout-common.md` (v0.8, 테이블·방치된 세션 정리)
- Figma: 5행 `dashboard-overview`(4:261)
- 변경 이력:
  - v0.1 (2026-10-09) — 최초 작성. 부위별 볼륨 추이, 부위별 종목 목록, 종목별 볼륨 추이 API 3개
  - v0.2 (2026-10-09) — 요구사항 v0.9 반영. 부위를 고르면 모든 종목 선택(BR-017), 종목 개수 제한 폐기(BR-018·ERR-008 폐기, DEC-STATS-009)
  - v0.3 (2026-10-10) — 요구사항 v0.10 반영. API-STATS-001이 부위 하나(`categoryId`, 필수)를 받아 그 부위를 한 날 7개와 그 부위의 볼륨만 준다(BR-019, BR-020, ERR-009, DEC-STATS-010·011). 부위 칩은 API-EXERCISE-004에서 받는다
  - v0.4 (2026-10-10) — 요구사항 v0.11 반영(종목이 사용자 소유, workout-exercise-manage). 종목 정렬에 `created_at`, `id`를 더함(DEC-WORKOUT-025), `exerciseIds`는 본인 종목만(다른 사용자의 종목은 없는 운동과 같이 400)
  - v0.5 (2026-10-10) — 요구사항 v0.12 반영(TODO-016 재결정). 종목별 추이의 가로축은 종목별 추이에서 고른 부위를 한 날이다. 앱이 API-STATS-001을 종목별 부위로 따로 불러 `dates`·`hasPrevious`를 얻고 API-STATS-003에 보낸다(DEC-STATS-012). API와 서버 동작은 그대로다. v0.4에서 남은 사용자 소유 종목 반영 누락을 고침: 종목 목록 순서·크기(API-STATS-002, DEC-STATS-009), 사용하는 인덱스(6.3), 데이터 접근 제한(7.2)
  - v0.6 (2026-10-10) — 요구사항 v0.13 반영(TODO-018). 종목별 추이의 가로축은 선택된 종목 중 하나라도 한 날이다. API-STATS-003이 `dates` 대신 `before`를 받아 서버가 날짜를 고르고 `hasPrevious`를 준다(DEC-STATS-013, DEC-STATS-004·012 대체). 앱은 종목별 추이에 API-STATS-001을 부르지 않는다
  - v0.7 (2026-10-10) — 요구사항 v0.14 반영(Figma 조회 기간, TODO-019 ~ 021). 두 API 모두 날을 최대 13개 가져와, 가장 최근 날로부터 3개월 안에 있는 날만 최대 12개 보여준다(BR-004, BR-021, DEC-STATS-014). 남긴 날이 있으면 `hasPrevious` = true. API 모양은 그대로다. 조회 기간은 앱이 그래프마다 `dates`의 첫 날 ~ 마지막 날로 보여준다

---

## 1. 설계 개요

### 1.1 목적
운동 통계 화면(ST-001)의 부위별 볼륨 추이와 종목별 추이를 읽기 전용 API 3개로 구현하는 방법을 확정한다. 새 테이블 없이 운동 기록 테이블(workout-common 6장)에서 요청마다 계산한다. 가로축은 고른 부위를 한 날 최대 12개(가장 최근 날로부터 3개월 안)이고, 기준 날짜(`before`) 하나로 최근 구간과 이전 구간을 같은 API로 조회한다(BR-020, DEC-STATS-002).

### 1.2 설계 범위
- **포함:** REQ-STATS-001 ~ REQ-STATS-004, 화면 ST-001
- **보류:** 없음
- **제외:** 요구사항 8.1의 범위 밖 항목. 폐기된 REQ-STATS-005, IF-STATS-006, BR-013, BR-014, ERR-007은 설계하지 않는다.

### 1.3 대상 시스템
- Backend API: 새 도메인 `stats`
- Database: 읽기만 한다 — `workout_session`, `workout_session_exercise`, `workout_set`, `exercise`, `exercise_category`
- Mobile App: 화면 ST-001

### 1.4 기술 스택
공통 설계 1.4를 따른다. 새로 필요한 기술 능력은 없다.

### 1.5 설계 원칙
1. **저장하지 않고 계산한다:** 추이는 조회할 때마다 운동 기록에서 계산한다(요구사항 DATA-001·002). 기록을 완료하거나 지우면 다음 조회부터 바로 반영된다.
2. **볼륨 계산은 운동 기록과 같다:** Σ(중량 × 반복 횟수)를 같은 세트(완료된 세션의 모든 세트)로 계산해, 운동 기록 화면의 날짜별 볼륨과 어긋나지 않게 한다(NFR-INTEG-001).
3. **날짜 축은 그래프마다:** 부위별 추이는 고른 부위를 한 날(API-STATS-001), 종목별 추이는 선택된 종목 중 하나라도 한 날(API-STATS-003)을 가로축으로 쓴다. 두 API 모두 같은 방식(최대 13개 → 3개월 안에서 12개 + `hasPrevious`, `before`)으로 날짜를 고른다(BR-015, BR-020, BR-021, DEC-STATS-013, DEC-STATS-014).
4. **화면 선택 상태는 앱이 가진다:** 고른 부위·종목과 넘겨 본 구간은 서버에 저장하지 않는다(요구사항 DATA-003).

### 1.6 요구사항 ↔ 설계 추적표
| 요구사항 ID | 요구사항 | 설계 반영 위치 | 구현 | 테스트 |
|------------|---------|--------------|-----|-------|
| REQ-STATS-001 | 최근 부위별 볼륨 추이 (부위 하나) | 3.2, API-STATS-001 (`categoryId`, `before` 없음) | | |
| REQ-STATS-002 | 이전 부위별 볼륨 추이 | 3.2, API-STATS-001 (`before`), DEC-STATS-002 | | |
| REQ-STATS-003 | 부위별 종목 목록 | 3.2, API-STATS-002 | | |
| REQ-STATS-004 | 종목별 볼륨 추이 | 3.2, API-STATS-003 | | |
| BR-001 | 본인 기록만 | 3.5, 7.2 | | |
| BR-002 | 운동한 날 = 완료된 세션의 수행 날짜 | 3.2, 3.5, DEC-STATS-007 (두 그래프 모두 BR-020으로 좁힘) | | |
| BR-003 | 처음에는 가장 최근 운동한 날부터 | 3.2 API-STATS-001 3단계 | | |
| BR-004 | 최대 12개 | 3.2, 3.5, API-STATS-001·003, DEC-STATS-014 | | |
| BR-005 | 날짜별 부위 볼륨 | 3.2, 3.5 | | |
| BR-006 | 하지 않은 부위·종목은 값 없음 | 3.5, 5.2 `exercises[].volumes`의 null (부위별 추이는 BR-020으로 null이 없음) | | |
| BR-007 | 이전이 없으면 넘기지 않음 | 3.5, `hasPrevious` | | |
| BR-008 | 날짜는 오래된 날부터 | 3.5, 5.2 `dates` | | |
| BR-009 | 날짜별 종목 볼륨 | 3.2, 3.5 | | |
| BR-010 | 종목별 부위는 하나, 바꾸면 종목 목록·선택도 바뀜 | 3.5, 4.2 (앱), API-STATS-002 | | |
| BR-011 | 기록한 종목만 목록에 | 3.2, 3.5, API-STATS-002 | | |
| BR-012 | 부위 4개, 순서대로 | 3.5, 4.2 (API-EXERCISE-004 순서) | | |
| BR-015 | 종목별 추이는 선택된 종목 중 하나라도 한 날 | 3.2, 3.5, 4.2, API-STATS-003 `before`·`hasPrevious`, DEC-STATS-013 | | |
| BR-016 | 하나씩 밀어 넘기기 | 3.2, 3.5, DEC-STATS-002 | | |
| BR-017 | 처음엔 가슴, 모든 종목 선택 | 3.5, 4.2 (앱), API-STATS-002 순서 | | |
| BR-019 | 부위별 추이는 부위 하나, 처음엔 가슴 | 3.5, 4.2 (앱), API-STATS-001 `categoryId`, DEC-STATS-010 | | |
| BR-020 | 가로축 = 고른 부위를 한 날 | 3.2, 3.5, API-STATS-001 | | |
| BR-021 | 가장 최근 날로부터 3개월 안 | 3.2, 3.5, API-STATS-001·003, DEC-STATS-014 | | |
| ERR-001 | 비로그인 | 8.2 UNAUTHORIZED | | |
| ERR-002 | 기준 날짜 형식 오류 | 8.2 VALIDATION_FAILED | | |
| ERR-003 | 고른 부위를 한 날 없음 | 200 빈 결과 (DEC-STATS-006) | | |
| ERR-004 | 없는 부위 | 8.2 VALIDATION_FAILED (DEC-STATS-005) | | |
| ERR-005 | 없는 종목 | 8.2 VALIDATION_FAILED (DEC-STATS-005) | | |
| ERR-006 | 보여줄 종목·값 없음 | 200 빈 목록·null (DEC-STATS-006) | | |
| ERR-009 | 부위별 추이에 없는 부위 | 8.2 VALIDATION_FAILED (DEC-STATS-005) | | |
| NFR-PERF-001 | p95 500ms | 9장 | | |
| NFR-PERF-002 | 기록이 많아도, 예전 날짜로 넘겨도 | 9장, 6.3 | | |
| NFR-SEC-001 | 인증된 사용자만 | 7.1 | | |
| NFR-SEC-002 | 본인 기록만 | 7.2 | | |
| NFR-INTEG-001 | 통계와 날짜별 기록의 볼륨이 같음 | 1.5, 9장, 10.3 | | |
| IF-STATS-001 | 부위를 주면 최근 최대 12개(3개월 안)와 그 부위 볼륨 | API-STATS-001 `categoryId` | | |
| IF-STATS-002 | 부위·날짜를 주면 그 이전 최대 12개(3개월 안) | API-STATS-001 `categoryId`, `before` | | |
| IF-STATS-003 | 더 이전이 있는지 | API-STATS-001 `hasPrevious` | | |
| IF-STATS-004 | 부위의 종목 목록 | API-STATS-002 | | |
| IF-STATS-005 | 종목(과 기준 날짜)으로 그 종목을 한 날과 종목별 볼륨 | API-STATS-003 | | |
| DATA-001 | 부위별 볼륨 추이 | 5.2 CategoryVolumeTrendResponse (저장 안 함) | | |
| DATA-002 | 종목별 볼륨 추이 | 5.2 ExerciseVolumeTrendResponse (저장 안 함) | | |
| DATA-003 | 화면 선택 상태 | 4.2 (앱에만 있음) | | |
| ST-001 | 운동 통계 화면 | 4.2 | | |

---

## 2. 시스템 아키텍처
공통 설계 2장을 따른다. 추가되는 것:
- 새 도메인 `stats`의 API 진입점·서비스·조회 저장소. 쓰기가 없으므로 저장소(행 저장)는 없다.
- 운동 기록과 같이, 처리 전에 방치된 세션 정리(workout-common 2장 ExpiredSessionCleaner)를 실행한다. 6시간이 지나 자동 완료될 세션이 "운동한 날"에 들어가야 하기 때문이다(BR-002, BR-020, DEC-STATS-007).

```
요청 ─▶ 인증 필터(userId) ─▶ StatsController (API 진입점)
                               ├─▶ ExpiredSessionCleaner.cleanUp(userId)   [독립 트랜잭션]
                               └─▶ StatsService [읽기 트랜잭션]
                                      ├─ 입력 검증, 부위·종목 존재 확인
                                      └─ 운동한 날·볼륨 집계: StatsQueryRepository (조회 저장소)
```

---

## 3. 기능 설계

### 3.1 기능 목록
| 요구사항 ID | 기능 | API | 주요 컴포넌트 |
|------------|-----|-----|-------------|
| REQ-STATS-001 | 최근 부위별 볼륨 추이 | API-STATS-001 | StatsService.categoryVolumes |
| REQ-STATS-002 | 이전 부위별 볼륨 추이 | API-STATS-001 (`before`) | StatsService.categoryVolumes |
| REQ-STATS-003 | 부위별 종목 목록 | API-STATS-002 | StatsService.exercises |
| REQ-STATS-004 | 종목별 볼륨 추이 | API-STATS-003 | StatsService.exerciseVolumes |

### 3.2 기능별 처리 흐름
모든 흐름의 공통 앞단은 workout-common 3.2의 (A) 인증, (B) 방치된 세션 정리를 따른다. 쓰기가 없으므로 (E)는 없다.

#### REQ-STATS-001·002 부위별 볼륨 추이 (API-STATS-001)
1. (A), (B).
2. `categoryId`(필수, UUID)를 검증한다. 없거나 형식이 틀리거나 없는 부위면 400 `VALIDATION_FAILED`, `errors[].field` = `categoryId` (ERR-009, DEC-STATS-005). `before`(선택, `YYYY-MM-DD`)도 검증한다. 형식이 틀리면 400 `VALIDATION_FAILED` (ERR-002).
3. 조회 저장소에서 고른 부위를 한 날을 고른다(쿼리 1회): 사용자의 **완료된** 세션 중, 그 부위에 속한 운동의 세트가 1개 이상 있는 세션의 `performed_date`를 중복 없이 내림차순으로, `before`가 있으면 `performed_date < before`(기준 날짜 제외) 조건으로 **최대 13개** 가져온다 (BR-002, BR-003, BR-016, BR-020).
4. 구간을 만든다: 가져온 날 중 가장 최근 날의 3개월 전 같은 날짜(그 달에 같은 날짜가 없으면 그 달의 마지막 날)를 하한으로 잡고, 최근 날부터 하한 이상인 날만 최대 12개 남긴다. 남기지 못한 날(13번째 날, 하한보다 이전 날)이 있으면 `hasPrevious` = true, 없으면 false (BR-004, BR-007, BR-021, DEC-STATS-001, DEC-STATS-014).
5. 고른 날이 없으면 `dates` = [], `volumes` = [], `hasPrevious` = false로 200 (ERR-003, DEC-STATS-006).
6. 조회 저장소에서 고른 날들의 그 부위 볼륨을 계산한다(쿼리 1회): 사용자의 완료된 세션 중 `performed_date`가 고른 날인 것 → 세션 운동 → 그 부위의 운동 → 세트를 이어 `performed_date`별 Σ(중량 × 반복 횟수) (BR-005).
7. 서비스에서 날짜를 오름차순으로 두고(BR-008) 각 날짜 자리에 볼륨을 채운다. 3에서 그 부위를 한 날만 골랐으므로 값이 없는 자리는 없다(BR-020). 중량이 모두 0인 세트만 있으면 0이다(BR-002).
8. 200과 CategoryVolumeTrendResponse.

#### REQ-STATS-003 부위별 종목 목록 (API-STATS-002)
1. (A), (B).
2. `categoryId`(필수, UUID)를 검증한다. 없거나 형식이 틀리거나 없는 부위면 400 `VALIDATION_FAILED`, `errors[].field` = `categoryId` (ERR-004, DEC-STATS-005).
3. 조회 저장소에서 그 부위의 운동 중, 사용자의 **완료된** 세션에서 세트를 1개 이상 기록한 운동을 `exercise.sort_order`, `created_at`, `id` 순으로 조회한다(쿼리 1회). 종목은 본인 것만 있다(workout-common BR-022) (BR-011, BR-017). 지금 보는 날짜와 관계없이 전체 기록에서 고른다.
4. 200과 목록. 없으면 빈 목록 (ERR-006).

#### REQ-STATS-004 종목별 볼륨 추이 (API-STATS-003)
1. (A), (B).
2. 검증: `exerciseIds` 1개 이상, UUID, 중복 없음 (DEC-STATS-009) / `before`(선택, `YYYY-MM-DD`). 위반 시 400 `VALIDATION_FAILED` (ERR-002).
3. `exerciseIds` 중 본인 종목 목록에 없는 운동이 있으면(다른 사용자의 종목 포함) 400 `VALIDATION_FAILED`, `errors[].field` = `exerciseIds` (ERR-005, DEC-STATS-005, workout-exercise-manage 7.2).
4. 조회 저장소에서 고른 종목 중 하나라도 한 날을 고른다(쿼리 1회): 사용자의 **완료된** 세션 중, 고른 운동의 세트가 1개 이상 있는 세션의 `performed_date`를 중복 없이 내림차순으로, `before`가 있으면 `performed_date < before` 조건으로 **최대 13개** (BR-002, BR-003, BR-015, BR-016). API-STATS-001 3단계에서 부위 조건을 운동 조건으로 바꾼 것이다.
5. API-STATS-001 4단계와 같이 3개월 안에서 최대 12개로 구간을 만들고 `hasPrevious`를 정한다 (BR-004, BR-007, BR-021, DEC-STATS-014). 고른 날이 없으면 `dates` = [], 각 `volumes` = [], `hasPrevious` = false (ERR-006, DEC-STATS-006).
6. 조회 저장소에서 사용자의 완료된 세션 중 `performed_date`가 고른 날인 것 → 세션 운동(고른 운동) → 세트를 이어 `(performed_date, 운동)`별 Σ(중량 × 반복 횟수) (BR-009). 쿼리 1회.
7. 서비스에서 날짜를 오름차순으로 두고(BR-008), 운동을 요청 순서대로 각 날짜 자리에 볼륨을 채운다. 그날 하지 않은 종목은 null (BR-006). 날마다 고른 종목 중 적어도 하나는 값이 있다(BR-015).
8. 200과 ExerciseVolumeTrendResponse.
- 종목이 고른 부위에 속하는지는 확인하지 않는다. 부위를 하나만 고르는 것은 화면 규칙이다(BR-010, 4.2).

### 3.3 주요 시나리오
```
App                                      API                                   DB
 │ [통계 탭] GET exercise-categories       │ 부위 4개 (목록 순서)                     │
 │◀── 200 [가슴, 등, 어깨, 하체]                                                    │
 │ GET stats/category-volumes?categoryId=<가슴>  cleanUp → 가슴을 한 날 최대 13개 → 3개월 안 12개 → 볼륨 │
 │◀── 200 dates(12), volumes(12), hasPrevious=true                                 │
 │ GET stats/exercises?categoryId=<가슴>   │ 가슴에서 기록한 종목 (목록 순서)          │
 │◀── 200 [벤치프레스, 인클라인 벤치프레스, …]                                        │
 │ (목록의 모든 종목 선택)                                                           │
 │ GET stats/exercise-volumes?exerciseIds=a&exerciseIds=b   고른 종목 중 하나라도 한 날 최대 13개 → 3개월 안 12개 → 볼륨 │
 │◀── 200 dates(12), exercises(목록 수만큼), hasPrevious                             │
 │ [부위별 추이를 왼쪽으로 넘김] GET stats/category-volumes?categoryId=<가슴>&before=<지금 구간의 가장 최근 날> │
 │◀── 200 (하나 밀린 구간). 종목별 추이는 그대로                                         │
 │ [오른쪽으로 넘김] 앱이 보관한 앞 구간을 다시 보여준다 (호출 없음, DEC-STATS-003)       │
 │ [부위별 추이에서 등 선택] GET stats/category-volumes?categoryId=<등> (가장 최근 구간)   │
 │◀── 200 등을 한 날 최대 12개. 종목별 추이(가슴)는 그대로                                    │
 │ [종목별 추이에서 하체 선택] GET stats/exercises?categoryId=<하체>                    │
 │ GET stats/exercise-volumes (하체 종목 모두, before 없음)                           │
 │ [종목 하나 선택 해제] GET stats/exercise-volumes (남은 종목, before 없음 = 가장 최근 구간) │
 │ [종목별 추이를 왼쪽으로 넘김] GET stats/exercise-volumes (같은 종목, before=…)        │
```

### 3.4 상태 변화
해당 없음 — 읽기 전용이다. 세션 상태는 workout-common 3.4를 따르고, 완료된 세션만 읽는다.

### 3.5 비즈니스 규칙 구현
| BR ID | 규칙 | 강제 위치 | 방법 | 위반 시 |
|-------|-----|----------|-----|--------|
| BR-001 | 본인 기록만 | 조회 저장소 | 모든 집계 조건에 `user_id = userId` | 다른 사용자 기록이 들어갈 수 없음 |
| BR-002 | 운동한 날 = 완료된 세션의 수행 날짜 | 조회 저장소, 앞단 | 조건 `status = COMPLETED`, `performed_date` 중복 제거. 앞단 (B)로 6시간 지난 세션을 먼저 완료 처리. 부위별 추이는 BR-020, 종목별 추이는 BR-015로 더 좁힌다 | — |
| BR-003 | 처음엔 가장 최근부터 | 조회 저장소 | `before` 없으면 날짜 상한 없이 내림차순 | — |
| BR-004 | 최대 12개 | 조회 저장소, 서비스 | 두 API 모두 13개 가져와 12개까지만 사용 (DEC-STATS-001) | — |
| BR-005 | 날짜별 부위 볼륨 | 조회 저장소 | 고른 부위의 운동 세트만 `performed_date`로 묶어 Σ(중량 × 반복 횟수) — workout-common BR-015와 같은 식 | — |
| BR-006 | 하지 않은 부위·종목은 값 없음 | 서비스 | 종목별 추이: 집계 결과에 없는 자리는 null (0을 넣지 않음, 고른 종목 중 그날 안 한 종목). 부위별 추이는 그 부위를 한 날만 고르므로 null이 생기지 않는다(BR-020) | — |
| BR-007 | 이전이 없으면 넘기지 않음 | 서비스, 앱 | 두 API 모두 `hasPrevious` = 가져온 날 중 보여주지 못한 날(13번째 날, 3개월 하한보다 이전 날)이 있는지. 앱은 false면 왼쪽 넘기기를 막는다 | — |
| BR-008 | 오래된 날부터 | 서비스 | `dates` 오름차순, `volumes[]`도 같은 순서 | — |
| BR-009 | 날짜별 종목 볼륨 | 조회 저장소 | `(performed_date, 운동)` 묶음 Σ(중량 × 반복 횟수) | — |
| BR-010 | 종목별 부위는 하나, 바꾸면 종목 목록·선택도 바뀜 | 앱, API 설계 | 부위를 바꾸면 앱이 API-STATS-002로 새 부위의 종목 목록을 받아 모두 선택한다(4.2 ST-001). 종목 목록이 부위로만 정해지므로 다른 부위의 종목이 남지 않는다 | — |
| BR-011 | 기록한 종목만 | 조회 저장소 | 완료된 세션에 세트가 1개 이상인 세션 운동이 있는 운동만 (부록 C-1) | — |
| BR-012 | 부위 4개, 순서대로 | 앱, 기존 API | 두 그래프의 부위 칩은 API-EXERCISE-004(`exercise_category`를 `sort_order` 순으로)를 쓴다 | — |
| BR-015 | 종목별 추이는 선택된 종목 중 하나라도 한 날 | 조회 저장소, 앱 | API-STATS-003이 고른 종목의 세트가 있는 세션의 날짜를 API-STATS-001과 같은 방식(최대 13개 → 3개월 안 12개, `before`)으로 고르고 `hasPrevious`를 준다 (DEC-STATS-013). 앱은 부위나 종목 선택을 바꾸면 `before` 없이, 넘기면 같은 종목 선택으로 다시 부른다. 부위별 추이와 구간 목록을 따로 가진다 | — |
| BR-016 | 하나씩 밀어 넘기기 | API 설계, 앱 | 두 그래프 모두 앱이 `before` = 지금 구간의 가장 최근 날로 다시 부른다. 그 날을 빼고 이전 날들이 오므로 하나 밀린 구간이 된다. 3개월 하한은 새 가장 최근 날로 다시 잡는다 (DEC-STATS-002, BR-021) | — |
| BR-017 | 처음엔 가슴, 모든 종목 선택 | 앱, 조회 저장소 | API-STATS-002가 본인 목록 순서(`exercise.sort_order`, `created_at`, `id`)를 보장하고, 앱이 가슴과 목록의 모든 종목을 고른다. 가슴에 기록이 없어도 가슴(요구사항 TODO-013) | — |
| BR-019 | 부위별 추이는 부위 하나, 처음엔 가슴 | 앱, 요청 검증 | 앱은 칩을 하나만 선택 상태로 두고 처음엔 가슴(요구사항 TODO-017). 서버는 `categoryId`를 하나만 받는다(필수, DEC-STATS-010). 바꾸면 `before` 없이 다시 부른다(요구사항 TODO-014) | 400 VALIDATION_FAILED (`categoryId` 없음) |
| BR-020 | 가로축 = 고른 부위를 한 날 | 조회 저장소 | 운동한 날 조회에 "그 부위의 운동 세트가 있는 세션" 조건을 더한다. 처음 보기·`before`·`hasPrevious`가 모두 같은 조회를 쓰므로 함께 지켜진다 | — |
| BR-021 | 가장 최근 날로부터 3개월 안 | 서비스 | 가져온 날(최대 13개) 중 가장 최근 날의 3개월 전 같은 날짜(없으면 그 달 마지막 날, 요구사항 TODO-021)를 하한으로, 하한 이상인 날만 남긴다. 처음 보기와 `before` 모두 같은 계산이다 (DEC-STATS-014) | — |

### 3.6 기능 간 의존관계
- 운동 기록 테이블과 방치된 세션 정리(workout-common 2장, 6장)에 의존한다. 새 스키마 변경이 없다.
- API-STATS-001·002는 API-EXERCISE-004(workout-exercise)의 부위 `id`를, API-STATS-003은 API-STATS-002의 종목 `id`를 쓴다(앱 호출 순서).
- 볼륨 계산은 날짜별 기록(workout-history API-WORKOUT-008)과 같은 식이어야 한다(NFR-INTEG-001).

---

## 4. 화면 / API 연계 설계
요구사항 03장과 Figma `dashboard-overview`를 근거로 한다.

### 4.1 화면-API 매핑
| 화면 ID | 화면 | 사용자 행동 | API |
|--------|-----|-----------|-----|
| ST-001 | 운동 통계 | 진입 | API-EXERCISE-004 `GET /api/v1/exercise-categories` → API-STATS-001 `GET /api/v1/stats/category-volumes?categoryId=` → API-STATS-002 `GET /api/v1/stats/exercises?categoryId=` → API-STATS-003 `GET /api/v1/stats/exercise-volumes` |
| ST-001 | 운동 통계 | 부위별 추이 왼쪽으로 넘기기 | API-STATS-001 `?categoryId=&before=` |
| ST-001 | 운동 통계 | 부위별 추이 부위 선택 | API-STATS-001 `?categoryId=` (`before` 없음) |
| ST-001 | 운동 통계 | 종목별 추이 왼쪽으로 넘기기 | API-STATS-003 `?exerciseIds=…&before=` |
| ST-001 | 운동 통계 | 오른쪽으로 넘기기 (두 그래프) | (호출 없음) 앱이 보관한 구간 |
| ST-001 | 운동 통계 | 종목별 추이 부위 선택 | API-STATS-002 → API-STATS-003 (`before` 없음) |
| ST-001 | 운동 통계 | 종목 선택·해제 | API-STATS-003 (`before` 없음, 선택이 0개면 호출 없음) |

### 4.2 화면별 연계 상세
공통 응답 처리(401·400·500)는 공통 설계 4장을 따른다.

#### ST-001 운동 통계 (Figma `dashboard-overview`)
- 진입 조건: 로그인 완료, 하단 탭 "통계"
- 필요 데이터:
  - 제목 "운동 통계", 부제 "부위별·종목별 볼륨 추이"(앱 문구, 요구사항 TODO-006)
  - 두 그래프의 부위 칩: API-EXERCISE-004 → `id`, `name` (목록 순서, BR-012). 두 칩 줄의 선택은 따로다(BR-019)
  - 부위별 볼륨 추이: API-STATS-001 → `dates`(가로축, 앱이 "9/22"로 표시), `volumes`(선 하나), `hasPrevious`
  - 조회 기간: 그래프마다 앱이 `dates`의 첫 날 ~ 마지막 날로 "2026.09.22–09.27"처럼 보여준다. `dates`가 []면 보여주지 않는다(요구사항 TODO-020). 서버는 따로 주지 않는다
  - 안내 "최대 3개월 · 최대 12개 지점"과 좌우로 넘기기 설명: 앱 문구(BR-004, BR-021)
  - 볼륨 표시: 두 그래프 모두 kg 값을 1,000으로 나눠 "단위: 1,000kg"로 보여준다(앱, 요구사항 ST-001). 서버는 kg 그대로 준다
  - 종목 칩: API-STATS-002 → `name`
  - 종목별 그래프: API-STATS-003 → `dates`(가로축), `hasPrevious`, `exercises[].name`(범례), `exercises[].volumes`
- 사용자 입력: 그래프별 넘기기, 부위별 추이 부위 선택(하나), 종목별 추이 부위 선택(하나), 종목 선택·해제
- API 호출:
  - 두 그래프는 각자 부위, 지금 구간(`dates`, `hasPrevious`), 쌓아 둔 구간 목록을 따로 가진다. 한쪽을 바꿔도 다른 쪽은 다시 부르지 않는다(BR-015, BR-019)
  - 진입: API-EXERCISE-004 → 가슴의 `id`로 API-STATS-001(`before` 없음)과 API-STATS-002 → 목록의 모든 종목으로 API-STATS-003(`before` 없음, 목록이 비면 호출하지 않는다)
  - 부위별 추이 왼쪽으로 넘기기: `hasPrevious`가 true일 때만. 지금 구간을 부위별 추이의 구간 목록에 쌓고 API-STATS-001(같은 `categoryId`, `before` = 지금 `dates`의 마지막 날)
  - 부위별 추이 부위 선택: 부위별 추이의 구간 목록을 비우고 API-STATS-001(새 `categoryId`, `before` 없음, 요구사항 TODO-014)
  - 종목별 추이 왼쪽으로 넘기기: 종목별 추이의 `hasPrevious`가 true일 때만. 지금 구간을 종목별 추이의 구간 목록에 쌓고 같은 종목 선택으로 API-STATS-003(`before` = 지금 `dates`의 마지막 날) (BR-015, BR-016)
  - 오른쪽으로 넘기기: 그 그래프에 쌓아 둔 구간이 있을 때만. 꺼내서 보여준다(호출 없음, DEC-STATS-003). 종목 선택을 바꾸면 구간 목록을 비우므로 쌓아 둔 구간은 늘 지금 선택의 것이다
  - 종목별 추이 부위 선택: 종목별 추이의 구간 목록을 비우고 API-STATS-002 → 새 부위의 종목을 모두 선택해 API-STATS-003(`before` 없음) (BR-010, BR-015, BR-017)
  - 종목 선택·해제: 종목별 추이의 구간 목록을 비우고, 선택이 1개 이상이면 API-STATS-003(`before` 없음, 가장 최근 구간). 0개면 호출하지 않고 빈 그래프 (BR-015)
- 성공 처리: 받은 값으로 그래프를 다시 그린다. 종목별 추이를 넘겨도 종목 선택은 그대로다(BR-015)
- 실패 처리: 400 → 앱 버그(입력 형식), 처음 구간으로 다시 조회
- 로딩 상태: 그래프별로 조회 중 표시, 조회 중 넘기기 막기
- 빈 상태:
  - 부위별 추이의 API-STATS-001 `dates`가 [] → 부위별 추이에 "이 부위의 운동 기록이 없어요"
  - API-STATS-002가 [] → 종목별 추이에 "이 부위에 기록한 운동이 없어요". 처음 부위(가슴)도 같다(요구사항 TODO-013)
- 화면을 다시 열면 기본 선택(두 그래프 모두 가슴)과 최근 구간으로 돌아간다(서버에 선택을 저장하지 않는다, 요구사항 DATA-003)
- Figma와 다른 점(요구사항 8.2): 연속 6일 대신 고른 부위를 한 날 최대 12개(3개월 안), 두 그래프 따로 넘기기와 그래프마다 조회 기간(디자인은 화면 위에 하나), 부위별 추이는 네 부위가 아니라 한 부위, null 처리, 빈 상태, 종목 칩은 기록한 종목만

---

## 5. API 설계
URL·필드·날짜 규칙은 공통 설계 5장을 따른다. 값 여러 개인 쿼리 파라미터는 같은 이름을 반복한다(공통 5장).

### 5.1 API 목록
| API ID | Method | URL | 인증 | 설명 | 관련 요구사항 |
|--------|--------|-----|-----|-----|-------------|
| API-STATS-001 | GET | /api/v1/stats/category-volumes?categoryId=&before= | 필요 | 고른 부위를 한 날 최대 12개(3개월 안)와 그 부위의 볼륨 | REQ-STATS-001, REQ-STATS-002, IF-STATS-001 ~ 003 |
| API-STATS-002 | GET | /api/v1/stats/exercises?categoryId= | 필요 | 부위에서 기록한 종목 목록 | REQ-STATS-003, IF-STATS-004 |
| API-STATS-003 | GET | /api/v1/stats/exercise-volumes?exerciseIds=&before= | 필요 | 고른 종목 중 하나라도 한 날 최대 12개(3개월 안)와 종목별 볼륨 | REQ-STATS-004, IF-STATS-005 |

### 5.2 응답 모델

**CategoryVolumeTrendResponse** (API-STATS-001) — DATA-001
```json
{
  "dates": ["2026-09-12", "2026-09-15", "2026-09-17", "2026-09-20", "2026-09-22", "2026-09-24", "2026-09-27"],
  "volumes": [4320.00, 3800.00, 3900.00, 0.00, 4500.00, 3950.00, 4100.00],
  "hasPrevious": true
}
```
| 필드 | 설명 | 근거 |
|-----|-----|-----|
| `dates` | 고른 부위를 한 날, 오름차순, 0~12개. 첫 날은 마지막 날의 3개월 전 같은 날짜 이후 | BR-004, BR-008, BR-020, BR-021 |
| `volumes` | `dates`와 같은 길이·순서의 그 부위 볼륨(kg). null 없음, 중량 0인 세트만 했으면 0 | BR-005, BR-020 |
| `hasPrevious` | `dates`의 첫 날보다 이전에 고른 부위를 한 날이 있는지 | BR-007, IF-STATS-003 |

요청한 부위는 응답에 다시 넣지 않는다. 앱이 보낸 `categoryId`와 API-EXERCISE-004의 이름을 쓴다(DEC-STATS-011).

**StatsExerciseResponse** (API-STATS-002 목록 항목)
```json
{ "id": "0199a0f1-2b30-7c55-8d14-5e7f9a1b3d24", "name": "벤치프레스" }
```

**ExerciseVolumeTrendResponse** (API-STATS-003) — DATA-002
```json
{
  "dates": ["2026-09-12", "2026-09-15", "2026-09-17", "2026-09-20", "2026-09-22", "2026-09-24", "2026-09-27"],
  "exercises": [
    { "id": "0199a0f1-2b30-7c55-8d14-5e7f9a1b3d24", "name": "벤치프레스", "volumes": [2340.00, null, 2100.00, null, 2500.00, null, 2200.00] },
    { "id": "0199a0f1-2b32-7e77-af36-7a9b1c3d5f46", "name": "인클라인 벤치프레스", "volumes": [1980.00, 1800.00, null, 1850.00, 2000.00, 1950.00, 1900.00] }
  ],
  "hasPrevious": true
}
```
| 필드 | 설명 | 근거 |
|-----|-----|-----|
| `dates` | 고른 종목 중 하나라도 한 날, 오름차순, 0~12개. 첫 날은 마지막 날의 3개월 전 같은 날짜 이후 | BR-004, BR-008, BR-015, BR-021 |
| `exercises` | 요청한 순서 | REQ-STATS-004 |
| `exercises[].volumes` | `dates`와 같은 길이·순서. 그날 안 했으면 null | BR-006, BR-009 |
| `hasPrevious` | `dates`의 첫 날보다 이전에 고른 종목 중 하나라도 한 날이 있는지 | BR-007, BR-015 |

### 5.3 API 상세

#### API-STATS-001 부위별 볼륨 추이
- 목적: 고른 부위를 한 날 최대 12개(가장 최근 날로부터 3개월 안)와 날짜별 그 부위의 볼륨을 준다. `before`가 없으면 최근 구간, 있으면 그 날 이전 구간이다.
- Method / URL: `GET /api/v1/stats/category-volumes?categoryId=0199a0f0-1a20-7b44-8c03-4d6e8f0a2c01` / `…&before=2026-09-27`
- 인증: 필요
- 관련 요구사항: REQ-STATS-001, REQ-STATS-002, IF-STATS-001 ~ 003, BR-001 ~ 005, BR-007, BR-008, BR-016, BR-019 ~ BR-021

Request
- Query: `categoryId` (필수), `before` (선택)

Response `200 OK` — CategoryVolumeTrendResponse. 그 부위를 한 날이 없으면 `{ "dates": [], "volumes": [], "hasPrevious": false }` (ERR-003)

Validation
| 필드 | 타입 | 필수 | 규칙 | 근거 |
|-----|-----|-----|-----|-----|
| categoryId | UUID | Y | UUID 형식, 있는 부위 | BR-019, ERR-009 |
| before | string | N | `YYYY-MM-DD`. 이 날은 결과에 들어가지 않는다 | BR-016, ERR-002 |

Errors
| HTTP | 에러 코드 | 조건 | 관련 |
|------|----------|-----|-----|
| 400 | VALIDATION_FAILED | `categoryId` 없음·형식 오류·없는 부위 | ERR-009 |
| 400 | VALIDATION_FAILED | `before` 형식 오류 | ERR-002 |
| 401 | UNAUTHORIZED | 인증 없음 | ERR-001 |

#### API-STATS-002 부위별 종목 목록
- 목적: 종목별 추이에서 고를 수 있는 종목(그 부위에서 기록한 종목)을 준다.
- Method / URL: `GET /api/v1/stats/exercises?categoryId=0199a0f0-1a20-7b44-8c03-4d6e8f0a2c01`
- 인증: 필요
- 관련 요구사항: REQ-STATS-003, IF-STATS-004, BR-001, BR-011, BR-017

Response `200 OK` — StatsExerciseResponse 배열, 본인 종목 목록 순서(`sort_order`, `created_at`, `id`). 없으면 `[]` (ERR-006). 페이지 없음(본인 종목 목록 API-EXERCISE-001과 같다)

Validation
| 필드 | 타입 | 필수 | 규칙 | 근거 |
|-----|-----|-----|-----|-----|
| categoryId | UUID | Y | UUID 형식, 있는 부위 | ERR-004 |

Errors
| HTTP | 에러 코드 | 조건 | 관련 |
|------|----------|-----|-----|
| 400 | VALIDATION_FAILED | `categoryId` 없음·형식 오류·없는 부위 | ERR-004 |
| 401 | UNAUTHORIZED | 인증 없음 | ERR-001 |

#### API-STATS-003 종목별 볼륨 추이
- 목적: 고른 종목 중 하나라도 한 날 최대 12개(가장 최근 날로부터 3개월 안)와 종목마다 날짜별 볼륨을 준다. `before`가 없으면 최근 구간, 있으면 그 날 이전 구간이다(API-STATS-001과 같은 방식).
- Method / URL: `GET /api/v1/stats/exercise-volumes?exerciseIds={id}&exerciseIds={id}` / `…&before=2026-09-27`
- 인증: 필요
- 관련 요구사항: REQ-STATS-004, IF-STATS-005, BR-001 ~ 004, BR-006 ~ 009, BR-015, BR-016, BR-021

Response `200 OK` — ExerciseVolumeTrendResponse. 고른 종목을 한 날이 없으면 `dates` = [], 각 `volumes` = [], `hasPrevious` = false (ERR-006)

Validation
| 필드 | 타입 | 필수 | 규칙 | 근거 |
|-----|-----|-----|-----|-----|
| exerciseIds | UUID 목록 | Y | 1개 이상, 중복 없음, 모두 있는 운동 | ERR-005, DEC-STATS-009 |
| before | string | N | `YYYY-MM-DD`. 이 날은 결과에 들어가지 않는다 | BR-016, ERR-002 |

Errors
| HTTP | 에러 코드 | 조건 | 관련 |
|------|----------|-----|-----|
| 400 | VALIDATION_FAILED | `exerciseIds` 없음·중복·형식 오류 | REQ-STATS-004 |
| 400 | VALIDATION_FAILED | 없는 운동, 다른 사용자의 종목 | ERR-005 |
| 400 | VALIDATION_FAILED | `before` 형식 오류 | ERR-002 |
| 401 | UNAUTHORIZED | 인증 없음 | ERR-001 |

---

## 6. 데이터 설계
새 테이블과 스키마 변경은 없다. workout-common 6장의 테이블을 읽기만 한다(요구사항 DATA-001 ~ 003은 저장하지 않는다).

### 6.1 ERD
workout-common 6.1을 따른다.

### 6.2 테이블 정의
해당 없음 — 새 테이블이 없다.

### 6.3 인덱스
새 인덱스는 없다. 이 기능이 쓰는 기존 인덱스:
| 인덱스 | 대상 조회 | 근거 |
|-------|---------|-----|
| idx_workout_session_user_performed | 사용자의 운동한 날을 최근순으로 (`before` 이전 포함), 고른 날짜의 세션 | REQ-STATS-001, 002, 004, NFR-PERF-002 |
| ux_workout_session_exercise_session_exercise | 세션의 운동, 고른 운동이 있는 세션 운동 (고른 종목을 한 날 확인 포함) | REQ-STATS-003, 004 |
| idx_workout_set_session_exercise | 세션 운동의 세트 (볼륨 합, 기록 여부) | REQ-STATS-001 ~ 004 |
| ux_exercise_user_category_name | 사용자의 그 부위 종목 (앞 두 컬럼 `user_id`, `exercise_category_id`) | REQ-STATS-003 |

### 6.4 삭제 정책
해당 없음 — 쓰지 않는다. 기록이 삭제되면 다음 조회부터 빠진다.

### 6.5 스키마 변경 목록
없음.

---

## 7. 인증 / 인가 및 보안 설계

### 7.1 API별 인증
API 3개 모두 인증 필요 (NFR-SEC-001).

### 7.2 사용자별 데이터 접근 제한
- userId는 인증 필터가 확인한 로그인에서만 얻는다. 요청에 사용자 ID를 받지 않는다.
- 모든 집계 조건에 `user_id = userId`가 있다(BR-001, NFR-SEC-002). 부위 ID는 모든 사용자에게 같은 기준 데이터라 소유자 확인이 필요 없다. 종목은 사용자 소유라(workout-common BR-022) 종목 목록은 본인 종목만 고르고, `exerciseIds`에 다른 사용자의 종목이 있으면 없는 운동과 같이 400 `VALIDATION_FAILED`다(3.2 REQ-STATS-004 3단계, workout-exercise-manage 7.2). 그래서 403이 생기지 않는다.

### 7.3 민감 데이터 / 로그
조회만 하므로 행위 로그를 남기지 않는다. 처리하지 못한 예외만 ERROR(공통 8.4).

---

## 8. 예외 / 에러 처리 설계
응답 형식·상태 코드 정책은 공통 설계 8장을 따른다. 기능 에러 코드는 새로 만들지 않는다.

### 8.1 에러 응답 예
```json
{
  "code": "VALIDATION_FAILED",
  "message": "입력값이 올바르지 않습니다.",
  "timestamp": "2026-10-09T18:00:00Z",
  "errors": [ { "field": "exerciseIds", "reason": "1개 이상 겹치지 않게 골라야 합니다." } ]
}
```

### 8.2 이 기능의 에러 코드
| 요구사항 ERR | 에러 코드 | HTTP | 메시지 | 발생 위치 |
|-------------|----------|------|-------|----------|
| ERR-001 | UNAUTHORIZED (공통) | 401 | 로그인이 필요합니다. | 인증 필터 |
| ERR-002 | VALIDATION_FAILED (공통) | 400 | 입력값이 올바르지 않습니다. (`before`) | 요청 검증 |
| ERR-003 | (에러 아님) | 200 | — | 빈 `dates` (DEC-STATS-006) |
| ERR-004 | VALIDATION_FAILED (공통) | 400 | 입력값이 올바르지 않습니다. (`categoryId`: "존재하지 않는 부위입니다.") | 요청 검증, StatsService.exercises 존재 확인 |
| ERR-005 | VALIDATION_FAILED (공통) | 400 | 입력값이 올바르지 않습니다. (`exerciseIds`: "존재하지 않는 운동입니다.") | StatsService.exerciseVolumes 존재 확인 |
| ERR-006 | (에러 아님) | 200 | — | 빈 목록, null 값 (DEC-STATS-006) |
| ERR-009 | VALIDATION_FAILED (공통) | 400 | 입력값이 올바르지 않습니다. (`categoryId`: "존재하지 않는 부위입니다.") | 요청 검증, StatsService.categoryVolumes 존재 확인 |

서비스의 존재 확인 실패도 요청 검증 실패와 같은 형식(`errors[]`에 필드와 사유)으로 응답한다.

---

## 9. 비기능 요구사항 설계
| NFR ID | 요구사항 | 설계 대응 | 확인 방법 |
|--------|---------|----------|----------|
| NFR-PERF-001 | p95 500ms | 요청당 쿼리 수 고정: API-STATS-001 3회(부위 확인, 그 부위를 한 날 13개, 볼륨 집계), API-STATS-002 2회(부위 확인, 목록), API-STATS-003 3회(운동 확인, 고른 종목을 한 날 13개, 볼륨 집계) + 앞단 cleanUp. 3개월 하한은 가져온 13개에서 서비스가 거른다(쿼리 추가 없음, DEC-STATS-014). 집계는 날짜 12개 이하로 좁힌 뒤 조인 | 부하 테스트 p95 (공통 D-TODO-ARCH-004) |
| NFR-PERF-002 | 기록이 많아도, 예전으로 넘겨도 | 고른 부위를 한 날 조회는 `idx_workout_session_user_performed`를 `before`부터 내림차순으로 읽으며 세션마다 그 부위의 세트가 있는지 확인하고 13개에서 멈춘다. 얼마나 예전이든 읽는 양은 그 부위를 하지 않은 세션 수만큼만 늘어난다(거의 안 하는 부위면 사용자의 완료된 세션을 대부분 읽는다). 고른 종목을 한 날 조회(API-STATS-003)도 같은 방식이다. 볼륨은 고른 12개 이하 날짜의 세션만 읽는다. 종목 목록은 사용자의 완료된 세션 전체를 읽지만 운동 하나당 "있는지"만 확인한다 | 사용자 1명에 세션 1,000건, 가장 오래된 구간까지 넘기며 응답 시간 측정 |
| NFR-SEC-001 | 인증된 사용자만 | 7.1 | 토큰 없는 요청이 API마다 401 |
| NFR-SEC-002 | 본인 기록만 | 7.2 | 다른 사용자의 기록이 결과에 섞이지 않는지 테스트 |
| NFR-INTEG-001 | 날짜별 기록과 볼륨이 같음 | 같은 세트(완료된 세션의 모든 세트), 같은 식(Σ 중량 × 반복 횟수, 소수 둘째 자리) | 같은 날에 대해 API-STATS-001의 부위 볼륨과 API-WORKOUT-008의 `categories[].volume`이 같은지 테스트 |

---

## 10. 구현 구조 및 개발 전략

### 10.1 컴포넌트 구성
역할은 공통 설계 2.2, 패키지·구현 기술은 공통 설계 10장을 따른다.

| 도메인 | 컴포넌트 | 역할 | 책임 |
|-------|---------|-----|-----|
| stats | StatsController | API 진입점 | API-STATS-001 ~ 003, 요청 형식 검증, 앞단 cleanUp 호출 |
| stats | StatsService | 서비스 | 개수·중복 검증, 부위·운동 존재 확인, 구간 만들기(13개 → 3개월 안 12개 + `hasPrevious`), 날짜 자리에 볼륨·null 채우기 |
| stats | StatsQueryRepository | 조회 저장소 | 고른 부위를 한 날·고른 종목을 한 날 조회, 날짜별 그 부위 볼륨 집계, 날짜·운동별 볼륨 집계, 부위에서 기록한 운동 목록 |
| session | ExpiredSessionCleaner | 서비스 (기존) | 앞단 (B) |

### 10.2 구현 순서
| 순서 | 작업 | 관련 요구사항 | 완료 기준 |
|-----|-----|-------------|----------|
| 1 | 부위별 볼륨 추이 (`categoryId`, `before` 없음) | REQ-STATS-001, BR-002 ~ 005, BR-007, BR-008, BR-019 ~ BR-021, ERR-009 | 그 부위를 한 날만 최근 12개·오름차순·`hasPrevious`, 3개월 하한, 다른 부위만 한 날 제외, 진행 중 세션 제외, 기록 없음 빈 결과, `categoryId` 없음·없는 부위 400 테스트 |
| 2 | `before`로 이전 구간 | REQ-STATS-002, BR-016, BR-020, BR-021, ERR-002 | 그 부위를 한 날 15개에서 4~15 → 3~14 → … → 1~12, 1~12에서 `hasPrevious` = false, 형식 오류 400 테스트 |
| 3 | 부위별 종목 목록 | REQ-STATS-003, BR-011, BR-017, ERR-004 | 기록한 종목만·목록 순서, 진행 중 세션만 있는 종목 제외, 없는 부위 400 테스트 |
| 4 | 종목별 볼륨 추이 | REQ-STATS-004, BR-009, BR-015, BR-016, BR-021, ERR-005 | 고른 종목 중 하나라도 한 날만·요청 순서·null, 12개·3개월 하한, 고르지 않은 종목만 한 날 제외, `before`로 넘기기·`hasPrevious`, 기록 없음 빈 결과, 8개 이상 200, 중복 400, 없는 운동 400 테스트 |

### 10.3 테스트 포인트
- BR-016: 3개월 안에 가슴을 한 날 15개(D1~D15). 최근 → D4~D15, `before`=D15 → D3~D14, `before`=D14 → D2~D13, `before`=D13 → D1~D12이고 `hasPrevious` = false.
- BR-021: 가장 최근 날이 9/27이면 6/27은 들어가고 6/26은 빠지며 `hasPrevious` = true. 12개가 안 되어도 3개월 전보다 이전 날은 빠진다. `before`로 넘기면 하한도 새 가장 최근 날로 옮겨 빠졌던 날이 들어온다. 5/31이 가장 최근이면 2/28부터(TODO-021).
- BR-002: 진행 중 세션만 있는 날은 들어가지 않는다. 6시간이 지난 진행 중 세션(세트 있음)은 조회 시 자동 완료되어 들어간다. 중량 0 세트만 있는 날도 들어가고 그 부위 값은 0.
- BR-020: 가슴과 하체를 번갈아 한 기록에서 `categoryId`=가슴이면 가슴을 한 날만 나온다. 가슴 운동을 세션에 추가만 하고 세트가 없는 날은 들어가지 않는다. `hasPrevious`도 가슴을 한 날로 따진다.
- BR-006: 종목별 추이에서 벤치프레스만 한 날의 다른 종목은 null(0이 아님).
- BR-015: 벤치프레스·인클라인 벤치프레스를 고르면 둘 중 하나라도 한 날만 나오고, 같은 가슴이라도 펙덱 플라이만 한 날은 들어가지 않는다. 종목 선택을 줄이면 날짜도 줄어든다. `hasPrevious`와 `before`도 고른 종목을 한 날로 따진다.
- BR-005, workout-history BR-020: 같은 날 두 세션의 가슴 볼륨은 합쳐진다.
- BR-001: 다른 사용자의 기록은 날짜·볼륨·종목 목록에 나오지 않는다.
- BR-011: 세트 없이 추가만 한 종목, 진행 중 세션에만 있는 종목은 종목 목록에 없다.
- NFR-INTEG-001: 같은 날의 API-STATS-001 부위 볼륨 = API-WORKOUT-008 `categories[].volume`.
- 경계값: `categoryId` 없음·없는 부위 400, `exerciseIds` 0개 400 / 8개 200, `before` 형식 오류 400 (두 API 모두).

---

## 부록 A. 설계 결정 기록
| ID | 결정 | 이유 | 버린 대안 |
|----|-----|-----|----------|
| DEC-STATS-001 | 운동한 날을 보여줄 수보다 하나 더(v0.7부터 12개 + 1 = 13개) 가져와 보여주지 못한 날이 있으면 `hasPrevious` = true | 쿼리 하나로 구간과 "더 이전이 있는지"를 함께 안다 | 전체 개수 세기: 기록이 많을수록 느려짐(NFR-PERF-002) |
| DEC-STATS-002 | 최근·이전 구간을 API 하나로, 이전은 `before`(그 날 제외)로 조회 | `before` = 지금 구간의 가장 최근 날이면 하나씩 밀린 구간이 나와 BR-016이 저절로 지켜진다. 서버가 구간 상태를 갖지 않는다 | 순번(offset): 그 사이 기록이 완료·삭제되면 구간이 어긋남 / 페이지 번호: 구간씩 겹치지 않게 넘기는 방식이라 요구사항과 다름 |
| DEC-STATS-003 | 오른쪽으로 넘기기는 서버 API 없이 앱이 보관한 이전 구간을 쓴다 | 요구사항에 "더 최근 구간 조회" 인터페이스가 없고, 왼쪽으로 넘겼던 구간을 다시 보는 것뿐이다 | `after` 파라미터 추가: 요구사항 근거가 없는 API 확장 |
| DEC-STATS-004 | ~~종목별 추이는 `before`가 아니라 날짜 목록(`dates`)을 받는다~~ **DEC-STATS-013으로 대체 (2026-10-10)** | 가로축이 부위를 한 날이라 API-STATS-001이 정하고 종목별 볼륨은 그 날짜로 계산했다 | — |
| DEC-STATS-005 | 없는 부위·운동은 404가 아니라 400 `VALIDATION_FAILED` (`errors[]`에 필드) | 요구사항 ERR-004·005가 "입력값 오류"다. 조회 대상이 아니라 조회 조건이 틀린 것이다 | 404 `EXERCISE_CATEGORY_NOT_FOUND`(API-EXERCISE-001과 같게): 요구사항과 다름 (부록 C-2) |
| DEC-STATS-006 | 기록이 없거나 값이 없어도 200과 빈 배열·null (204 아님) | 목록 조회라 빈 결과가 정상이다. 앱이 그래프 틀을 늘 같은 형태로 그린다. 공통 5장의 204는 단건 선택 조회용이다 | 204: 앱이 형태가 다른 두 응답을 처리해야 함 |
| DEC-STATS-007 | 통계 API도 앞단에서 방치된 세션 정리를 실행 | 6시간 지난 세션이 정리되지 않은 채로 빠지면, 같은 시점에 운동 기록 달력에는 보이는데 통계에는 안 보일 수 있다(BR-002) | 실행하지 않음: 화면 간 결과가 어긋남 |
| DEC-STATS-008 | 볼륨 배열은 `dates`와 같은 길이·순서의 배열, 값 없음은 null | 그래프 라이브러리가 바로 쓸 수 있는 모양이고 응답이 작다 | 날짜별 객체 목록: 앱이 날짜로 다시 맞춰야 함 |
| DEC-STATS-009 | `exerciseIds`에 개수 상한을 두지 않는다 | 요구사항이 개수 제한을 폐기했다(BR-018 폐기). 중복 없음 + 모두 본인 종목 검증으로 개수는 본인 종목 수를 넘을 수 없다 | 부위의 기본 종목 수(하체 8개)로 상한: 사용자가 종목을 추가하면 고를 수 없는 종목이 생김 |
| DEC-STATS-010 | API-STATS-001의 `categoryId`는 필수. 앱이 API-EXERCISE-004에서 가슴의 `id`를 찾아 보낸다 | 처음 고를 부위(가슴)는 화면 규칙이다(요구사항 BR-019, TODO-017). 앱은 두 칩 줄을 그리려고 어차피 부위 목록이 필요하다 | `categoryId` 없으면 서버가 가슴으로: 화면 규칙이 서버에 들어가고, 이름으로 부위를 찾아야 함 |
| DEC-STATS-011 | 응답은 `categories[]` 배열 대신 `volumes` 하나 | 부위를 하나만 받으므로 배열이 필요 없다. 부위 `id`·`name`은 앱이 이미 안다 | `categories`에 하나만 담기: 모양은 그대로지만 "여러 부위가 올 수 있다"는 오해를 남김 |
| DEC-STATS-012 | ~~종목별 추이의 가로축은 앱이 API-STATS-001을 종목별 추이의 부위로 따로 불러 얻는다~~ **DEC-STATS-013으로 대체 (2026-10-10)** | 가로축이 부위를 한 날일 때 BR-020과 같은 조회를 썼다 | — |
| DEC-STATS-013 | API-STATS-003이 `dates` 대신 `before`를 받아 서버가 고른 종목 중 하나라도 한 날을 고르고 `hasPrevious`를 준다 (2026-10-10, 요구사항 TODO-018) | 가로축이 선택된 종목에 따라 정해지므로 다른 API로 미리 날짜를 얻을 수 없다. API-STATS-001과 같은 방식(하나 더 가져와 `hasPrevious`, `before`)이라 넘기기 규칙(BR-016, BR-007)이 그대로 지켜지고, 앱 호출이 하나로 준다. 날짜와 볼륨을 한 요청에서 계산해 축과 값이 어긋나지 않는다 | 앱이 API-STATS-001로 부위를 한 날을 넘겨 받으며 고른 종목을 한 날을 골라냄: 호출이 여러 번이고 `hasPrevious`를 정확히 알 수 없음 / 날짜만 주는 API를 따로 둠: 호출이 둘이고 두 요청 사이 변경 시 축과 값이 어긋남 |
| DEC-STATS-014 | 3개월 하한은 쿼리 조건이 아니라, 가져온 13개에서 서비스가 가장 최근 날로 하한을 계산해 거른다 (2026-10-10, 요구사항 BR-021) | 하한은 그 구간의 가장 최근 날로 정해지는데, 그 날은 날짜를 가져와 봐야 안다. 13개는 이미 가져오므로 걸러도 쿼리가 늘지 않고, 거른 날이 있다는 것으로 `hasPrevious`도 함께 안다 | 쿼리에서 하한 조건: 가장 최근 날을 먼저 구하는 쿼리나 하위 조회가 더 필요함 / `before` 기준으로 하한: 처음 보기에는 `before`가 없고, 기준 날짜 바로 앞에 운동한 날이 없으면 구간이 비어 BR-016과 다름 |

## 부록 B. 설계 미결정 사항
없음.

## 부록 C. 요구사항 피드백
1. **BR-011 "기록한 종목":** 세션에 추가만 하고 세트가 없는 종목도 "기록한" 것인지 정해져 있지 않다. (설계: 완료된 세션에서 세트를 1개 이상 기록한 종목만. 세트가 없으면 볼륨도 없어 빈 선만 보이기 때문이다)
2. **ERR-004와 운동 선택 화면의 불일치:** 통계는 없는 부위를 "입력값 오류"(400)로, 운동 선택 화면(workout-exercise API-EXERCISE-001)은 "찾을 수 없음"(404)으로 응답한다. 앱이 정상 흐름에서 만나지 않는 경우라 영향은 작다. (설계: 각 요구사항대로. 맞추려면 한쪽 요구사항을 고친다)
3. **오른쪽으로 넘기기의 인터페이스:** 요구사항 3.1은 오른쪽으로 넘기기를 말하지만 IF에는 없다. (설계: 앱이 보관한 구간을 다시 보여준다, DEC-STATS-003. 앱을 다시 열면 최근 구간부터다)
