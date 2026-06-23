# 설계서: 시스템 설치

## 0. 개요

| 항목 | 내용 |
|------|------|
| 배치 이름 | LogAnalysisSetupJob (가칭) |
| 실행 시점 | 최초 1회 (시스템 설치 시) |
| 목적 | 로그 파일을 분석하여 이후 배치 실행에 필요한 설정값을 탐지하고 저장 |
| 작성일 | 2026-06-22 |

### 전체 처리 흐름

```
configureLogFilePath()
        ↓ (설정된 경로)
detectEncoding()
        ↓ (인코딩)
readSampleLog()
        ↓ (샘플 로그)
requestDateFormatToDify()
        ↓ (날짜 형식)
detectTimezone()
        ↓ (타임존)
saveSetupConfig()
```

---

## 1. configureLogFilePath()

### 1.1 책임
사용자가 입력한 로그 파일 경로를 분석 대상으로 설정한다.

### 1.2 메서드 시그니처
```java
public String configureLogFilePath(String logFilePath)
```

### 1.3 파라미터

| 이름 | 타입 | 필수 | 설명 | 제약조건 |
|------|------|------|------|----------|
| logFilePath | String | Y | 분석할 로그 파일 경로 | null 또는 빈 문자열 불가 |

### 1.4 반환값

| 타입 | 설명 |
|------|------|
| String | 설정된 로그 파일 절대 경로 |

### 1.5 예외 처리

| 상황 | Exception | 처리 방안 |
|------|-----------|-----------|
| 파일이 존재하지 않음 | FileNotFoundException | 설치 중단 + 오류 메시지 출력 |
| 읽기 권한 없음 | IOException | 설치 중단 + 오류 메시지 출력 |
| 디렉터리를 경로로 입력함 | IllegalArgumentException | 설치 중단 + 오류 메시지 출력 |

### 1.6 내부 처리 로직 (의사코드)
```
1. logFilePath가 null 또는 빈 문자열이면 예외
2. 해당 경로의 File 객체 생성
3. 파일 존재 여부 확인
4. 디렉터리 여부 확인 (파일이어야 함)
5. 읽기 권한 확인
6. 절대 경로로 변환하여 반환
```

### 1.7 의존성
- 외부 시스템 호출 없음 (순수 파일 IO)

---

## 2. detectEncoding()

### 2.1 책임
로그 파일의 문자 인코딩을 탐지한다.

### 2.2 메서드 시그니처
```java
public String detectEncoding(String logFilePath)
```

### 2.3 파라미터

| 이름 | 타입 | 필수 | 설명 | 제약조건 |
|------|------|------|------|----------|
| logFilePath | String | Y | 검증된 로그 파일 경로 | configureLogFilePath()를 통과한 경로 |

### 2.4 반환값

| 타입 | 설명 | 예시 |
|------|------|------|
| String | 탐지된 인코딩 이름 | `"UTF-8"`, `"EUC-KR"` |

### 2.5 예외 처리

| 상황 | Exception | 처리 방안 |
|------|-----------|-----------|
| 파일 읽기 실패 | IOException | 설치 중단 |
| 인코딩 탐지 불가 | (정상 케이스) | 기본값 UTF-8 사용 후 경고 로그 출력 |

### 2.6 내부 처리 로직 (의사코드)
```
1. 파일 앞부분 바이트 읽기 (BOM 또는 문자 패턴 분석용)
2. 인코딩 탐지 라이브러리로 인코딩 추론 (예: juniversalchardet)
3. 탐지 성공 시 해당 인코딩 반환
4. 탐지 실패 시 UTF-8 반환 + 경고 로그
```

### 2.7 의존성
- 인코딩 탐지 라이브러리 (예: juniversalchardet 또는 ICU4J — 라이브러리 미확정)

### 2.8 비고
- BOM(Byte Order Mark)이 있는 파일은 BOM으로 UTF-8/UTF-16 등을 판별 가능.
- 탐지 라이브러리 없이 구현할 경우 BOM 여부만 확인하고 나머지는 UTF-8 기본값으로 처리하는 단순화 방안도 검토.

---

## 3. readSampleLog()

### 3.1 책임
인코딩 정보를 적용하여 로그 파일의 앞부분 일부 라인을 읽는다.

### 3.2 메서드 시그니처
```java
public String readSampleLog(String logFilePath, String encoding, int maxLines)
```

### 3.3 파라미터

| 이름 | 타입 | 필수 | 설명 | 제약조건 |
|------|------|------|------|----------|
| logFilePath | String | Y | 검증된 로그 파일 경로 | - |
| encoding | String | Y | detectEncoding()에서 탐지한 인코딩 | 유효한 Charset 이름 |
| maxLines | int | Y | 읽을 최대 라인 수 | 100 (기본값) |

### 3.4 반환값

| 타입 | 설명 |
|------|------|
| String | 샘플로 읽은 로그 라인들을 합친 문자열 (줄바꿈 포함) |

### 3.5 예외 처리

| 상황 | Exception | 처리 방안 |
|------|-----------|-----------|
| 파일이 비어있음 | EmptyLogFileException | 설치 중단 |
| 파일 읽기 실패 | IOException | 설치 중단 |

### 3.6 내부 처리 로직 (의사코드)
```
1. 지정된 encoding으로 파일 스트림 열기
2. 라인 단위로 읽기 시작
3. maxLines 도달하거나 EOF에 도달하면 중단
4. 읽은 라인이 0개이면 EmptyLogFileException
5. 읽은 라인들을 하나의 문자열로 결합하여 반환
```

### 3.7 의존성
- 외부 시스템 호출 없음 (순수 파일 IO)

### 3.8 비고
- `maxLines`는 하드코딩 대신 `application.properties` 설정값으로 분리 권장.

---

## 4. requestDateFormatToDify()

### 4.1 책임
샘플 로그를 Dify에 전달하여 날짜/시간 형식을 추론받는다.

### 4.2 메서드 시그니처
```java
public String requestDateFormatToDify(String sampleLogContent)
```

### 4.3 파라미터

| 이름 | 타입 | 필수 | 설명 | 제약조건 |
|------|------|------|------|----------|
| sampleLogContent | String | Y | readSampleLog()에서 받은 샘플 로그 | 빈 문자열 불가 |

### 4.4 반환값

| 타입 | 설명 | 예시 |
|------|------|------|
| String | 추론된 날짜 형식 패턴 | `"yyyy-MM-dd HH:mm:ss"` |

### 4.5 예외 처리

| 상황 | Exception | 처리 방안 |
|------|-----------|-----------|
| Dify API 호출 실패 (timeout, 5xx) | DifyApiException | 재시도 N회 후 설치 중단 |
| 응답에서 날짜 형식 파싱 불가 | DateFormatParseException | 설치 중단 |

### 4.6 내부 처리 로직 (의사코드)
```
1. sampleLogContent를 Dify Workflow API 요청 페이로드로 구성
2. Dify에 POST 요청 전송
3. 응답에서 날짜 형식 패턴 추출
4. 추출된 패턴이 유효한 SimpleDateFormat 형식인지 검증
5. 반환
```

### 4.7 의존성
- 외부 시스템: Dify Workflow API

### 4.8 비고
- 설치 시 1회만 호출. 결과는 `saveSetupConfig()`를 통해 `config/setup.properties`에 저장되므로 이후 배치에서 재호출 불필요.

---

## 5. detectTimezone()

### 5.1 책임
샘플 로그에서 타임존 정보를 파싱하거나, 파싱이 불가능한 경우 시스템 기본 타임존을 사용한다.

### 5.2 메서드 시그니처
```java
public String detectTimezone(String sampleLogContent, String dateFormat)
```

### 5.3 파라미터

| 이름 | 타입 | 필수 | 설명 | 제약조건 |
|------|------|------|------|----------|
| sampleLogContent | String | Y | 샘플 로그 문자열 | 빈 문자열 불가 |
| dateFormat | String | Y | requestDateFormatToDify()에서 받은 날짜 형식 | 유효한 날짜 패턴 |

### 5.4 반환값

| 타입 | 설명 | 예시 |
|------|------|------|
| String | 타임존 ID | `"Asia/Seoul"`, `"UTC"` |

### 5.5 예외 처리

| 상황 | Exception | 처리 방안 |
|------|-----------|-----------|
| 타임존 파싱 실패 | (정상 케이스) | 시스템 기본 타임존 사용 + 경고 로그 출력 |

### 5.6 내부 처리 로직 (의사코드)
```
1. sampleLogContent에서 dateFormat으로 타임스탬프 파싱 시도
2. 파싱된 타임스탬프에 타임존 정보(오프셋 또는 ZoneId)가 포함되어 있으면 추출하여 반환
3. 타임존 정보가 없으면 시스템 기본 타임존(ZoneId.systemDefault()) 반환 + 경고 로그
```

### 5.7 의존성
- 외부 시스템 호출 없음

### 5.8 비고
- 로그 타임스탬프에 타임존이 명시되어 있지 않은 경우가 많음. 이때 시스템 타임존을 기본값으로 쓰되, 운영 환경(EC2)의 타임존이 로그 서버와 일치하는지 사전 확인 필요.

---

## 6. saveSetupConfig()

### 6.1 책임
탐지된 설정값들을 저장소에 저장한다.

### 6.2 메서드 시그니처
```java
public void saveSetupConfig(SetupConfig config)
```

### 6.3 파라미터

| 이름 | 타입 | 필수 | 설명 |
|------|------|------|------|
| config | SetupConfig | Y | 로그 파일 경로, 인코딩, 날짜 형식, 타임존을 담은 설정 객체 |

**SetupConfig 필드 → properties 키 매핑**

| 필드명 | properties 키 | 예시 값 |
|--------|--------------|---------|
| logFilePath | `setup.log-file-path` | `/var/log/app/application.log` |
| encoding | `setup.encoding` | `UTF-8` |
| dateFormat | `setup.date-format` | `yyyy-MM-dd HH:mm:ss` |
| timezone | `setup.timezone` | `Asia/Seoul` |

**저장 파일 경로**: `config/setup.properties` (프로젝트 루트 기준)

### 6.4 반환값

| 타입 | 설명 |
|------|------|
| void | 없음 (저장 성공/실패는 예외로 판단) |

### 6.5 예외 처리

| 상황 | Exception | 처리 방안 |
|------|-----------|-----------|
| 파일 쓰기 실패 (권한, 경로 없음 등) | IOException | 설치 중단 |

### 6.6 내부 처리 로직 (의사코드)
```
1. config/setup.properties 경로의 파일 생성 (없으면 신규, 있으면 덮어쓰기)
2. SetupConfig 각 필드를 properties 키-값 형태로 변환
3. Properties 객체에 저장 후 파일에 write
4. 저장 완료
```

### 6.7 의존성
- 외부 시스템 호출 없음 (순수 파일 IO)

### 6.8 비고
- 저장된 설정은 이후 시간 단위 배치, 실시간 모니터링 배치에서 공통으로 참조함.
- 재설치 시 기존 파일을 덮어쓰는 방식으로 처리.

---

## 7. 공통 고려사항

- **실행 시점**: 최초 1회만 실행. 재실행 시 `config/setup.properties`를 덮어쓴다.
- **설정 저장 위치**: `config/setup.properties` (프로젝트 루트 기준). `config/` 디렉터리가 없으면 생성 필요.
- **이후 배치와의 관계**: 시간 단위 배치와 실시간 모니터링 배치는 이 설치 Job이 완료된 이후에만 실행 가능하도록 사전 조건 처리 필요.

---

## 8. 변경 이력

| 버전 | 날짜 | 내용 | 작성자 |
|------|------|------|--------|
| v1.0 | 2026-06-23 | 초안 완성 | |
