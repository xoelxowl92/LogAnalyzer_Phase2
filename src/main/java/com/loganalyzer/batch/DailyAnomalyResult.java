package com.loganalyzer.batch;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DailyAnomalyResult {

    private String content;
    private LocalDate reportDate;
}
