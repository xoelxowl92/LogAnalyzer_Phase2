package com.loganalyzer.setup;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loganalyzer.dify.DifyApiException;
import com.loganalyzer.dify.DifyProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Properties;
import java.util.stream.Collectors;

/**
 * 최초 1회 실행되는 초기 설정 로직.
 * 설정 결과는 config/setup.properties에 저장되며 이후 배치에서 공통 참조함.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SetupService {

    private final DifyProperties difyProperties;

    /**
     * 로그 파일 경로 유효성 검증 후 절대 경로 반환.
     * 파일 존재 여부, 디렉터리 여부, 읽기 권한을 순서대로 확인.
     */
    public String configureLogFilePath(String logFilePath) throws IOException {
        if (logFilePath == null || logFilePath.isBlank()) {
            throw new IllegalArgumentException("로그 파일 경로는 필수입니다.");
        }
        File file = new File(logFilePath);
        if (!file.exists()) {
            throw new FileNotFoundException("파일을 찾을 수 없습니다: " + logFilePath);
        }
        if (file.isDirectory()) {
            throw new IllegalArgumentException("디렉터리가 아닌 파일 경로를 입력해주세요: " + logFilePath);
        }
        if (!file.canRead()) {
            throw new IOException("파일 읽기 권한이 없습니다: " + logFilePath);
        }
        String absolutePath = file.getAbsolutePath();
        log.info("[Setup] 로그 파일 경로 설정 완료: {}", absolutePath);
        return absolutePath;
    }

    /**
     * 파일 앞 3바이트 BOM 확인으로 인코딩 탐지.
     * BOM이 없으면 UTF-8을 기본값으로 사용. (juniversalchardet 도입 시 교체 권장)
     */
    public String detectEncoding(String logFilePath) throws IOException {
        try (InputStream is = new BufferedInputStream(new FileInputStream(logFilePath))) {
            byte[] bom = new byte[3];
            int read = is.read(bom, 0, 3);

            if (read >= 3 && bom[0] == (byte) 0xEF && bom[1] == (byte) 0xBB && bom[2] == (byte) 0xBF) {
                log.info("[Setup] 인코딩 탐지: UTF-8 (BOM)");
                return "UTF-8";
            }
            if (read >= 2 && bom[0] == (byte) 0xFE && bom[1] == (byte) 0xFF) {
                log.info("[Setup] 인코딩 탐지: UTF-16 BE");
                return "UTF-16BE";
            }
            if (read >= 2 && bom[0] == (byte) 0xFF && bom[1] == (byte) 0xFE) {
                log.info("[Setup] 인코딩 탐지: UTF-16 LE");
                return "UTF-16LE";
            }
        }
        log.warn("[Setup] 인코딩 탐지 불가 — UTF-8 기본값 사용");
        return "UTF-8";
    }

    /**
     * 지정 인코딩으로 로그 파일 앞 maxLines 줄을 읽어 반환.
     * Dify에 날짜 형식 추론을 요청하기 위한 샘플 데이터로 사용됨.
     */
    public String readSampleLog(String logFilePath, String encoding, int maxLines) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(logFilePath), Charset.forName(encoding)))) {

            String content = reader.lines()
                    .limit(maxLines)
                    .collect(Collectors.joining("\n"));

            if (content.isBlank()) {
                throw new IOException("로그 파일이 비어있습니다: " + logFilePath);
            }
            log.info("[Setup] 샘플 로그 읽기 완료 (최대 {} 줄)", maxLines);
            return content;
        }
    }

    /**
     * 샘플 로그를 Dify Workflow API에 전달하여 날짜 형식 패턴을 추론받는다.
     * 네트워크/IO 오류는 maxRetries만큼 재시도하며, API 오류 및 파싱 실패는 즉시 중단.
     */
    public String requestDateFormatToDify(String sampleLogContent) {
        int maxAttempts = difyProperties.getMaxRetries();
        Exception lastFailure = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                URL url = new URL(difyProperties.getBaseUrl() + "/v1/workflows/run");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setConnectTimeout(difyProperties.getTimeoutSeconds() * 1000);
                conn.setReadTimeout(difyProperties.getTimeoutSeconds() * 1000);
                conn.setRequestProperty("Authorization", "Bearer " + difyProperties.getWorkflow().getDateFormat().getApiKey());
                conn.setRequestProperty("Content-Type", "application/json");

                ObjectMapper mapper = new ObjectMapper();
                ObjectNode inputs = mapper.createObjectNode();
                inputs.put("sample_log", sampleLogContent);
                ObjectNode root = mapper.createObjectNode();
                root.set("inputs", inputs);
                root.put("response_mode", "blocking");
                root.put("user", difyProperties.getUser());

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(mapper.writeValueAsString(root).getBytes(StandardCharsets.UTF_8));
                }

                int statusCode = conn.getResponseCode();
                InputStream responseStream = statusCode >= 400 ? conn.getErrorStream() : conn.getInputStream();
                String response = new String(responseStream.readAllBytes(), StandardCharsets.UTF_8);
                log.debug("[Setup] Dify 응답: {}", response);

                if (statusCode >= 400) {
                    JsonNode errorJson = mapper.readTree(response);
                    throw new DifyApiException("Dify API 오류 (" + statusCode + "): " + errorJson.path("message").asText(response));
                }

                JsonNode json = mapper.readTree(response);
                String status = json.path("data").path("status").asText("");
                if (!"succeeded".equals(status)) {
                    String error = json.path("data").path("error").asText("unknown");
                    throw new DifyApiException("Dify 워크플로우 실패 - status=" + status + ", error=" + error);
                }

                String dateFormat = json.path("data").path("outputs").path("date_format").asText("").trim();
                if (dateFormat.isBlank()) {
                    throw new DifyApiException("날짜 형식을 추론할 수 없습니다 (빈 응답)");
                }

                try {
                    DateTimeFormatter.ofPattern(dateFormat, Locale.ENGLISH);
                } catch (IllegalArgumentException e) {
                    throw new DifyApiException("유효하지 않은 DateTimeFormatter 패턴: " + dateFormat);
                }

                log.info("[Setup] 날짜 형식 탐지 완료: {}", dateFormat);
                return dateFormat;

            } catch (DifyApiException e) {
                throw e;
            } catch (Exception e) {
                lastFailure = e;
                log.warn("[Setup] Dify 호출 실패 (attempt {}/{}): {}", attempt, maxAttempts, e.getMessage());
            }
        }

        throw new DifyApiException("Dify 호출 " + maxAttempts + "회 재시도 후 실패", lastFailure);
    }

    /**
     * 샘플 로그에서 타임존 파싱 시도. 파싱 실패 시 EC2 시스템 기본 타임존 사용.
     * 로그 타임스탬프에 타임존이 명시되지 않은 경우가 많으므로 EC2 타임존을 로그 서버와 일치시켜야 함.
     */
    public String detectTimezone(String sampleLogContent, String dateFormat) {
        String[] lines = sampleLogContent.split("\n");
        for (String line : lines) {
            if (line.isBlank()) continue;
            try {
                DateTimeFormatter formatter = DateTimeFormatter.ofPattern(dateFormat, Locale.ENGLISH);
                ZonedDateTime zdt = ZonedDateTime.parse(line.substring(0, Math.min(line.length(), 35)).trim(), formatter);
                String zoneId = zdt.getZone().getId();
                log.info("[Setup] 타임존 탐지 성공: {}", zoneId);
                return zoneId;
            } catch (Exception ignored) {
            }
        }
        String systemZone = ZoneId.systemDefault().getId();
        log.warn("[Setup] 타임존 탐지 불가 — 시스템 기본값 사용: {}", systemZone);
        return systemZone;
    }

    /**
     * 탐지된 설정값을 config/setup.properties에 저장.
     * 재실행 시 기존 파일을 덮어씀. 이후 배치(1분/1시간/1일)에서 이 파일을 공통 참조함.
     */
    public void saveSetupConfig(SetupConfig config) throws IOException {
        File configDir = new File("config");
        if (!configDir.exists()) {
            configDir.mkdirs();
        }

        Properties props = new Properties();
        props.setProperty("setup.log-file-path", config.getLogFilePath());
        props.setProperty("setup.encoding", config.getEncoding());
        props.setProperty("setup.date-format", config.getDateFormat());
        props.setProperty("setup.timezone", config.getTimezone());

        try (OutputStream os = new FileOutputStream("config/setup.properties")) {
            props.store(os, "LogAnalyzer Setup Config");
        }
        log.info("[Setup] 설정 저장 완료: config/setup.properties");
    }
}
