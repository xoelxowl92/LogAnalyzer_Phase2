package com.loganalyzer.batch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loganalyzer.dify.DifyApiException;
import com.loganalyzer.dify.DifyClientErrorException;
import com.loganalyzer.dify.DifyMode;
import com.loganalyzer.dify.DifyProperties;
import com.loganalyzer.dify.ResponseMappingException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Comparator;

@Slf4j
@Service
@RequiredArgsConstructor
public class DailyMonitorService {

    private static final String HOURLY_ANOMALY_DIR = "output/hourly/anomaly";
    private static final String DAILY_RESULT_DIR = "output/daily/anomaly";
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final int HOURLY_KEEP_DAYS = 7;

    private final DifyProperties difyProperties;

    /**
     * 1일 단위로 실행되는 일일 운영보고 배치의 진입점 (F-05).
     * 전일 hourly anomaly 결과를 취합 → 통합 Dify 워크플로우(mode=daily_report)에 요청 →
     * 결과 저장 → 보관 기간이 지난 hourly 결과 정리 순서로 처리한다.
     * <p>
     * {@code BatchConfig}의 스케쥴 반복 실행(runScheduledJob, 매일 자정)에서만 호출되는 진입점 -
     * 항상 "실행 시점 기준 전일"을 기준일로 삼는다. 화면 "실행" 버튼의 1회성 실행은 기준일을 직접
     * 지정하는 아래 {@link #execute(LocalDate)}를 사용한다.
     */
    public void execute() {
        execute(LocalDate.now().minusDays(1));
    }

    /**
     * targetDate(기준일) 하루치 hourly anomaly 결과를 취합해 일일 운영보고를 생성한다.
     * 웹 화면 "기준시간" 기반 1회성 실행에서 사용하며({@code BatchConfig.runHourlyBackfill}로 먼저
     * 채운 그 날짜를 그대로 받는다), 위 {@link #execute()}를 통해 인자 없이 호출되면(스케쥴 반복 실행)
     * 항상 전일자를 기준으로 동작한다.
     */
    public void execute(LocalDate targetDate) {

        log.info("[DailyMonitor] 실행 시작");

        String logContent = readTargetDateLogContent(targetDate);

        if (logContent == null || logContent.trim().isEmpty()) {
            log.info("[DailyMonitor] {} 날짜에 대한 분석 대상 로그 없음", targetDate);
        } else {
            DailyAnomalyResult result = requestDailyReportToDify(logContent, targetDate);
            saveDailyAnomalyResult(result, targetDate);
        }

        deleteOldHourlyFiles(targetDate);

        log.info("[DailyMonitor] 실행 완료");
    }

    /**
     * 통합 Dify 워크플로우(mode=daily_report)는 하루치 원본 로그가 아니라
     * {@link HourlyMonitorService}가 이미 저장해 둔 시간대별 anomaly 결과(.dat)를 log_content로 사용한다.
     * <p>
     * 원본 로그 전체는 log_content 20,000자 제한(dify-api-spec.md 2.2.1)을 통상 크게 초과하는 반면,
     * hourly anomaly 결과는 시간당 최대 800 토큰으로 이미 요약되어 있어 하루치(최대 24건)를 모아도
     * 제한 이내로 들어오는 경우가 대부분이고, "특이사항 없음" 시간대도 고정 정상 응답으로 남아있어
     * 시간 흐름 정보 손실이 적다.
     */
    public String readTargetDateLogContent(LocalDate targetDate) {

        File dir = new File(HOURLY_ANOMALY_DIR);

        if (!dir.exists() || !dir.isDirectory()) {
            log.warn("[DailyMonitor] hourly anomaly 결과 폴더가 없습니다 : {}", dir.getAbsolutePath());
            return "";
        }

        String datePrefix = targetDate.format(DATE_FORMATTER);

        File[] files = dir.listFiles(
                (d, name) -> name.startsWith(datePrefix + "_") && name.endsWith(".dat")
        );

        if (files == null || files.length == 0) {
            log.info("[DailyMonitor] {} 날짜에 해당하는 hourly anomaly 결과 없음", targetDate);
            return "";
        }

        // 파일명(yyyy-MM-dd_HH.dat)이 시각 오름차순 정렬과 문자열 정렬이 일치하므로 이름 기준 정렬로 충분하다.
        Arrays.sort(files, Comparator.comparing(File::getName));

        StringBuilder result = new StringBuilder();

        for (File file : files) {

            String fileName = file.getName();
            String hour = fileName.substring(datePrefix.length() + 1, fileName.length() - ".dat".length());

            String message = readSavedMessage(file);

            if (message.isEmpty()) {
                continue;
            }

            result.append("[").append(hour).append("시] ")
                    .append(message)
                    .append(System.lineSeparator());
        }

        String content = result.toString();

        if (content.length() > DifyMode.MAX_LOG_CONTENT_LENGTH) {
            log.warn(
                    "[DailyMonitor] 로그 길이 초과로 절단 : {} -> {}자",
                    content.length(),
                    DifyMode.MAX_LOG_CONTENT_LENGTH
            );
            content = content.substring(0, DifyMode.MAX_LOG_CONTENT_LENGTH);
        }

        return content;
    }

    /**
     * HourlyMonitorService.saveAnomalyResult()가 저장한 .dat 파일에서 message= 라인 이후 내용을 추출한다.
     * message는 LLM 응답이라 내부에 줄바꿈을 포함할 수 있어 앞 두 줄(anomalyDetected/severity)만 분리하고
     * 나머지 전체를 message로 취급한다.
     */
    private String readSavedMessage(File file) {

        try {

            String content = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            String[] parts = content.split(System.lineSeparator(), 3);

            if (parts.length < 3) {
                return "";
            }

            String messagePart = parts[2];
            int prefixIndex = messagePart.indexOf("message=");

            return (prefixIndex >= 0
                    ? messagePart.substring(prefixIndex + "message=".length())
                    : messagePart).trim();

        } catch (IOException e) {
            log.warn(
                    "[DailyMonitor] hourly anomaly 결과 읽기 실패 : {}",
                    file.getAbsolutePath(),
                    e
            );
            return "";
        }
    }

    /**
     * 취합된 hourly anomaly 결과(log_content)를 통합 Dify 워크플로우(mode=daily_report)에 전달해
     * 일일 운영보고를 요청한다.
     * 네트워크/일시적 오류는 maxRetries만큼 재시도하고, 모두 실패하면 DifyApiException을 던진다.
     */
    public DailyAnomalyResult requestDailyReportToDify(String logContent, LocalDate targetDate) {

        if (logContent == null || logContent.trim().isEmpty()) {
            return DailyAnomalyResult.builder()
                    .reportText("")
                    .errorCount(0)
                    .warnCount(0)
                    .uniqueIssueCount(0)
                    .reportDate(targetDate)
                    .build();
        }

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
                inputs.put("mode", DifyMode.DAILY_REPORT);
                inputs.put("log_content", logContent);

                ObjectNode root = mapper.createObjectNode();
                root.set("inputs", inputs);
                root.put("response_mode", "blocking");
                root.put("user", difyProperties.getUser());

                String requestBody = mapper.writeValueAsString(root);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(requestBody.getBytes(StandardCharsets.UTF_8));
                }

                int statusCode = conn.getResponseCode();

                InputStream is =
                        statusCode >= 400
                                ? conn.getErrorStream()
                                : conn.getInputStream();

                String response =
                        (is != null)
                                ? new String(StreamUtils.copyToByteArray(is), StandardCharsets.UTF_8)
                                : "";

                log.info("[DailyMonitor] Dify 응답 (daily_report) : {}", response);

                JsonNode json = response.isEmpty() ? mapper.createObjectNode() : mapper.readTree(response);

                if (statusCode >= 400 && statusCode < 500) {
                    throw new DifyClientErrorException(
                            "[daily_report] Dify API 클라이언트 오류 (" + statusCode + ") : "
                                    + json.path("message").asText(response)
                    );
                }

                if (statusCode >= 500) {
                    throw new DifyApiException(
                            "[daily_report] Dify API 서버 오류 (" + statusCode + ") : "
                                    + json.path("message").asText(response)
                    );
                }

                String status = json.path("data").path("status").asText("");

                if (!"succeeded".equals(status)) {
                    String error = json.path("data").path("error").asText("unknown");
                    throw new DifyClientErrorException(
                            "[daily_report] Dify 워크플로우 실패 - status=" + status + ", error=" + error
                    );
                }

                JsonNode outputs = json.path("data").path("outputs");

                String reportText = outputs.path("report_text").asText("").trim();

                if (reportText.isEmpty()) {
                    throw new ResponseMappingException(
                            "[daily_report] 응답에 outputs.report_text가 없습니다 : " + response
                    );
                }

                return DailyAnomalyResult.builder()
                        .reportText(reportText)
                        .errorCount(outputs.path("daily_error_count").asInt(0))
                        .warnCount(outputs.path("daily_warn_count").asInt(0))
                        .uniqueIssueCount(outputs.path("daily_unique_issue_count").asInt(0))
                        .reportDate(targetDate)
                        .build();

            } catch (ResponseMappingException | DifyClientErrorException e) {

                throw e;

            } catch (Exception e) {

                lastFailure = e;

                log.warn(
                        "[DailyMonitor] Dify 호출 실패 (daily_report) (attempt {}/{})",
                        attempt,
                        maxAttempts,
                        e
                );
            }
        }

        throw new DifyApiException(
                "[daily_report] Dify 호출 " + maxAttempts + "회 재시도 후 실패",
                lastFailure
        );
    }

    public void saveDailyAnomalyResult(DailyAnomalyResult result, LocalDate targetDate) {

        File dir = new File(DAILY_RESULT_DIR);

        if (!dir.exists()) {
            dir.mkdirs();
        }

        File file = new File(dir, targetDate.format(DATE_FORMATTER) + ".dat");

        String content =
                "reportDate=" + targetDate + System.lineSeparator()
                        + "errorCount=" + result.getErrorCount() + System.lineSeparator()
                        + "warnCount=" + result.getWarnCount() + System.lineSeparator()
                        + "uniqueIssueCount=" + result.getUniqueIssueCount() + System.lineSeparator()
                        + "reportText=" + result.getReportText();

        try (OutputStream os = new FileOutputStream(file)) {
            os.write(content.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.error(
                    "[DailyMonitor] 결과 저장 실패 : {}",
                    file.getAbsolutePath(),
                    e
            );
            return;
        }

        log.info(
                "[DailyMonitor] 결과 저장 완료 : {}",
                file.getAbsolutePath()
        );
    }

    /**
     * output/hourly/anomaly/ 에서 baseDate 기준 7일이 지난 .dat 파일을 삭제한다.
     * 구 스펙의 output/hourly/optimization/ 은 더 이상 생성되지 않으므로 대상에서 제외한다.
     */
    public void deleteOldHourlyFiles(LocalDate baseDate) {

        File dir = new File(HOURLY_ANOMALY_DIR);

        if (!dir.exists() || !dir.isDirectory()) {
            log.warn("[DailyMonitor] hourly anomaly 결과 폴더가 없습니다 : {}", dir.getAbsolutePath());
            return;
        }

        LocalDate cutoff = baseDate.minusDays(HOURLY_KEEP_DAYS);

        File[] files = dir.listFiles((d, name) -> name.endsWith(".dat"));

        if (files == null) {
            return;
        }

        for (File file : files) {

            String fileName = file.getName();
            String datePart = fileName.length() >= 10 ? fileName.substring(0, 10) : "";

            try {

                LocalDate fileDate = LocalDate.parse(datePart, DATE_FORMATTER);

                if (fileDate.isBefore(cutoff)) {

                    if (file.delete()) {
                        log.info("[DailyMonitor] 만료된 hourly anomaly 결과 삭제 : {}", file.getName());
                    } else {
                        log.error("[DailyMonitor] hourly anomaly 결과 삭제 실패 : {}", file.getAbsolutePath());
                    }
                }

            } catch (Exception e) {
                // 파일명이 날짜 형식이 아니면(예상치 못한 파일) 건드리지 않고 다음 파일로 진행
                log.warn("[DailyMonitor] 파일명에서 날짜 파싱 실패 - 삭제 대상에서 제외 : {}", fileName);
            }
        }
    }
}
