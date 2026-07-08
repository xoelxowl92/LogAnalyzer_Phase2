package com.loganalyzer.batch;

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

@Slf4j
@Configuration
@EnableBatchProcessing
@RequiredArgsConstructor
public class BatchConfig {

    private final JobBuilderFactory jobBuilderFactory;
    private final StepBuilderFactory stepBuilderFactory;

    @Bean
    public Job setupJob() {
        return jobBuilderFactory.get("setupJob")
                .start(placeholderStep("setupStep"))
                .build();
    }

    @Bean
    public Job minuteMonitorJob() {
        return jobBuilderFactory.get("minuteMonitorJob")
                .start(placeholderStep("minuteMonitorStep"))
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
