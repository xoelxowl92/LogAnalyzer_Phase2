package com.loganalyzer.batch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loganalyzer.dify.AnalysisResultSizeExceededException;
import com.loganalyzer.dify.DifyApiException;
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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;

import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class HourlyMonitorService {

    private static final String ANOMALY_RESULT_DIR = "output/hourly/anomaly";
    private static final String OPTIMIZATION_RESULT_DIR = "output/hourly/optimization";
    private static final int MAX_CONTENT_BYTES = 1_048_576;

    private final DifyProperties difyProperties;

    public void execute() {

        log.info("[HourlyMonitor] 실행 시작");

        // 1. 설정 읽기
        SetupConfig config = loadSetupConfig();

        log.info(
            "[HourlyMonitor] 로그 경로 : {}",
            config.getLogFilePath()
        );

        // 2. 최근 1시간 로그 추출
        String logContent = readLastHourLog(config);

        // 3. 로그 없으면 종료
        if (logContent == null ||
            logContent.isBlank()) {
            log.info(
                "[HourlyMonitor] 분석 대상 로그 없음"
            );
            return;
        }

        log.info(
            "[HourlyMonitor] Dify 요청 데이터 size={}",
            logContent.length()
        );

        LocalDateTime batchTime = LocalDateTime.now();

        // 4. 이상 패턴 분석 / 최적화 인사이트 요청 (동일 입력, 독립적이므로 병렬 실행)
        CompletableFuture<AnomalyAnalysisResult> anomalyFuture =
                CompletableFuture.supplyAsync(() -> requestAnomalyAnalysisToDify(logContent));

        CompletableFuture<OptimizationAnalysisResult> optimizationFuture =
                CompletableFuture.supplyAsync(() -> requestOptimizationAnalysisToDify(logContent));

        AnomalyAnalysisResult anomalyResult = anomalyFuture.join();
        OptimizationAnalysisResult optimizationResult = optimizationFuture.join();

        // 5. 결과 저장
        saveAnomalyResult(anomalyResult, batchTime);
        saveOptimizationResult(optimizationResult, batchTime);

        log.info("[HourlyMonitor] 실행 완료");
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
     * setup.properties의 dateFormat·encoding 기준으로 최근 1시간 구간 로그 라인을 추출한다.
     * <p>
     * 타임스탬프 파싱에 실패한 라인(멀티라인 스택트레이스 등)은 무시하고 계속 진행한다.
     */
    public String readLastHourLog(SetupConfig config) {

        StringBuilder result = new StringBuilder();

        // TODO-TEST: 정적 테스트 로그(test_logs/cmp-catalina.2026-06-01.log) 시각대에 맞춘 고정값.
        // 실제 운영 전환 시 반드시 LocalDateTime.now()로 되돌릴 것.
        LocalDateTime now = LocalDateTime.of(2026, 6, 1, 9, 44, 0);

        LocalDateTime from = now.minusHours(1);
        LocalDateTime to = now;

        DateTimeFormatter formatter =
                DateTimeFormatter.ofPattern(
                        config.getDateFormat(),
                        Locale.ENGLISH
                );

        int timestampLength = now.format(formatter).length();

        File file = new File(config.getLogFilePath());

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

                if (line.length() < timestampLength) {
                    continue;
                }


                try {

                    String dateText =
                            line.substring(0, timestampLength);


                    LocalDateTime logTime =
                            LocalDateTime.parse(
                                    dateText,
                                    formatter
                            );


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
     * 이상 패턴 분석 워크플로우를 Dify에 요청하고 결과를 반환한다.
     * <p>
     * 로그가 비어있으면 Dify를 호출하지 않고 빈 content를 가진 결과를 즉시 반환한다.
     */
    public AnomalyAnalysisResult requestAnomalyAnalysisToDify(String logContent) {

        if (logContent == null || logContent.isBlank()) {
            return AnomalyAnalysisResult.builder().content("").build();
        }

        String content = requestDifyWorkflowContent(
                logContent,
                difyProperties.getWorkflow().getAnomalyAnalysis().getApiKey(),
                "anomaly-analysis"
        );

        return AnomalyAnalysisResult.builder().content(content).build();
    }

    /**
     * 최적화 인사이트 분석 워크플로우를 Dify에 요청하고 결과를 반환한다.
     * <p>
     * 로그가 비어있으면 Dify를 호출하지 않고 빈 content를 가진 결과를 즉시 반환한다.
     */
    public OptimizationAnalysisResult requestOptimizationAnalysisToDify(String logContent) {

        if (logContent == null || logContent.isBlank()) {
            return OptimizationAnalysisResult.builder().content("").build();
        }

        String content = requestDifyWorkflowContent(
                logContent,
                difyProperties.getWorkflow().getOptimizationAnalysis().getApiKey(),
                "optimization-analysis"
        );

        return OptimizationAnalysisResult.builder().content(content).build();
    }

    private String requestDifyWorkflowContent(String logContent, String apiKey, String workflowLabel) {

        int maxAttempts = difyProperties.getMaxRetries();
        Exception lastFailure = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {

            try {

                // TODO-TEST: anomaly-analysis/optimization-analysis 전용 Dify Workflow 앱이 아직 없어
                // fault-check와 동일한 Chatflow 앱(/v1/chat-messages)으로 임시 테스트 중.
                // 실제 Workflow 앱 발급 후에는 반드시 "/v1/workflows/run" + inputs.log_content 방식으로 되돌릴 것.
                URL url = new URL(difyProperties.getBaseUrl() + "/v1/chat-messages");

                HttpURLConnection conn =
                        (HttpURLConnection) url.openConnection();

                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setConnectTimeout(difyProperties.getTimeoutSeconds() * 1000);
                conn.setReadTimeout(difyProperties.getTimeoutSeconds() * 1000);

                conn.setRequestProperty(
                        "Authorization",
                        "Bearer " + apiKey
                );
                conn.setRequestProperty(
                        "Content-Type",
                        "application/json"
                );

                ObjectMapper mapper = new ObjectMapper();

                ObjectNode root = mapper.createObjectNode();
                root.set("inputs", mapper.createObjectNode());
                root.put("query", logContent);
                root.put("response_mode", "blocking");
                root.put("conversation_id", "");
                root.put("user", difyProperties.getUser());

                String requestBody =
                        mapper.writeValueAsString(root);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(
                            requestBody.getBytes(StandardCharsets.UTF_8)
                    );
                }

                int statusCode = conn.getResponseCode();

                InputStream is =
                        statusCode >= 400
                                ? conn.getErrorStream()
                                : conn.getInputStream();

                String response =
                        new String(
                                is.readAllBytes(),
                                StandardCharsets.UTF_8
                        );

                log.info("[HourlyMonitor] Dify 응답 ({}) : {}", workflowLabel, response);

                JsonNode json = mapper.readTree(response);

                if (statusCode >= 400) {
                    throw new DifyApiException(
                            "[" + workflowLabel + "] Dify API 오류 (" + statusCode + ") : "
                                    + json.path("message").asText(response)
                    );
                }

                String content = json.path("answer").asText("").trim();

                if (content.isBlank()) {
                    throw new ResponseMappingException(
                            "[" + workflowLabel + "] 응답에 answer가 없습니다 : " + response
                    );
                }

                int contentBytes = content.getBytes(StandardCharsets.UTF_8).length;

                if (contentBytes > MAX_CONTENT_BYTES) {
                    throw new AnalysisResultSizeExceededException(
                            "[" + workflowLabel + "] 분석 결과 크기 초과 : " + contentBytes
                                    + " bytes (최대 " + MAX_CONTENT_BYTES + " bytes)"
                    );
                }

                return content;

            } catch (ResponseMappingException | AnalysisResultSizeExceededException e) {

                // 재시도로 해결되지 않는 오류이므로 즉시 전파
                throw e;

            } catch (Exception e) {

                lastFailure = e;

                log.warn(
                        "[HourlyMonitor] Dify 호출 실패 ({}) (attempt {}/{})",
                        workflowLabel,
                        attempt,
                        maxAttempts,
                        e
                );
            }
        }

        throw new DifyApiException(
                "[" + workflowLabel + "] Dify 호출 " + maxAttempts + "회 재시도 후 실패",
                lastFailure
        );
    }

    /** output/hourly/anomaly/yyyy-MM-dd_HH.dat 경로에 이상 패턴 분석 결과를 저장한다. */
    public void saveAnomalyResult(AnomalyAnalysisResult result, LocalDateTime batchTime) {
        saveResultContent(ANOMALY_RESULT_DIR, result.getContent(), batchTime, "anomaly");
    }

    /** output/hourly/optimization/yyyy-MM-dd_HH.dat 경로에 최적화 인사이트 분석 결과를 저장한다. */
    public void saveOptimizationResult(OptimizationAnalysisResult result, LocalDateTime batchTime) {
        saveResultContent(OPTIMIZATION_RESULT_DIR, result.getContent(), batchTime, "optimization");
    }

    private void saveResultContent(String dirPath, String content, LocalDateTime batchTime, String label) {

        File dir = new File(dirPath);

        if (!dir.exists()) {
            dir.mkdirs();
        }

        DateTimeFormatter fileFormatter =
                DateTimeFormatter.ofPattern("yyyy-MM-dd_HH");

        File file = new File(dir, batchTime.format(fileFormatter) + ".dat");

        try (OutputStream os = new FileOutputStream(file)) {
            os.write(content.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.error(
                    "[HourlyMonitor] {} 결과 저장 실패 : {}",
                    label,
                    file.getAbsolutePath(),
                    e
            );
            return;
        }

        log.info(
                "[HourlyMonitor] {} 결과 저장 완료 : {}",
                label,
                file.getAbsolutePath()
        );
    }
}
