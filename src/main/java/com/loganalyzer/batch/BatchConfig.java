package com.loganalyzer.batch;

import com.loganalyzer.setup.SetupConfig;
import com.loganalyzer.setup.SetupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing;
import org.springframework.batch.core.configuration.annotation.JobBuilderFactory;
import org.springframework.batch.core.configuration.annotation.StepBuilderFactory;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 전체 배치 Job/Step 등록.
 * setupJob은 최초 1회 실행, 나머지는 스케줄러로 주기적 실행.
 */
@Slf4j
@Configuration
@EnableBatchProcessing
@RequiredArgsConstructor
public class BatchConfig {

    private final JobBuilderFactory jobBuilderFactory;
    private final StepBuilderFactory stepBuilderFactory;
    private final SetupService setupService;
    private final MinuteMonitorService minuteMonitorService;

    @Bean
    public Job setupJob() {
        return jobBuilderFactory.get("setupJob")
                .start(setupStep())
                .build();
    }

    @Bean
    public Step setupStep() {
        return stepBuilderFactory.get("setupStep")
                .tasklet((contribution, chunkContext) -> {
                    // BatchController에서 logFilePath를 JobParameter로 전달받음
                    String logFilePath = (String) chunkContext.getStepContext()
                            .getJobParameters().get("logFilePath");

                    String validatedPath = setupService.configureLogFilePath(logFilePath);
                    String encoding     = setupService.detectEncoding(validatedPath);
                    String sampleLog    = setupService.readSampleLog(validatedPath, encoding, 100);

                    // Dify 연동 전 임시 고정값. 실제 로그 형식: "yyyy-MM-dd HH:mm:ss"
                    // String dateFormat = setupService.requestDateFormatToDify(sampleLog); // TODO: Dify 연동 후 활성화
                    String dateFormat = "yyyy-MM-dd HH:mm:ss";

                    String timezone = setupService.detectTimezone(sampleLog, dateFormat);

                    SetupConfig config = SetupConfig.builder()
                            .logFilePath(validatedPath)
                            .encoding(encoding)
                            .dateFormat(dateFormat)
                            .timezone(timezone)
                            .build();

                    setupService.saveSetupConfig(config);
                    return RepeatStatus.FINISHED;
                })
                .build();
    }

    // ── 이하 배치는 실제 구현 전 placeholder ────────────────────────────

    @Bean
    public Job minuteMonitorJob() {
        return jobBuilderFactory.get("minuteMonitorJob")
                .start(minuteMonitorStep())
                .build();
    }

    @Bean
    public Step minuteMonitorStep() {

        return stepBuilderFactory
                .get("minuteMonitorStep")
                .tasklet((contribution, chunkContext) -> {
                    minuteMonitorService.execute();
                    return RepeatStatus.FINISHED;
                })
                .build();
    }    



    @Bean
    public Job hourlyMonitorJob() {
        return jobBuilderFactory.get("hourlyMonitorJob")
                .start(placeholderStep("hourlyMonitorStep"))
                .build();
    }

    @Bean
    public Job dailyMonitorJob() {
        return jobBuilderFactory.get("dailyMonitorJob")
                .start(placeholderStep("dailyMonitorStep"))
                .build();
    }

    // 테스트 화면 전용 - hourly → daily 순서 확인용. 운영에서는 사용하지 않음
    @Bean
    public Job testDailyMonitorJob() {
        return jobBuilderFactory.get("testDailyMonitorJob")
                .start(placeholderStep("hourlyMonitorStep-for-daily"))
                .next(placeholderStep("dailyMonitorStep-for-daily"))
                .build();
    }

    @Bean
    public Job monthlyMonitorJob() {
        return jobBuilderFactory.get("monthlyMonitorJob")
                .start(placeholderStep("monthlyMonitorStep"))
                .build();
    }

    private Step placeholderStep(String name) {
        return stepBuilderFactory.get(name)
                .tasklet((contribution, chunkContext) -> {
                    String jobName = chunkContext.getStepContext().getJobName();
                    log.info("[{}] placeholder 실행됨 — 실제 구현 전 테스트용", jobName);
                    return RepeatStatus.FINISHED;
                })
                .build();
    }
}
