# 설계서: 시간 단위 로그 분석 배치

## 0. 개요

| 항목 | 내용 |
|------|------|
| 배치 이름 | LogAnalysisHourlyMonitorJob (가칭) |
| 실행 주기 | 1시간 (매 정시 등 — 확정 필요) |
| 목적 | 서버 로그를 주기적으로 읽어 통합 Dify 워크플로우(mode=anomaly)에 이상 패턴 분석을 요청하고 결과를 저장 |
| 작성일 | 2026-06-22 |
| 최종 수정일 | 2026-08-25 (통합 워크플로우 전환 반영) |

> **로그 최적화 인사이트(F-04)는 통합 워크플로우에 아직 mode가 추가되지 않아 현재 스킵한다.**
> 예전에는 anomaly-analysis와 optimization-analysis를 병렬로 호출했으나, 두 워크플로우가 log-suite 통합 앱으로 합쳐지면서
> optimization 쪽 mode가 아직 없는 상태다. Dify 쪽에 mode가 추가되면 이 설계서와 `HourlyMonitorService`를 함께 갱신해야 한다.

### 전체 처리 흐름

```
loadSetupConfig()
      ↓ (로그 파일 경로, 인코딩, 날짜 형식, 타임존)
readLastHourLog(config)
      ↓ (1시간치 로그 문자열, 20,000자 초과 시 절단)
loadPrevAnomalyCounts()
      ↓ (직전 실행에서 저장해 둔 카테고리별 누적 건수 — 최초 실행은 전부 0)
requestAnomalyAnalysisToDify(logContent, prevCounts)   [mode=anomaly]
      ↓ (anomalyDetected / severity / message / nextCounts)
      ├─ saveAnomalyResult(result, batchTime)      → output/hourly/anomaly/yyyy-MM-dd_HH.dat
      └─ savePrevAnomalyCounts(result.nextCounts)  → output/hourly/anomaly-state.properties (다음 실행용)
```

---

## 1. loadSetupConfig()

### 1.1 책임
`config/setup.properties`에서 설정값을 불러온다.

### 1.2 메서드 시그니처
```java
public SetupConfig loadSetupConfig()
```

### 1.3 파라미터
없음

### 1.4 반환값

| 타입 | 설명 |
|------|------|
| SetupConfig | 로그 파일 경로, 인코딩, 날짜 형식, 타임존을 담은 설정 객체 |

### 1.5 예외 처리

| 상황 | Exception | 처리 방안 |
|------|-----------|-----------|
| config/setup.properties 파일 없음 (설치 미완료) | IllegalStateException | 배치 중단 + 알림 |
| 파일 읽기 실패 | RuntimeException | 배치 중단 + 알림 |

### 1.6 비고
- **공용 메서드** — 1분/1시간 배치에서 공유하는 로직이지만, 공통 부모 없이 서비스별로 복제하여 유지한다 (배치 실행 주기·책임이 다른 서비스 간 결합도를 피하기 위함).

---

## 2. readLastHourLog()

### 2.1 책임
설정값을 기준으로, 현재 시각으로부터 최근 1시간 동안 기록된 로그만 필터링하여 읽는다.

### 2.2 메서드 시그니처
```java
public String readLastHourLog(SetupConfig config)
```

### 2.3 파라미터

| 이름 | 타입 | 필수 | 설명 | 제약조건 |
|------|------|------|------|----------|
| config | SetupConfig | Y | loadSetupConfig()에서 불러온 설정 객체 | null 불가 |

### 2.4 반환값

| 타입 | 설명 |
|------|------|
| String | 최근 1시간 범위에 해당하는 로그 라인들을 합친 문자열 |

### 2.5 예외 처리

| 상황 | Exception | 처리 방안 |
|------|-----------|-----------|
| 로그 파일이 없음 | (정상 케이스) | 경고 로그 후 빈 문자열 반환 |
| dateFormat으로 라인 파싱 실패 | (정상 케이스) | 해당 라인 skip 후 계속 진행 |
| 1시간 범위 내 로그가 0건 | (정상 케이스) | 빈 문자열 반환, 이후 Dify 호출 스킵 |

### 2.6 비고
- 대상 로그 파일이 클 경우 전체 파일을 스캔해야 하므로 성능 이슈 가능 — 역방향 읽기(tail 방식) 최적화 고려.
- **통합 워크플로우 제약**: `log_content`는 최대 20,000자. 초과 시 앞부분(오래된 로그) 기준으로 절단하고 경고 로그를 남긴다.

---

## 3. requestAnomalyAnalysisToDify()

### 3.1 책임
1시간치 로그와 직전 주기 누적 건수를 통합 Dify 워크플로우(mode=anomaly)에 전달하여 이상 여부를 판정받는다.

### 3.2 메서드 시그니처
```java
public AnomalyAnalysisResult requestAnomalyAnalysisToDify(String logContent, AnomalyCounts prevCounts)
```

### 3.3 파라미터

| 이름 | 타입 | 필수 | 설명 | 제약조건 |
|------|------|------|------|----------|
| logContent | String | Y | readLastHourLog()에서 받은 로그 내용 (최대 20,000자) | 빈 문자열이면 호출 스킵 |
| prevCounts | AnomalyCounts | Y | 직전 실행의 카테고리별 누적 건수 | 최초 실행이면 전부 0 |

### 3.4 반환값

**AnomalyAnalysisResult 필드**

| 필드명 | 타입 | 설명 |
|--------|------|------|
| anomalyDetected | boolean | 이상 감지 여부 |
| severity | String | 치명적 / 높음 / 보통 / 낮음 |
| message | String | 이상 감지 시 알림 텍스트, 정상 시 고정 정상 응답 |
| nextCounts | AnomalyCounts | 다음 실행에 prevCounts로 이어서 전달할 이번 주기 누적 건수 (error/warn/timeout/http5xx/dbConn/loginFail/batchFail/externalApiFail) |

### 3.5 예외 처리

| 상황 | Exception | 처리 방안 |
|------|-----------|-----------|
| Dify API 호출 실패 (4xx/5xx, 재시도 소진) | DifyApiException / DifyClientErrorException | 재시도 N회 후 배치 중단 |
| 응답에 anomaly_message 없음 | ResponseMappingException | 배치 중단 |

### 3.6 내부 처리 로직 (의사코드)
```
1. logContent가 빈 문자열이면 스킵 (anomalyDetected=false, nextCounts=prevCounts 그대로 반환)
2. mode=anomaly, log_content, prev_error_count 등 8개 prev_* 값으로 요청 페이로드 구성
3. Dify에 POST 요청 전송
4. outputs.anomaly_detected / anomaly_severity / anomaly_message / next_prev_* 를 AnomalyAnalysisResult로 매핑
5. anomaly_message가 비어있으면 ResponseMappingException
6. 매핑된 객체 반환
```

### 3.7 의존성
- 외부 시스템: 통합 Dify 워크플로우 (log-suite, mode=anomaly)

### 3.8 비고
- **1MB 크기 제한 폐기**: anomaly_message는 LLM `max_tokens=800`으로 제한되어 있어 더 이상 별도 크기 검증이 불필요하다 (구 스펙의 AnalysisResultSizeExceededException은 제거됨).
- **상태 유지 필요**: prevCounts를 관리하지 않으면(항상 0 전달) 매 시간 절대 임계치(5건)로만 판정되어, 서서히 증가하는 이상징후를 놓칠 수 있다.

---

## 4. loadPrevAnomalyCounts() / savePrevAnomalyCounts()

### 4.1 책임
anomaly 모드 호출에 필요한 직전/다음 카테고리별 누적 건수를 `output/hourly/anomaly-state.properties`에 저장하고 불러온다.

### 4.2 메서드 시그니처
```java
public AnomalyCounts loadPrevAnomalyCounts()
public void savePrevAnomalyCounts(AnomalyCounts counts)
```

### 4.3 내부 처리 로직 (의사코드)
```
[load]
1. output/hourly/anomaly-state.properties 파일이 없으면 전부 0인 AnomalyCounts 반환 (최초 실행)
2. 있으면 Properties로 읽어 8개 필드에 매핑

[save]
1. 폴더 없으면 생성
2. AnomalyAnalysisResult.nextCounts의 8개 필드를 Properties로 저장 (매번 덮어쓰기)
```

### 4.4 비고
- 이 상태 파일이 손상되거나 삭제되면 다음 실행은 "최초 실행"으로 간주되어 절대 임계치 기준으로만 판정한다 (안전한 폴백).

---

## 5. saveAnomalyResult()

### 5.1 책임
이상 패턴 분석 결과를 `output/hourly/anomaly/`에 .dat 파일로 저장한다.

### 5.2 메서드 시그니처
```java
public void saveAnomalyResult(AnomalyAnalysisResult result, LocalDateTime batchTime)
```

### 5.3 저장 파일 규칙

| 항목 | 내용 |
|------|------|
| 저장 경로 | `output/hourly/anomaly/` |
| 파일명 | `yyyy-MM-dd_HH.dat` (예: `2026-06-22_14.dat`) |
| 저장 내용 | `anomalyDetected=...`, `severity=...`, `message=...` (라인 구분) |
| 중복 처리 | 동일 시각 파일 존재 시 덮어쓰기 |

### 5.4 예외 처리

| 상황 | Exception | 처리 방안 |
|------|-----------|-----------|
| 폴더 없음 | - | 폴더 자동 생성 |
| 파일 쓰기 실패 | IOException | ERROR 로그 후 배치는 계속 진행 (다음 단계로 넘어가지 않고 return) |

---

## 6. 공통 고려사항

- **재시도 정책**: Dify 호출 재시도 횟수, 백오프 전략은 `log-analyzer.dify.max-retries` / `timeout-seconds` 설정을 따른다.
- **출력 파일 보관**: `output/hourly/anomaly/` 파일은 daily-monitor 처리 후 `4_daily-monitor`의 deleteOldHourlyFiles()에서 7일 기준으로 정리.
- **로깅**: 각 단계 시작/종료 시점에 로그 남기기.
- **알림**: 배치 실패 시 알림 발송 여부 결정.

---

## 7. 변경 이력

| 버전 | 날짜 | 내용 | 작성자 |
|------|------|------|--------|
| v1.0 | 2026-06-23 | 초안 완성 | |
| v2.0 | 2026-08-25 | anomaly-analysis/optimization-analysis 분리 구조 → log-suite 통합 워크플로우(mode=anomaly) 단일 호출로 변경. prev/next 카테고리별 누적 건수 상태 관리 추가. optimization 관련 절 삭제(미지원) | |
