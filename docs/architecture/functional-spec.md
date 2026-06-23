# 기능 정의서

## 기능 목록

| ID | 기능명 | 배치 | 주기 |
|----|--------|------|------|
| F-01 | 시스템 설치 | Setup | 최초 1회 |
| F-02 | 실시간 장애 감지 | Minute Monitor | 1분 |
| F-03 | 시간 단위 이상 패턴 분석 | Hourly Monitor | 1시간 |
| F-04 | 시간 단위 최적화 인사이트 수집 | Hourly Monitor | 1시간 |
| F-05 | 일간 이상 패턴 보고 | Daily Monitor | 1일 |
| F-06 | 일간 최적화 인사이트 저장 | Daily Monitor | 1일 |
| F-07 | Hourly 파일 정리 | Daily Monitor | 1일 |
| F-08 | 월간 최적화 인사이트 보고 | Monthly Monitor | 1월 (**개발 보류**) |

---

## F-01. 시스템 설치

| 항목 | 내용 |
|------|------|
| 설명 | 분석 대상 로그 파일을 등록하고, 인코딩 / 날짜 형식 / 타임존을 자동 탐지하여 이후 배치가 참조할 설정 파일을 생성한다 |
| 트리거 | 최초 1회 수동 실행 |
| 사전 조건 | 분석 대상 로그 파일이 존재하고 읽기 권한이 있어야 한다 |
| 입력 | 로그 파일 경로 (사용자 입력) |
| 출력 | `config/setup.properties` — 로그 파일 경로, 인코딩, 날짜 형식, 타임존 저장 |
| 제약 | 재실행 시 기존 설정 파일을 덮어쓴다 |
| 관련 설계서 | `docs/design/1_setup-design.md` |

**처리 흐름**

```
로그 파일 경로 설정 → 인코딩 탐지 → 샘플 로그 읽기
  → Dify로 날짜 형식 추론 → 타임존 탐지 → 설정 저장
```

---

## F-02. 실시간 장애 감지

| 항목 | 내용 |
|------|------|
| 설명 | 최근 1분 구간 로그를 Dify에 전달하여 치명적 장애 발생 여부를 판단하고, 장애 감지 시 Swing 클라이언트에 즉시 알림을 발송한다 |
| 트리거 | 1분 단위 자동 실행 |
| 사전 조건 | `config/setup.properties`가 존재해야 한다 (F-01 완료 필수) |
| 입력 | 로그 파일 중 `(현재 시각 - 80초) ~ (현재 시각 - 20초)` 구간 |
| 출력 | 장애 감지 시 Dify MCP → Swing 알림 발송 (파일 저장 없음) |
| 제약 | 해당 구간 로그가 0건이면 Dify 호출 스킵 |
| 관련 설계서 | `docs/design/2_minute-monitor-design.md` |

**처리 흐름**

```
설정 로드 → 최근 1분 로그 읽기 → Dify 장애 판단 요청
  → [장애 감지 시] Dify MCP → Swing 알림
```

---

## F-03. 시간 단위 이상 패턴 분석

| 항목 | 내용 |
|------|------|
| 설명 | 최근 1시간 로그를 Dify에 전달하여 이상 패턴을 분석하고 결과를 파일로 저장한다 |
| 트리거 | 1시간 단위 자동 실행 |
| 사전 조건 | `config/setup.properties`가 존재해야 한다 (F-01 완료 필수) |
| 입력 | 최근 1시간 로그 |
| 출력 | `output/hourly/anomaly/yyyy-MM-dd_HH.dat` |
| 제약 | 결과 content 1MB 초과 시 배치 중단. F-04와 병렬 실행 가능 |
| 관련 설계서 | `docs/design/3_hourly-monitor-design.md` |

**처리 흐름**

```
설정 로드 → 최근 1시간 로그 읽기 → Dify 이상 패턴 분석 요청
  → output/hourly/anomaly/yyyy-MM-dd_HH.dat 저장
```

---

## F-04. 시간 단위 최적화 인사이트 수집

| 항목 | 내용 |
|------|------|
| 설명 | 최근 1시간 로그를 Dify에 전달하여 최적화 인사이트를 수집하고 결과를 파일로 저장한다 |
| 트리거 | 1시간 단위 자동 실행 (F-03과 동시 실행) |
| 사전 조건 | `config/setup.properties`가 존재해야 한다 (F-01 완료 필수) |
| 입력 | 최근 1시간 로그 (F-03과 동일 입력) |
| 출력 | `output/hourly/optimization/yyyy-MM-dd_HH.dat` |
| 제약 | 결과 content 1MB 초과 시 배치 중단. F-03과 병렬 실행 가능 |
| 관련 설계서 | `docs/design/3_hourly-monitor-design.md` |

**처리 흐름**

```
설정 로드 → 최근 1시간 로그 읽기 → Dify 최적화 인사이트 요청
  → output/hourly/optimization/yyyy-MM-dd_HH.dat 저장
```

---

## F-05. 일간 이상 패턴 보고

| 항목 | 내용 |
|------|------|
| 설명 | 전일 hourly anomaly 결과를 취합하여 Dify에 전달하고, 일간 이상 패턴 보고를 Dify MCP를 통해 알림으로 발송한다 |
| 트리거 | 1일 단위 자동 실행 (전일 hourly 배치 완료 이후) |
| 사전 조건 | `output/hourly/anomaly/` 폴더가 존재해야 한다 (파일 0건이면 Dify 호출 스킵 후 정상 종료) |
| 입력 | `output/hourly/anomaly/` 전일 .dat 파일 전체 |
| 출력 | Dify MCP → 알림 발송 + `output/daily/anomaly/yyyy-MM-dd.dat` 저장 |
| 제약 | anomaly 파일 0건이면 Dify 호출 스킵. F-06과 병렬 실행 가능 |
| 관련 설계서 | `docs/design/4_daily-monitor-design.md` |

**처리 흐름**

```
hourly anomaly .dat 취합 → Dify 일간 이상 패턴 보고 요청
  → [병렬] Dify MCP → 알림 발송
  → output/daily/anomaly/yyyy-MM-dd.dat 저장
```

---

## F-06. 일간 최적화 인사이트 저장

| 항목 | 내용 |
|------|------|
| 설명 | 전일 hourly optimization 결과를 취합하여 Dify에 전달하고, 일간 최적화 인사이트 결과를 파일로 저장한다 |
| 트리거 | 1일 단위 자동 실행 (전일 hourly 배치 완료 이후) |
| 사전 조건 | `output/hourly/optimization/` 폴더가 존재해야 한다 (파일 0건이면 Dify 호출 스킵 후 정상 종료) |
| 입력 | `output/hourly/optimization/` 전일 .dat 파일 전체 |
| 출력 | `output/daily/optimization/yyyy-MM-dd.dat` |
| 제약 | optimization 파일 0건이면 Dify 호출 스킵. F-05와 병렬 실행 가능 |
| 관련 설계서 | `docs/design/4_daily-monitor-design.md` |

**처리 흐름**

```
hourly optimization .dat 취합 → Dify 일간 최적화 인사이트 요청
  → output/daily/optimization/yyyy-MM-dd.dat 저장
```

---

## F-07. Hourly 파일 정리

| 항목 | 내용 |
|------|------|
| 설명 | 보관 기간이 지난 hourly .dat 파일을 삭제하여 디스크 용량을 관리한다. hourly 결과(anomaly/optimization) 7일, daily 결과(anomaly/optimization) 90일 보관 |
| 트리거 | Daily Monitor 배치 실행 시 마지막 단계로 자동 실행 |
| 사전 조건 | 없음 (폴더가 없으면 스킵) |
| 입력 | `output/hourly/anomaly/`, `output/hourly/optimization/` 내 .dat 파일 |
| 출력 | hourly anomaly/optimization 7일 초과 파일 삭제 |
| 제약 | 개별 파일 삭제 실패 시 해당 파일만 skip, 배치는 계속 진행 |
| 관련 설계서 | `docs/design/4_daily-monitor-design.md` |

**처리 흐름**

```
output/hourly/anomaly/ → 7일 초과 파일 삭제
output/hourly/optimization/ → 7일 초과 파일 삭제
```

---

## F-08. 월간 최적화 인사이트 보고 (**개발 보류**)

| 항목 | 내용 |
|------|------|
| 설명 | 전월 daily 결과를 취합하여 Dify에 전달하고, 월간 최적화 인사이트를 메일로 발송 및 파일로 저장한다 |
| 트리거 | 1월 단위 자동 실행 (전월 daily 배치 완료 이후) |
| 입력 | `output/daily/optimization/` 전월 .dat 파일 전체 |
| 출력 | Dify MCP → 메일 발송 + `output/monthly/yyyy-MM.dat` |
| 보류 사유 | 총 용량 10MB 초과 시 청킹 처리 및 N개 결과 병합 공수 이슈 |
| 관련 설계서 | `docs/design/5_monthly-monitor-design.md` |

---

## 변경 이력

| 버전 | 날짜 | 내용 | 작성자 |
|------|------|------|--------|
| v1.0 | 2026-06-23 | 초안 완성 | |
