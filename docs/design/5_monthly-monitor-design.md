# 설계서: 월 단위 모니터링 배치

> **✅ 1차 구현 완료 (2026-09-27)**
> `MonthlyMonitorService`/`MonthlyBatchOrchestrationService` 구현 및 웹 화면 연동 완료.
> 단, 4절의 10MB 초과 시 청킹 문제는 **아직 코드에 반영되지 않았다** — daily_results_json 크기
> 검증/절단 로직 없이 그대로 전송한다. 실제 운영 데이터로 10MB 초과 여부를 확인 후 후속 조치 필요.

## 0. 개요

| 항목 | 내용 |
|------|------|
| 배치 이름 | LogAnalysisMonthlyMonitorJob (가칭) |
| 실행 주기 | 1개월 단위 (매월 1일 새벽 — 확정 필요) |
| 대상 기간 | 전월 기준 (실행 시각 기준 전달) |
| 목적 | 전월 daily 보고서를 통합하여 로그 최적화 인사이트를 생성하고 메일 발송 및 결과 저장 |
| 작성일 | 2026-06-22 |

### 전체 처리 흐름

```
readDailyResults(targetYearMonth)
      ↓ (전월 daily .dat 파일 내용 목록)
requestMonthlyReportToDify(contents, targetYearMonth)
      ↓ (월간 최적화 인사이트 + 메일 발송은 Dify MCP에서 처리)
saveMonthlyResult(result, targetYearMonth)
```

---

## 1. readDailyResults()

### 1.1 책임
`output/daily/optimization/`에서 대상 월의 daily .dat 파일들을 전부 읽어 내용 목록으로 반환한다.

### 1.2 메서드 시그니처
```java
public List<String> readDailyResults(YearMonth targetYearMonth)
```

### 1.3 파라미터

| 이름 | 타입 | 필수 | 설명 | 제약조건 |
|------|------|------|------|----------|
| targetYearMonth | YearMonth | Y | 읽을 대상 연월 (전월) | null 불가 |

### 1.4 반환값

| 타입 | 설명 |
|------|------|
| List\<String\> | 해당 월의 daily .dat 파일 내용 목록 (날짜 오름차순) |

### 1.5 예외 처리

| 상황 | Exception | 처리 방안 |
|------|-----------|-----------|
| output/daily/optimization/ 폴더 없음 | FileNotFoundException | 배치 중단 + 알림 |
| 파일 읽기 실패 | IOException | 배치 중단 + 알림 |
| 해당 월 .dat 파일 0건 | (정상 케이스) | 빈 리스트 반환, 이후 Dify 호출 스킵 |

### 1.6 내부 처리 로직 (의사코드)
```
1. output/daily/optimization/에서 targetYearMonth에 해당하는 .dat 파일 목록 조회
   (파일명 패턴: yyyy-MM-dd.dat)
2. 파일명 기준 오름차순 정렬 (1일 → 말일)
3. 각 파일을 순서대로 읽어 내용을 List에 추가
4. List 반환
```

### 1.7 의존성
- 외부 시스템 호출 없음 (순수 파일 IO)

### 1.8 비고
- 최대 31개 파일. 일부 날짜 파일이 없는 경우 있는 파일만 읽고 계속 진행.

---

## 2. requestMonthlyReportToDify()

### 2.1 책임
전월 daily 보고서 목록을 Dify에 전달하여 월간 로그 최적화 인사이트 생성을 요청한다. 메일 발송은 Dify 워크플로우 내 MCP를 통해 처리된다.

### 2.2 메서드 시그니처
```java
public MonthlyReportResult requestMonthlyReportToDify(List<String> dailyContents, YearMonth targetYearMonth)
```

### 2.3 파라미터

| 이름 | 타입 | 필수 | 설명 | 제약조건 |
|------|------|------|------|----------|
| dailyContents | List\<String\> | Y | readDailyResults()에서 받은 daily 결과 목록 | 빈 리스트이면 호출 스킵 |
| targetYearMonth | YearMonth | Y | 보고서 대상 연월 (페이로드에 포함) | null 불가 |

### 2.4 반환값

| 타입 | 설명 |
|------|------|
| MonthlyReportResult (커스텀 객체) | 월간 최적화 인사이트 내용을 담은 객체 |

**MonthlyReportResult 필드**

| 필드명 | 타입 | 설명 |
|--------|------|------|
| content | String | 월간 최적화 인사이트 보고서 전문 |
| targetYearMonth | YearMonth | 보고서 대상 연월 |

### 2.5 예외 처리

| 상황 | Exception | 처리 방안 |
|------|-----------|-----------|
| Dify API 호출 실패 | DifyApiException | 재시도 N회 후 배치 중단 + 알림 |
| 응답 스키마 불일치 | ResponseMappingException | 배치 중단 + 알림 |

### 2.6 내부 처리 로직 (의사코드)
```
1. dailyContents가 빈 리스트이면 스킵 (빈 MonthlyReportResult 반환)
2. dailyContents와 targetYearMonth를 Dify 월간 보고서 Workflow API 요청 페이로드로 구성
3. Dify에 POST 요청 전송
// 메일 발송은 Dify 워크플로우 내 MCP 서버를 통해 처리 (Java 코드에서 별도 처리 불필요)
4. 응답을 MonthlyReportResult 객체로 매핑하여 반환
```

### 2.7 의존성
- 외부 시스템: Dify Workflow API (월간 최적화 인사이트용 — daily 보고서 워크플로우와 별도)

### 2.8 비고
- 월간 보고서의 핵심은 이상 패턴 요약이 아닌 **로그 최적화 인사이트** — Dify 워크플로우 설계 시 목적을 명확히 구분할 것.
- 메일 발송 성공 여부는 Dify 응답에 포함되도록 워크플로우 설계 권장.

---

## 3. saveMonthlyResult()

### 3.1 책임
월간 최적화 인사이트 결과를 `output/monthly/`에 .dat 파일로 저장한다.

### 3.2 메서드 시그니처
```java
public void saveMonthlyResult(MonthlyReportResult result, YearMonth targetYearMonth)
```

### 3.3 파라미터

| 이름 | 타입 | 필수 | 설명 | 제약조건 |
|------|------|------|------|----------|
| result | MonthlyReportResult | Y | requestMonthlyReportToDify()의 결과 객체 | null 불가 |
| targetYearMonth | YearMonth | Y | 보고서 대상 연월 (파일명 생성에 사용) | null 불가 |

### 3.4 반환값

| 타입 | 설명 |
|------|------|
| void | 없음 |

### 3.5 저장 파일 규칙

| 항목 | 내용 |
|------|------|
| 저장 경로 | `output/monthly/` (프로젝트 루트 기준) |
| 파일명 | `yyyy-MM.dat` (예: `2026-06.dat`) |
| 중복 처리 | 동일 연월 파일 존재 시 덮어쓰기 |

### 3.6 예외 처리

| 상황 | Exception | 처리 방안 |
|------|-----------|-----------|
| output/monthly/ 폴더 없음 | IOException | 폴더 자동 생성 후 재시도 |
| 파일 쓰기 실패 | IOException | 배치 실패 처리 + 알림 |

### 3.7 내부 처리 로직 (의사코드)
```
1. output/monthly/ 폴더 존재 여부 확인, 없으면 생성
2. targetYearMonth로 파일명 생성 (yyyy-MM.dat)
3. MonthlyReportResult.content를 output/monthly/{파일명}에 write
4. 저장 완료
```

### 3.8 의존성
- 외부 시스템 호출 없음 (순수 파일 IO)

---

## 4. 미해결 기술적 고려사항 (청킹)

전월 daily .dat 파일의 총 용량이 10MB를 초과할 경우 Dify 전송 전에 청킹 처리가 필요하다.
아래 항목들은 구현 공수가 커서 착수 전에 먼저 방향을 정해야 한다.

| 항목 | 내용 | 난이도 |
|------|------|--------|
| 청킹 로직 | `List<String>`을 9MB 단위로 분할 (일자 경계 보존 필요) | 중간 |
| N번 Dify 호출 | 청크별 개별 호출 및 재시도 처리 | 중간 |
| 부분 결과 병합 | N개의 Dify 응답을 하나의 월간 보고서로 합산 — Java 단 처리 또는 Dify 추가 워크플로우 필요 | 높음 |
| 일자 경계 처리 | 청킹 시 특정 일자 데이터가 두 청크에 걸치지 않도록 보장 | 중간 |

**우선 판단할 것:**
- 실제로 monthly daily 파일 합산이 10MB를 초과하는 경우가 발생하는가 (운영 데이터 확인 필요)
- 초과 시 단순 배치 중단 + 알림으로 처리할지, 청킹 구현을 할지

현재 `MonthlyMonitorService.requestMonthlyReportToDify()`는 위 크기 검증/절단 로직이 없다 —
`daily_results_json`을 그대로 Dify에 전송한다. 실제 데이터로 초과 여부를 확인하기 전까지는
알려진 미해결 리스크로 남겨둔다.

---

## 5. 웹 화면 1회성 실행 (예외 흐름)

- `execute()` — 스케쥴(자동, 매월 1일 자정) 반복 실행 전용. 인자 없이 호출되며 항상 전월을 기준으로 동작한다.
- `execute(YearMonth targetYearMonth)` — 웹 화면 카드의 "실행" 버튼(1회성)에서 기준시간을 선택한 채로
  눌렀을 때 사용되는 별도 진입점. targetYearMonth는 전월이 아니라 **화면에서 선택한 기준시간이 속한 연월**이다.
- 이 1회성 실행은 `MonthlyBatchOrchestrationService`가 먼저 기준월 1일부터 기준일까지 날짜별로
  `DailyBatchOrchestrationService.runDailyMonitor(LocalDate, int)`를 반복 호출해(기준일 이전 날짜는
  1~23시 전체, 기준일 당일은 1시~기준시각까지) 그 달의 일자별 daily 결과를 만든 뒤, 그 연월로
  `execute(targetYearMonth)`를 호출하는 방식으로 동작한다 — 자세한 일자별 백필 로직은
  `4_daily-monitor-design.md` 5절 참고.
- 날짜 수(최대 31일) × 시간 수(최대 23회)만큼 1시간 배치 Dify 호출이 반복되므로 전체 실행 시간이
  상당히 길어질 수 있다 — 관리자가 화면에서 수동으로 실행하는 백필/테스트 용도임을 전제로 한다.
- 한 날짜의 백필 실패가 나머지 날짜 처리나 월간 배치 실행에 영향을 주지 않도록 개별적으로 격리한다.
- 이 예외 흐름은 스케쥴 등록 상태(반복 실행 on/off)와 전혀 무관하다.

---

## 6. 공통 고려사항

- **실행 시점**: 전월 daily 배치가 모두 완료된 이후 실행 — 매월 1일 새벽 2시 이후 권장.
- **재시도 정책**: Dify 호출(2번)에 대한 재시도 횟수, 백오프 전략 정의 필요.
- **메일 발송 실패**: Dify MCP를 통한 메일 발송 실패 시 별도 알림 수단 검토 필요.
- **monthly 파일 보관 기간**: `output/monthly/` 파일 보관 정책 미정 — 용량 대비 장기 보관 가능하나 정책 결정 필요.
- **로깅**: 각 단계 시작/종료 시점 및 처리된 daily 파일 수를 INFO로 남길 것.

---

## 7. 변경 이력

| 버전 | 날짜 | 내용 | 작성자 |
|------|------|------|--------|
| v1.0 | 2026-06-23 | 초안 완성 | |
| v1.1 | 2026-09-26 | 개발 착수 결정 — "개발 보류" 표현 제거, 4절을 "구현 전 기술적 고려사항"으로 재정리 | |
| v1.2 | 2026-09-27 | `MonthlyMonitorService.execute(YearMonth)`, `MonthlyBatchOrchestrationService`(월간 백필 오케스트레이션), 웹 화면 실행/결과 보기 버튼, 스케쥴링 일괄 시작/중단 포함 — 1차 구현 완료. 4절 청킹 이슈는 미해결로 남음 | |
