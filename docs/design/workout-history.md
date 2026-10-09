# 운동 기록 달력 설계 문서

- 문서 버전: v0.8
- 작성일: 2026-10-05
- 상태: 초안
- 요구사항: `docs/requirements/workout-history.md` (v0.7), 공통 `docs/requirements/workout-common.md` (v0.7)
- 공통 설계: `docs/design/architecture.md`, `docs/design/workout-common.md`
- Figma: 4행 `workout-history`(5:31), `media-viewer-popup`(18:168)
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
운동 기록 달력, 날짜별 기록, 날짜 단위 삭제를 API 3개로 구현하는 방법을 확정한다. 지난 기록은 사용자에게 날짜 단위로 보이므로 "운동한 날"(`workout-days`)을 조회·삭제의 단위로 둔다(DEC-WORKOUT-019). 사진·동영상 목록과 뷰어는 workout-media 설계에 있다.

### 1.2 설계 범위
- **포함:** REQ-WORKOUT-005, 007, 008, 화면 WO-003
- **보류:** 없음
- **제외:** 요구사항 workout-common 8.1의 범위 밖 항목과 폐기된 요구사항.

### 1.3 대상 시스템
- Backend API: 도메인 `session`(WorkoutDayController)
- Mobile App: 화면 WO-003

### 1.4 기술 스택
workout-common 1.4를 따른다. 새로 필요한 기술 능력은 없다.

### 1.5 설계 원칙
workout-common 1.5를 따른다.

### 1.6 요구사항 ↔ 설계 추적표
이 문서의 요구사항 ID만 둔다. 공통 ID(BR-001 등, DATA, NFR)는 workout-common 1.6에 있다.

| 요구사항 ID | 요구사항 | 설계 반영 위치 | 구현 | 테스트 |
|------------|---------|--------------|-----|-------|
| REQ-WORKOUT-005 | 운동 기록 삭제(날짜 단위) | 3.2, API-WORKOUT-009 | | |
| REQ-WORKOUT-007 | 월별 운동 달력 | 3.2, API-WORKOUT-007 | | |
| REQ-WORKOUT-008 | 날짜별 운동 기록 | 3.2, API-WORKOUT-008, 5.2 WorkoutDayResponse | | |
| BR-018 | 달력에 운동한 부위, 완료 세션만 | 3.5, API-WORKOUT-007 | | |
| BR-020 | 하루 여러 세션은 합치고 날짜 단위로 삭제 | 3.5, API-WORKOUT-008, API-WORKOUT-009, DEC-WORKOUT-020 | | |
| ERR-004 | 그날 기록 없음·이미 삭제 | 8.2 WORKOUT_DAY_NOT_FOUND | | |
| ERR-014 | 달력 연·월 형식 오류 | 8.2 VALIDATION_FAILED | | |
| ERR-015 | 날짜 형식 오류 | 8.2 VALIDATION_FAILED | | |
| IF-WORKOUT-006 | 날짜의 기록 삭제 | API-WORKOUT-009 | | |
| IF-WORKOUT-007 | 월별 운동 날짜·부위 | API-WORKOUT-007 | | |
| IF-WORKOUT-008 | 날짜의 기록 조회 | API-WORKOUT-008 | | |
| WO-003 | 운동 기록 (달력) | 4.2 | | |

---

## 2. 시스템 아키텍처
workout-common 2장을 따른다.

---

## 3. 기능 설계

### 3.1 기능 목록
| 요구사항 ID | 기능 | API | 주요 컴포넌트 |
|------------|-----|-----|-------------|
| REQ-WORKOUT-007 | 월별 운동 달력 | API-WORKOUT-007 | WorkoutDayQueryRepository.findMonth |
| REQ-WORKOUT-008 | 날짜별 운동 기록 | API-WORKOUT-008 | WorkoutDayService.get |
| REQ-WORKOUT-005 | 날짜 단위 기록 삭제 | API-WORKOUT-009 | WorkoutDayService.delete |

### 3.2 기능별 처리 흐름
모든 흐름의 공통 앞단 (A) 인증, (B) 방치된 세션 정리, (E) 편집 가능 세션 확보는 workout-common 3.2를 따른다.

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

### 3.3 주요 시나리오
해당 없음 — 달력 → 날짜 선택 → 삭제 순서는 4.2 WO-003에 있다.

### 3.4 상태 변화
workout-common 3.4를 따른다.

### 3.5 비즈니스 규칙 구현
이 문서의 규칙만 둔다. 공통 규칙은 workout-common 3.5에 있다.

| BR ID | 규칙 | 강제 위치 | 방법 | 위반 시 |
|-------|-----|----------|-----|--------|
| BR-018 | 달력은 완료 세션의 운동한 부위 | 조회 저장소 | API-WORKOUT-007이 완료된 세션, 세트가 있는 운동의 부위만 모은다 | — |
| BR-020 | 하루 여러 세션은 합치고 날짜 단위로 삭제 | 서비스, 조회 저장소 | 날짜별 기록은 그날 완료된 세션을 합쳐 계산(DEC-WORKOUT-020), 삭제는 그날 완료된 세션 전부를 조건부 일괄 삭제 | 404 WORKOUT_DAY_NOT_FOUND |

### 3.6 기능 간 의존관계
- 날짜별 기록의 사진·동영상 주소와 내려받기는 workout-media API-MEDIA-002·003에 의존한다.
- 공통 의존관계는 workout-common 3.6에 있다.

---

## 4. 화면 / API 연계 설계

### 4.1 화면-API 매핑
| 화면 ID | 화면 | 사용자 행동 | API |
|--------|-----|-----------|-----|
| WO-003 | 운동 기록 (달력) | 진입, 이전 달·다음 달 | API-WORKOUT-007 `GET /api/v1/workout-days?month=` |
| WO-003 | 운동 기록 (달력) | 날짜 선택 | API-WORKOUT-008 `GET /api/v1/workout-days/{date}` |
| WO-003 | 운동 기록 (달력) | 그날 기록 삭제 | API-WORKOUT-009 `DELETE /api/v1/workout-days/{date}` |
| WO-003 | 운동 기록 (달력) | 사진·동영상 미리보기, 누르면 뷰어 | workout-media API-MEDIA-002·003 (workout-media 4.2) |

### 4.2 화면별 연계 상세
공통 응답 처리(401·403·404·400·409·500·503)는 공통 설계 4장을 따른다. 아래는 화면별로 추가되는 처리다.

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
URL·필드·날짜·페이지 규칙은 공통 설계 5장을 따른다. 운동 기록 API 전체 목록은 workout-common 5.1에 있다.

### 5.1 API 목록
| API ID | Method | URL | 인증 | 설명 | 관련 요구사항 |
|--------|--------|-----|-----|-----|-------------|
| ~~API-WORKOUT-004~~ | ~~GET~~ | ~~/api/v1/workout-sessions~~ | — | **폐기 (v0.6)** — REQ-WORKOUT-003 폐기. 달력·날짜별 조회로 바뀐다(보류) | — |
| ~~API-WORKOUT-005~~ | ~~GET~~ | ~~/api/v1/workout-sessions/{sessionId}~~ | — | **폐기 (v0.6)** — REQ-WORKOUT-004 폐기. 진행 중 세션은 API-WORKOUT-002로 본다 | — |
| API-WORKOUT-007 | GET | /api/v1/workout-days?month=YYYY-MM | 필요 | 월별 운동한 날과 부위 | REQ-WORKOUT-007, IF-WORKOUT-007 |
| API-WORKOUT-008 | GET | /api/v1/workout-days/{date} | 필요 | 날짜별 운동 기록 | REQ-WORKOUT-008, IF-WORKOUT-008 |
| API-WORKOUT-009 | DELETE | /api/v1/workout-days/{date} | 필요 | 그날 완료된 기록 삭제 | REQ-WORKOUT-005, IF-WORKOUT-006 |

### 5.2 응답 모델
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

### 5.3 API 상세

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
workout-common 6장을 따른다. 이 문서의 기능은 `workout_session`(완료된 세션), `workout_session_exercise`, `workout_set`, `exercise`, `exercise_category`와 workout-media의 `workout_media`를 읽고, 날짜 단위로 `workout_session`을 지운다.

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
| ERR-004 | WORKOUT_DAY_NOT_FOUND (v0.7 신규) | 404 | 그날의 운동 기록을 찾을 수 없습니다. | WorkoutDayService.delete |
| ERR-014 | VALIDATION_FAILED (공통) | 400 | 입력값이 올바르지 않습니다. | API-WORKOUT-007 `month` 해석 |
| ERR-015 | VALIDATION_FAILED (공통) | 400 | 입력값이 올바르지 않습니다. | API-WORKOUT-008·009 `date` 해석 |

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
| 4 | 목록·상세 API(API-WORKOUT-004·005)와 메모 제거 | 폐기 | 해당 경로 404, `memo` 무시 테스트 |
| 9 | 월별 달력 | REQ-WORKOUT-007, BR-018, ERR-014 | 완료 세션 부위만, 진행 중 제외, `month` 생략 시 가장 늦은 달, 형식 오류 400 테스트 |
| 10 | 날짜별 기록 | REQ-WORKOUT-008, BR-020, ERR-015 | 하루 두 세션 합치기(같은 운동 하나로, 운동 시간 합), 사진·동영상 순서, 없는 날 204 테스트 |
| 11 | 날짜 단위 삭제 | REQ-WORKOUT-005, BR-020, ERR-004 | 그날 완료 세션 모두 삭제·진행 중은 남음, 파일 삭제, 다시 삭제 404 테스트 |

모두 구현되었다(2026-10-09).

### 10.3 테스트 포인트
- BR-020: 같은 날 두 세션(둘 다 벤치프레스)을 완료하면 날짜별 기록의 벤치프레스는 하나이고 세트 번호가 이어지며, 운동 시간은 두 세션의 합. 그날 삭제하면 두 세션 모두 사라지고 진행 중 세션은 남는다.
- BR-018·TODO-024: 진행 중 세션의 날짜·부위는 달력에 나오지 않는다.

---

## 부록 A. 설계 결정 기록
| ID | 결정 | 이유 | 버린 대안 |
|----|-----|-----|----------|
| DEC-WORKOUT-019 | 지난 기록의 조회·삭제 단위를 "운동한 날"(`/workout-days/{date}`)로 둔다 | 화면(WO-003)과 요구사항(BR-020)이 날짜 단위다. 세션 ID를 앱에 노출해 여러 번 부르게 하지 않는다 | 세션 단위 API + 앱이 합치기: 합치는 규칙이 앱에 흩어지고 요청 수가 늘어남 |
| DEC-WORKOUT-020 | 하루 여러 세션을 합칠 때 같은 운동은 한 항목으로 합치고 세트를 추가 시각 순으로 이어 번호를 매긴다 | 화면에서 같은 운동이 두 번 보이지 않는다. 사용자가 보는 것은 "그날 무엇을 했는가"이다 | 세션별로 따로 나열: 요구사항 TODO-021(합쳐서 하나로)과 다름 |

## 부록 B. 설계 미결정 사항
없음. 결정된 항목은 workout-common 부록 B에 있다.

## 부록 C. 요구사항 피드백
번호는 분리 전 `workout-record.md` 설계 부록 C의 번호다.
