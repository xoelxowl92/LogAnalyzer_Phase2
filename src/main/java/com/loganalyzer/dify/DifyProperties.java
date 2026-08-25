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
        private LogSuite logSuite = new LogSuite();
    }

    // 시스템 설치 시 날짜 형식 추론 워크플로우 (dify-api-spec.md 2.1, 별도 앱)
    @Getter
    @Setter
    public static class DateFormat {
        private String apiKey;
    }

    // 단건분석(single_analyze)/일일보고(daily_report)/이상감지(anomaly)가 하나로 합쳐진 통합 워크플로우.
    // mode 입력값으로 분기하며 API Key는 하나만 발급된다 (dify-api-spec.md 2.2, docs/lhs_logSuite_integrated_fix.yml 참고).
    // 로그 최적화 인사이트는 아직 이 워크플로우에 추가되지 않았다.
    @Getter
    @Setter
    public static class LogSuite {
        private String apiKey;
    }
}
