package com.loganalyzer.batch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loganalyzer.dify.DifyApiException;
import com.loganalyzer.dify.DifyProperties;
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

import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class MinuteMonitorService {

    private static final String RESULT_DIR = "dify/result";

    private final DifyProperties difyProperties;

    public void execute() {

            log.info("[MinuteMonitor] 실행 시작");


            // 1. 설정 읽기
            SetupConfig config = loadSetupConfig();

            log.info(
                "[MinuteMonitor] 로그 경로 : {}",
                config.getLogFilePath()
            );

            // 2. 최근 로그 추출
            String logContent = readLastMinuteLog(config);


            // 3. 로그 없으면 종료
            if (logContent == null ||
                logContent.isBlank()) {
                log.info(
                    "[MinuteMonitor] 분석 대상 로그 없음"
                );
                return;
            }

            log.info(
                "[MinuteMonitor] Dify 요청 데이터 size={}",
                logContent.length()
            );

            // 4. Dify 장애 판단
            FaultCheckResult result =
                    requestFaultCheckToDify(logContent);

            // 5. 결과 저장
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



    public SetupConfig loadSetupConfig() {
        // 공용 메서드 - HourlyMonitorService와 동일

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

    public String readLastMinuteLog(SetupConfig config) {

        StringBuilder result = new StringBuilder();

        // TODO-TEST: 정적 테스트 로그(test_logs/cmp-catalina.2026-06-01.log) 시각대에 맞춘 고정값.
        // 실제 운영 전환 시 반드시 LocalDateTime.now()로 되돌릴 것.
        LocalDateTime now = LocalDateTime.of(2026, 6, 1, 9, 44, 0);

        LocalDateTime from = now.minusSeconds(80);
        LocalDateTime to = now.minusSeconds(20);

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
            "[Monitor] 최근 로그 추출 완료. {} ~ {}, size={}",
            from,
            to,
            result.length()
        );

        return result.toString();
    }

    public FaultCheckResult requestFaultCheckToDify(String logContent) {

        FaultCheckResult result = new FaultCheckResult();

        if (logContent == null || logContent.isBlank()) {
            result.setFault(false);
            result.setSummary("분석할 로그가 없습니다.");
            return result;
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
                        "Bearer " + difyProperties.getWorkflow().getFaultCheck().getApiKey()
                );
                conn.setRequestProperty(
                        "Content-Type",
                        "application/json"
                );

                ObjectMapper mapper = new ObjectMapper();

                ObjectNode inputs = mapper.createObjectNode();
                inputs.put("log_content", logContent);

                ObjectNode root = mapper.createObjectNode();
                root.set("inputs", inputs);
                root.put("response_mode", "blocking");
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

                log.info("[MinuteMonitor] Dify 응답 : {}", response);

                JsonNode json = mapper.readTree(response);

                if (statusCode >= 400) {
                    throw new DifyApiException(
                            "Dify API 오류 (" + statusCode + ") : "
                                    + json.path("message").asText(response)
                    );
                }

                String status = json.path("data").path("status").asText("");

                if (!"succeeded".equals(status)) {
                    String error = json.path("data").path("error").asText("unknown");
                    throw new DifyApiException(
                            "Dify 워크플로우 실패 - status=" + status + ", error=" + error
                    );
                }

                JsonNode outputs = json.path("data").path("outputs");

                result.setFault(outputs.path("is_fault").asBoolean(false));
                result.setSummary(outputs.path("summary").asText(""));

                return result;

            } catch (Exception e) {

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
