package com.loganalyzer.batch;

import com.loganalyzer.setup.SetupConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Slf4j
@Service
public class HourlyMonitorService {

    public SetupConfig loadSetupConfig() {
        // TODO: config/setup.properties 읽어 SetupConfig 반환 (공용 - MinuteMonitorService와 동일)
        throw new UnsupportedOperationException("Not implemented yet");
    }

    public String readLastHourLog(SetupConfig config) {
        // TODO: (현재시각 - 1시간) ~ 현재시각 구간 로그 읽기
        throw new UnsupportedOperationException("Not implemented yet");
    }

    public AnomalyAnalysisResult requestAnomalyAnalysisToDify(String logContent) {
        // TODO: 빈 문자열이면 스킵, Dify 이상 패턴 분석 Workflow 호출, content 1MB 초과 시 예외
        throw new UnsupportedOperationException("Not implemented yet");
    }

    public void saveAnomalyResult(AnomalyAnalysisResult result, LocalDateTime batchTime) {
        // TODO: output/hourly/anomaly/yyyy-MM-dd_HH.dat 에 저장
        throw new UnsupportedOperationException("Not implemented yet");
    }

    public OptimizationAnalysisResult requestOptimizationAnalysisToDify(String logContent) {
        // TODO: 빈 문자열이면 스킵, Dify 최적화 인사이트 Workflow 호출, content 1MB 초과 시 예외
        throw new UnsupportedOperationException("Not implemented yet");
    }

    public void saveOptimizationResult(OptimizationAnalysisResult result, LocalDateTime batchTime) {
        // TODO: output/hourly/optimization/yyyy-MM-dd_HH.dat 에 저장
        throw new UnsupportedOperationException("Not implemented yet");
    }
}
