package com.loganalyzer.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.*;
import java.nio.charset.Charset;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@RestController
@RequestMapping("/api")
public class InfoController {

    @Value("${spring.profiles.active:default}")
    private String activeProfile;

    @GetMapping("/profile")
    public Map<String, String> getProfile() {
        return Collections.singletonMap("profile", activeProfile);
    }

    @GetMapping("/setup/status")
    public Map<String, Boolean> getSetupStatus() {
        boolean completed = new File("config/setup.properties").exists();
        return Collections.singletonMap("completed", completed);
    }

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

            DateTimeFormatter formatter = DateTimeFormatter.ofPattern(dateFormat);

            // Reservoir Sampling: 파일 전체를 한 번만 읽으면서 메모리는 5개만 유지
            List<String> reservoir = new ArrayList<>(5);
            Random random = new Random();
            int count = 0;

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(new FileInputStream(logFilePath), Charset.forName(encoding)))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.length() < formatLen) continue;
                    try {
                        String prefix = line.substring(0, formatLen);
                        LocalDateTime.parse(prefix, formatter);

                        if (reservoir.size() < 5) {
                            reservoir.add(prefix);
                        } else {
                            int j = random.nextInt(++count);
                            if (j < 5) reservoir.set(j, prefix);
                        }
                        count++;
                    } catch (Exception ignored) {}
                }
            }

            Map<String, Object> result = new HashMap<>();
            result.put("timestamps", reservoir);
            result.put("dateFormat", dateFormat);
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("timestamps", Collections.emptyList(), "dateFormat", ""));
        }
    }
}
