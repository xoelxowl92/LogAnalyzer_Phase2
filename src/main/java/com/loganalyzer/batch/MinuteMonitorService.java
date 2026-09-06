package com.loganalyzer.batch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loganalyzer.dify.DifyApiException;
import com.loganalyzer.dify.DifyClientErrorException;
import com.loganalyzer.dify.DifyMode;
import com.loganalyzer.dify.DifyProperties;
import com.loganalyzer.dify.ResponseMappingException;
import com.loganalyzer.setup.SetupConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Properties;

import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

@Slf4j
@Service
@RequiredArgsConstructor
public class MinuteMonitorService {

    private static final String RESULT_DIR = "output/minute";

    private final DifyProperties difyProperties;

    /**
     * 1분 단위로 실행되는 실시간 장애 감지 배치의 진입점 (F-02).
     * 최근 1분 구간 로그를 추출 → Dify에 장애 판단 요청 → 결과를 파일로 저장하는 순서로 처리한다.
     */
    public void execute() {

            log.info("[MinuteMonitor] 실행 시작");


            // 1. 설정 읽기 (로그 파일 경로, 인코딩, 날짜 형식, 타임존)
            SetupConfig config = loadSetupConfig();

            log.info(
                "[MinuteMonitor] 로그 경로 : {}",
                config.getLogFilePath()
            );

            // 2. 최근 1분 구간(now-80s ~ now-20s) 로그 추출
            String logContent = readLastMinuteLog(config);


            // 3. 해당 구간 로그가 0건이면 Dify 호출 없이 종료 (F-02 제약사항)
            if (logContent == null ||
                logContent.trim().isEmpty()) {
                log.info(
                    "[MinuteMonitor] 분석 대상 로그 없음"
                );
                return;
            }

            log.info(
                "[MinuteMonitor] Dify 요청 데이터 size={}",
                logContent.length()
            );

            // 4. Dify Workflow에 장애 판단 요청 (실패 시 재시도 후 DifyApiException)
            FaultCheckResult result =
                    requestFaultCheckToDify(logContent);

            // 5. 장애 여부와 무관하게 매 실행 결과를 파일로 저장
            saveFaultCheckResult(result, LocalDateTime.now());

            if(result.isFault()) {

                log.error(
                    "[MinuteMonitor] 장애 감지 : {}",
                    result.getSummary()
                );

            } else {

                log.info(
                    "[MinuteMonitor] 정상 : {}",
                    result.getSummary()
                );
            }

        }



    /**
     * config/setup.properties에서 초기 설정값을 읽어온다.
     * HourlyMonitorService와 동일한 설정 파일을 공유하는 공용 메서드다.
     */
    public SetupConfig loadSetupConfig() {
        // 공용 메서드 - HourlyMonitorService와 동일

        File configFile = new File("config/setup.properties");

        // F-01(시스템 설치) 완료 여부를 파일 존재로 판단한다.
        if (!configFile.exists()) {
            throw new IllegalStateException(
                "초기 설정이 완료되지 않았습니다 : config/setup.properties 없음"
            );
        }

        Properties props = new Properties();

        try (InputStream is = new FileInputStream(configFile)) {
            props.load(is);
        } catch (IOException e) {
            throw new RuntimeException(
                "setup.properties 로딩 실패",
                e
            );
        }

        SetupConfig config = new SetupConfig();

        config.setLogFilePath(props.getProperty("setup.log-file-path"));
        config.setEncoding(props.getProperty("setup.encoding"));
        config.setDateFormat(props.getProperty("setup.date-format"));
        config.setTimezone(props.getProperty("setup.timezone"));

        return config;
    }

    /**
     * 설정된 로그 파일에서 (현재 시각 - 80초) ~ (현재 시각 - 20초) 구간에 속하는 라인만 추출한다.
     * 각 라인의 앞부분을 타임스탬프로 파싱해 구간 포함 여부를 판단하는 방식이라,
     * 로그 포맷이 "타임스탬프로 시작"한다는 것을 전제로 한다.
     */
    public String readLastMinuteLog(SetupConfig config) {

        StringBuilder result = new StringBuilder();

        LocalDateTime now = LocalDateTime.now();

        // 20초 버퍼를 두는 이유: 로그 파일 write 지연을 고려해 아직 기록 중인 라인이 섞이지 않도록 함.
        LocalDateTime from = now.minusSeconds(80);
        LocalDateTime to = now.minusSeconds(20);

        DateTimeFormatter formatter =
                DateTimeFormatter.ofPattern(
                        config.getDateFormat(),
                        Locale.ENGLISH
                );

        // 타임스탬프 포맷의 문자 길이. 각 라인의 앞부분을 이 길이만큼 잘라 파싱한다.
        int timestampLength = now.format(formatter).length();

        File file = new File(config.getLogFilePath());

        // 파일 없음은 정상적인 "로그 0건" 상황과 구분되는 설정/운영 오류이므로 배치를 중단시킨다.
        if (!file.exists() || !file.isFile()) {
            throw new UncheckedIOException(
                    new FileNotFoundException(
                            "로그 파일이 없습니다 : " + file.getAbsolutePath()
                    )
            );
        }

        try (BufferedReader reader =
                    new BufferedReader(
                        new InputStreamReader(
                            new FileInputStream(file),
                            Charset.forName(config.getEncoding())))) {


            String line;

            while ((line = reader.readLine()) != null) {

                // 타임스탬프 길이보다 짧은 라인은 파싱 대상이 아니므로 스킵
                if (line.length() < timestampLength) {
                    continue;
                }


                try {

                    // 라인 앞부분(timestampLength만큼)을 타임스탬프로 간주하고 파싱
                    String dateText =
                            line.substring(0, timestampLength);


                    LocalDateTime logTime =
                            LocalDateTime.parse(
                                    dateText,
                                    formatter
                            );


                    // [from, to] 구간에 포함되는 라인만 결과에 추가
                    if (!logTime.isBefore(from)
                            && !logTime.isAfter(to)) {

                        result.append(line)
                            .append(System.lineSeparator());
                    }


                } catch (Exception e) {
                    // 날짜 형식 아닌 라인은 무시
                }
            }


        } catch (IOException e) {

            // 읽기 권한 없음 등 IO 오류는 부분 결과로 계속 진행하지 않고 배치를 중단시킨다.
            throw new UncheckedIOException(
                "로그 파일 읽기 실패 : " + file.getAbsolutePath(),
                e
            );
        }

        log.info(
            "[Monitor] 최근 로그 추출 완료. {} ~ {}, size={}",
            from,
            to,
            result.length()
        );

        return result.toString();
    }

    /**
     * 최근 1분 구간 로그를 통합 Dify 워크플로우(mode=single_analyze)에 전달해 장애 여부를 판정받는다.
     * 네트워크/일시적 오류는 maxRetries만큼 재시도하고, 모두 실패하면 DifyApiException을 던진다.
     */
    public FaultCheckResult requestFaultCheckToDify(String logContent) {

        FaultCheckResult result = new FaultCheckResult();

        // 단독으로 호출되는 경우(F-02 제약)에도 로그가 없으면 Dify를 호출하지 않는다.
        if (logContent == null || logContent.trim().isEmpty()) {
            result.setFault(false);
            result.setSummary("분석할 로그가 없습니다.");
            return result;
        }

        // 통합 워크플로우 start 노드 log_content(paragraph) 필드는 20,000자를 초과할 수 없다.
        if (logContent.length() > DifyMode.MAX_LOG_CONTENT_LENGTH) {
            log.warn(
                    "[MinuteMonitor] 로그 길이 초과로 절단 : {} -> {}자",
                    logContent.length(),
                    DifyMode.MAX_LOG_CONTENT_LENGTH
            );
            logContent = logContent.substring(0, DifyMode.MAX_LOG_CONTENT_LENGTH);
        }

        int maxAttempts = difyProperties.getMaxRetries();
        Exception lastFailure = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {

            try {

                URL url = new URL(difyProperties.getBaseUrl() + "/v1/workflows/run");

                HttpURLConnection conn =
                        (HttpURLConnection) url.openConnection();

                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setConnectTimeout(difyProperties.getTimeoutSeconds() * 1000);
                conn.setReadTimeout(difyProperties.getTimeoutSeconds() * 1000);

                conn.setRequestProperty(
                        "Authorization",
                        "Bearer " + difyProperties.getWorkflow().getLogSuite().getApiKey()
                );
                conn.setRequestProperty(
                        "Content-Type",
                        "application/json"
                );
                // Java 기본 User-Agent("Java/1.8.0_xxx")는 Cloudflare 등 WAF가 봇으로 차단하는 경우가 많아
                // (error code: 1010 등 비 JSON 응답 원인) 일반 클라이언트처럼 보이도록 명시적으로 지정한다.
                conn.setRequestProperty(
                        "User-Agent",
                        "LogAnalyzer-Batch/1.0"
                );

                ObjectMapper mapper = new ObjectMapper();

                // 통합 워크플로우 입력 폼 변수 구성 (docs/lhs_logSuite_integrated_fix.yml 참고)
                ObjectNode inputs = mapper.createObjectNode();
                inputs.put("mode", DifyMode.SINGLE_ANALYZE);
                inputs.put("log_content", logContent);

                ObjectNode root = mapper.createObjectNode();
                root.set("inputs", inputs);
                root.put("response_mode", "blocking"); // 응답이 완료될 때까지 동기 대기
                root.put("user", difyProperties.getUser());

                String requestBody =
                        mapper.writeValueAsString(root);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(
                            requestBody.getBytes(StandardCharsets.UTF_8)
                    );
                }

                int statusCode = conn.getResponseCode();

                // 4xx/5xx면 에러 바디는 getInputStream()이 아닌 getErrorStream()에 담겨 온다.
                InputStream is =
                        statusCode >= 400
                                ? conn.getErrorStream()
                                : conn.getInputStream();

                String response =
                        (is != null)
                                ? new String(StreamUtils.copyToByteArray(is), StandardCharsets.UTF_8)
                                : "";

                log.info("[MinuteMonitor] Dify 응답 : {}", response);

                JsonNode json = response.isEmpty() ? mapper.createObjectNode() : mapper.readTree(response);

                // 4xx 클라이언트 오류(인증 실패, 필수 파라미터 누락 등)는 동일 요청을 다시 보내도
                // 결과가 같으므로 즉시 중단한다. 5xx는 서버 측 일시 장애일 수 있어 재시도 대상으로 남긴다.
                if (statusCode >= 400 && statusCode < 500) {
                    throw new DifyClientErrorException(
                            "Dify API 클라이언트 오류 (" + statusCode + ") : "
                                    + json.path("message").asText(response)
                    );
                }

                if (statusCode >= 500) {
                    throw new DifyApiException(
                            "Dify API 서버 오류 (" + statusCode + ") : "
                                    + json.path("message").asText(response)
                    );
                }

                // HTTP는 200이어도 워크플로우 내부 실행이 실패할 수 있다 (예: 연결된 LLM 모델의 크레딧 소진 등).
                // 같은 입력으로 재시도해도 동일하게 실패하므로 즉시 중단한다.
                String status = json.path("data").path("status").asText("");

                if (!"succeeded".equals(status)) {
                    String error = json.path("data").path("error").asText("unknown");
                    throw new DifyClientErrorException(
                            "Dify 워크플로우 실패 - status=" + status + ", error=" + error
                    );
                }

                JsonNode outputs = json.path("data").path("outputs");

                if (!outputs.has("is_fault")) {
                    throw new ResponseMappingException(
                            "[single_analyze] 응답에 outputs.is_fault가 없습니다 : " + response
                    );
                }

                boolean isFault = outputs.path("is_fault").asBoolean(false);
                String summary = outputs.path("summary").asText("").trim();

                // summary는 isFault=true일 때만 유효한 필드이므로, 그 경우에만 누락을 스키마 불일치로 취급한다.
                if (isFault && summary.isEmpty()) {
                    throw new ResponseMappingException(
                            "[single_analyze] 장애 감지(is_fault=true)인데 outputs.summary가 없습니다 : " + response
                    );
                }

                result.setFault(isFault);
                result.setSummary(summary);

                return result;

            } catch (ResponseMappingException | DifyClientErrorException e) {

                // 재시도로 해결되지 않는 오류이므로 즉시 전파
                throw e;

            } catch (Exception e) {

                // 네트워크/IO 오류, 5xx 서버 오류 등 재시도로 회복 가능한 실패로 간주하고 다음 attempt로 넘어감
                lastFailure = e;

                log.warn(
                        "[MinuteMonitor] Dify 호출 실패 (attempt {}/{})",
                        attempt,
                        maxAttempts,
                        e
                );
            }
        }

        throw new DifyApiException(
                "Dify 호출 " + maxAttempts + "회 재시도 후 실패",
                lastFailure
        );
    }

    public void saveFaultCheckResult(FaultCheckResult result, LocalDateTime batchTime) {

        File dir = new File(RESULT_DIR);

        if (!dir.exists()) {
            dir.mkdirs();
        }

        DateTimeFormatter fileFormatter =
                DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

        File file = new File(dir, batchTime.format(fileFormatter) + ".dat");

        String content =
                "isFault=" + result.isFault() + System.lineSeparator()
                        + "summary=" + result.getSummary();

        try (OutputStream os = new FileOutputStream(file)) {
            os.write(content.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.error(
                    "[MinuteMonitor] 결과 저장 실패 : {}",
                    file.getAbsolutePath(),
                    e
            );
            return;
        }

        log.info(
                "[MinuteMonitor] 결과 저장 완료 : {}",
                file.getAbsolutePath()
        );
    }
}
