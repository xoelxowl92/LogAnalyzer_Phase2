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
    private final HourlyMonitorService hourlyMonitorService;
    private final DailyMonitorService dailyMonitorService;
    private final MonthlyMonitorService monthlyMonitorService;

    /** 최초 1회 실행. logFilePath를 JobParameter로 전달받아 인코딩/날짜형식/타임존을 자동 탐지 후 config/setup.properties에 저장한다. */
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

                    String dateFormat = setupService.requestDateFormatToDify(sampleLog);

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

    /** 1분 단위 실행. 최근 로그를 Dify에 전달하여 장애 여부를 판단한다. */
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



    /** 1시간 단위 실행. 통합 Dify 워크플로우(mode=anomaly)로 이상 패턴 분석 결과를 파일로 저장한다. */
    @Bean
    public Job hourlyMonitorJob() {
        return jobBuilderFactory.get("hourlyMonitorJob")
                .start(hourlyMonitorStep())
                .build();
    }

    @Bean
    public Step hourlyMonitorStep() {

        return stepBuilderFactory
                .get("hourlyMonitorStep")
                .tasklet((contribution, chunkContext) -> {
                    hourlyMonitorService.execute();
                    return RepeatStatus.FINISHED;
                })
                .build();
    }

    /** 1일 단위 실행. hourly anomaly 결과를 취합하여 통합 Dify 워크플로우(mode=daily_report)에 일간 보고를 요청한다. */
    @Bean
    public Job dailyMonitorJob() {
        return jobBuilderFactory.get("dailyMonitorJob")
                .start(dailyMonitorStep())
                .build();
    }

    @Bean
    public Step dailyMonitorStep() {

        return stepBuilderFactory
                .get("dailyMonitorStep")
                .tasklet((contribution, chunkContext) -> {
                    dailyMonitorService.execute();
                    return RepeatStatus.FINISHED;
                })
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

    /** 1개월 단위 실행. 전월 daily anomaly 결과를 취합하여 통합 Dify 워크플로우(mode=monthly_report)에 월간 보고를 요청한다. */
    @Bean
    public Job monthlyMonitorJob() {
        return jobBuilderFactory.get("monthlyMonitorJob")
                .start(monthlyMonitorStep())
                .build();
    }

    @Bean
    public Step monthlyMonitorStep() {

        return stepBuilderFactory
                .get("monthlyMonitorStep")
                .tasklet((contribution, chunkContext) -> {
                    monthlyMonitorService.execute();
                    return RepeatStatus.FINISHED;
                })
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
