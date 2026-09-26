package com.loganalyzer.batch;

import com.loganalyzer.setup.SetupConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * 일일 배치 실행 시 필요하면 1시간 배치를 먼저 채우는(백필) 오케스트레이션을 담당한다.
 * <p>
 * {@link HourlyMonitorService}와 {@link DailyMonitorService}는 서로를 직접 의존하지 않도록
 * 설계되어 있어(배치 실행 주기·책임이 다른 두 서비스 간 결합도를 피하기 위함), 두 서비스를 조합해야 하는
 * 이 오케스트레이션 로직은 별도 서비스로 분리한다. {@code BatchConfig}는 Job/Step 메타데이터 설정만
 * 담당하고 이런 비즈니스 로직은 갖지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DailyBatchOrchestrationService {

    private final HourlyMonitorService hourlyMonitorService;
    private final DailyMonitorService dailyMonitorService;

    /**
     * baseTime이 없으면(스케쥴 반복 실행) 기존 동작(전일 기준) 그대로 일일 배치를 실행한다.
     * baseTime이 있으면(웹 화면 1회성 실행) 기준일 00시~기준시간까지 1시간 배치를 정각 단위로 먼저
     * 채운 뒤, 그 기준일로 일일 배치를 실행한다. baseTime 파싱에 실패하면 백필을 건너뛰고
     * 스케쥴 실행과 동일하게(전일 기준) 처리한다.
     */
    public void runDailyMonitor(String baseTime) {

        if (baseTime == null || baseTime.trim().isEmpty()) {
            dailyMonitorService.execute();
            return;
        }

        LocalDate targetDate = runHourlyBackfill(baseTime);

        if (targetDate == null) {
            dailyMonitorService.execute();
        } else {
            dailyMonitorService.execute(targetDate);
        }
    }

    /**
     * baseTime이 속한 날짜의 00시부터 baseTime 시(정각 단위)까지 1시간 배치를 순서대로 실행해
     * output/hourly/anomaly/{날짜}_{HH}.dat 파일을 채운다.
     * 한 시간대 실행이 실패해도 나머지 시간대와 이어지는 일일 배치 실행에 영향을 주지 않도록 개별적으로 격리한다.
     * <p>
     * hourlyMonitorService.execute()를 자바 메서드로 바로 호출할 뿐 별도 JobExecution을 만들지 않으므로,
     * 이 백필 실행들은 Spring Batch 이력이나 스케쥴 등록 상태에 나타나지 않는다 - 오직 이 dailyMonitorJob
     * 1회 실행 안에서만 일어나는 일이다.
     *
     * @return 백필에 사용한 기준일. baseTime 파싱에 실패하면(포맷 불일치 등) 백필 전체를 건너뛰고 null을
     *         반환한다 - 호출부는 이 경우 스케쥴 실행과 동일하게(전일 기준) 처리해야 한다.
     */
    private LocalDate runHourlyBackfill(String baseTime) {

        SetupConfig config = hourlyMonitorService.loadSetupConfig();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(config.getDateFormat(), Locale.ENGLISH);

        LocalDateTime referenceTime;
        try {
            referenceTime = LocalDateTime.parse(baseTime.trim(), formatter);
        } catch (Exception e) {
            log.error("[DailyMonitor] 기준시간 파싱 실패 - 백필 건너뛰고 전일 기준으로 대체: {}", baseTime, e);
            return null;
        }

        LocalDate targetDate = referenceTime.toLocalDate();
        int hour = referenceTime.getHour();

        if (hour == 0) {
            // 0시대는 아직 완결된 1시간 구간이 하루 안에 하나도 없으므로(00:00~01:00 미완료) 정상적으로 0번 돈다.
            log.info("[DailyMonitor] 기준시간이 0시대라 완결된 1시간 구간이 없어 백필을 건너뜁니다 - baseTime={}", baseTime);
        }

        for (int h = 1; h <= hour; h++) {
            LocalDateTime hourMark = LocalDateTime.of(targetDate, LocalTime.of(h, 0));
            String hourMarkText = hourMark.format(formatter);

            try {
                hourlyMonitorService.execute(hourMarkText);
            } catch (Exception e) {
                log.error("[DailyMonitor] {}시 1시간 배치 백필 실패 - 다음 시간대 계속 진행", h, e);
            }
        }

        return targetDate;
    }
}
