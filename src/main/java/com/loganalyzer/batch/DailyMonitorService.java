package com.loganalyzer.batch;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

@Slf4j
@Service
public class DailyMonitorService {

    public List<String> readHourlyAnomalyResults(LocalDate targetDate) {
        // TODO: output/hourly/anomaly/ 에서 targetDate 해당 .dat 파일 목록 읽기 (오름차순)
        throw new UnsupportedOperationException("Not implemented yet");
    }

    public List<String> readHourlyOptimizationResults(LocalDate targetDate) {
        // TODO: output/hourly/optimization/ 에서 targetDate 해당 .dat 파일 목록 읽기 (오름차순)
        throw new UnsupportedOperationException("Not implemented yet");
    }

    public DailyAnomalyResult requestDailyAnomalyToDify(List<String> anomalyContents, LocalDate targetDate) {
        // TODO: 빈 리스트이면 스킵, Dify daily anomaly Workflow 호출 (알림은 Dify MCP에서 처리)
        throw new UnsupportedOperationException("Not implemented yet");
    }

    public void saveDailyAnomalyResult(DailyAnomalyResult result, LocalDate targetDate) {
        // TODO: output/daily/anomaly/yyyy-MM-dd.dat 에 저장
        throw new UnsupportedOperationException("Not implemented yet");
    }

    public DailyOptimizationResult requestDailyOptimizationToDify(List<String> optimizationContents, LocalDate targetDate) {
        // TODO: 빈 리스트이면 스킵, Dify daily optimization Workflow 호출
        throw new UnsupportedOperationException("Not implemented yet");
    }

    public void saveDailyOptimizationResult(DailyOptimizationResult result, LocalDate targetDate) {
        // TODO: output/daily/optimization/yyyy-MM-dd.dat 에 저장
        throw new UnsupportedOperationException("Not implemented yet");
    }

    public void deleteOldHourlyFiles(LocalDate baseDate) {
        // TODO: baseDate - 7일 이전 .dat 파일 삭제 (anomaly/optimization 두 폴더)
        throw new UnsupportedOperationException("Not implemented yet");
    }
}
