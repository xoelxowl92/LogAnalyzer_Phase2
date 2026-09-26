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
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 전체 배치 Job/Step 등록.
 * setupJob은 최초 1회 실행, 나머지는 스케줄러로 주기적 실행.
 * <p>
 * 여기 등록된 Job 빈(hourlyMonitorJob, dailyMonitorJob 등)은 화면 "실행" 버튼의 1회성 실행
 * ({@code BatchController.runJob}, baseTime 있음)과 "스케쥴링" 팝업의 반복 실행
 * ({@code BatchController.runScheduledJob}, baseTime 없음) 양쪽에서 똑같이 재사용된다.
 * 즉 Step 자신은 "누가 호출했는지"를 모르며, 오직 전달받은 baseTime JobParameter의 유무로만
 * 두 흐름을 구분한다 - baseTime이 있으면 화면에서 지정한 기준시간/기준일 기준, 없으면 항상
 * 현재 시각 기준으로 동작한다.
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
    private final DailyBatchOrchestrationService dailyBatchOrchestrationService;
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
                .tasklet(hourlyMonitorTasklet(null))
                .build();
    }

    /**
     * baseTime 있음 = 화면 "실행" 버튼(1회성, 기준시간 지정)
     * baseTime 없음 = "스케쥴링" 팝업의 반복 실행(항상 현재 시각 기준) - runScheduledJob은 이 값을 넣지 않는다
     * <p>
     * {@code @StepScope}로 선언해 baseTime을 JobParameter에서 늦은 바인딩으로 직접 주입받는다 -
     * {@code chunkContext.getStepContext().getJobParameters().get(...)}로 꺼내는 것보다 타입 안정성과
     * 테스트 용이성이 높다. 생성자에 넘기는 {@code null}은 이 팩토리 메서드가 실제로 즉시 호출되는 게
     * 아니라 Step 스코프 프록시로 대체되므로 무시된다(Spring Batch 관례).
     */
    @StepScope
    @Bean
    public Tasklet hourlyMonitorTasklet(@Value("#{jobParameters['baseTime']}") String baseTime) {
        return (contribution, chunkContext) -> {
            hourlyMonitorService.execute(baseTime);
            return RepeatStatus.FINISHED;
        };
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
                .tasklet(dailyMonitorTasklet(null))
                .build();
    }

    /**
     * baseTime 있음 = 화면 "실행" 버튼(1회성, 기준시간 지정 → 기준일 00시~기준시간까지 1시간 배치를
     * 정각 단위로 먼저 채운 뒤 일일 배치 실행)
     * baseTime 없음 = "스케쥴링" 팝업의 반복 실행(매일 자정, 항상 전일자 기준) - runScheduledJob은 이 값을 넣지 않는다
     * <p>
     * 실제 오케스트레이션(백필 여부 판단, 기준일 파싱, 시간대별 반복 실행)은
     * {@link DailyBatchOrchestrationService}에 위임한다 - 이 Job/Step 설정 클래스는 baseTime을
     * JobParameter에서 꺼내 넘겨주는 것 이상의 비즈니스 로직을 갖지 않는다.
     */
    @StepScope
    @Bean
    public Tasklet dailyMonitorTasklet(@Value("#{jobParameters['baseTime']}") String baseTime) {
        return (contribution, chunkContext) -> {
            dailyBatchOrchestrationService.runDailyMonitor(baseTime);
            return RepeatStatus.FINISHED;
        };
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
