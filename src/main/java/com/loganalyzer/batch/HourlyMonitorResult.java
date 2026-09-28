package com.loganalyzer.batch;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * output/hourly/anomaly에 저장된 최근 1시간 배치 실행 결과 1건. 웹 화면의 "결과 보기"에서 사용한다.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HourlyMonitorResult {

    private boolean anomalyDetected;
    private String severity;
    private String message;
    private String targetHour;
}
