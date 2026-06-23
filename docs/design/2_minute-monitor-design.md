# 설계서: 실시간 모니터링 배치

## 0. 개요

| 항목 | 내용 |
|------|------|
| 배치 이름 | LogAnalysisMinuteMonitorJob (가칭) |
| 실행 주기 | 1분 단위 |
| 목적 | 최근 1분 구간의 로그를 Dify에 전달하여 치명적 장애 발생 여부를 즉시 감지 |
| 작성일 | 2026-06-22 |

### 읽기 시간 구간

매 실행 시 아래 구간의 로그만 읽는다.

```
읽기 구간: (현재 시각 - 80초) ~ (현재 시각 - 20초)
```

20초 버퍼를 두는 이유: 로그 파일 write 지연을 고려하여 아직 기록 중인 라인이 포함되지 않도록 함.

### 전체 처리 흐름

```
loadSetupConfig()
      ↓ (로그 파일 경로, 인코딩, 날짜 형식, 타임존)
readLastMinuteLog(config)
      ↓ (최근 1분 구간 로그)
requestFaultCheckToDify(logContent)
      ↓ (장애 여부 + 알림은 Dify 단에서 처리)
```

---

## 1. loadSetupConfig()

> **공용 메서드** — 1분/1시간 배치에서 공유 사용.
> 상세 스펙은 `3_hourly-monitor-design.md` 1번 항목 참고.

---

## 2. readLastMinuteLog()

### 2.1 책임
설정값을 기준으로 `(현재 시각 - 80초) ~ (현재 시각 - 20초)` 구간의 로그를 읽는다.

### 2.2 메서드 시그니처
```java
public String readLastMinuteLog(SetupConfig config)
```

### 2.3 파라미터

| 이름 | 타입 | 필수 | 설명 | 제약조건 |
|------|------|------|------|----------|
| config | SetupConfig | Y | loadSetupConfig()에서 불러온 설정 객체 | null 불가 |

### 2.4 반환값

| 타입 | 설명 |
|------|------|
| String | 해당 구간에 해당하는 로그 라인들을 합친 문자열 |

### 2.5 예외 처리

| 상황 | Exception | 처리 방안 |
|------|-----------|-----------|
| 파일이 존재하지 않음 | FileNotFoundException | 배치 중단 + 알림 |
| 읽기 권한 없음 | IOException | 배치 중단 + 알림 |
| 라인 파싱 실패 | DateTimeParseException | 해당 라인 skip 후 로깅 |
| 구간 내 로그가 0건 | (정상 케이스) | 빈 문자열 반환, 이후 Dify 호출 스킵 |

### 2.6 내부 처리 로직 (의사코드)
```
1. config에서 logFilePath, encoding, dateFormat, timezone 추출
2. 읽기 구간 계산: start = now - 80s, end = now - 20s
3. logFilePath를 encoding으로 열어 라인 단위 순회
4. 각 라인에서 dateFormat으로 타임스탬프 파싱
5. 파싱된 시각이 [start, end] 범위 내에 있으면 결과에 포함
6. 결과 문자열 반환
```

### 2.7 의존성
- 외부 시스템 호출 없음 (순수 파일 IO + 날짜 파싱)

### 2.8 비고
- 로그 파일이 클 경우 매분 전체 파일을 스캔하는 것은 부담이 큼 — 역방향 읽기(tail 방식)로 최근 구간만 빠르게 탐색하는 최적화 권장.

---

## 3. requestFaultCheckToDify()

### 3.1 책임
최근 1분 구간 로그를 Dify에 전달하여 치명적 장애 발생 여부를 판단받는다.

### 3.2 메서드 시그니처
```java
public FaultCheckResult requestFaultCheckToDify(String logContent)
```

### 3.3 파라미터

| 이름 | 타입 | 필수 | 설명 | 제약조건 |
|------|------|------|------|----------|
| logContent | String | Y | readLastMinuteLog()에서 받은 로그 내용 | 빈 문자열이면 호출 스킵 |

### 3.4 반환값

| 타입 | 설명 |
|------|------|
| FaultCheckResult (커스텀 객체) | 장애 여부(boolean), 감지된 장애 요약 문자열을 담은 객체 |

**FaultCheckResult 필드**

| 필드명 | 타입 | 설명 |
|--------|------|------|
| isFault | boolean | 장애 감지 여부 |
| summary | String | 장애 요약 (isFault=true 일 때만 유효) |

### 3.5 예외 처리

| 상황 | Exception | 처리 방안 |
|------|-----------|-----------|
| Dify API 호출 실패 | DifyApiException | 재시도 N회 후 배치 중단 + 알림 |
| 응답 스키마 불일치 | ResponseMappingException | 배치 중단 + 알림 |

### 3.6 내부 처리 로직 (의사코드)
```
1. logContent가 빈 문자열이면 isFault=false인 FaultCheckResult 반환 (Dify 호출 스킵)
2. logContent를 Dify 장애 판단 Workflow API 요청 페이로드로 구성
3. Dify에 POST 요청 전송
4. 응답에서 장애 여부와 요약 추출
5. FaultCheckResult 객체로 변환하여 반환
```

### 3.7 의존성
- 외부 시스템: Dify Workflow API (장애 판단용 — 시간 단위 배치의 분석 워크플로우와 별도)

### 3.8 비고
- 이 워크플로우는 "장애인가 아닌가"만 판단하는 단순 분류 용도. 상세 분석은 시간 단위 배치에서 수행.
- 장애 감지 시 Swing 알림은 Dify 워크플로우 내에서 MCP 서버를 통해 처리 (Java 코드에서 별도 sendAlert 불필요).

---

## 4. 공통 고려사항

- **실행 주기**: 1분 단위. 시간 단위 배치와 실행 시점이 겹칠 수 있으므로 리소스 경합 여부 확인 필요.
- **읽기 구간 중복 없음**: start = now - 80s, end = now - 20s 고정이므로 매 실행 구간이 겹치지 않음.
- **Dify 호출 비용**: 1분마다 호출되므로 빈 로그 구간에서는 반드시 스킵 처리.
- **로깅**: 장애 감지 여부와 무관하게 매 실행마다 처리 결과를 INFO로 남길 것.

---

## 5. 변경 이력

| 버전 | 날짜 | 내용 | 작성자 |
|------|------|------|--------|
| v1.0 | 2026-06-23 | 초안 완성 | |
