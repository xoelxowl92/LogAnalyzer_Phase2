package com.loganalyzer.dify;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * log-analyzer.dify.* 설정값. dify-api-spec.md 1.3/1.4 참고.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "log-analyzer.dify")
public class DifyProperties {

    private String baseUrl;
    private String user;
    private int maxRetries = 3;
    private int timeoutSeconds = 60;
    private Workflow workflow = new Workflow();

    @Getter
    @Setter
    public static class Workflow {
        private DateFormat dateFormat = new DateFormat();
        private FaultCheck faultCheck = new FaultCheck();
        private AnomalyAnalysis anomalyAnalysis = new AnomalyAnalysis();
        private OptimizationAnalysis optimizationAnalysis = new OptimizationAnalysis();
    }

    @Getter
    @Setter
    public static class DateFormat {
        private String apiKey;
    }

    @Getter
    @Setter
    public static class FaultCheck {
        private String apiKey;
    }

    @Getter
    @Setter
    public static class AnomalyAnalysis {
        private String apiKey;
    }

    @Getter
    @Setter
    public static class OptimizationAnalysis {
        private String apiKey;
    }
}
