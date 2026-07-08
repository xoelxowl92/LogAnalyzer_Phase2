package com.loganalyzer.web;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.context.ApplicationContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 배치 수동 실행 API.
 * setupJob은 logFilePath 파라미터가 필요하므로 별도 엔드포인트로 분리.
 */
@Slf4j
@RestController
@RequestMapping("/api/batch")
@RequiredArgsConstructor
public class BatchController {

    private final JobLauncher jobLauncher;
    private final ApplicationContext applicationContext;

    /**
     * 초기 설정 배치 실행.
     * 웹 모달 팝업에서 입력한 logFilePath를 JobParameter로 전달.
     */
    @PostMapping("/run/setupJob")
    public ResponseEntity<Map<String, Object>> runSetupJob(@RequestBody Map<String, String> body) {
        Map<String, Object> result = new HashMap<>();
        String logFilePath = body.get("logFilePath");
        if (logFilePath == null || logFilePath.isBlank()) {
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

    /**
     * 일반 배치 수동 실행 (1분 / 1시간 / 1일).
     * jobName은 Spring Context에 등록된 Bean 이름과 일치해야 함.
     */
    @PostMapping("/run/{jobName}")
    public ResponseEntity<Map<String, Object>> runJob(@PathVariable String jobName) {
        Map<String, Object> result = new HashMap<>();
        try {
            Job job = applicationContext.getBean(jobName, Job.class);
            JobParameters params = new JobParametersBuilder()
                    .addLong("timestamp", System.currentTimeMillis())
                    .toJobParameters();
            JobExecution execution = jobLauncher.run(job, params);

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
}
