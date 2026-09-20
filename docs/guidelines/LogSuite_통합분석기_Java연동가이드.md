# LogSuite 통합 분석기 — Java 연동 가이드

하나의 Dify 워크플로우(`logSuite_integrated_FIN_monthly.yml`)를 `mode` 파라미터로 분기해서 총 10개 기능을 처리합니다. 이 문서는 Java 쪽에서 각 모드를 호출할 때 무엇을 보내고, 무엇이 돌아오고, 그 다음에 무엇을 해야 하는지를 정리한 연동 가이드입니다.

---

## 0. 공통 규칙

- **입력**: `mode`(필수, select) + 모드별 전용 파라미터. 안 쓰는 파라미터는 안 보내도 됩니다.
- **잘못된 mode 값**: `error_message`에 안내 문구가 채워지고 나머지 필드는 비어있습니다. Java는 응답을 받으면 `error_message`가 채워져 있는지부터 확인하는 게 안전합니다.
- **모든 응답에 공통으로 들어있는 필드**
  - `mode`: 요청한 mode 값 echo
  - `source_id`: 요청 시 넘긴 값 echo (여러 서버/서비스를 구분할 때 사용)
  - `notify_message_title` / `notify_message_text`: 어떤 모드가 실행되든 그 결과를 사람이 읽을 수 있는 제목/본문 형태로 정리해둔 값. 내부 쪽지(MCP) 발송 Tool 노드에 그대로 파라미터로 연결하면 됨

---

## 1. `single_analyze` — 단건 로그 분석

| 구분 | 내용 |
|---|---|
| 용도 | 로그 한 건(에러 스니펫 등)을 즉석에서 분석 |
| 입력 | `log_content` (필수, 5000자 초과 시 자동 차단되어 분석하지 않음) |
| 출력 | `is_fault`(bool) / `sa_severity`(문자열) / `summary`(문자열) |
| Java가 할 일 | 응답을 그대로 화면 표시하거나 로그로 저장. 하루치를 모아뒀다가 `daily_single_analyze`에 넘기려면 `{"timestamp": "...", "is_fault": "...", "severity": "...", "summary": "..."}` 형태로 쌓아둘 것 |

---

## 2. `daily_report` — 일일 운영보고

| 구분 | 내용 |
|---|---|
| 용도 | 하루치 로그 원문 전체를 받아 사람이 읽는 운영보고서 생성 |
| 입력 | `log_content` (하루치 로그 원문 통째로 전달 가능. 사전집계 후 LLM에 전달되므로 길어도 안전) |
| 출력 | `report_text`(마크다운 형식 보고서) / `daily_error_count` / `daily_warn_count` / `daily_unique_issue_count` / `daily_unique_warn_count` |
| Java가 할 일 | `report_text`를 그대로 저장/발송. 월간보고(`monthly_report`)를 만들 때 `daily_results_json`의 `report_text` 필드로 재사용 가능 |

---

## 3. `anomaly` — 실시간(주기별) 이상감지

| 구분 | 내용 |
|---|---|
| 용도 | 매 주기(예: 5분/1시간)마다 짧은 배치 로그를 직전 주기 값과 비교해 이상 여부 판단 |
| 입력 | `log_content`(이번 주기 로그만) + `prev_error_count` / `prev_warn_count` / `prev_timeout_count` / `prev_http5xx_count` / `prev_db_conn_count` / `prev_login_fail_count` / `prev_batch_fail_count` / `prev_external_api_fail_count` |
| 출력 | `anomaly_detected`(bool) / `anomaly_severity` / `anomaly_reasons` / `anomaly_message`(사람이 읽는 알림/정상 텍스트) + `next_prev_error_count` 등 `next_prev_*` 8종 |
| 판단 기준 | ERROR / Timeout / HTTP 5xx / DB Connection / 외부 API 실패는 절대치 5건 이상 또는 직전 대비 +3 증가 시 이상 판정("높음"). WARN은 절대치 10건 기준(평소에도 자주 발생해서 기준을 높게 잡음, "보통"). 로그인 실패 / 배치 실패도 "보통" 심각도 |
| Java가 할 일 | ① `next_prev_*` 8개 값을 저장해뒀다가 **다음 호출의 `prev_*`로 그대로 전달**해야 함 (안 하면 매번 0부터 비교되어 정확도가 떨어짐) ② 이 호출 결과를 `{"timestamp": "...", "anomaly_detected": "...", "severity": anomaly_severity, "reasons_text": anomaly_reasons}` 형태로 `output/hourly/anomaly/`에 쌓아서 나중에 `daily_anomaly`에 사용 |

**상태 유지형 호출 주의**: `anomaly`는 이전 호출 결과에 의존하는 유일한 모드입니다. 서버가 여러 대라면 `source_id`로 구분해서 `prev_*` 상태를 서버별로 따로 관리해야 합니다.

---

## 4. `daily_optimization` — 일간 최적화 인사이트 취합 (F-06)

| 구분 | 내용 |
|---|---|
| 용도 | 이 워크플로우 밖의 별도 hourly optimization 프로세스 결과를 하루 단위로 취합 |
| 전제조건 | 전일 `output/hourly/optimization/` 파일이 0건이면 **호출 자체를 스킵**하고 정상 종료 (Java 쪽 로직) |
| 입력 | `hourly_optimization_json`: 전일 `.dat` 파일들 내용을 JSON 배열 문자열로 합쳐서 전달. 예: `[{"timestamp": "2026-09-01T01:00:00", ...}, ...]` |
| 출력 | `daily_optimization_result` (JSON 문자열) |
| Java가 할 일 | `daily_optimization_result`를 파싱해서 `output/daily/optimization/yyyy-MM-dd.dat`로 저장. `status`가 `"error"`면 파싱 실패이므로 알림/재시도 필요 |

**`daily_optimization_result` 예시 (정상)**
```json
{
  "status": "ok",
  "hour_count": 24,
  "insight": {
    "overall_status": "주의",
    "trend_summary": "오후 시간대 캐시 적중률이 지속적으로 하락함(추정)",
    "key_findings": ["14~18시 slow_query_count 급증"],
    "recommendations": ["오후 시간대 쿼리 인덱스 점검 필요"]
  },
  "message": ""
}
```

---

## 5. `daily_anomaly` — 일간 이상감지 결과 취합 (F-05, F-06과 병렬 실행 가능)

| 구분 | 내용 |
|---|---|
| 용도 | 3번(`anomaly`) 호출 결과들을 하루 단위로 취합 |
| 입력 | `hourly_anomaly_json`: 전일 `output/hourly/anomaly/` 파일들(3번 결과를 Java가 저장해둔 것) 배열 |
| 출력 | `daily_anomaly_daily_result` — 4번과 동일한 shape(`status`/`hour_count`/`insight`) |
| Java가 할 일 | `output/daily/anomaly/yyyy-MM-dd.dat`로 저장 |

---

## 6. `daily_single_analyze` — 일간 단건분석 결과 취합

| 구분 | 내용 |
|---|---|
| 용도 | 1번(`single_analyze`) 호출 결과들을 하루 단위로 취합 |
| 입력 | `hourly_single_analyze_json`: 하루치 `{is_fault, severity, summary}` 배열 |
| 출력 | `daily_single_analyze_result` — 4/5번과 동일한 shape |
| Java가 할 일 | 필요 시 별도 저장, 또는 월간보고용으로 보관 |

---

## 7. `monthly_report` — 월간 종합 운영보고

| 구분 | 내용 |
|---|---|
| 용도 | 한 달치 일별 결과를 모두 섞어서 **하나의 서술형 월간 보고서** 생성 (2/4/5/6번 결과를 다 참고 가능) |
| 입력 | `daily_results_json`: `[{"date": "2026-09-01", "report_text": "...", "optimization": {...}, "anomaly": {...}, "single_analyze": {...}}, ...]` — 필드는 있는 것만 넣으면 됨 |
| 출력 | `monthly_report_result` |
| Java가 할 일 | `monthly_report`의 `report_text`(마크다운)를 그대로 배포/저장 |

**`monthly_report_result` 예시 (정상)**
```json
{
  "status": "ok",
  "day_count": 30,
  "monthly_report": {
    "overall_status": "주의",
    "monthly_summary": "...",
    "major_issues": ["..."],
    "recurring_patterns": ["..."],
    "trend_summary": "...",
    "recommendations": ["...", "...", "..."],
    "report_text": "# 월간 시스템 로그 운영보고\n## 1. 월간 종합 상태\n..."
  },
  "message": ""
}
```

---

## 8~10. `monthly_optimization` / `monthly_anomaly` / `monthly_single_analyze`

7번(`monthly_report`)과는 별개로, **항목별 순수 인사이트만** 월간 단위로 취합하는 3개 모드입니다. 7번과 역할이 겹치지 않고 서로 보완합니다.

| mode | 입력 | 취합 대상 |
|---|---|---|
| `monthly_optimization` | `monthly_optimization_json` | 한 달치 4번(`daily_optimization_result`) 배열 |
| `monthly_anomaly` | `monthly_anomaly_json` | 한 달치 5번(`daily_anomaly_daily_result`) 배열 |
| `monthly_single_analyze` | `monthly_single_analyze_json` | 한 달치 6번(`daily_single_analyze_result`) 배열 |

- 배열 원소는 해당 일간 취합 결과 객체를 그대로 `date`와 함께 담으면 됩니다. 예: `{"date": "2026-09-01", "status": "ok", "hour_count": 24, "insight": {...}}`
- 출력: `monthly_optimization_result` / `monthly_anomaly_result` / `monthly_single_analyze_result` — `day_count`, `no_data_day_count`(그날 데이터가 없었던 날 수), `insight` 포함

**주의**: 특정 날짜의 일간 결과가 `status: "no_data"`였다면, 그 날은 월간 인사이트 텍스트에서 제외되고 `no_data_day_count`로만 별도 집계됩니다 (정상으로 오인하지 않도록 하기 위함).

**월간 취합 결과 예시**
```json
{
  "status": "ok",
  "day_count": 28,
  "no_data_day_count": 2,
  "insight": {
    "overall_status": "양호",
    "monthly_summary": "...",
    "trend_summary": "...",
    "key_findings": ["..."],
    "recommendations": ["..."]
  },
  "message": ""
}
```

---

## 공통 에러 처리 (4~10번, 취합류 모드 전체)

응답의 `status` 값으로 3가지 상황을 구분합니다.

| status | 의미 | Java 대응 |
|---|---|---|
| `ok` | 정상 취합됨 | `insight` 사용해서 저장 |
| `no_data` | 넘긴 배열이 비어있음 | 정상 상황, 그냥 스킵 |
| `error` | 넘긴 JSON이 깨져서 파싱 실패 (`message`에 사유 포함) | 알림 필요. Java 쪽 직렬화 버그일 가능성이 높음 |

---

## 전체 모드 요약표

| # | mode | 성격 | 핵심 입력 | 핵심 출력 |
|---|---|---|---|---|
| 1 | `single_analyze` | 단건 | `log_content` | `is_fault`, `sa_severity`, `summary` |
| 2 | `daily_report` | 일일 종합 | `log_content`(하루치) | `report_text` |
| 3 | `anomaly` | 실시간(상태유지) | `log_content` + `prev_*` | `anomaly_detected` 등 + `next_prev_*` |
| 4 | `daily_optimization` | 일간 취합 | `hourly_optimization_json` | `daily_optimization_result` |
| 5 | `daily_anomaly` | 일간 취합 | `hourly_anomaly_json` | `daily_anomaly_daily_result` |
| 6 | `daily_single_analyze` | 일간 취합 | `hourly_single_analyze_json` | `daily_single_analyze_result` |
| 7 | `monthly_report` | 월간 종합 | `daily_results_json` | `monthly_report_result` |
| 8 | `monthly_optimization` | 월간 취합 | `monthly_optimization_json` | `monthly_optimization_result` |
| 9 | `monthly_anomaly` | 월간 취합 | `monthly_anomaly_json` | `monthly_anomaly_result` |
| 10 | `monthly_single_analyze` | 월간 취합 | `monthly_single_analyze_json` | `monthly_single_analyze_result` |

---

## 향후 작업 (참고)

- **MCP 내부 쪽지 발송**: `notify-prepare` 코드 노드가 모든 모드의 결과를 `notify_message_title`/`notify_message_text`로 정리해두었습니다. Dify 편집기에서 이 노드와 `end-main` 사이에 실제 연결된 MCP Tool 노드를 드래그해서 넣고, 두 값을 파라미터로 연결하면 됩니다.
