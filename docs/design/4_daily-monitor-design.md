# 설계서: 일 단위 모니터링 배치

## 0. 개요

| 항목 | 내용 |
|------|------|
| 배치 이름 | LogAnalysisDailyMonitorJob (가칭) |
| 실행 주기 | 1일 단위 (매일 자정 또는 익일 새벽 — 확정 필요) |
| 대상 날짜 | 전일 기준 (실행 시각 기준 전날) — **스케쥴(자동) 실행 한정.** 웹 화면 1회성 실행은 기준일을 직접 지정할 수 있다 (5절 참고) |
| 목적 | 전일 로그를 통합 Dify 워크플로우(mode=daily_report)에 전달해 일일 운영보고 생성 |
| 작성일 | 2026-06-22 |
| 최종 수정일 | 2026-09-26 (웹 화면 1회성 실행 예외 흐름 추가) |

> **로그 최적화 인사이트(구 daily-optimization)는 통합 워크플로우에 mode가 없어 이 설계에서 제외한다.**

### 전체 처리 흐름

```
readTargetDateLogContent(targetDate)
      ↓ (output/hourly/anomaly/*.dat 중 targetDate 해당분을 시각 오름차순으로 모아 log_content 구성 — 20,000자 초과 시 절단)
requestDailyReportToDify(logContent, targetDate)      [mode=daily_report]
      ↓ (report_text / daily_error_count / daily_warn_count / daily_unique_issue_count)
saveDailyAnomalyResult(result, targetDate)
      → output/daily/anomaly/yyyy-MM-dd.dat
      ↓
deleteOldHourlyFiles(baseDate)   → output/hourly/anomaly/ 7일 경과분 삭제
```

---

## 1. readTargetDateLogContent()

### 1.1 책임
`mode=daily_report` 호출에 사용할 `log_content`를 확보한다.

### 1.2 메서드 시그니처
```java
public String readTargetDateLogContent(LocalDate targetDate)
```

### 1.3 구현 방식 — hourly anomaly 결과 재활용 (후보 B 채택)

통합 워크플로우의 `daily_report` 모드는 원본 로그가 아니라 사전에 요약된 텍스트도 `log_content`로 받아 워크플로우 내부(dr-preprocess 코드 노드)에서 집계에 활용할 수 있다 (`docs/lhs_logSuite_integrated_fix.yml` 참고). `log_content` 필드는 **최대 20,000자**로 제한되는데, 하루치 원본 로그 전체는 이를 크게 초과하므로, `HourlyMonitorService`가 이미 저장해 둔 `output/hourly/anomaly/yyyy-MM-dd_HH.dat`의 `message`(이상 감지 시 알림 텍스트, 정상 시 고정 정상 응답)를 시각 오름차순으로 모아 사용한다.

- 시간당 메시지는 LLM `max_tokens=800`으로 이미 요약되어 있어, 하루 최대 24건을 모아도 대부분 20,000자 이내로 들어온다. 초과 시에는 앞부분(오래된 시간대) 기준으로 절단하고 경고 로그를 남긴다 (`DifyMode.MAX_LOG_CONTENT_LENGTH`).
- 정상 시간대도 고정 정상 응답(`✅ [정상] ...`)이 저장되어 있으므로, "특이사항 없음" 시간대 정보도 함께 전달된다 — 순수 이상 시간대만 모으는 방식 대비 정보 손실이 적다.
- 각 줄 앞에 `[HH시]` 접두어를 붙여 시간 흐름을 알 수 있게 한다.
- 해당 날짜의 hourly 결과가 하나도 없으면 빈 문자열을 반환하고, 상위(`execute()`)에서 Dify 호출을 스킵한다.

기각한 후보:

| 후보 | 설명 | 기각 사유 |
|------|------|-----------|
| (A) 원본 로그 사전 필터링 | Java 단에서 ERROR/WARN/Exception 등 핵심 라인만 미리 걸러서 20,000자 이내로 구성 | dr-preprocess의 그룹화 로직과 중복 작업이 되어 유지보수 부담 증가 |
| (C) 원본 로그 앞/뒤 N줄만 샘플링 | 단순하지만 중요한 이슈를 놓칠 위험 큼 | 구현은 쉬우나 품질 보장 어려움 |

---

## 2. requestDailyReportToDify()

### 2.1 책임
`log_content`를 통합 Dify 워크플로우(mode=daily_report)에 전달해 일일 운영보고를 요청한다.

### 2.2 메서드 시그니처
```java
public DailyAnomalyResult requestDailyReportToDify(String logContent, LocalDate targetDate)
```

### 2.3 파라미터

| 이름 | 타입 | 필수 | 설명 | 제약조건 |
|------|------|------|------|----------|
| logContent | String | Y | readTargetDateLogContent()에서 받은 로그 내용 | 최대 20,000자, 빈 문자열이면 호출 스킵 |
| targetDate | LocalDate | Y | 보고 대상 날짜 | null 불가 |

### 2.4 반환값

**DailyAnomalyResult 필드**

| 필드명 | 타입 | 설명 |
|--------|------|------|
| reportText | String | 일일 운영보고 전문 |
| errorCount | int | 집계된 ERROR 건수 (daily_error_count) |
| warnCount | int | 집계된 WARN 건수 (daily_warn_count) |
| uniqueIssueCount | int | 그룹화된 고유 이슈 수 (daily_unique_issue_count) |
| reportDate | LocalDate | 보고서 대상 날짜 |

### 2.5 예외 처리

| 상황 | Exception | 처리 방안 |
|------|-----------|-----------|
| Dify API 호출 실패 | DifyApiException | 재시도 N회 후 배치 중단 + 알림 |
| 응답 스키마 불일치 | ResponseMappingException | 배치 중단 + 알림 |

### 2.6 의존성
- 외부 시스템: 통합 Dify 워크플로우 (log-suite, mode=daily_report)

### 2.7 비고
- 구 스펙에 있던 "MCP를 통한 알림 발송"은 통합 워크플로우 DSL(`lhs_logSuite_integrated_fix.yml`)에 daily_report 경로의 MCP 노드가 확인되지 않았다 — 재확인 필요.

---

## 3. saveDailyAnomalyResult()

### 3.1 책임
일일 운영보고 결과를 `output/daily/anomaly/`에 .dat 파일로 저장한다.

### 3.2 메서드 시그니처
```java
public void saveDailyAnomalyResult(DailyAnomalyResult result, LocalDate targetDate)
```

### 3.3 저장 파일 규칙

| 항목 | 내용 |
|------|------|
| 저장 경로 | `output/daily/anomaly/` |
| 파일명 | `yyyy-MM-dd.dat` (예: `2026-06-22.dat`) |
| 중복 처리 | 동일 날짜 파일 존재 시 덮어쓰기 |

---

## 4. deleteOldHourlyFiles()

### 4.1 책임
`output/hourly/anomaly/`에서 7일이 지난 .dat 파일을 삭제한다.

### 4.2 메서드 시그니처
```java
public void deleteOldHourlyFiles(LocalDate baseDate)
```

### 4.3 삭제 기준

| 항목 | 내용 |
|------|------|
| 삭제 기준 | 파일명 날짜 기준 `baseDate - 7일` 이전 |
| 삭제 범위 | `output/hourly/anomaly/` (구 스펙의 `output/hourly/optimization/`은 더 이상 생성되지 않으므로 대상에서 제외) |

### 4.4 예외 처리

| 상황 | Exception | 처리 방안 |
|------|-----------|-----------|
| 폴더 없음 | (정상 케이스) | 경고 로그 후 스킵 |
| 개별 파일 삭제 실패 | IOException | 해당 파일 ERROR 로그 후 나머지 파일 계속 처리 |

---

## 5. 웹 화면 1회성 실행 (예외 흐름)

- `execute()` — 스케쥴(매일 자정) 반복 실행 전용. 인자 없이 호출되며 항상 "실행 시점 기준 전일"을 targetDate로 사용한다. 위 "대상 날짜: 전일 기준"은 이 경로에서만 성립한다.
- `execute(LocalDate targetDate)` — 웹 화면 카드의 "실행" 버튼(1회성)에서 기준시간을 선택한 채로 눌렀을 때 사용되는 별도 진입점. targetDate는 전일이 아니라 **화면에서 선택한 기준시간이 속한 날짜(기준일)**다.
- 이 1회성 실행은 `DailyBatchOrchestrationService`(daily Step이 위임하는 별도 오케스트레이션 서비스, `BatchConfig`는 Job/Step 설정만 담당)가 먼저 기준일 00시~기준시간까지 `HourlyMonitorService.execute(String)`을 정각 단위로 순서대로 호출해(백필) `output/hourly/anomaly/`를 채운 뒤, 그 기준일로 `execute(targetDate)`를 호출하는 방식으로 동작한다 — 자세한 백필 로직은 `3_hourly-monitor-design.md` 6절 참고.
- 한 시간대의 백필 실행이 실패해도 나머지 시간대와 뒤이은 일간 배치 실행은 계속 진행된다(부분 실패 허용).
- 이 예외 흐름은 스케쥴 등록 상태(반복 실행 on/off)와 전혀 무관하다 — JobLauncher나 스케쥴 등록을 거치지 않고 서비스 메서드를 직접 호출하는 것뿐이라, 별도 JobExecution 이력을 남기거나 반복 등록되지 않는다.

---

## 6. 공통 고려사항

- **실행 시점**: 전일 hourly 배치가 모두 완료된 이후 실행 — 새벽 1시 이후 권장.
- **재시도 정책**: `log-analyzer.dify.max-retries` / `timeout-seconds` 설정을 따른다.
- **파일 보관 정책**: hourly 결과(anomaly) 7일, daily 결과(anomaly) 90일. daily 파일 정리 로직은 별도 구현 필요 (현재 미정).
- **로깅**: 각 단계 시작/종료 시점 및 삭제된 파일 목록을 INFO로 남길 것.

---

## 7. 변경 이력

| 버전 | 날짜 | 내용 | 작성자 |
|------|------|------|--------|
| v1.0 | 2026-06-23 | 초안 완성 | |
| v2.0 | 2026-08-25 | daily-anomaly/daily-optimization 분리 구조 → log-suite 통합 워크플로우(mode=daily_report)로 변경. 20,000자 제한 이슈 발견 — 구현 보류 상태로 전환. optimization 관련 절 삭제(미지원) | |
| v2.1 | 2026-08-25 | hourly anomaly 결과(.dat) 재활용 방식으로 20,000자 제한 이슈 해결, `DailyMonitorService` 구현 완료 및 `BatchConfig.dailyMonitorJob`에 연결 | |
| v2.2 | 2026-09-26 | execute(LocalDate targetDate) 추가 및 웹 화면 1회성 실행(기준일 지정 + 1시간 배치 백필) 예외 흐름 추가 — 스케쥴 실행(execute())은 그대로 전일 기준 | |
| v2.3 | 2026-09-26 | 백필 오케스트레이션 로직을 `BatchConfig`(Job/Step 설정 전용)에서 `DailyBatchOrchestrationService`로 분리(SRP). `BatchConfig`의 baseTime 참조 방식도 `chunkContext.getJobParameters().get(...)` 대신 `@StepScope` + `@Value("#{jobParameters['baseTime']}")`로 변경 | |
