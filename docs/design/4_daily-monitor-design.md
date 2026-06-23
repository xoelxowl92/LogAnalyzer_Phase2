# 설계서: 일 단위 모니터링 배치

## 0. 개요

| 항목 | 내용 |
|------|------|
| 배치 이름 | LogAnalysisDailyMonitorJob (가칭) |
| 실행 주기 | 1일 단위 (매일 자정 또는 익일 새벽 — 확정 필요) |
| 대상 날짜 | 전일 기준 (실행 시각 기준 전날) |
| 목적 | 전일 hourly 결과를 anomaly/optimization으로 분리하여 각각 Dify에 전달 — anomaly는 MCP 알림 + 파일 저장, optimization은 파일 저장 |
| 작성일 | 2026-06-22 |

### 전체 처리 흐름

```
readHourlyAnomalyResults(targetDate) ──────────────────────────────┐
                                                                    ├─ requestDailyAnomalyToDify(anomalyContents, targetDate)
readHourlyOptimizationResults(targetDate) ─┐                       │         ↓ (이상 패턴 일일 보고 — 알림은 Dify MCP에서 처리)
                                           │               saveDailyAnomalyResult(result, targetDate)
                                           │                        → output/daily/anomaly/yyyy-MM-dd.dat
                                           │
                                           └─ requestDailyOptimizationToDify(optimizationContents, targetDate)
                                                     ↓ (최적화 인사이트)
                                             saveDailyOptimizationResult(result, targetDate)
                                                     → output/daily/optimization/yyyy-MM-dd.dat
↓ (anomaly/optimization 완료 후)
deleteOldHourlyFiles()
```

---

## 1. readHourlyAnomalyResults()

### 1.1 책임
`output/hourly/anomaly/`에서 대상 날짜의 .dat 파일들을 읽어 내용 목록으로 반환한다.

### 1.2 메서드 시그니처
```java
public List<String> readHourlyAnomalyResults(LocalDate targetDate)
```

### 1.3 파라미터

| 이름 | 타입 | 필수 | 설명 | 제약조건 |
|------|------|------|------|----------|
| targetDate | LocalDate | Y | 읽을 대상 날짜 (전일) | null 불가 |

### 1.4 반환값

| 타입 | 설명 |
|------|------|
| List\<String\> | anomaly .dat 파일 내용 목록 (시각 오름차순). 파일 0건이면 빈 리스트 반환 |

### 1.5 예외 처리

| 상황 | Exception | 처리 방안 |
|------|-----------|-----------|
| 폴더 없음 | FileNotFoundException | 배치 중단 + 알림 |
| 파일 읽기 실패 | IOException | 배치 중단 + 알림 |
| 해당 날짜 파일 0건 | (정상 케이스) | 빈 리스트 반환, 이후 Dify 호출 스킵 |

### 1.6 내부 처리 로직 (의사코드)
```
1. output/hourly/anomaly/에서 targetDate 해당 .dat 파일 목록 조회 및 오름차순 정렬
2. 각 파일 읽어 contents 리스트에 추가
3. 리스트 반환
```

### 1.7 의존성
- 외부 시스템 호출 없음 (순수 파일 IO)

### 1.8 비고
- 최대 24개 파일. 일부 시간대 파일이 없는 경우 있는 파일만 읽고 계속 진행.

---

## 2. readHourlyOptimizationResults()

### 2.1 책임
`output/hourly/optimization/`에서 대상 날짜의 .dat 파일들을 읽어 내용 목록으로 반환한다.

### 2.2 메서드 시그니처
```java
public List<String> readHourlyOptimizationResults(LocalDate targetDate)
```

### 2.3 파라미터

| 이름 | 타입 | 필수 | 설명 | 제약조건 |
|------|------|------|------|----------|
| targetDate | LocalDate | Y | 읽을 대상 날짜 (전일) | null 불가 |

### 2.4 반환값

| 타입 | 설명 |
|------|------|
| List\<String\> | optimization .dat 파일 내용 목록 (시각 오름차순). 파일 0건이면 빈 리스트 반환 |

### 2.5 예외 처리

| 상황 | Exception | 처리 방안 |
|------|-----------|-----------|
| 폴더 없음 | FileNotFoundException | 배치 중단 + 알림 |
| 파일 읽기 실패 | IOException | 배치 중단 + 알림 |
| 해당 날짜 파일 0건 | (정상 케이스) | 빈 리스트 반환, 이후 Dify 호출 스킵 |

### 2.6 내부 처리 로직 (의사코드)
```
1. output/hourly/optimization/에서 targetDate 해당 .dat 파일 목록 조회 및 오름차순 정렬
2. 각 파일 읽어 contents 리스트에 추가
3. 리스트 반환
```

### 2.7 의존성
- 외부 시스템 호출 없음 (순수 파일 IO)

### 2.8 비고
- 최대 24개 파일. 일부 시간대 파일이 없는 경우 있는 파일만 읽고 계속 진행.
- 1번과 독립적으로 실행 가능 — 병렬 처리로 성능 개선 가능.

---

## 3. requestDailyAnomalyToDify()

### 3.1 책임
하루치 anomaly 결과를 Dify에 전달하여 이상 패턴 일일 보고를 요청한다. 알림 발송은 Dify 워크플로우 내 MCP를 통해 처리되며, 결과는 Java단에서 파일로 저장한다.

### 3.2 메서드 시그니처
```java
public DailyAnomalyResult requestDailyAnomalyToDify(List<String> anomalyContents, LocalDate targetDate)
```

### 3.3 파라미터

| 이름 | 타입 | 필수 | 설명 | 제약조건 |
|------|------|------|------|----------|
| anomalyContents | List\<String\> | Y | hourly anomaly .dat 내용 목록 | 빈 리스트이면 호출 스킵 |
| targetDate | LocalDate | Y | 보고 대상 날짜 | null 불가 |

### 3.4 반환값

| 타입 | 설명 |
|------|------|
| DailyAnomalyResult (커스텀 객체) | 일일 이상 패턴 보고 내용을 담은 객체 |

**DailyAnomalyResult 필드**

| 필드명 | 타입 | 설명 |
|--------|------|------|
| content | String | 일일 이상 패턴 보고 전문 |
| reportDate | LocalDate | 보고서 대상 날짜 |

### 3.5 예외 처리

| 상황 | Exception | 처리 방안 |
|------|-----------|-----------|
| Dify API 호출 실패 | DifyApiException | 재시도 N회 후 배치 중단 + 알림 |
| 응답 스키마 불일치 | ResponseMappingException | 배치 중단 + 알림 |

### 3.6 내부 처리 로직 (의사코드)
```
1. anomalyContents가 빈 리스트이면 스킵
2. anomalyContents와 targetDate를 Dify 이상 패턴 일일 보고 Workflow API 요청 페이로드로 구성
3. Dify에 POST 요청 전송
// 알림 발송은 Dify 워크플로우 내 MCP 서버를 통해 처리 (Java 코드에서 별도 처리 불필요)
4. 응답을 DailyAnomalyResult 객체로 매핑하여 반환
```

### 3.7 의존성
- 외부 시스템: Dify Workflow API (daily anomaly 보고용)

### 3.8 비고
- 알림 발송 성공 여부는 Dify 응답에 포함되도록 워크플로우 설계 권장.

---

## 4. saveDailyAnomalyResult()

### 4.1 책임
일일 이상 패턴 보고 결과를 `output/daily/anomaly/`에 .dat 파일로 저장한다.

### 4.2 메서드 시그니처
```java
public void saveDailyAnomalyResult(DailyAnomalyResult result, LocalDate targetDate)
```

### 4.3 파라미터

| 이름 | 타입 | 필수 | 설명 | 제약조건 |
|------|------|------|------|----------|
| result | DailyAnomalyResult | Y | requestDailyAnomalyToDify()의 결과 객체 | null 불가 |
| targetDate | LocalDate | Y | 보고서 대상 날짜 (파일명 생성에 사용) | null 불가 |

### 4.4 반환값

| 타입 | 설명 |
|------|------|
| void | 없음 |

### 4.5 저장 파일 규칙

| 항목 | 내용 |
|------|------|
| 저장 경로 | `output/daily/anomaly/` |
| 파일명 | `yyyy-MM-dd.dat` (예: `2026-06-22.dat`) |
| 중복 처리 | 동일 날짜 파일 존재 시 덮어쓰기 |

### 4.6 예외 처리

| 상황 | Exception | 처리 방안 |
|------|-----------|-----------|
| output/daily/anomaly/ 폴더 없음 | IOException | 폴더 자동 생성 후 재시도 |
| 파일 쓰기 실패 | IOException | 배치 실패 처리 + 알림 |

### 4.7 내부 처리 로직 (의사코드)
```
1. output/daily/anomaly/ 폴더 존재 여부 확인, 없으면 생성
2. targetDate로 파일명 생성 (yyyy-MM-dd.dat)
3. DailyAnomalyResult.content를 파일에 write (기존 파일 있으면 덮어쓰기)
4. 저장 완료
```

### 4.8 의존성
- 외부 시스템 호출 없음 (순수 파일 IO)

---

## 5. requestDailyOptimizationToDify()

### 5.1 책임
하루치 optimization 결과를 Dify에 전달하여 일일 최적화 인사이트를 받아온다.

### 5.2 메서드 시그니처
```java
public DailyOptimizationResult requestDailyOptimizationToDify(List<String> optimizationContents, LocalDate targetDate)
```

### 5.3 파라미터

| 이름 | 타입 | 필수 | 설명 | 제약조건 |
|------|------|------|------|----------|
| optimizationContents | List\<String\> | Y | hourly optimization .dat 내용 목록 | 빈 리스트이면 호출 스킵 |
| targetDate | LocalDate | Y | 보고 대상 날짜 | null 불가 |

### 5.4 반환값

| 타입 | 설명 |
|------|------|
| DailyOptimizationResult (커스텀 객체) | 일일 최적화 인사이트 내용을 담은 객체 |

**DailyOptimizationResult 필드**

| 필드명 | 타입 | 설명 |
|--------|------|------|
| content | String | 일일 최적화 인사이트 전문 |
| reportDate | LocalDate | 보고서 대상 날짜 |

### 5.5 예외 처리

| 상황 | Exception | 처리 방안 |
|------|-----------|-----------|
| Dify API 호출 실패 | DifyApiException | 재시도 N회 후 배치 중단 + 알림 |
| 응답 스키마 불일치 | ResponseMappingException | 배치 중단 + 알림 |

### 5.6 내부 처리 로직 (의사코드)
```
1. optimizationContents가 빈 리스트이면 스킵 (빈 DailyOptimizationResult 반환)
2. optimizationContents와 targetDate를 Dify 최적화 인사이트 Workflow API 요청 페이로드로 구성
3. Dify에 POST 요청 전송
4. 응답을 DailyOptimizationResult 객체로 매핑하여 반환
```

### 5.7 의존성
- 외부 시스템: Dify Workflow API (daily optimization용 — daily anomaly 워크플로우와 별도)

---

## 6. saveDailyOptimizationResult()

### 6.1 책임
일일 최적화 인사이트 결과를 `output/daily/optimization/`에 .dat 파일로 저장한다.

### 6.2 메서드 시그니처
```java
public void saveDailyOptimizationResult(DailyOptimizationResult result, LocalDate targetDate)
```

### 6.3 파라미터

| 이름 | 타입 | 필수 | 설명 | 제약조건 |
|------|------|------|------|----------|
| result | DailyOptimizationResult | Y | requestDailyOptimizationToDify()의 결과 객체 | null 불가 |
| targetDate | LocalDate | Y | 보고서 대상 날짜 (파일명 생성에 사용) | null 불가 |

### 6.4 반환값

| 타입 | 설명 |
|------|------|
| void | 없음 |

### 6.5 저장 파일 규칙

| 항목 | 내용 |
|------|------|
| 저장 경로 | `output/daily/optimization/` |
| 파일명 | `yyyy-MM-dd.dat` (예: `2026-06-22.dat`) |
| 중복 처리 | 동일 날짜 파일 존재 시 덮어쓰기 |

### 6.6 예외 처리

| 상황 | Exception | 처리 방안 |
|------|-----------|-----------|
| output/daily/optimization/ 폴더 없음 | IOException | 폴더 자동 생성 후 재시도 |
| 파일 쓰기 실패 | IOException | 배치 실패 처리 + 알림 |

### 6.7 내부 처리 로직 (의사코드)
```
1. output/daily/optimization/ 폴더 존재 여부 확인, 없으면 생성
2. targetDate로 파일명 생성 (yyyy-MM-dd.dat)
3. DailyOptimizationResult.content를 파일에 write (기존 파일 있으면 덮어쓰기)
4. 저장 완료
```

### 6.8 의존성
- 외부 시스템 호출 없음 (순수 파일 IO)

### 6.9 비고
- 저장된 파일은 월 단위 배치(`5_monthly-monitor`)에서 통합 분석에 사용 (현재 개발 보류).

---

## 7. deleteOldHourlyFiles()

### 7.1 책임
`output/hourly/anomaly/`와 `output/hourly/optimization/`에서 7일이 지난 .dat 파일을 삭제한다.

### 7.2 메서드 시그니처
```java
public void deleteOldHourlyFiles(LocalDate baseDate)
```

### 7.3 파라미터

| 이름 | 타입 | 필수 | 설명 | 제약조건 |
|------|------|------|------|----------|
| baseDate | LocalDate | Y | 기준일 (오늘 날짜) | null 불가 |

### 7.4 반환값

| 타입 | 설명 |
|------|------|
| void | 없음 |

### 7.5 삭제 기준

| 항목 | 내용 |
|------|------|
| anomaly 삭제 기준 | 파일명 날짜 기준 `baseDate - 7일` 이전 |
| optimization 삭제 기준 | 파일명 날짜 기준 `baseDate - 7일` 이전 |
| 삭제 범위 | `output/hourly/anomaly/`, `output/hourly/optimization/` 두 폴더 |

### 7.6 예외 처리

| 상황 | Exception | 처리 방안 |
|------|-----------|-----------|
| 폴더 없음 | (정상 케이스) | 경고 로그 후 스킵 |
| 개별 파일 삭제 실패 | IOException | 해당 파일 ERROR 로그 후 나머지 파일 계속 처리 |

### 7.7 내부 처리 로직 (의사코드)
```
1. output/hourly/anomaly/ 파일 목록 조회 → 날짜 파싱 → (baseDate - 7일) 이전이면 삭제
2. output/hourly/optimization/ 파일 목록 조회 → 날짜 파싱 → (baseDate - 7일) 이전이면 삭제
3. 삭제 성공 시 INFO 로그, 실패 시 ERROR 로그 후 계속 진행
```

### 7.8 의존성
- 외부 시스템 호출 없음 (순수 파일 IO)

### 7.9 비고
- 개별 파일 삭제 실패가 전체 배치를 중단시키지 않도록 처리.

---

## 8. 공통 고려사항

- **실행 시점**: 전일 hourly 배치가 모두 완료된 이후 실행 — 새벽 1시 이후 권장.
- **병렬 실행**: 1·2번(읽기)과 3·5번(Dify 요청)은 각각 독립적이므로 병렬 실행으로 성능 개선 가능.
- **재시도 정책**: 두 Dify 호출에 대한 재시도 횟수, 백오프 전략 통일 필요.
- **알림 발송 실패**: Dify MCP를 통한 알림 발송 실패 시 별도 수단 검토 필요.
- **파일 보관 정책**: hourly 결과(anomaly/optimization) 7일, daily 결과(anomaly/optimization) 90일. daily 파일 정리 로직은 별도 구현 필요 (현재 미정).
- **로깅**: 각 단계 시작/종료 시점 및 삭제된 파일 목록을 INFO로 남길 것.

---

## 9. 변경 이력

| 버전 | 날짜 | 내용 | 작성자 |
|------|------|------|--------|
| v1.0 | 2026-06-23 | 초안 완성 | |
