package com.loganalyzer.batch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class MonthlyMonitorService {

    private static final String DAILY_ANOMALY_DIR = "output/daily/anomaly";
    private static final String MONTHLY_RESULT_DIR = "output/monthly";
    private static final DateTimeFormatter MONTH_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM");

    private final DifyProperties difyProperties;

    /**
     * 1개월 단위로 실행되는 월간 운영보고 배치의 진입점.
     * 전월 daily anomaly 결과를 취합 → 통합 Dify 워크플로우(mode=monthly_report)에 요청 → 결과 저장 순서로 처리한다.
     */
    public void execute() {

        log.info("[MonthlyMonitor] 실행 시작");

        YearMonth targetYearMonth = YearMonth.now().minusMonths(1);

        String dailyResultsJson = readMonthlyDailyResults(targetYearMonth);

        if (dailyResultsJson == null || dailyResultsJson.trim().isEmpty()) {
            log.info("[MonthlyMonitor] {} 월에 대한 daily 결과 없음", targetYearMonth);
        } else {
            MonthlyReportResult result = requestMonthlyReportToDify(dailyResultsJson, targetYearMonth);
            saveMonthlyResult(result, targetYearMonth);
        }

        log.info("[MonthlyMonitor] 실행 완료");
    }

    /**
     * {@link DailyMonitorService}가 저장해 둔 output/daily/anomaly/yyyy-MM-dd.dat 파일들을 대상 월 기준으로 모아
     * 통합 Dify 워크플로우(mode=monthly_report)의 daily_results_json(JSON 배열 문자열) 형태로 구성한다.
     * 각 항목은 docs/logSuite_integrated_FIN_monthly.yml의 mr-guard 코드 노드가 우선적으로 인식하는
     * date/report_text 키를 포함한다.
     */
    public String readMonthlyDailyResults(YearMonth targetYearMonth) {

        File dir = new File(DAILY_ANOMALY_DIR);

        if (!dir.exists() || !dir.isDirectory()) {
            log.warn("[MonthlyMonitor] daily anomaly 결과 폴더가 없습니다 : {}", dir.getAbsolutePath());
            return "";
        }

        String monthPrefix = targetYearMonth.format(MONTH_FORMATTER);

        File[] files = dir.listFiles(
                (d, name) -> name.startsWith(monthPrefix + "-") && name.endsWith(".dat")
        );

        if (files == null || files.length == 0) {
            log.info("[MonthlyMonitor] {} 월에 해당하는 daily anomaly 결과 없음", monthPrefix);
            return "";
        }

        // 파일명(yyyy-MM-dd.dat)이 날짜 오름차순 정렬과 문자열 정렬이 일치하므로 이름 기준 정렬로 충분하다.
        Arrays.sort(files, Comparator.comparing(File::getName));

        ObjectMapper mapper = new ObjectMapper();
        ArrayNode array = mapper.createArrayNode();

        for (File file : files) {
            appendDailyResult(array, mapper, file);
        }

        if (array.size() == 0) {
            return "";
        }

        try {
            return mapper.writeValueAsString(array);
        } catch (IOException e) {
            log.error("[MonthlyMonitor] daily_results_json 직렬화 실패", e);
            return "";
        }
    }

    /**
     * DailyMonitorService.saveDailyAnomalyResult()가 저장한 .dat 파일(reportDate/errorCount/warnCount/
     * uniqueIssueCount/reportText 순서의 key=value 라인)을 파싱해 array에 JSON 객체로 추가한다.
     * reportText는 LLM 응답이라 내부에 줄바꿈을 포함할 수 있어 앞 네 줄만 분리하고 나머지 전체를 reportText로 취급한다.
     */
    private void appendDailyResult(ArrayNode array, ObjectMapper mapper, File file) {

        try {

            String content = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            String[] parts = content.split(System.lineSeparator(), 5);

            if (parts.length < 5) {
                log.warn("[MonthlyMonitor] daily 결과 형식이 올바르지 않아 건너뜀 : {}", file.getName());
                return;
            }

            ObjectNode node = mapper.createObjectNode();
            node.put("date", stripPrefix(parts[0], "reportDate="));
            node.put("error_count", parseIntSafely(stripPrefix(parts[1], "errorCount=")));
            node.put("warn_count", parseIntSafely(stripPrefix(parts[2], "warnCount=")));
            node.put("unique_issue_count", parseIntSafely(stripPrefix(parts[3], "uniqueIssueCount=")));
            node.put("report_text", stripPrefix(parts[4], "reportText="));

            array.add(node);

        } catch (IOException e) {
            log.warn("[MonthlyMonitor] daily 결과 읽기 실패 : {}", file.getAbsolutePath(), e);
        }
    }

    private String stripPrefix(String line, String prefix) {
        return line.startsWith(prefix) ? line.substring(prefix.length()) : line;
    }

    private int parseIntSafely(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 취합된 daily_results_json을 통합 Dify 워크플로우(mode=monthly_report)에 전달해 월간 운영보고를 요청한다.
     * start 노드의 log_content는 required=true(모드 무관 공통 필드)라 monthly_report 모드에서도 값이 필요하지만
     * 실제로 사용되지 않으므로 대상 연월을 명시하는 placeholder 문자열만 채운다.
     */
    public MonthlyReportResult requestMonthlyReportToDify(String dailyResultsJson, YearMonth targetYearMonth) {

        if (dailyResultsJson == null || dailyResultsJson.trim().isEmpty()) {
            return MonthlyReportResult.builder()
                    .status("no_data")
                    .dayCount(0)
                    .message("집계할 일별 결과 데이터가 없습니다.")
                    .targetYearMonth(targetYearMonth)
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
                conn.setRequestProperty(
                        "User-Agent",
                        "LogAnalyzer-Batch/1.0"
                );

                ObjectMapper mapper = new ObjectMapper();

                // 통합 워크플로우 입력 폼 변수 구성 (docs/logSuite_integrated_FIN_monthly.yml 참고)
                ObjectNode inputs = mapper.createObjectNode();
                inputs.put("mode", DifyMode.MONTHLY_REPORT);
                inputs.put("log_content", "[monthly_report] target=" + targetYearMonth + " (실제 분석 대상은 daily_results_json)");
                inputs.put("daily_results_json", dailyResultsJson);

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

                log.info("[MonthlyMonitor] Dify 응답 (monthly_report) : {}", response);

                JsonNode json = response.isEmpty() ? mapper.createObjectNode() : mapper.readTree(response);

                if (statusCode >= 400 && statusCode < 500) {
                    throw new DifyClientErrorException(
                            "[monthly_report] Dify API 클라이언트 오류 (" + statusCode + ") : "
                                    + json.path("message").asText(response)
                    );
                }

                if (statusCode >= 500) {
                    throw new DifyApiException(
                            "[monthly_report] Dify API 서버 오류 (" + statusCode + ") : "
                                    + json.path("message").asText(response)
                    );
                }

                String status = json.path("data").path("status").asText("");

                if (!"succeeded".equals(status)) {
                    String error = json.path("data").path("error").asText("unknown");
                    throw new DifyClientErrorException(
                            "[monthly_report] Dify 워크플로우 실패 - status=" + status + ", error=" + error
                    );
                }

                JsonNode outputs = json.path("data").path("outputs");

                String monthlyReportResultRaw = outputs.path("monthly_report_result").asText("").trim();

                if (monthlyReportResultRaw.isEmpty()) {
                    throw new ResponseMappingException(
                            "[monthly_report] 응답에 outputs.monthly_report_result가 없습니다 : " + response
                    );
                }

                return mapToMonthlyReportResult(mapper, monthlyReportResultRaw, targetYearMonth);

            } catch (ResponseMappingException | DifyClientErrorException e) {

                throw e;

            } catch (Exception e) {

                lastFailure = e;

                log.warn(
                        "[MonthlyMonitor] Dify 호출 실패 (monthly_report) (attempt {}/{})",
                        attempt,
                        maxAttempts,
                        e
                );
            }
        }

        throw new DifyApiException(
                "[monthly_report] Dify 호출 " + maxAttempts + "회 재시도 후 실패",
                lastFailure
        );
    }

    /**
     * outputs.monthly_report_result(mr-merge가 만든 JSON 문자열)를 파싱한다.
     * status=no_data(또는 monthly_report가 없는 경우)에는 message/day_count만 채워 반환한다.
     */
    private MonthlyReportResult mapToMonthlyReportResult(
            ObjectMapper mapper,
            String monthlyReportResultRaw,
            YearMonth targetYearMonth
    ) throws IOException {

        JsonNode resultJson = mapper.readTree(monthlyReportResultRaw);

        String status = resultJson.path("status").asText("");
        int dayCount = resultJson.path("day_count").asInt(0);
        JsonNode monthlyReport = resultJson.path("monthly_report");

        if ("no_data".equals(status) || monthlyReport.isMissingNode() || monthlyReport.isNull()) {
            return MonthlyReportResult.builder()
                    .status(status)
                    .dayCount(dayCount)
                    .message(resultJson.path("message").asText(""))
                    .targetYearMonth(targetYearMonth)
                    .build();
        }

        return MonthlyReportResult.builder()
                .status(status)
                .dayCount(dayCount)
                .overallStatus(monthlyReport.path("overall_status").asText(""))
                .monthlySummary(monthlyReport.path("monthly_summary").asText(""))
                .majorIssues(toStringList(monthlyReport.path("major_issues")))
                .recurringPatterns(toStringList(monthlyReport.path("recurring_patterns")))
                .trendSummary(monthlyReport.path("trend_summary").asText(""))
                .recommendations(toStringList(monthlyReport.path("recommendations")))
                .reportText(monthlyReport.path("report_text").asText(""))
                .message(resultJson.path("message").asText(""))
                .targetYearMonth(targetYearMonth)
                .build();
    }

    private List<String> toStringList(JsonNode arrayNode) {

        List<String> result = new ArrayList<>();

        if (arrayNode != null && arrayNode.isArray()) {
            for (JsonNode item : arrayNode) {
                result.add(item.asText(""));
            }
        }

        return result;
    }

    public void saveMonthlyResult(MonthlyReportResult result, YearMonth targetYearMonth) {

        File dir = new File(MONTHLY_RESULT_DIR);

        if (!dir.exists()) {
            dir.mkdirs();
        }

        File file = new File(dir, targetYearMonth.format(MONTH_FORMATTER) + ".dat");

        String content =
                "status=" + result.getStatus() + System.lineSeparator()
                        + "dayCount=" + result.getDayCount() + System.lineSeparator()
                        + "overallStatus=" + nullToEmpty(result.getOverallStatus()) + System.lineSeparator()
                        + "monthlySummary=" + nullToEmpty(result.getMonthlySummary()) + System.lineSeparator()
                        + "trendSummary=" + nullToEmpty(result.getTrendSummary()) + System.lineSeparator()
                        + "majorIssues=" + joinOrEmpty(result.getMajorIssues()) + System.lineSeparator()
                        + "recurringPatterns=" + joinOrEmpty(result.getRecurringPatterns()) + System.lineSeparator()
                        + "recommendations=" + joinOrEmpty(result.getRecommendations()) + System.lineSeparator()
                        + "message=" + nullToEmpty(result.getMessage()) + System.lineSeparator()
                        + "reportText=" + nullToEmpty(result.getReportText());

        try (OutputStream os = new FileOutputStream(file)) {
            os.write(content.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.error(
                    "[MonthlyMonitor] 결과 저장 실패 : {}",
                    file.getAbsolutePath(),
                    e
            );
            return;
        }

        log.info(
                "[MonthlyMonitor] 결과 저장 완료 : {}",
                file.getAbsolutePath()
        );
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String joinOrEmpty(List<String> values) {
        return values == null ? "" : String.join(" | ", values);
    }
}
