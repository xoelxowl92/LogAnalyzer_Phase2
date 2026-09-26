package com.loganalyzer.batch;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * output/minute에 저장된 최근 1분 배치 실행 결과 1건. 웹 화면의 "실행 결과 보기"에서 사용한다.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MinuteMonitorResult {

    private boolean fault;
    private String summary;
    private String executedAt;
}
