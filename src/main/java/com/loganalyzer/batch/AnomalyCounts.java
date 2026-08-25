package com.loganalyzer.batch;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 통합 Dify 워크플로우 anomaly 모드의 prev_ 및 next_prev_ 카테고리별 누적 건수.
 * 직전 호출의 next_prev_ 값을 다음 호출의 prev_ 값으로 그대로 이어서 전달해야 증감 판단이 가능하다.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnomalyCounts {

    private int errorCount;
    private int warnCount;
    private int timeoutCount;
    private int http5xxCount;
    private int dbConnCount;
    private int loginFailCount;
    private int batchFailCount;
    private int externalApiFailCount;
}
