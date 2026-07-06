package com.loganalyzer.batch;

import com.loganalyzer.setup.SetupConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class MinuteMonitorService {

    public SetupConfig loadSetupConfig() {
        // TODO: config/setup.properties 읽어 SetupConfig 반환 (공용 - HourlyMonitorService와 동일)
        throw new UnsupportedOperationException("Not implemented yet");
    }

    public String readLastMinuteLog(SetupConfig config) {
        // TODO: (현재시각 - 80초) ~ (현재시각 - 20초) 구간 로그 읽기
        throw new UnsupportedOperationException("Not implemented yet");
    }

    public FaultCheckResult requestFaultCheckToDify(String logContent) {
        // TODO: 빈 문자열이면 isFault=false 반환, 아니면 Dify 장애 판단 Workflow 호출
        throw new UnsupportedOperationException("Not implemented yet");
    }
}
