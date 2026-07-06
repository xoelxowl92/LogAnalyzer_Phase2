package com.loganalyzer.setup;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class SetupService {

    public String configureLogFilePath(String logFilePath) {
        // TODO: 경로 유효성 검증 후 절대 경로 반환
        throw new UnsupportedOperationException("Not implemented yet");
    }

    public String detectEncoding(String logFilePath) {
        // TODO: juniversalchardet 등으로 인코딩 탐지, 실패 시 UTF-8 반환
        throw new UnsupportedOperationException("Not implemented yet");
    }

    public String readSampleLog(String logFilePath, String encoding, int maxLines) {
        // TODO: 지정 인코딩으로 파일 앞 maxLines 줄 읽어 반환
        throw new UnsupportedOperationException("Not implemented yet");
    }

    public String requestDateFormatToDify(String sampleLogContent) {
        // TODO: Dify Workflow API 호출하여 날짜 형식 패턴 추론
        throw new UnsupportedOperationException("Not implemented yet");
    }

    public String detectTimezone(String sampleLogContent, String dateFormat) {
        // TODO: 타임스탬프에서 타임존 파싱, 없으면 시스템 기본 타임존 반환
        throw new UnsupportedOperationException("Not implemented yet");
    }

    public void saveSetupConfig(SetupConfig config) {
        // TODO: config/setup.properties 에 설정값 저장
        throw new UnsupportedOperationException("Not implemented yet");
    }
}
