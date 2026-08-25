package com.loganalyzer.batch;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 통합 Dify 워크플로우(mode=anomaly) 응답 결과.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnomalyAnalysisResult {

    private boolean anomalyDetected;
    private String severity;
    private String message;

    // 다음 실행에서 prevCounts로 이어서 전달해야 하는 이번 주기의 카테고리별 누적 건수
    private AnomalyCounts nextCounts;
}
