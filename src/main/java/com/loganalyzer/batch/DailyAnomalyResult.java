package com.loganalyzer.batch;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * 통합 Dify 워크플로우(mode=daily_report) 응답 결과.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DailyAnomalyResult {

    private String reportText;
    private int errorCount;
    private int warnCount;
    private int uniqueIssueCount;
    private LocalDate reportDate;
}
