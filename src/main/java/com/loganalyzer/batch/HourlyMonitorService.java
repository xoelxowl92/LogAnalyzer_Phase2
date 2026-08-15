package com.loganalyzer.batch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loganalyzer.dify.AnalysisResultSizeExceededException;
import com.loganalyzer.dify.DifyApiException;
import com.loganalyzer.dify.DifyClientErrorException;
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

    /**
     * 1시간 단위로 실행되는 이상 패턴 분석 + 최적화 인사이트 배치의 진입점 (F-03 / F-04).
     * 최근 1시간 로그를 추출 → Dify에 이상 패턴 분석/최적화 인사이트를 병렬 요청 →
     * 각 결과를 파일로 저장하는 순서로 처리한다.
     */
    public void execute() {

        log.info("[HourlyMonitor] 실행 시작");

        // 1. 설정 읽기 (로그 파일 경로, 인코딩, 날짜 형식, 타임존)
        SetupConfig config = loadSetupConfig();

        log.info(
            "[HourlyMonitor] 로그 경로 : {}",
            config.getLogFilePath()
        );

        // 2. 최근 1시간 구간 로그 추출
        String logContent = readLastHourLog(config);

        // 3. 해당 구간 로그가 0건이면 Dify 호출 없이 종료
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

        // 4. 이상 패턴 분석(F-03) / 최적화 인사이트(F-04) 요청
        // 두 워크플로우는 동일한 로그를 입력으로 받되 서로 결과에 의존하지 않으므로
        // CompletableFuture로 병렬 실행해 전체 대기 시간을 단축한다.
        CompletableFuture<AnomalyAnalysisResult> anomalyFuture =
                CompletableFuture.supplyAsync(() -> requestAnomalyAnalysisToDify(logContent));

        CompletableFuture<OptimizationAnalysisResult> optimizationFuture =
                CompletableFuture.supplyAsync(() -> requestOptimizationAnalysisToDify(logContent));

        // join()은 각 future 내부에서 던져진 예외를 CompletionException으로 감싸 재던진다.
        AnomalyAnalysisResult anomalyResult = anomalyFuture.join();
        OptimizationAnalysisResult optimizationResult = optimizationFuture.join();

        // 5. 두 결과를 각자의 디렉터리에 파일로 저장 (F-03: anomaly, F-04: optimization)
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
     * setup.properties의 dateFormat·encoding 기준으로 최근 1시간 구간 로그 라인을 추출한다.
     * <p>
     * 각 라인의 앞부분을 타임스탬프로 파싱해 구간 포함 여부를 판단하는 방식이라,
     * 로그 포맷이 "타임스탬프로 시작"한다는 것을 전제로 한다.
     * 타임스탬프 파싱에 실패한 라인(멀티라인 스택트레이스 등)은 무시하고 계속 진행한다.
     */
    public String readLastHourLog(SetupConfig config) {

        StringBuilder result = new StringBuilder();

        // TODO-TEST: 정적 테스트 로그(test_logs/cmp-catalina.2026-06-01.log) 시각대에 맞춘 고정값.
        // 실제 운영 전환 시 반드시 LocalDateTime.now()로 되돌릴 것.
        LocalDateTime now = LocalDateTime.of(2026, 6, 1, 9, 44, 0);

        // MinuteMonitor와 달리 지연 버퍼 없이 정확히 최근 1시간(now-1h ~ now) 구간을 사용한다.
        LocalDateTime from = now.minusHours(1);
        LocalDateTime to = now;

        DateTimeFormatter formatter =
                DateTimeFormatter.ofPattern(
                        config.getDateFormat(),
                        Locale.ENGLISH
                );

        // 타임스탬프 포맷의 문자 길이. 각 라인의 앞부분을 이 길이만큼 잘라 파싱한다.
        int timestampLength = now.format(formatter).length();

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

    /**
     * anomaly-analysis/optimization-analysis 공용 Dify Workflow 호출.
     * 네트워크/일시적 오류는 maxRetries만큼 재시도하고, 모두 실패하면 DifyApiException을 던진다.
     */
    private String requestDifyWorkflowContent(String logContent, String apiKey, String workflowLabel) {

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
                        "Bearer " + apiKey
                );
                conn.setRequestProperty(
                        "Content-Type",
                        "application/json"
                );

                ObjectMapper mapper = new ObjectMapper();

                // Dify 워크플로우 입력 폼 변수 구성 (실제 앱의 입력 변수명과 일치해야 함)
                ObjectNode inputs = mapper.createObjectNode();
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
                        new String(
                                is.readAllBytes(),
                                StandardCharsets.UTF_8
                        );

                log.info("[HourlyMonitor] Dify 응답 ({}) : {}", workflowLabel, response);

                JsonNode json = mapper.readTree(response);

                // 4xx 클라이언트 오류(인증 실패, 필수 파라미터 누락 등)는 동일 요청을 다시 보내도
                // 결과가 같으므로 즉시 중단한다. 5xx는 서버 측 일시 장애일 수 있어 재시도 대상으로 남긴다.
                if (statusCode >= 400 && statusCode < 500) {
                    throw new DifyClientErrorException(
                            "[" + workflowLabel + "] Dify API 클라이언트 오류 (" + statusCode + ") : "
                                    + json.path("message").asText(response)
                    );
                }

                if (statusCode >= 500) {
                    throw new DifyApiException(
                            "[" + workflowLabel + "] Dify API 서버 오류 (" + statusCode + ") : "
                                    + json.path("message").asText(response)
                    );
                }

                // HTTP는 200이어도 워크플로우 내부 실행이 실패할 수 있다 (예: 연결된 LLM 모델의 크레딧 소진 등).
                // 같은 입력으로 재시도해도 동일하게 실패하므로 즉시 중단한다.
                String status = json.path("data").path("status").asText("");

                if (!"succeeded".equals(status)) {
                    String error = json.path("data").path("error").asText("unknown");
                    throw new DifyClientErrorException(
                            "[" + workflowLabel + "] Dify 워크플로우 실패 - status=" + status + ", error=" + error
                    );
                }

                JsonNode outputs = json.path("data").path("outputs");

                String content = outputs.path("content").asText("").trim();

                if (content.isBlank()) {
                    throw new ResponseMappingException(
                            "[" + workflowLabel + "] 응답에 outputs.content가 없습니다 : " + response
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

            } catch (ResponseMappingException | AnalysisResultSizeExceededException | DifyClientErrorException e) {

                // 재시도로 해결되지 않는 오류이므로 즉시 전파
                throw e;

            } catch (Exception e) {

                // 네트워크/IO 오류, 5xx 서버 오류 등 재시도로 회복 가능한 실패로 간주하고 다음 attempt로 넘어감
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

    
    public void saveAnomalyResult(AnomalyAnalysisResult result, LocalDateTime batchTime) {
        saveResultContent(ANOMALY_RESULT_DIR, result.getContent(), batchTime, "anomaly");
    }

    
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
