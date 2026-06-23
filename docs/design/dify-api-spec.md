# Dify API 연동 스펙

## 0. 개요

| 항목 | 내용 |
|------|------|
| 연동 방식 | Dify Workflow API (HTTP REST) |
| 인증 | Bearer Token (`Authorization: Bearer {API_KEY}`) |
| Content-Type | `application/json` |
| Response Mode | `blocking` (응답 완료까지 대기) |
| 작성일 | 2026-06-22 |

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

각 워크플로우는 Dify에서 별도로 발급된 API Key를 사용한다.

```properties
log-analyzer.dify.workflow.date-format.api-key=
log-analyzer.dify.workflow.fault-check.api-key=
log-analyzer.dify.workflow.anomaly-analysis.api-key=
log-analyzer.dify.workflow.optimization-analysis.api-key=
log-analyzer.dify.workflow.daily-anomaly.api-key=
log-analyzer.dify.workflow.daily-optimization.api-key=
```

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
| `sample_log` | String | readSampleLog()에서 읽은 샘플 로그 (최대 100줄) |

**응답 outputs**

| 필드명 | 타입 | 설명 | 예시 |
|--------|------|------|------|
| `date_format` | String | 추론된 날짜 형식 패턴 (Java SimpleDateFormat 형식) | `yyyy-MM-dd HH:mm:ss` |

**프롬프트 제약**
- 반드시 Java `SimpleDateFormat` 호환 패턴으로만 응답할 것
- 날짜 형식을 추론할 수 없는 경우 빈 문자열 반환

---

### 2.2 장애 판단 (fault-check)

| 항목 | 내용 |
|------|------|
| 호출 메서드 | `requestFaultCheckToDify()` |
| 호출 주체 | 실시간 모니터링 배치 (1분 단위) |
| 목적 | 최근 1분 로그에서 치명적 장애 발생 여부 판단 |
| 부가 동작 | 장애 감지 시 Dify 워크플로우 내 MCP 서버를 통해 Swing 클라이언트에 알림 |

**요청 inputs**

| 필드명 | 타입 | 설명 |
|--------|------|------|
| `log_content` | String | 최근 1분 구간 로그 (`now-80s` ~ `now-20s`) |

**응답 outputs**

| 필드명 | 타입 | 설명 |
|--------|------|------|
| `is_fault` | boolean | 장애 감지 여부 |
| `summary` | String | 장애 요약 (`is_fault=true`일 때만 유효) |

**프롬프트 제약**
- "장애인가 아닌가"만 판단 — 상세 분석은 이 워크플로우의 목적이 아님
- `is_fault=true`인 경우 MCP 서버를 통해 Swing 알림 발송

---

### 2.3 이상 패턴 분석 (anomaly-analysis)

| 항목 | 내용 |
|------|------|
| 호출 메서드 | `requestAnomalyAnalysisToDify()` |
| 호출 주체 | 시간 단위 배치 |
| 목적 | 1시간치 로그에서 이상 패턴 분석 |

**요청 inputs**

| 필드명 | 타입 | 설명 |
|--------|------|------|
| `log_content` | String | 최근 1시간 로그 |

**응답 outputs**

| 필드명 | 타입 | 설명 | 제한 |
|--------|------|------|------|
| `content` | String | 이상 패턴 분석 결과 전문 | **1MB 이하 필수** |

**프롬프트 제약**
- 응답 content는 반드시 1MB(1,048,576 bytes) 이하로 생성할 것
- 저장 경로: `output/hourly/anomaly/yyyy-MM-dd_HH.dat`

---

### 2.4 최적화 인사이트 분석 (optimization-analysis)

| 항목 | 내용 |
|------|------|
| 호출 메서드 | `requestOptimizationAnalysisToDify()` |
| 호출 주체 | 시간 단위 배치 |
| 목적 | 1시간치 로그에서 로그 최적화 인사이트 도출 |

**요청 inputs**

| 필드명 | 타입 | 설명 |
|--------|------|------|
| `log_content` | String | 최근 1시간 로그 |

**응답 outputs**

| 필드명 | 타입 | 설명 | 제한 |
|--------|------|------|------|
| `content` | String | 최적화 인사이트 전문 | **1MB 이하 필수** |

**프롬프트 제약**
- 응답 content는 반드시 1MB(1,048,576 bytes) 이하로 생성할 것
- 저장 경로: `output/hourly/optimization/yyyy-MM-dd_HH.dat`

---

### 2.5 일일 이상 패턴 보고 (daily-anomaly)

| 항목 | 내용 |
|------|------|
| 호출 메서드 | `requestDailyAnomalyToDify()` |
| 호출 주체 | 일 단위 배치 |
| 목적 | 하루치 hourly anomaly 결과를 통합하여 이상 패턴 일일 보고 |
| 부가 동작 | Dify 워크플로우 내 MCP 서버를 통해 알림 발송 |

**요청 inputs**

| 필드명 | 타입 | 설명 |
|--------|------|------|
| `anomaly_contents` | String | hourly anomaly .dat 내용을 하나로 합친 문자열 (시각 오름차순) |
| `target_date` | String | 보고 대상 날짜 (`yyyy-MM-dd` 형식) |

**응답 outputs**

| 필드명 | 타입 | 설명 |
|--------|------|------|
| `content` | String | 일일 이상 패턴 보고 전문 (Java단에서 파일 저장에 사용) |
| `alert_sent` | boolean | MCP 알림 발송 성공 여부 |

**프롬프트 제약**
- 저장 경로: `output/daily/anomaly/yyyy-MM-dd.dat`

---

### 2.6 일일 최적화 인사이트 (daily-optimization)

| 항목 | 내용 |
|------|------|
| 호출 메서드 | `requestDailyOptimizationToDify()` |
| 호출 주체 | 일 단위 배치 |
| 목적 | 하루치 hourly optimization 결과를 통합하여 일일 최적화 인사이트 생성 |

**요청 inputs**

| 필드명 | 타입 | 설명 |
|--------|------|------|
| `optimization_contents` | String | hourly optimization .dat 내용을 하나로 합친 문자열 (시각 오름차순) |
| `target_date` | String | 보고 대상 날짜 (`yyyy-MM-dd` 형식) |

**응답 outputs**

| 필드명 | 타입 | 설명 |
|--------|------|------|
| `content` | String | 일일 최적화 인사이트 전문 |

**프롬프트 제약**
- 저장 경로: `output/daily/optimization/yyyy-MM-dd.dat`

---

## 3. 미확정 항목

| 항목 | 내용 |
|------|------|
| `DIFY_BASE_URL` | Dify 서버 주소 미확정 |
| 각 워크플로우 API Key | Dify 워크플로우 생성 후 발급 필요 |
| timeout 기준 | 워크플로우 복잡도에 따라 조정 필요 |
| MCP 서버 연동 스펙 | Dify ↔ MCP 상세 연동 방식 별도 문서 필요 |

---

## 4. 변경 이력

| 버전 | 날짜 | 내용 | 작성자 |
|------|------|------|--------|
| v1.0 | 2026-06-23 | 초안 완성 | |
