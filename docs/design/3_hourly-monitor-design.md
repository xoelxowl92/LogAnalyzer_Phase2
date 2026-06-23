# 설계서: 시간 단위 로그 분석 배치

## 0. 개요

| 항목 | 내용 |
|------|------|
| 배치 이름 | LogAnalysisHourlyMonitorJob (가칭) |
| 실행 주기 | 1시간 (매 정시 등 — 확정 필요) |
| 목적 | 서버 로그를 주기적으로 읽어 이상 패턴 분석과 최적화 인사이트를 각각 Dify에 요청하고 결과를 저장 |
| 작성일 | 2026-06-22 |

### 전체 처리 흐름

```
loadSetupConfig()
      ↓ (로그 파일 경로, 인코딩, 날짜 형식, 타임존)
readLastHourLog(config)
      ↓ (1시간치 로그 문자열)
      ├─ requestAnomalyAnalysisToDify(logContent)
      │         ↓ (이상 패턴 분석 결과)
      │   saveAnomalyResult(result, batchTime)    → output/hourly/anomaly/yyyy-MM-dd_HH.dat
      │
      └─ requestOptimizationAnalysisToDify(logContent)
                ↓ (최적화 인사이트)
          saveOptimizationResult(result, batchTime) → output/hourly/optimization/yyyy-MM-dd_HH.dat
```

> anomaly와 optimization 두 Dify 요청은 동일한 logContent를 입력으로 하며 독립적이므로 병렬 실행 가능.

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
| config/setup.properties 파일 없음 (설치 미완료) | SetupNotCompletedException | 배치 중단 + 알림 |
| 파일 읽기 실패 | IOException | 배치 중단 + 알림 |

### 1.6 내부 처리 로직 (의사코드)
```
1. config/setup.properties 파일 존재 여부 확인
2. 없으면 SetupNotCompletedException
3. Properties 파일을 읽어 SetupConfig 객체로 변환하여 반환
```

### 1.7 의존성
- 외부 시스템 호출 없음 (순수 파일 IO)

### 1.8 비고
- **공용 메서드** — 1분/1시간 배치에서 공유 사용. 별도 유틸 클래스로 구현 권장.

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
| 파일이 존재하지 않음 | FileNotFoundException | 배치 중단 + 알림 |
| 읽기 권한 없음 | IOException | 배치 중단 + 알림 |
| dateFormat으로 라인 파싱 실패 | DateTimeParseException | 해당 라인 skip 후 로깅, 또는 배치 중단 (정책 결정 필요) |
| 1시간 범위 내 로그가 0건 | (정상 케이스) | 빈 문자열 반환, 이후 Dify 호출 모두 스킵 |

### 2.6 내부 처리 로직 (의사코드)
```
1. config에서 logFilePath, encoding, dateFormat, timezone 추출
2. 현재 시각 기준 (현재시각 - 1시간) ~ 현재시각 범위 계산 (timezone 적용)
3. logFilePath를 encoding으로 열어 라인 단위 순회
4. 각 라인에서 dateFormat으로 타임스탬프 파싱
5. 파싱된 시각이 범위 내에 있으면 결과에 포함
6. 결과 문자열 반환
```

### 2.7 의존성
- 외부 시스템 호출 없음 (순수 파일 IO + 날짜 파싱)

### 2.8 비고
- 대상 로그 파일이 클 경우 전체 파일을 스캔해야 하므로 성능 이슈 가능 — 역방향 읽기(tail 방식) 최적화 고려.

---

## 3. requestAnomalyAnalysisToDify()

### 3.1 책임
1시간치 로그를 Dify에 전달하여 이상 패턴 분석 결과를 받아온다.

### 3.2 메서드 시그니처
```java
public AnomalyAnalysisResult requestAnomalyAnalysisToDify(String logContent)
```

### 3.3 파라미터

| 이름 | 타입 | 필수 | 설명 | 제약조건 |
|------|------|------|------|----------|
| logContent | String | Y | readLastHourLog()에서 받은 로그 내용 | 빈 문자열이면 호출 스킵 |

### 3.4 반환값

| 타입 | 설명 | 크기 제한 |
|------|------|-----------|
| AnomalyAnalysisResult (커스텀 객체) | 탐지된 이상 패턴 분석 내용을 담은 객체 | **content 필드 1MB 이하 필수** |

**AnomalyAnalysisResult 필드**

| 필드명 | 타입 | 설명 |
|--------|------|------|
| content | String | 이상 패턴 분석 결과 전문 (1MB 이하) |

### 3.5 예외 처리

| 상황 | Exception | 처리 방안 |
|------|-----------|-----------|
| Dify API 호출 실패 | DifyApiException | 재시도 N회 후 배치 중단 + 알림 |
| 응답 스키마 불일치 | ResponseMappingException | 배치 중단 + 알림 |
| 응답 content가 1MB 초과 | AnalysisResultSizeExceededException | 배치 중단 + 알림 |

### 3.6 내부 처리 로직 (의사코드)
```
1. logContent가 빈 문자열이면 스킵
2. logContent를 Dify 이상 패턴 분석 Workflow API 요청 페이로드로 구성
3. Dify에 POST 요청 전송
4. 응답 JSON을 AnomalyAnalysisResult 객체로 매핑
// [중요] content는 반드시 1MB(1,048,576 bytes) 이하여야 한다.
// 초과 시 daily-monitor 통합 분석에 영향을 미치므로 배치를 중단한다.
5. result.content 크기가 1MB 초과이면 AnalysisResultSizeExceededException
6. 매핑된 객체 반환
```

### 3.7 의존성
- 외부 시스템: Dify Workflow API (이상 패턴 분석용)

### 3.8 비고
- **결과 크기 제한 1MB는 Dify 워크플로우 설계 시에도 반드시 반영되어야 한다.**

---

## 4. saveAnomalyResult()

### 4.1 책임
이상 패턴 분석 결과를 `output/hourly/anomaly/`에 .dat 파일로 저장한다.

### 4.2 메서드 시그니처
```java
public void saveAnomalyResult(AnomalyAnalysisResult result, LocalDateTime batchTime)
```

### 4.3 파라미터

| 이름 | 타입 | 필수 | 설명 | 제약조건 |
|------|------|------|------|----------|
| result | AnomalyAnalysisResult | Y | requestAnomalyAnalysisToDify()의 결과 객체 | null 불가, content 1MB 이하 |
| batchTime | LocalDateTime | Y | 배치 실행 시각 (파일명 생성에 사용) | null 불가 |

### 4.4 반환값

| 타입 | 설명 |
|------|------|
| void | 없음 |

### 4.5 저장 파일 규칙

| 항목 | 내용 |
|------|------|
| 저장 경로 | `output/hourly/anomaly/` |
| 파일명 | `yyyy-MM-dd_HH.dat` (예: `2026-06-22_14.dat`) |
| 중복 처리 | 동일 시각 파일 존재 시 덮어쓰기 |

### 4.6 예외 처리

| 상황 | Exception | 처리 방안 |
|------|-----------|-----------|
| 폴더 없음 | IOException | 폴더 자동 생성 후 재시도 |
| 파일 쓰기 실패 | IOException | 배치 실패 처리 + 알림 |

### 4.7 내부 처리 로직 (의사코드)
```
1. output/hourly/anomaly/ 폴더 존재 여부 확인, 없으면 생성
2. batchTime으로 파일명 생성 (yyyy-MM-dd_HH.dat)
3. AnomalyAnalysisResult.content를 파일에 write
4. 저장 완료
```

### 4.8 의존성
- 외부 시스템 호출 없음 (순수 파일 IO)

---

## 5. requestOptimizationAnalysisToDify()

### 5.1 책임
1시간치 로그를 Dify에 전달하여 로그 최적화 인사이트를 받아온다.

### 5.2 메서드 시그니처
```java
public OptimizationAnalysisResult requestOptimizationAnalysisToDify(String logContent)
```

### 5.3 파라미터

| 이름 | 타입 | 필수 | 설명 | 제약조건 |
|------|------|------|------|----------|
| logContent | String | Y | readLastHourLog()에서 받은 로그 내용 | 빈 문자열이면 호출 스킵 |

### 5.4 반환값

| 타입 | 설명 | 크기 제한 |
|------|------|-----------|
| OptimizationAnalysisResult (커스텀 객체) | 로그 최적화 인사이트 내용을 담은 객체 | **content 필드 1MB 이하 필수** |

**OptimizationAnalysisResult 필드**

| 필드명 | 타입 | 설명 |
|--------|------|------|
| content | String | 최적화 인사이트 결과 전문 (1MB 이하) |

### 5.5 예외 처리

| 상황 | Exception | 처리 방안 |
|------|-----------|-----------|
| Dify API 호출 실패 | DifyApiException | 재시도 N회 후 배치 중단 + 알림 |
| 응답 스키마 불일치 | ResponseMappingException | 배치 중단 + 알림 |
| 응답 content가 1MB 초과 | AnalysisResultSizeExceededException | 배치 중단 + 알림 |

### 5.6 내부 처리 로직 (의사코드)
```
1. logContent가 빈 문자열이면 스킵
2. logContent를 Dify 최적화 인사이트 Workflow API 요청 페이로드로 구성
3. Dify에 POST 요청 전송
4. 응답 JSON을 OptimizationAnalysisResult 객체로 매핑
// [중요] content는 반드시 1MB(1,048,576 bytes) 이하여야 한다.
5. result.content 크기가 1MB 초과이면 AnalysisResultSizeExceededException
6. 매핑된 객체 반환
```

### 5.7 의존성
- 외부 시스템: Dify Workflow API (최적화 인사이트용 — 이상 패턴 분석 워크플로우와 별도)

### 5.8 비고
- **결과 크기 제한 1MB는 Dify 워크플로우 설계 시에도 반드시 반영되어야 한다.**

---

## 6. saveOptimizationResult()

### 6.1 책임
최적화 인사이트 결과를 `output/hourly/optimization/`에 .dat 파일로 저장한다.

### 6.2 메서드 시그니처
```java
public void saveOptimizationResult(OptimizationAnalysisResult result, LocalDateTime batchTime)
```

### 6.3 파라미터

| 이름 | 타입 | 필수 | 설명 | 제약조건 |
|------|------|------|------|----------|
| result | OptimizationAnalysisResult | Y | requestOptimizationAnalysisToDify()의 결과 객체 | null 불가, content 1MB 이하 |
| batchTime | LocalDateTime | Y | 배치 실행 시각 (파일명 생성에 사용) | null 불가 |

### 6.4 반환값

| 타입 | 설명 |
|------|------|
| void | 없음 |

### 6.5 저장 파일 규칙

| 항목 | 내용 |
|------|------|
| 저장 경로 | `output/hourly/optimization/` |
| 파일명 | `yyyy-MM-dd_HH.dat` (예: `2026-06-22_14.dat`) |
| 중복 처리 | 동일 시각 파일 존재 시 덮어쓰기 |

### 6.6 예외 처리

| 상황 | Exception | 처리 방안 |
|------|-----------|-----------|
| 폴더 없음 | IOException | 폴더 자동 생성 후 재시도 |
| 파일 쓰기 실패 | IOException | 배치 실패 처리 + 알림 |

### 6.7 내부 처리 로직 (의사코드)
```
1. output/hourly/optimization/ 폴더 존재 여부 확인, 없으면 생성
2. batchTime으로 파일명 생성 (yyyy-MM-dd_HH.dat)
3. OptimizationAnalysisResult.content를 파일에 write
4. 저장 완료
```

### 6.8 의존성
- 외부 시스템 호출 없음 (순수 파일 IO)

---

## 7. 공통 고려사항

- **⚠️ 결과 크기 제한**: anomaly/optimization 두 결과 모두 **content 1MB(1,048,576 bytes) 이하** 필수. 초과 시 배치 중단. Dify 워크플로우 설계 시에도 동일 제약 적용 필요.
- **병렬 실행**: 3번(anomaly)과 5번(optimization) Dify 요청은 동일 입력에 독립적이므로 병렬 실행으로 성능 개선 가능.
- **재시도 정책**: 두 Dify 호출에 대한 재시도 횟수, 백오프 전략 통일 필요.
- **출력 파일 보관**: `output/hourly/anomaly/`, `output/hourly/optimization/` 파일은 daily-monitor 처리 후 `4_daily-monitor`의 deleteOldHourlyFiles()에서 7일 기준으로 정리.
- **로깅**: 각 단계 시작/종료 시점에 로그 남기기.
- **알림**: 배치 실패 시 알림 발송 여부 결정.

---

## 8. 변경 이력

| 버전 | 날짜 | 내용 | 작성자 |
|------|------|------|--------|
| v1.0 | 2026-06-23 | 초안 완성 | |
