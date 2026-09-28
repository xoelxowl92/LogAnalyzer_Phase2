package com.loganalyzer.web;

import com.loganalyzer.batch.BatchHistoryEntry;
import com.loganalyzer.batch.BatchScheduleHistoryService;
import com.loganalyzer.batch.DailyAnomalyResult;
import com.loganalyzer.batch.DailyMonitorService;
import com.loganalyzer.batch.HourlyMonitorResult;
import com.loganalyzer.batch.HourlyMonitorService;
import com.loganalyzer.batch.MinuteMonitorResult;
import com.loganalyzer.batch.MinuteMonitorService;
import com.loganalyzer.batch.MonthlyMonitorService;
import com.loganalyzer.batch.MonthlyReportResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.context.ApplicationContext;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * 배치 실행 API.
 * <p>
 * 이 컨트롤러에는 서로 완전히 독립적인 두 개의 실행 흐름이 공존한다. 같은 배치(Job)를 실행시키지만
 * 서로의 상태를 전혀 참조하지 않으므로, 한쪽이 켜져 있어도/꺼져 있어도 다른 쪽 동작에는 영향이 없다.
 * <ol>
 *   <li><b>1회성 실행</b> — {@code POST /run/{jobName}} (아래 {@link #runJob}, {@link #runSetupJob}).
 *       화면의 "실행" 버튼에서 호출되며, {@link JobLauncher#run}으로 그 자리에서 딱 1번만 실행하고 끝난다.
 *       기준시간(baseTime)을 함께 받아 참고하는 배치(1시간/1일/1월)는 그 값을 기준으로 동작한다.</li>
 *   <li><b>반복 스케쥴 등록</b> — {@code POST /schedule/**} (아래 {@link #toggleSchedule},
 *       {@link #startAllSchedules}, {@link #stopAllSchedules}). 화면의 "스케쥴링" 팝업에서 호출되며,
 *       {@link #scheduledJobs}에 등록된 {@link TaskScheduler}의 {@link CronTrigger}가 벽시계 기준으로
 *       (매분 0초/매시 정각/매일 자정/매월 1일 자정) 계속 반복 실행한다. 이때는 baseTime을 넣지 않으므로 항상
 *       "실행되는 그 순간"을 기준으로 동작한다({@link #runScheduledJob}).</li>
 * </ol>
 * setupJob은 logFilePath 파라미터가 필요하므로 별도 엔드포인트로 분리.
 */
@Slf4j
@RestController
@RequestMapping("/api/batch")
@RequiredArgsConstructor
public class BatchController {

    private final JobLauncher jobLauncher;
    private final ApplicationContext applicationContext;
    private final TaskScheduler taskScheduler;
    private final BatchScheduleHistoryService batchScheduleHistoryService;
    private final MinuteMonitorService minuteMonitorService;
    private final HourlyMonitorService hourlyMonitorService;
    private final DailyMonitorService dailyMonitorService;
    private final MonthlyMonitorService monthlyMonitorService;

    private final Map<String, ScheduledFuture<?>> scheduledJobs = new ConcurrentHashMap<>();

    /** 스케쥴링 모달에 표시할 배치 노출 순서. JOB_CRONS(HashMap)는 순서를 보장하지 않아 별도 정의. */
    private static final List<String> SCHEDULE_JOB_ORDER = Collections.unmodifiableList(Arrays.asList(
            "minuteMonitorJob", "hourlyMonitorJob", "dailyMonitorJob", "monthlyMonitorJob"));
    private static final int SCHEDULE_HISTORY_LIMIT = 10;

    /** 스케쥴링 일괄 시작/중단 대상 배치. */
    private static final List<String> BULK_SCHEDULE_JOBS = Collections.unmodifiableList(Arrays.asList(
            "minuteMonitorJob", "hourlyMonitorJob", "dailyMonitorJob", "monthlyMonitorJob"));

    /**
     * 배치별 반복 실행 cron 표현식 (초 분 시 일 월 요일). toggle 대상 배치는 여기 등록되어야 한다.
     * 매분 0초 / 매시 정각 / 매일 자정에 맞춰 벽시계 기준으로 돈다.
     */
    private static final Map<String, String> JOB_CRONS;
    private static final Map<String, String> JOB_SCHEDULE_LABELS;

    static {
        Map<String, String> crons = new HashMap<>();
        crons.put("minuteMonitorJob", "0 * * * * *");
        crons.put("hourlyMonitorJob", "0 0 * * * *");
        crons.put("dailyMonitorJob", "0 0 0 * * *");
        crons.put("monthlyMonitorJob", "0 0 0 1 * *");
        JOB_CRONS = Collections.unmodifiableMap(crons);

        Map<String, String> labels = new HashMap<>();
        labels.put("minuteMonitorJob", "매분 0초");
        labels.put("hourlyMonitorJob", "매시 정각");
        labels.put("dailyMonitorJob", "매일 자정");
        labels.put("monthlyMonitorJob", "매월 1일 자정");
        JOB_SCHEDULE_LABELS = Collections.unmodifiableMap(labels);
    }

    /**
     * 초기 설정 배치 실행.
     * 웹 모달 팝업에서 입력한 logFilePath를 JobParameter로 전달.
     */
    @PostMapping("/run/setupJob")
    public ResponseEntity<Map<String, Object>> runSetupJob(@RequestBody Map<String, String> body) {
        Map<String, Object> result = new HashMap<>();
        String logFilePath = body.get("logFilePath");
        if (logFilePath == null || logFilePath.trim().isEmpty()) {
            result.put("status", "FAILED");
            result.put("error", "logFilePath는 필수입니다.");
            return ResponseEntity.badRequest().body(result);
        }
        try {
            Job job = applicationContext.getBean("setupJob", Job.class);
            JobParameters params = new JobParametersBuilder()
                    .addString("logFilePath", logFilePath)
                    .addLong("timestamp", System.currentTimeMillis())
                    .toJobParameters();
            JobExecution execution = jobLauncher.run(job, params);

            result.put("jobName", "setupJob");
            result.put("status", execution.getStatus().toString());
            result.put("jobExecutionId", execution.getId());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("[setupJob] 실행 실패: {}", e.getMessage());
            result.put("jobName", "setupJob");
            result.put("status", "FAILED");
            result.put("error", e.getMessage());
            return ResponseEntity.status(500).body(result);
        }
    }

    // ── 1회성 실행 (화면 "실행" 버튼) ───────────────────────────────
    // scheduledJobs를 전혀 참조하지 않는다 - 스케쥴 등록 여부와 무관하게 그 자리에서 1번만 실행된다.

    /**
     * 일반 배치 1회성 실행 (1분 / 1시간 / 1일 / 1월).
     * jobName은 Spring Context에 등록된 Bean 이름과 일치해야 함.
     * body의 baseTime은 화면 "기준시간" 선택값으로, 이를 참고하는 배치에만 사용되고 나머지 배치는
     * 무시한다 - 1시간은 그 시각 기준, 1일은 그 기준일 00시~기준시간까지, 1월은 그 기준월 1일부터
     * 기준일까지 날짜별로 1시간 배치를 먼저 채운 뒤 실행한다 ({@link com.loganalyzer.batch.BatchConfig}
     * 참고). 비어있으면 각 배치가 현재 시각(1분/1시간)/전일(1일)/전월(1월) 기준으로 실행한다.
     */
    @PostMapping("/run/{jobName}")
    public ResponseEntity<Map<String, Object>> runJob(
            @PathVariable String jobName,
            @RequestBody(required = false) Map<String, String> body) {
        Map<String, Object> result = new HashMap<>();
        try {
            Job job = applicationContext.getBean(jobName, Job.class);
            JobParametersBuilder paramsBuilder = new JobParametersBuilder()
                    .addLong("timestamp", System.currentTimeMillis());

            String baseTime = body != null ? body.get("baseTime") : null;
            if (baseTime != null && !baseTime.trim().isEmpty()) {
                paramsBuilder.addString("baseTime", baseTime.trim());
            }

            JobExecution execution = jobLauncher.run(job, paramsBuilder.toJobParameters());

            result.put("jobName", jobName);
            result.put("status", execution.getStatus().toString());
            result.put("jobExecutionId", execution.getId());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("[{}] 배치 실행 실패: {}", jobName, e.getMessage());
            result.put("jobName", jobName);
            result.put("status", "FAILED");
            result.put("error", e.getMessage());
            return ResponseEntity.status(500).body(result);
        }
    }

    /**
     * 1분 배치의 가장 최근 실행 결과를 조회한다. 웹 화면 "실행 결과 보기" 버튼 전용.
     * status는 최근 JobExecution의 성공/실패, isFault/summary/executedAt은 output/minute에 저장된
     * 실제 Dify 분석 결과다. 아직 한 번도 실행된 적이 없으면 status/isFault/summary/executedAt이 모두 null이다.
     */
    @GetMapping("/run/minuteMonitorJob/last-result")
    public ResponseEntity<Map<String, Object>> getMinuteMonitorLastResult() {
        Map<String, Object> result = new HashMap<>();

        List<BatchHistoryEntry> history = batchScheduleHistoryService.getRecentHistory("minuteMonitorJob", 1);
        result.put("status", history.isEmpty() ? null : history.get(0).getStatus());

        MinuteMonitorResult latest = minuteMonitorService.loadLatestResult();
        result.put("isFault", latest != null ? latest.isFault() : null);
        result.put("summary", latest != null ? latest.getSummary() : null);
        result.put("executedAt", latest != null ? latest.getExecutedAt() : null);

        return ResponseEntity.ok(result);
    }

    /**
     * 1시간 배치의 가장 최근 실행 결과를 조회한다. 웹 화면 "결과 보기" 버튼 전용.
     * status는 최근 JobExecution의 성공/실패, anomalyDetected/severity/message/targetHour는 output/hourly/anomaly에
     * 저장된 실제 Dify 분석 결과다. 아직 한 번도 실행된 적이 없으면 모든 필드가 null이다.
     */
    @GetMapping("/run/hourlyMonitorJob/last-result")
    public ResponseEntity<Map<String, Object>> getHourlyMonitorLastResult() {
        Map<String, Object> result = new HashMap<>();

        List<BatchHistoryEntry> history = batchScheduleHistoryService.getRecentHistory("hourlyMonitorJob", 1);
        result.put("status", history.isEmpty() ? null : history.get(0).getStatus());

        HourlyMonitorResult latest = hourlyMonitorService.loadLatestResult();
        result.put("anomalyDetected", latest != null ? latest.isAnomalyDetected() : null);
        result.put("severity", latest != null ? latest.getSeverity() : null);
        result.put("message", latest != null ? latest.getMessage() : null);
        result.put("targetHour", latest != null ? latest.getTargetHour() : null);

        return ResponseEntity.ok(result);
    }

    /**
     * 1일 배치의 가장 최근 실행 결과를 조회한다. 웹 화면 "결과 보기" 버튼 전용.
     * status는 최근 JobExecution의 성공/실패, 나머지 필드는 output/daily/anomaly에 저장된 실제 Dify 일일
     * 보고 결과다. 저장된 결과가 없으면 status 외의 필드는 응답에 포함되지 않는다.
     */
    @GetMapping("/run/dailyMonitorJob/last-result")
    public ResponseEntity<Map<String, Object>> getDailyMonitorLastResult() {
        Map<String, Object> result = new HashMap<>();

        List<BatchHistoryEntry> history = batchScheduleHistoryService.getRecentHistory("dailyMonitorJob", 1);
        result.put("status", history.isEmpty() ? null : history.get(0).getStatus());

        DailyAnomalyResult latest = dailyMonitorService.loadLatestResult();
        if (latest != null) {
            result.put("reportDate", latest.getReportDate() != null ? latest.getReportDate().toString() : null);
            result.put("errorCount", latest.getErrorCount());
            result.put("warnCount", latest.getWarnCount());
            result.put("uniqueIssueCount", latest.getUniqueIssueCount());
            result.put("reportText", latest.getReportText());
        }

        return ResponseEntity.ok(result);
    }

    /**
     * 1월 배치의 가장 최근 실행 결과를 조회한다. 웹 화면 "결과 보기" 버튼 전용.
     * status는 최근 JobExecution의 성공/실패, 나머지 필드는 output/monthly에 저장된 실제 Dify 월간
     * 보고 결과다. 아직 한 번도 실행된 적이 없으면 status를 제외한 모든 필드가 null/빈 값이다.
     */
    @GetMapping("/run/monthlyMonitorJob/last-result")
    public ResponseEntity<Map<String, Object>> getMonthlyMonitorLastResult() {
        Map<String, Object> result = new HashMap<>();

        List<BatchHistoryEntry> history = batchScheduleHistoryService.getRecentHistory("monthlyMonitorJob", 1);
        result.put("status", history.isEmpty() ? null : history.get(0).getStatus());

        MonthlyReportResult latest = monthlyMonitorService.loadLatestResult();
        if (latest != null) {
            result.put("targetYearMonth", latest.getTargetYearMonth() != null ? latest.getTargetYearMonth().toString() : null);
            result.put("reportStatus", latest.getStatus());
            result.put("dayCount", latest.getDayCount());
            result.put("overallStatus", latest.getOverallStatus());
            result.put("monthlySummary", latest.getMonthlySummary());
            result.put("majorIssues", latest.getMajorIssues());
            result.put("recurringPatterns", latest.getRecurringPatterns());
            result.put("trendSummary", latest.getTrendSummary());
            result.put("recommendations", latest.getRecommendations());
            result.put("reportText", latest.getReportText());
            result.put("message", latest.getMessage());
        }

        return ResponseEntity.ok(result);
    }

    // ── 반복 스케쥴 등록 (화면 "스케쥴링" 팝업) ───────────────────────
    // scheduledJobs(ConcurrentHashMap)로 등록 상태를 관리하며, 위 1회성 실행 API와는 서로의 상태를
    // 참조하지 않는 별개의 흐름이다. 등록된 반복 실행은 baseTime 없이 항상 "실행되는 그 순간"을
    // 기준으로 동작한다 (runScheduledJob 참고).

    /**
     * 배치별 지정 cron(JOB_CRONS) 반복 실행 토글. 실행 중이 아니면 시작, 실행 중이면 중지.
     * 토글 시점에 1회 즉시 실행하고, 이후에는 CronTrigger로 매분 0초/매시 정각/매일 자정 등
     * 벽시계 기준 시각에 맞춰 반복 실행한다.
     */
    @PostMapping("/schedule/{jobName}/toggle")
    public ResponseEntity<Map<String, Object>> toggleSchedule(@PathVariable String jobName) {
        Map<String, Object> result = new HashMap<>();
        result.put("jobName", jobName);

        if (scheduledJobs.containsKey(jobName)) {
            stopJobSchedule(jobName);
            result.put("scheduled", false);
            return ResponseEntity.ok(result);
        }

        if (!JOB_CRONS.containsKey(jobName)) {
            result.put("scheduled", false);
            result.put("error", "반복 실행을 지원하지 않는 배치입니다: " + jobName);
            return ResponseEntity.badRequest().body(result);
        }

        startJobSchedule(jobName);
        result.put("scheduled", true);
        result.put("scheduleLabel", JOB_SCHEDULE_LABELS.get(jobName));
        return ResponseEntity.ok(result);
    }

    /**
     * BULK_SCHEDULE_JOBS(1분/1시간/1일/1월)를 한 번에 시작한다. 이미 실행 중인 배치는 건드리지 않는다.
     */
    @PostMapping("/schedule/start-all")
    public ResponseEntity<Map<String, Object>> startAllSchedules() {
        return applyBulkSchedule(true);
    }

    /**
     * BULK_SCHEDULE_JOBS(1분/1시간/1일/1월)를 한 번에 중단한다. 이미 중지된 배치는 건드리지 않는다.
     */
    @PostMapping("/schedule/stop-all")
    public ResponseEntity<Map<String, Object>> stopAllSchedules() {
        return applyBulkSchedule(false);
    }

    /**
     * jobName의 반복 실행을 시작한다. 호출 전 scheduledJobs에 존재하지 않는지(미실행 상태인지) 확인은 호출부 책임.
     * 토글과 동일하게 시작 시점에 1회 즉시 실행 후 cron 기준으로 반복 실행을 등록한다.
     */
    private void startJobSchedule(String jobName) {
        String cron = JOB_CRONS.get(jobName);
        Job job = applicationContext.getBean(jobName, Job.class);

        // 시작 시점 즉시 1회 실행 (HTTP 응답을 막지 않도록 스케줄러 스레드에서 비동기 실행)
        taskScheduler.schedule(() -> runScheduledJob(jobName, job), Instant.now());

        // 이후에는 cron 표현식이 가리키는 벽시계 시각(매분 0초/매시 정각/매일 자정)마다 반복 실행
        ScheduledFuture<?> future = taskScheduler.schedule(
                () -> runScheduledJob(jobName, job),
                new CronTrigger(cron)
        );

        scheduledJobs.put(jobName, future);
        log.info("[{}] cron({}) 기준 반복 실행 시작", jobName, cron);
    }

    /** jobName의 반복 실행을 중단한다. scheduledJobs에 없으면(이미 중지 상태) 아무 것도 하지 않는다. */
    private void stopJobSchedule(String jobName) {
        ScheduledFuture<?> existing = scheduledJobs.remove(jobName);
        if (existing != null) {
            existing.cancel(false);
            log.info("[{}] 반복 실행 중지", jobName);
        }
    }

    /**
     * BULK_SCHEDULE_JOBS 대상만 일괄 시작/중단한다. 이미 목표 상태인 job은 건드리지 않아
     * (재시작으로 인한 중복 즉시실행/cron 재등록 방지) 기존 개별 toggle과 충돌하지 않는다.
     */
    private ResponseEntity<Map<String, Object>> applyBulkSchedule(boolean turnOn) {
        Map<String, Object> perJobResult = new LinkedHashMap<>();

        for (String jobName : BULK_SCHEDULE_JOBS) {
            Map<String, Object> jobResult = new HashMap<>();
            boolean alreadyTarget = scheduledJobs.containsKey(jobName) == turnOn;

            if (alreadyTarget) {
                jobResult.put("scheduled", turnOn);
                jobResult.put("skipped", true);
            } else {
                try {
                    if (turnOn) {
                        startJobSchedule(jobName);
                    } else {
                        stopJobSchedule(jobName);
                    }
                    jobResult.put("scheduled", turnOn);
                } catch (Exception e) {
                    // 개별 job 실패가 나머지 job 처리에 영향을 주지 않도록 격리 (getScheduleStatus와 동일한 패턴)
                    log.error("[{}] 일괄 {} 실패: {}", jobName, turnOn ? "시작" : "중단", e.getMessage(), e);
                    jobResult.put("scheduled", scheduledJobs.containsKey(jobName));
                    jobResult.put("error", e.getMessage());
                }
            }
            perJobResult.put(jobName, jobResult);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("action", turnOn ? "start-all" : "stop-all");
        result.put("results", perJobResult);
        return ResponseEntity.ok(result);
    }

    /**
     * 배치별 반복 실행 on/off 상태와 최근 수행 이력(success/fail)을 조회한다. 스케쥴링 모달 전용 조회 API.
     */
    @GetMapping("/schedule/status")
    public ResponseEntity<Map<String, Object>> getScheduleStatus() {
        Map<String, Object> jobs = new LinkedHashMap<>();

        for (String jobName : SCHEDULE_JOB_ORDER) {
            Map<String, Object> jobStatus = new HashMap<>();
            jobStatus.put("scheduleLabel", JOB_SCHEDULE_LABELS.get(jobName));
            jobStatus.put("scheduled", scheduledJobs.containsKey(jobName));

            List<BatchHistoryEntry> history;
            try {
                history = batchScheduleHistoryService.getRecentHistory(jobName, SCHEDULE_HISTORY_LIMIT);
            } catch (Exception e) {
                // job 1건의 이력 조회 실패가 나머지 job 조회에 영향을 주지 않도록 격리
                log.error("[{}] 스케줄 이력 조회 실패: {}", jobName, e.getMessage(), e);
                history = Collections.emptyList();
            }
            jobStatus.put("history", history);

            jobs.put(jobName, jobStatus);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("jobs", jobs);
        return ResponseEntity.ok(result);
    }

    /**
     * 스케쥴 등록(startJobSchedule)에 의해 cron 시각마다 반복 호출되는 실행부.
     * 1회성 실행({@link #runJob})과 달리 baseTime을 전혀 넣지 않으므로, 1시간/1일/1월 배치는
     * 항상 이 메서드가 호출되는 순간의 현재 시각(1시간)/전일(1일)/전월(1월)을 기준으로 동작한다.
     */
    private void runScheduledJob(String jobName, Job job) {
        try {
            JobParameters params = new JobParametersBuilder()
                    .addLong("timestamp", System.currentTimeMillis())
                    .toJobParameters();
            JobExecution execution = jobLauncher.run(job, params);
            log.info("[{}] 반복 실행 완료 - status={}", jobName, execution.getStatus());
        } catch (Exception e) {
            log.error("[{}] 반복 실행 실패: {}", jobName, e.getMessage());
        }
    }
}
