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

    // 시스템 설치 시 날짜 형식 추론 워크플로우 (dify-api-spec.md 2.1)
    @Getter
    @Setter
    public static class DateFormat {
        private String apiKey;
    }

    // 1분 배치 장애 판단 워크플로우 (dify-api-spec.md 2.2)
    @Getter
    @Setter
    public static class FaultCheck {
        private String apiKey;
    }

    // 1시간 배치 이상 패턴 분석 워크플로우 (dify-api-spec.md 2.3)
    @Getter
    @Setter
    public static class AnomalyAnalysis {
        private String apiKey;
    }

    // 1시간 배치 최적화 인사이트 분석 워크플로우 (dify-api-spec.md 2.4)
    @Getter
    @Setter
    public static class OptimizationAnalysis {
        private String apiKey;
    }
}
