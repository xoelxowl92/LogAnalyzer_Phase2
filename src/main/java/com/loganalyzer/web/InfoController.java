package com.loganalyzer.web;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.*;
import java.nio.charset.Charset;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@RestController
@RequestMapping("/api")
public class InfoController {

    @Value("${spring.profiles.active:default}")
    private String activeProfile;

    /**
     * 현재 활성 Spring 프로파일을 반환한다.
     * <p>
     * 프론트엔드에서 LOCAL 환경 배지 표시 여부를 결정하는 데 사용한다.
     */
    @GetMapping("/profile")
    public Map<String, String> getProfile() {
        return Collections.singletonMap("profile", activeProfile);
    }

    /**
     * config/setup.properties 존재 여부와 저장된 로그 파일 경로를 반환한다.
     * <p>
     * 프론트엔드 초기 로드 시 setup 완료 여부를 확인하는 데 사용한다.
     */
    @GetMapping("/setup/status")
    public Map<String, Object> getSetupStatus() {
        File configFile = new File("config/setup.properties");
        Map<String, Object> result = new HashMap<>();
        result.put("completed", configFile.exists());
        if (configFile.exists()) {
            try {
                Properties props = new Properties();
                try (InputStream is = new FileInputStream(configFile)) {
                    props.load(is);
                }
                result.put("logFilePath", props.getProperty("setup.log-file-path", ""));
            } catch (Exception e) {
                log.error("[InfoController] setup status 조회 실패", e);
            }
        }
        return result;
    }

    /**
     * 로그 파일에서 타임스탬프를 reservoir sampling으로 최대 5개 추출하여 반환한다.
     * <p>
     * 프론트엔드 기준시간 선택 드롭다운에 사용한다. Reservoir sampling을 쓰는 이유는
     * 대용량 로그 파일 전체를 메모리에 올리지 않고 균등한 확률로 샘플을 선택하기 위함이다.
     */
    @GetMapping("/log/timestamps")
    public ResponseEntity<Map<String, Object>> getLogTimestamps() {
        try {
            Properties props = new Properties();
            try (InputStream is = new FileInputStream("config/setup.properties")) {
                props.load(is);
            }

            String logFilePath = props.getProperty("setup.log-file-path");
            String encoding    = props.getProperty("setup.encoding");
            String dateFormat  = props.getProperty("setup.date-format");
            int formatLen      = dateFormat.length();

            DateTimeFormatter formatter = DateTimeFormatter.ofPattern(dateFormat, Locale.ENGLISH);
            List<String> reservoir = new ArrayList<>(5);
            int count = 0;
            Random random = new Random();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(new FileInputStream(logFilePath), Charset.forName(encoding)))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.length() < formatLen) continue;
                    String prefix = line.substring(0, formatLen);
                    try {
                        formatter.parse(prefix);
                        count++;
                        if (reservoir.size() < 5) {
                            reservoir.add(prefix);
                        } else {
                            int j = random.nextInt(count);
                            if (j < 5) {
                                reservoir.set(j, prefix);
                            }
                        }
                    } catch (Exception ignored) {}
                }
            }

            Map<String, Object> result = new HashMap<>();
            result.put("timestamps", reservoir);
            result.put("dateFormat", dateFormat);
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("[InfoController] 타임스탬프 조회 실패", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("timestamps", Collections.emptyList(), "dateFormat", ""));
        }
    }
}
