package com.loganalyzer.dify;

/**
 * 통합 Dify 워크플로우(docs/logSuite_integrated_FIN_monthly.yml)의 mode 입력값.
 * 로그 최적화 인사이트는 아직 이 워크플로우에 추가되지 않아 별도 mode가 없다.
 */
public final class DifyMode {

    public static final String SINGLE_ANALYZE = "single_analyze";
    public static final String DAILY_REPORT = "daily_report";
    public static final String ANOMALY = "anomaly";
    public static final String MONTHLY_REPORT = "monthly_report";

    // 통합 워크플로우 start 노드의 log_content(paragraph) 필드 max_length 제약
    public static final int MAX_LOG_CONTENT_LENGTH = 20_000;

    private DifyMode() {
    }
}
