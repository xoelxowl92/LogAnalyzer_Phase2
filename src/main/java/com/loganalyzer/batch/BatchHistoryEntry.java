package com.loganalyzer.batch;

import lombok.Getter;

/**
 * 배치 스케줄링 모달에 표시할 최근 수행 이력 1건. success/fail 여부만 표현한다.
 */
@Getter
public class BatchHistoryEntry {

    public enum Status { SUCCESS, FAIL }

    private final Status status;

    public BatchHistoryEntry(Status status) {
        this.status = status;
    }
}
