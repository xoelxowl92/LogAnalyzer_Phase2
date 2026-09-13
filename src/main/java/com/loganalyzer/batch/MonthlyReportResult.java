package com.loganalyzer.batch;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.YearMonth;
import java.util.List;

/**
 * 통합 Dify 워크플로우(mode=monthly_report) 응답 결과.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MonthlyReportResult {

    private String status;
    private int dayCount;
    private String overallStatus;
    private String monthlySummary;
    private List<String> majorIssues;
    private List<String> recurringPatterns;
    private String trendSummary;
    private List<String> recommendations;
    private String reportText;
    private String message;
    private YearMonth targetYearMonth;
}
