# 코딩 컨벤션

Google Java Style Guide를 기반으로 하며, 이 프로젝트에 맞게 일부 규칙을 추가한다.

---

## 1. 네이밍 컨벤션

### 1.1 패키지

- 모두 소문자, 단어 구분 없이 연속 작성
- 예: `com.example.loganalyzer.batch`

### 1.2 클래스 / 인터페이스

| 종류 | 규칙 | 예시 |
|------|------|------|
| 일반 클래스 | UpperCamelCase | `LogAnalysisBatchJob` |
| 인터페이스 | UpperCamelCase | `DifyClient` |
| 예외 클래스 | UpperCamelCase + `Exception` 접미사 | `DifyApiException`, `EmptyLogFileException` |
| Enum | UpperCamelCase | `BatchStatus` |

### 1.3 메서드

- lowerCamelCase
- 동사 또는 동사구로 시작
- 예: `readSampleLog()`, `requestAnalysisFromDify()`, `saveAnalysisResult()`

### 1.4 변수 / 파라미터

- lowerCamelCase
- 의미를 알 수 있는 이름 사용 (단일 문자 변수 금지, 루프 인덱스 `i` 제외)
- 예: `logFilePath`, `dateFormat`, `sampleLogContent`

### 1.5 상수

- UPPER_SNAKE_CASE (`static final`)
- 예: `MAX_SAMPLE_LINES`, `DEFAULT_DATE_FORMAT`

---

## 2. 코드 스타일

### 2.1 들여쓰기 및 공백

- 들여쓰기: **스페이스 4칸** (탭 사용 금지)
- 줄 최대 길이: **120자**
- 연산자 전후 공백 1칸: `int result = a + b;`
- 쉼표 뒤 공백 1칸: `method(a, b, c)`

### 2.2 중괄호

- K&R 스타일 — 여는 중괄호는 같은 줄에
- `if`, `for`, `while` 등 본문이 1줄이어도 중괄호 필수

```java
// Good
if (condition) {
    doSomething();
}

// Bad
if (condition) doSomething();
```

### 2.3 임포트

- 와일드카드 임포트 금지 (`import java.util.*` 금지)
- 미사용 임포트 제거
- 정렬 순서: static → java → javax → org → com → 프로젝트 내부

### 2.4 빈 줄

- 메서드 사이: 빈 줄 1개
- 클래스 내 논리적 블록 구분: 빈 줄 1개
- 연속 빈 줄 2개 이상 금지

### 2.5 주석

- 코드로 의도가 명확한 경우 주석 생략
- 비즈니스 규칙, 외부 제약, 비직관적인 동작에만 주석 작성
- Javadoc은 public API에만 작성 (내부 private 메서드 불필요)

```java
// Bad — 코드가 설명하는 내용 반복
// 파일을 읽어서 반환한다
public String readSampleLog(...) { ... }

// Good — 숨겨진 제약을 설명
// maxLines 초과 시 나머지 라인을 버리므로, 샘플 용도로만 사용할 것
public String readSampleLog(...) { ... }
```

---

## 3. Spring Boot 구조 규칙

### 3.1 레이어 구분

| 레이어 | 클래스 접미사 | 역할 |
|--------|--------------|------|
| 배치 Job | `BatchJob` | 배치 진입점, Step 구성 |
| 서비스 | `Service` | 비즈니스 로직 |
| 외부 연동 | `Client` | Dify 등 외부 API 호출 |
| 저장소 | `Repository` | DB 접근 |
| 도메인 | (접미사 없음) | Entity, VO |
| DTO | `Request` / `Result` / `Response` | 데이터 전달 객체 |

### 3.2 어노테이션 순서 (클래스 레벨)

```java
@Slf4j
@Component          // 또는 @Service, @Repository 등
@RequiredArgsConstructor
public class SomeClass { ... }
```

### 3.3 의존성 주입

- **생성자 주입만 사용** (`@Autowired` 필드 주입 금지)
- `@RequiredArgsConstructor` + `final` 필드 조합 권장

```java
// Good
@RequiredArgsConstructor
public class LogAnalysisService {
    private final DifyClient difyClient;
    private final LogRepository logRepository;
}

// Bad
@Autowired
private DifyClient difyClient;
```

---

## 4. 로깅 규칙

### 4.1 로거 선언

- `@Slf4j` (Lombok) 사용 — 직접 `LoggerFactory.getLogger(...)` 선언 금지
- 로거는 클래스 레벨에서 1개만 선언

```java
@Slf4j
@Service
@RequiredArgsConstructor
public class LogAnalysisService {
    ...
}
```

### 4.2 로그 레벨 기준

| 레벨 | 사용 기준 | 예시 |
|------|-----------|------|
| `ERROR` | 복구 불가능한 오류 — 배치 중단이 필요한 상황 | DB 저장 실패, Dify API 재시도 초과 |
| `WARN` | 복구 가능하나 주의가 필요한 상황 | 파싱 실패한 로그 라인 skip, Dify 응답 지연 |
| `INFO` | 정상 흐름의 주요 이벤트 — 배치 모니터링 기준 | 각 메서드 시작/종료, 처리 건수 |
| `DEBUG` | 개발/디버깅용 상세 정보 — 운영 환경 비활성화 | 요청/응답 페이로드 전문, 파싱 중간값 |
| `TRACE` | 극도로 상세한 추적 — 거의 사용하지 않음 | 라인별 파싱 과정 |

### 4.3 배치 단계별 로깅 패턴

각 메서드의 시작과 종료 시점에 INFO 로그를 남긴다.

```java
// Good
log.info("[readSampleLog] 시작 - filePath={}, maxLines={}", logFilePath, maxLines);
// ... 처리 ...
log.info("[readSampleLog] 완료 - 읽은 라인 수={}", lineCount);

// 예외 발생 시
log.error("[readSampleLog] 파일을 읽을 수 없습니다 - filePath={}", logFilePath, e);
```

### 4.4 로그 메시지 작성 규칙

- **파라미터는 `{}` 플레이스홀더** 사용 — 문자열 연결(`+`) 금지 (불필요한 문자열 생성 방지)
- 예외 로깅 시 **반드시 예외 객체를 마지막 인자**로 전달 (스택 트레이스 출력)
- 민감 정보(API 키, 로그 원문 전체 등)는 DEBUG 이하 레벨에서만 출력

```java
// Bad
log.info("파일 경로: " + logFilePath);
log.error("오류 발생: " + e.getMessage());

// Good
log.info("파일 경로: {}", logFilePath);
log.error("파일 읽기 실패 - filePath={}", logFilePath, e);
```

### 4.5 운영 환경 로그 레벨 설정

```properties
# application-prod.properties
logging.level.root=WARN
logging.level.com.example.loganalyzer=INFO
```

```properties
# application-dev.properties
logging.level.root=INFO
logging.level.com.example.loganalyzer=DEBUG
```

---

## 5. 설정값 관리

### 5.1 원칙

- 환경마다 달라질 수 있는 값은 코드에 하드코딩 금지
- 모든 설정값은 `application.properties` (또는 프로파일별 파일)에서 관리
- 시크릿(API 키, DB 패스워드 등)은 환경변수 또는 AWS Secrets Manager로 주입 — 설정 파일에 평문 저장 금지

### 5.2 @Value vs @ConfigurationProperties

| 구분 | 사용 기준 |
|------|-----------|
| `@Value` | 단일 값 1~2개를 간단히 주입할 때 |
| `@ConfigurationProperties` | 관련 설정이 3개 이상이거나 그룹으로 묶이는 경우 (권장) |

관련 설정은 `@ConfigurationProperties`로 묶어 타입 안전성과 IDE 자동완성을 확보한다.

```java
// Good — 관련 설정을 하나의 클래스로 묶음
@ConfigurationProperties(prefix = "log-analyzer.batch")
@Validated
public record BatchProperties(
    @NotNull String logFilePath,
    @Min(1) int sampleLines,
    @NotNull Duration analysisWindow
) {}
```

```properties
# application.properties
log-analyzer.batch.log-file-path=/var/log/app/application.log
log-analyzer.batch.sample-lines=100
log-analyzer.batch.analysis-window=PT1H
```

### 5.3 프로퍼티 네이밍

- **kebab-case** 사용 (`logFilePath` → `log-file-path`)
- 프로젝트 전용 프로퍼티는 고유 prefix로 네임스페이스 구분
  - 예: `log-analyzer.batch.*`, `log-analyzer.dify.*`

### 5.4 프로파일 분리

```
src/main/resources/
├── application.properties          ← 공통 설정 (프로파일 무관)
├── application-local.properties    ← 로컬 개발용
├── application-dev.properties      ← 개발 서버
└── application-prod.properties     ← 운영 서버
```

- 공통값은 `application.properties`에, 환경별 오버라이드만 각 프로파일 파일에 작성
- 운영 프로파일 파일에 시크릿 값 직접 기입 금지

### 5.5 설정값 검증

`@ConfigurationProperties` 클래스에 `@Validated`를 적용하여 애플리케이션 기동 시점에 잘못된 설정을 즉시 감지한다.

```java
@ConfigurationProperties(prefix = "log-analyzer.dify")
@Validated
public record DifyProperties(
    @NotBlank String apiUrl,
    @NotBlank String apiKey,
    @Min(1) @Max(10) int maxRetries
) {}
```

---

## 6. 변경 이력

| 날짜 | 내용 |
|------|------|
| 2026-06-23 | 초안 완성 |
