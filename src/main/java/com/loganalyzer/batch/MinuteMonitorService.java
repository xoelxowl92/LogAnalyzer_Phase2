package com.loganalyzer.batch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loganalyzer.setup.SetupConfig;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Properties;

import org.springframework.stereotype.Service;

@Slf4j
@Service
public class MinuteMonitorService {

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
        // TODO: config/setup.properties 읽어 SetupConfig 반환 (공용 - HourlyMonitorService와 동일)
        //
        Properties props = new Properties();

        try (InputStream is =
                new FileInputStream("config/setup.properties")) {

            props.load(is);

            SetupConfig config = new SetupConfig();

            config.setLogFilePath(
                props.getProperty("setup.log-file-path")
            );

            return config;

        } catch (IOException e) {
            throw new RuntimeException(
                "setup.properties 로딩 실패",
                e
            );
        }
        //


    }

    public String readLastMinuteLog(SetupConfig config) {
        // TODO: (현재시각 - 80초) ~ (현재시각 - 20초) 구간 로그 읽기
        StringBuilder result = new StringBuilder();

        // LocalDateTime now = LocalDateTime.now();
        LocalDateTime now = LocalDateTime.of(2026, 6, 1, 9, 44, 0); // 테스트용 고정 시각

        LocalDateTime from = now.minusSeconds(80);
        LocalDateTime to = now.minusSeconds(20);

        DateTimeFormatter formatter =
                DateTimeFormatter.ofPattern(
                        "dd-MMM-yyyy HH:mm:ss.SSS",
                        Locale.ENGLISH
                );


        File dir = new File(config.getLogFilePath());

        if (!dir.exists() || !dir.isDirectory()) {
            log.warn("로그 디렉터리가 없습니다 : {}", dir.getAbsolutePath());
            return "";
        }


        File[] files = dir.listFiles();

        if (files == null) {
            return "";
        }


        for (File file : files) {

            // 압축 로그 제외
            if (!file.isFile()) {
                continue;
            }

            String name = file.getName();

            if (!(name.endsWith(".log")
                    || name.endsWith(".out"))) {
                continue;
            }

            try (BufferedReader reader =
                        new BufferedReader(
                            new InputStreamReader(
                                new FileInputStream(file),
                                StandardCharsets.UTF_8))) {


                String line;

                while ((line = reader.readLine()) != null) {

                    if (line.length() < 23) {
                        continue;
                    }


                    try {

                        String dateText =
                                line.substring(0, 23);


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
        // TODO: 빈 문자열이면 isFault=false 반환, 아니면 Dify 장애 판단 Workflow 호출
        FaultCheckResult result = new FaultCheckResult();

        if (logContent == null || logContent.isBlank()) {
            result.setFault(false);
            result.setSummary("분석할 로그가 없습니다.");
            return result;
        }

        try {

            URL url = new URL("http://localhost/v1/workflows/run");

            HttpURLConnection conn =
                    (HttpURLConnection) url.openConnection();

            conn.setRequestMethod("POST");
            conn.setDoOutput(true);

            conn.setRequestProperty(
                    "Authorization",
                    "Bearer " + "difyApiKey"
            );
            conn.setRequestProperty(
                    "Content-Type",
                    "application/json"
            );

            ObjectMapper mapper = new ObjectMapper();

            ObjectNode root = mapper.createObjectNode();
            root.put("response_mode", "blocking");
            root.put("user", "MinuteMonitor");

            ObjectNode inputs = mapper.createObjectNode();
            inputs.put("logContent", logContent);

            root.set("inputs", inputs);

            String requestBody =
                    mapper.writeValueAsString(root);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(
                        requestBody.getBytes(StandardCharsets.UTF_8)
                );
            }

            InputStream is =
                    conn.getResponseCode() >= 400
                            ? conn.getErrorStream()
                            : conn.getInputStream();

            String response =
                    new String(
                            is.readAllBytes(),
                            StandardCharsets.UTF_8
                    );

            log.info("[MinuteMonitor] Dify 응답 : {}", response);

            JsonNode json = mapper.readTree(response);

            JsonNode outputs =
                    json.path("data")
                        .path("outputs");

            result.setFault(
                    outputs.path("isFault").asBoolean(false)
            );

            result.setSummary(
                    outputs.path("summary").asText("")
            );

            return result;

        } catch (Exception e) {

            log.error("Dify 호출 실패", e);

            result.setFault(false);
            result.setSummary("Dify 호출 실패 : " + e.getMessage());

            return result;
        }
    }
}
