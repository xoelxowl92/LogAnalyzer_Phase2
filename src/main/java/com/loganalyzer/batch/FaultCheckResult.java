package com.loganalyzer.batch;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FaultCheckResult {

    private boolean isFault;
    private String summary;
}
