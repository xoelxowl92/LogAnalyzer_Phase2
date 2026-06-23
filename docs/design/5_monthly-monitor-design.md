# 설계서: 월 단위 모니터링 배치

> **⚠️ 개발 보류 — 향후 계획**
> 아래 기술적 고려사항으로 인해 현재 개발을 보류한다. 우선순위가 확정되면 재검토한다.

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

## 4. 개발 보류 사유 — 기술적 고려사항

전월 daily .dat 파일의 총 용량이 10MB를 초과할 경우 Dify 전송 전에 청킹 처리가 필요하다.
이 과정에서 아래 항목들의 구현 공수가 크다고 판단하여 개발을 보류한다.

| 항목 | 내용 | 난이도 |
|------|------|--------|
| 청킹 로직 | `List<String>`을 9MB 단위로 분할 (일자 경계 보존 필요) | 중간 |
| N번 Dify 호출 | 청크별 개별 호출 및 재시도 처리 | 중간 |
| 부분 결과 병합 | N개의 Dify 응답을 하나의 월간 보고서로 합산 — Java 단 처리 또는 Dify 추가 워크플로우 필요 | 높음 |
| 일자 경계 처리 | 청킹 시 특정 일자 데이터가 두 청크에 걸치지 않도록 보장 | 중간 |

**향후 검토 시 우선 판단할 것:**
- 실제로 monthly daily 파일 합산이 10MB를 초과하는 경우가 발생하는가 (운영 데이터 확인 필요)
- 초과 시 단순 배치 중단 + 알림으로 처리할지, 청킹 구현을 할지

---

## 5. 공통 고려사항

- **실행 시점**: 전월 daily 배치가 모두 완료된 이후 실행 — 매월 1일 새벽 2시 이후 권장.
- **재시도 정책**: Dify 호출(2번)에 대한 재시도 횟수, 백오프 전략 정의 필요.
- **메일 발송 실패**: Dify MCP를 통한 메일 발송 실패 시 별도 알림 수단 검토 필요.
- **monthly 파일 보관 기간**: `output/monthly/` 파일 보관 정책 미정 — 용량 대비 장기 보관 가능하나 정책 결정 필요.
- **로깅**: 각 단계 시작/종료 시점 및 처리된 daily 파일 수를 INFO로 남길 것.

---

## 6. 변경 이력

| 버전 | 날짜 | 내용 | 작성자 |
|------|------|------|--------|
| v1.0 | 2026-06-23 | 초안 완성 | |
