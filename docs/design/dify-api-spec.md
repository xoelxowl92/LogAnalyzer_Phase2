# Dify API 연동 스펙

## 0. 개요

| 항목 | 내용 |
|------|------|
| 연동 방식 | Dify Workflow API (HTTP REST) |
| 인증 | Bearer Token (`Authorization: Bearer {API_KEY}`) |
| Content-Type | `application/json` |
| Response Mode | `blocking` (응답 완료까지 대기) |
| 작성일 | 2026-06-22 |
| 최종 수정일 | 2026-08-25 (통합 워크플로우 전환 반영) |

---

## 1. 공통 규칙

### 1.1 요청 기본 구조

```
POST {DIFY_BASE_URL}/v1/workflows/run
Authorization: Bearer {WORKFLOW_API_KEY}
Content-Type: application/json
```

```json
{
  "inputs": { ... },
  "response_mode": "blocking",
  "user": "loganalyzer-batch"
}
```

### 1.2 응답 기본 구조

```json
{
  "workflow_run_id": "...",
  "task_id": "...",
  "data": {
    "id": "...",
    "workflow_id": "...",
    "status": "succeeded",
    "outputs": { ... },
    "error": null,
    "elapsed_time": 1.23,
    "total_tokens": 100,
    "created_at": 1700000000,
    "finished_at": 1700000001
  }
}
```

- `data.status`가 `"succeeded"` 이외인 경우 실패로 처리
- `data.error`가 null이 아닌 경우 `DifyApiException` 발생

### 1.3 공통 설정값 (application.properties)

```properties
log-analyzer.dify.base-url=https://api.dify.ai
log-analyzer.dify.user=loganalyzer-batch
log-analyzer.dify.max-retries=3
log-analyzer.dify.timeout-seconds=60
```

### 1.4 워크플로우별 API Key

```properties
log-analyzer.dify.workflow.date-format.api-key=
log-analyzer.dify.workflow.log-suite.api-key=
```

- `date-format` : 시스템 설치 시 날짜 형식 추론 전용 앱 (별도 앱, 변경 없음)
- `log-suite` : 단건분석/일일보고/이상감지가 하나로 합쳐진 통합 워크플로우 앱 (아래 2.2 참고)

---

## 2. 워크플로우 스펙

### 2.1 날짜 형식 추론 (date-format)

| 항목 | 내용 |
|------|------|
| 호출 메서드 | `requestDateFormatToDify()` |
| 호출 주체 | 시스템 설치 배치 |
| 목적 | 샘플 로그에서 날짜/시간 형식 패턴 추론 |

**요청 inputs**

| 필드명 | 타입 | 설명 |
|--------|------|------|
| `log_sample` | String | readSampleLog()에서 읽은 샘플 로그 (최대 100줄) |

**응답 outputs**

| 필드명 | 타입 | 설명 | 예시 |
|--------|------|------|------|
| `datetime_format` | String | 추론된 날짜 형식 패턴 (Java DateTimeFormatter 형식) | `yyyy-MM-dd HH:mm:ss` |

**프롬프트 제약**
- 반드시 Java `DateTimeFormatter` 호환 패턴으로만 응답할 것
- 날짜 형식을 추론할 수 없는 경우 빈 문자열 반환

---

### 2.2 통합 워크플로우 (log-suite) — mode 파라미터로 분기

> 원본: `docs/lhs_logSuite_integrated_fix.yml` (Dify DSL export, `mode: workflow`, app name `lhs_logSuite_integrated_fix`)

기존에 fault-check / anomaly-analysis / daily-anomaly 3개로 나뉘어 있던 워크플로우가 **앱 하나(log-suite)**로 합쳐졌다.
API Key도 하나만 발급되며, 요청 시 `mode` 입력값으로 동작을 분기한다.

**로그 최적화 인사이트(구 optimization-analysis / daily-optimization)는 이 통합 워크플로우에 아직 추가되지 않았다.**
추후 Dify 쪽에서 mode가 추가되면 Java 코드도 함께 대응해야 한다.

#### 2.2.1 공통 요청 inputs

| 필드명 | 타입 | 필수 | 설명 |
|--------|------|------|------|
| `mode` | String (select) | Y | `single_analyze` / `daily_report` / `anomaly` 중 하나 |
| `log_content` | String (paragraph) | Y | 분석 대상 로그. **최대 20,000자** — 초과 시 400 오류 (Dify start 노드 max_length 제약) |
| `prev_error_count` 외 7개 | Number | N (anomaly 전용, 기본값 0) | 아래 2.2.4 참고 |

#### 2.2.2 mode=single_analyze (구 fault-check, 1분 배치)

| 항목 | 내용 |
|------|------|
| 호출 메서드 | `MinuteMonitorService.requestFaultCheckToDify()` |
| 호출 주체 | 실시간 모니터링 배치 (1분 단위) |
| 목적 | 최근 1분 로그에서 치명적 장애 발생 여부 판단 |

**응답 outputs**

| 필드명 | 타입 | 설명 |
|--------|------|------|
| `is_fault` | boolean | 장애 감지 여부 |
| `summary` | String | 장애 분석 요약 |

#### 2.2.3 mode=daily_report (구 daily-anomaly, 1일 배치)

| 항목 | 내용 |
|------|------|
| 호출 메서드 | `DailyMonitorService.requestDailyReportToDify()` |
| 호출 주체 | 일 단위 배치 |
| 목적 | 하루치 로그 요약을 워크플로우 내부(dr-preprocess 코드 노드)에서 집계하여 일일 운영보고 작성 |

**구 스펙과의 차이 (중요)**
- `log_content` 필드는 원본 로그가 아니라 `HourlyMonitorService`가 이미 저장한 `output/hourly/anomaly/yyyy-MM-dd_HH.dat`의 시간대별 `message`를 시각 오름차순으로 모은 값이다 (20,000자 제한 안에 담기 위함, `4_daily-monitor-design.md` 1.3 참고).
- 집계(ERROR/WARN 카운트, 그룹화 등)는 워크플로우 내부에서 처리한다.

**응답 outputs**

| 필드명 | 타입 | 설명 |
|--------|------|------|
| `report_text` | String | 일일 운영보고 전문 |
| `daily_error_count` | Number | 집계된 ERROR 건수 |
| `daily_warn_count` | Number | 집계된 WARN 건수 |
| `daily_unique_issue_count` | Number | 그룹화된 고유 이슈 수 |

#### 2.2.4 mode=anomaly (구 anomaly-analysis, 1시간 배치)

| 항목 | 내용 |
|------|------|
| 호출 메서드 | `HourlyMonitorService.requestAnomalyAnalysisToDify()` |
| 호출 주체 | 시간 단위 배치 |
| 목적 | 이번 주기 로그의 카테고리별 건수를 직전 주기와 비교해 이상 증가 여부 판단 |

**구 스펙과의 차이 (중요)**
- 예전에는 "이상 패턴 분석 결과 전문(content, 1MB 이하)"을 그대로 저장하는 방식이었으나,
  이제는 **직전 호출 대비 증감을 비교하는 상태 기반(stateful) 방식**으로 바뀌었다.
- 매 호출마다 직전 호출의 카테고리별 누적 건수(`prev_*`)를 함께 보내야 하며,
  응답으로 받은 `next_prev_*` 값을 다음 호출의 `prev_*`로 그대로 이어서 전달해야 한다.
  (Java 쪽 구현은 `output/hourly/anomaly-state.properties` 파일에 저장해 다음 실행에 재사용한다.)
- 결과 텍스트(`anomaly_message`)는 LLM `max_tokens=800` 제한이 걸려 있어 더 이상 1MB 크기 검증이 필요 없다.

**요청 inputs (prev_* — anomaly 모드 전용, 기본값 0)**

| 필드명 | 설명 |
|--------|------|
| `prev_error_count` / `prev_warn_count` / `prev_timeout_count` / `prev_http5xx_count` / `prev_db_conn_count` / `prev_login_fail_count` / `prev_batch_fail_count` / `prev_external_api_fail_count` | 직전 호출 응답의 `next_prev_*`를 그대로 전달 (최초 호출은 전부 0) |

**응답 outputs**

| 필드명 | 타입 | 설명 |
|--------|------|------|
| `anomaly_detected` | String ("true"/"false") | 이상 감지 여부 |
| `anomaly_severity` | String | 치명적 / 높음 / 보통 / 낮음 |
| `anomaly_message` | String | 이상 감지 시 알림 텍스트, 정상 시 고정 정상 응답 |
| `next_prev_error_count` 외 7개 | Number | 다음 호출에 `prev_*`로 이어서 전달할 이번 주기 누적 건수 |

**저장 경로**: `output/hourly/anomaly/yyyy-MM-dd_HH.dat` (anomalyDetected/severity/message 저장)

---

## 3. 미확정 항목

| 항목 | 내용 |
|------|------|
| `DIFY_BASE_URL` | Dify 서버 주소 미확정 |
| log-suite API Key | Dify 콘솔에서 발급 필요 |
| 로그 최적화 인사이트 | 통합 워크플로우에 아직 mode 없음 — 추가되면 Java 쪽 연동 재개 필요 |
| timeout 기준 | 워크플로우 복잡도에 따라 조정 필요 |
| MCP 서버 연동 스펙 | Dify ↔ MCP 상세 연동 방식 별도 문서 필요 (통합 이후 MCP 알림 흐름 재확인 필요) |

---

## 4. 변경 이력

| 버전 | 날짜 | 내용 | 작성자 |
|------|------|------|--------|
| v1.0 | 2026-06-23 | 초안 완성 | |
| v2.0 | 2026-08-25 | fault-check/anomaly-analysis/daily-anomaly를 log-suite 통합 워크플로우(mode 분기)로 변경. 최적화 인사이트 관련 워크플로우는 아직 미포함이라 스펙에서 제외 | |
| v2.1 | 2026-08-25 | daily_report의 log_content를 hourly anomaly 결과 재활용 방식으로 확정, `DailyMonitorService` 구현 완료 | |
