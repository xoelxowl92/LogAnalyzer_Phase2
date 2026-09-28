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
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import java.util.Properties;

import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

@Slf4j
@Service
@RequiredArgsConstructor
public class HourlyMonitorService {

    private static final String ANOMALY_RESULT_DIR = "output/hourly/anomaly";
    private static final String ANOMALY_STATE_FILE = "output/hourly/anomaly-state.properties";

    private final DifyProperties difyProperties;

    /**
     * 1시간 단위로 실행되는 이상 패턴 분석 배치의 진입점 (F-03).
     * 최근 1시간 로그를 추출 → 통합 Dify 워크플로우(mode=anomaly)에 요청 → 결과를 파일로 저장하는 순서로 처리한다.
     * <p>
     * 로그 최적화 인사이트(F-04)는 통합 워크플로우에 아직 추가되지 않아 현재는 스킵한다.
     * <p>
     * {@code BatchConfig}의 스케쥴 반복 실행(runScheduledJob)에서만 호출되는 진입점 - 항상 현재 시각을
     * 기준으로 동작한다. 화면 "실행" 버튼의 1회성 실행이나 {@link DailyBatchOrchestrationService}의
     * 시간대별 백필은 아래 {@link #execute(String)}을 사용한다.
     */
    public void execute() {
        execute(null);
    }

    /**
     * baseTime이 주어지면(웹 화면 "기준시간" 선택 후 1회성 실행, 또는 1일 배치의 시간대별 백필) 그 시각을,
     * 없으면(위 {@link #execute()}를 통한 스케쥴 반복 실행) 현재 시각을 기준으로 최근 1시간 구간 로그를 분석한다.
     * baseTime은 setup.properties의 dateFormat과 동일한 포맷의 문자열이어야 한다.
     */
    public void execute(String baseTime) {

        log.info("[HourlyMonitor] 실행 시작");

        // 1. 설정 읽기 (로그 파일 경로, 인코딩, 날짜 형식, 타임존)
        SetupConfig config = loadSetupConfig();

        log.info(
            "[HourlyMonitor] 로그 경로 : {}",
            config.getLogFilePath()
        );

        LocalDateTime referenceTime = resolveReferenceTime(baseTime, config);

        // 2. 기준시간 이전 1시간 구간 로그 추출
        String logContent = readLastHourLog(config, referenceTime);

        // 3. 해당 구간 로그가 0건이면 Dify 호출 없이 종료
        if (logContent == null ||
            logContent.trim().isEmpty()) {
            log.info(
                "[HourlyMonitor] 분석 대상 로그 없음"
            );
            return;
        }

        // 통합 워크플로우 start 노드 log_content(paragraph) 필드는 20,000자를 초과할 수 없다.
        if (logContent.length() > DifyMode.MAX_LOG_CONTENT_LENGTH) {
            log.warn(
                    "[HourlyMonitor] 로그 길이 초과로 절단 : {} -> {}자",
                    logContent.length(),
                    DifyMode.MAX_LOG_CONTENT_LENGTH
            );
            logContent = logContent.substring(0, DifyMode.MAX_LOG_CONTENT_LENGTH);
        }

        log.info(
            "[HourlyMonitor] Dify 요청 데이터 size={}",
            logContent.length()
        );

        // 4. anomaly 모드는 직전 실행의 카테고리별 누적 건수를 함께 전달해야 증감 여부를 판단할 수 있다.
        AnomalyCounts prevCounts = loadPrevAnomalyCounts();

        AnomalyAnalysisResult result = requestAnomalyAnalysisToDify(logContent, prevCounts);

        // 5. 결과 저장 + 다음 실행에서 쓸 누적 건수 갱신
        saveAnomalyResult(result, referenceTime);
        savePrevAnomalyCounts(result.getNextCounts());

        log.info("[HourlyMonitor] 실행 완료");
    }

    /**
     * baseTime이 없으면(자동 스케쥴 실행) 현재 시각을 사용하고, 있으면(웹 화면 수동 실행)
     * config의 dateFormat으로 파싱해 사용한다. 파싱에 실패하면 현재 시각으로 대체한다.
     */
    private LocalDateTime resolveReferenceTime(String baseTime, SetupConfig config) {

        if (baseTime == null || baseTime.trim().isEmpty()) {
            return LocalDateTime.now();
        }

        try {
            DateTimeFormatter formatter =
                    DateTimeFormatter.ofPattern(config.getDateFormat(), Locale.ENGLISH);
            return LocalDateTime.parse(baseTime.trim(), formatter);
        } catch (Exception e) {
            log.warn(
                    "[HourlyMonitor] 기준시간 파싱 실패 - 현재 시각으로 대체 : {}",
                    baseTime,
                    e
            );
            return LocalDateTime.now();
        }
    }

    /**
     * config/setup.properties를 읽어 SetupConfig로 반환한다.
     * <p>
     * MinuteMonitorService와 동일한 로직이지만 공통 부모 없이 복제하여 유지한다.
     * 두 서비스를 공통 부모로 묶으면 배치 실행 주기·책임이 다른 서비스 간 결합도가 생기기 때문이다.
     */
    public SetupConfig loadSetupConfig() {
        // 공용 메서드 - MinuteMonitorService와 동일

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
     * setup.properties의 dateFormat·encoding 기준으로 referenceTime 이전 1시간 구간 로그 라인을 추출한다.
     * <p>
     * 각 라인의 앞부분을 타임스탬프로 파싱해 구간 포함 여부를 판단하는 방식이라,
     * 로그 포맷이 "타임스탬프로 시작"한다는 것을 전제로 한다.
     * 타임스탬프 파싱에 실패한 라인(멀티라인 스택트레이스 등)은 무시하고 계속 진행한다.
     */
    public String readLastHourLog(SetupConfig config, LocalDateTime referenceTime) {

        StringBuilder result = new StringBuilder();

        // MinuteMonitor와 달리 지연 버퍼 없이 정확히 referenceTime 기준 1시간(referenceTime-1h ~ referenceTime) 구간을 사용한다.
        LocalDateTime from = referenceTime.minusHours(1);
        LocalDateTime to = referenceTime;

        DateTimeFormatter formatter =
                DateTimeFormatter.ofPattern(
                        config.getDateFormat(),
                        Locale.ENGLISH
                );

        // 타임스탬프 포맷의 문자 길이. 각 라인의 앞부분을 이 길이만큼 잘라 파싱한다.
        int timestampLength = referenceTime.format(formatter).length();

        File file = new File(config.getLogFilePath());

        // 로그 파일이 없으면 예외 없이 빈 문자열 반환 → 상위(execute)에서 Dify 호출 스킵으로 처리됨
        if (!file.exists() || !file.isFile()) {
            log.warn("로그 파일이 없습니다 : {}", file.getAbsolutePath());
            return "";
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

            // 읽기 도중 실패해도 배치를 중단하지 않고, 그때까지 모은 결과만 반환
            log.warn(
                "로그 파일 읽기 실패 : {}",
                file.getAbsolutePath(),
                e
            );
        }

        log.info(
            "[HourlyMonitor] 최근 로그 추출 완료. {} ~ {}, size={}",
            from,
            to,
            result.length()
        );

        return result.toString();
    }

    /**
     * 이상 패턴 분석을 통합 Dify 워크플로우(mode=anomaly)에 요청한다.
     * <p>
     * anomaly 모드는 직전 호출의 카테고리별 누적 건수(prevCounts)를 함께 전달해야 증감 여부를 판단할 수 있고,
     * 응답의 next_prev_* 값을 다음 호출의 prevCounts로 그대로 이어서 전달해야 한다.
     * 네트워크/일시적 오류는 maxRetries만큼 재시도하고, 모두 실패하면 DifyApiException을 던진다.
     */
    public AnomalyAnalysisResult requestAnomalyAnalysisToDify(String logContent, AnomalyCounts prevCounts) {

        if (logContent == null || logContent.trim().isEmpty()) {
            return AnomalyAnalysisResult.builder()
                    .anomalyDetected(false)
                    .severity("")
                    .message("")
                    .nextCounts(prevCounts)
                    .build();
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
                inputs.put("mode", DifyMode.ANOMALY);
                inputs.put("log_content", logContent);
                inputs.put("prev_error_count", prevCounts.getErrorCount());
                inputs.put("prev_warn_count", prevCounts.getWarnCount());
                inputs.put("prev_timeout_count", prevCounts.getTimeoutCount());
                inputs.put("prev_http5xx_count", prevCounts.getHttp5xxCount());
                inputs.put("prev_db_conn_count", prevCounts.getDbConnCount());
                inputs.put("prev_login_fail_count", prevCounts.getLoginFailCount());
                inputs.put("prev_batch_fail_count", prevCounts.getBatchFailCount());
                inputs.put("prev_external_api_fail_count", prevCounts.getExternalApiFailCount());

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

                log.info("[HourlyMonitor] Dify 응답 (anomaly) : {}", response);

                JsonNode json = response.isEmpty() ? mapper.createObjectNode() : mapper.readTree(response);

                // 4xx 클라이언트 오류(인증 실패, 필수 파라미터 누락 등)는 동일 요청을 다시 보내도
                // 결과가 같으므로 즉시 중단한다. 5xx는 서버 측 일시 장애일 수 있어 재시도 대상으로 남긴다.
                if (statusCode >= 400 && statusCode < 500) {
                    throw new DifyClientErrorException(
                            "[anomaly] Dify API 클라이언트 오류 (" + statusCode + ") : "
                                    + json.path("message").asText(response)
                    );
                }

                if (statusCode >= 500) {
                    throw new DifyApiException(
                            "[anomaly] Dify API 서버 오류 (" + statusCode + ") : "
                                    + json.path("message").asText(response)
                    );
                }

                // HTTP는 200이어도 워크플로우 내부 실행이 실패할 수 있다 (예: 연결된 LLM 모델의 크레딧 소진 등).
                // 같은 입력으로 재시도해도 동일하게 실패하므로 즉시 중단한다.
                String status = json.path("data").path("status").asText("");

                if (!"succeeded".equals(status)) {
                    String error = json.path("data").path("error").asText("unknown");
                    throw new DifyClientErrorException(
                            "[anomaly] Dify 워크플로우 실패 - status=" + status + ", error=" + error
                    );
                }

                JsonNode outputs = json.path("data").path("outputs");

                boolean anomalyDetected = "true".equalsIgnoreCase(outputs.path("anomaly_detected").asText("false"));
                String severity = outputs.path("anomaly_severity").asText("");
                String message = outputs.path("anomaly_message").asText("").trim();

                if (message.isEmpty()) {
                    throw new ResponseMappingException(
                            "[anomaly] 응답에 outputs.anomaly_message가 없습니다 : " + response
                    );
                }

                AnomalyCounts nextCounts = AnomalyCounts.builder()
                        .errorCount(outputs.path("next_prev_error_count").asInt(0))
                        .warnCount(outputs.path("next_prev_warn_count").asInt(0))
                        .timeoutCount(outputs.path("next_prev_timeout_count").asInt(0))
                        .http5xxCount(outputs.path("next_prev_http5xx_count").asInt(0))
                        .dbConnCount(outputs.path("next_prev_db_conn_count").asInt(0))
                        .loginFailCount(outputs.path("next_prev_login_fail_count").asInt(0))
                        .batchFailCount(outputs.path("next_prev_batch_fail_count").asInt(0))
                        .externalApiFailCount(outputs.path("next_prev_external_api_fail_count").asInt(0))
                        .build();

                return AnomalyAnalysisResult.builder()
                        .anomalyDetected(anomalyDetected)
                        .severity(severity)
                        .message(message)
                        .nextCounts(nextCounts)
                        .build();

            } catch (ResponseMappingException | DifyClientErrorException e) {

                // 재시도로 해결되지 않는 오류이므로 즉시 전파
                throw e;

            } catch (Exception e) {

                // 네트워크/IO 오류, 5xx 서버 오류 등 재시도로 회복 가능한 실패로 간주하고 다음 attempt로 넘어감
                lastFailure = e;

                log.warn(
                        "[HourlyMonitor] Dify 호출 실패 (anomaly) (attempt {}/{})",
                        attempt,
                        maxAttempts,
                        e
                );
            }
        }

        throw new DifyApiException(
                "[anomaly] Dify 호출 " + maxAttempts + "회 재시도 후 실패",
                lastFailure
        );
    }

    public void saveAnomalyResult(AnomalyAnalysisResult result, LocalDateTime batchTime) {

        File dir = new File(ANOMALY_RESULT_DIR);

        if (!dir.exists()) {
            dir.mkdirs();
        }

        DateTimeFormatter fileFormatter =
                DateTimeFormatter.ofPattern("yyyy-MM-dd_HH");

        File file = new File(dir, batchTime.format(fileFormatter) + ".dat");

        String content =
                "anomalyDetected=" + result.isAnomalyDetected() + System.lineSeparator()
                        + "severity=" + result.getSeverity() + System.lineSeparator()
                        + "message=" + result.getMessage();

        try (OutputStream os = new FileOutputStream(file)) {
            os.write(content.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.error(
                    "[HourlyMonitor] anomaly 결과 저장 실패 : {}",
                    file.getAbsolutePath(),
                    e
            );
            return;
        }

        log.info(
                "[HourlyMonitor] anomaly 결과 저장 완료 : {}",
                file.getAbsolutePath()
        );
    }

    /**
     * 직전 실행에서 저장해 둔 카테고리별 누적 건수를 읽어온다.
     * 상태 파일이 없으면(최초 실행) 전부 0으로 시작한다 — Dify 쪽에서도 prev_* 전부 0이면
     * 절대 임계치(5건)만 적용하고 작은 증감으로는 이상 판정을 내리지 않는다.
     */
    public AnomalyCounts loadPrevAnomalyCounts() {

        File file = new File(ANOMALY_STATE_FILE);

        if (!file.exists()) {
            return AnomalyCounts.builder().build();
        }

        Properties props = new Properties();

        try (InputStream is = new FileInputStream(file)) {
            props.load(is);
        } catch (IOException e) {
            log.warn(
                    "[HourlyMonitor] anomaly 상태 파일 읽기 실패 - 0으로 초기화 : {}",
                    file.getAbsolutePath(),
                    e
            );
            return AnomalyCounts.builder().build();
        }

        return AnomalyCounts.builder()
                .errorCount(parseIntProperty(props, "error_count"))
                .warnCount(parseIntProperty(props, "warn_count"))
                .timeoutCount(parseIntProperty(props, "timeout_count"))
                .http5xxCount(parseIntProperty(props, "http5xx_count"))
                .dbConnCount(parseIntProperty(props, "db_conn_count"))
                .loginFailCount(parseIntProperty(props, "login_fail_count"))
                .batchFailCount(parseIntProperty(props, "batch_fail_count"))
                .externalApiFailCount(parseIntProperty(props, "external_api_fail_count"))
                .build();
    }

    /**
     * 이번 실행에서 받은 next_prev_* 값을 다음 실행의 prevCounts로 쓸 수 있도록 저장한다.
     */
    public void savePrevAnomalyCounts(AnomalyCounts counts) {

        File file = new File(ANOMALY_STATE_FILE);
        File dir = file.getParentFile();

        if (dir != null && !dir.exists()) {
            dir.mkdirs();
        }

        Properties props = new Properties();
        props.setProperty("error_count", String.valueOf(counts.getErrorCount()));
        props.setProperty("warn_count", String.valueOf(counts.getWarnCount()));
        props.setProperty("timeout_count", String.valueOf(counts.getTimeoutCount()));
        props.setProperty("http5xx_count", String.valueOf(counts.getHttp5xxCount()));
        props.setProperty("db_conn_count", String.valueOf(counts.getDbConnCount()));
        props.setProperty("login_fail_count", String.valueOf(counts.getLoginFailCount()));
        props.setProperty("batch_fail_count", String.valueOf(counts.getBatchFailCount()));
        props.setProperty("external_api_fail_count", String.valueOf(counts.getExternalApiFailCount()));

        try (OutputStream os = new FileOutputStream(file)) {
            props.store(os, "HourlyMonitor anomaly 모드 직전 카테고리별 누적 건수");
        } catch (IOException e) {
            log.error(
                    "[HourlyMonitor] anomaly 상태 파일 저장 실패 : {}",
                    file.getAbsolutePath(),
                    e
            );
        }
    }

    private int parseIntProperty(Properties props, String key) {
        try {
            return Integer.parseInt(props.getProperty(key, "0"));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * output/hourly/anomaly에 저장된 가장 최근 1시간 배치 실행 결과 1건을 읽어온다. 웹 화면 "결과 보기"에서 사용한다.
     * 파일명이 yyyy-MM-dd_HH.dat 형식이라 이름순 정렬이 곧 시간순 정렬이다. 저장된 결과가 하나도 없으면 null을 반환한다.
     */
    public HourlyMonitorResult loadLatestResult() {

        File dir = new File(ANOMALY_RESULT_DIR);

        if (!dir.exists()) {
            return null;
        }

        File[] files = dir.listFiles((d, name) -> name.endsWith(".dat"));

        if (files == null || files.length == 0) {
            return null;
        }

        File latest = Arrays.stream(files)
                .max(Comparator.comparing(File::getName))
                .orElse(null);

        try {
            String content = new String(Files.readAllBytes(latest.toPath()), StandardCharsets.UTF_8);
            // message는 LLM 응답이라 내부에 줄바꿈을 포함할 수 있어 앞 두 줄만 분리하고 나머지 전체를 message로 취급한다.
            String[] parts = content.split(System.lineSeparator(), 3);

            if (parts.length < 3) {
                log.warn("[HourlyMonitor] 저장된 결과 형식이 올바르지 않습니다 : {}", latest.getName());
                return null;
            }

            String severity = stripPrefix(parts[1], "severity=");

            return HourlyMonitorResult.builder()
                    .anomalyDetected(Boolean.parseBoolean(stripPrefix(parts[0], "anomalyDetected=").trim()))
                    // saveAnomalyResult()가 null severity를 "null" 문자열로 저장하므로 빈 값으로 정규화한다.
                    .severity("null".equals(severity) ? "" : severity)
                    .message(stripPrefix(parts[2], "message="))
                    .targetHour(latest.getName().replace(".dat", ""))
                    .build();

        } catch (IOException e) {
            log.error("[HourlyMonitor] 최근 결과 파일 읽기 실패 : {}", latest.getAbsolutePath(), e);
            return null;
        }
    }

    private String stripPrefix(String line, String prefix) {
        return line.startsWith(prefix) ? line.substring(prefix.length()) : line;
    }
}
