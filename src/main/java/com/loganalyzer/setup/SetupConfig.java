package com.loganalyzer.setup;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SetupConfig {

    private String logFilePath;
    private String encoding;
    private String dateFormat;
    private String timezone;
}
