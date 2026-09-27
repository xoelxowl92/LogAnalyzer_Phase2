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
 * <p>
 * {@link MonthlyBatchOrchestrationService}가 월간 백필(여러 날짜 반복)을 수행할 때도
 * {@link #runDailyMonitor(LocalDate, int)}를 하루 단위로 재사용한다.
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

        SetupConfig config = hourlyMonitorService.loadSetupConfig();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(config.getDateFormat(), Locale.ENGLISH);

        LocalDateTime referenceTime;
        try {
            referenceTime = LocalDateTime.parse(baseTime.trim(), formatter);
        } catch (Exception e) {
            log.error("[DailyMonitor] 기준시간 파싱 실패 - 백필 건너뛰고 전일 기준으로 대체: {}", baseTime, e);
            dailyMonitorService.execute();
            return;
        }

        runDailyMonitor(referenceTime.toLocalDate(), referenceTime.getHour());
    }

    /**
     * targetDate의 00시부터 upToHour시(정각 단위)까지 1시간 배치를 순서대로 실행해(백필) 채운 뒤,
     * 그 날짜로 일일 배치를 실행한다.
     * <p>
     * 시간대별 백필 실패, 일일 배치 자체의 실패 모두 개별적으로 격리한다 — 백필 목적상 특정 시간대나
     * 날짜의 실패가 전체 흐름(월간 배치의 나머지 날짜 등)을 막아서는 안 되기 때문이다.
     * <p>
     * hourlyMonitorService/dailyMonitorService를 자바 메서드로 바로 호출할 뿐 별도 JobExecution을
     * 만들지 않으므로, 이 백필 실행들은 Spring Batch 이력이나 스케쥴 등록 상태에 나타나지 않는다.
     */
    public void runDailyMonitor(LocalDate targetDate, int upToHour) {

        runHourlyBackfill(targetDate, upToHour);

        try {
            dailyMonitorService.execute(targetDate);
        } catch (Exception e) {
            log.error("[DailyMonitor] {} 일일 배치 실행 실패 - 이어지는 처리는 계속 진행", targetDate, e);
        }
    }

    private void runHourlyBackfill(LocalDate targetDate, int upToHour) {

        SetupConfig config = hourlyMonitorService.loadSetupConfig();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(config.getDateFormat(), Locale.ENGLISH);

        if (upToHour == 0) {
            // 0시대는 아직 완결된 1시간 구간이 하루 안에 하나도 없으므로(00:00~01:00 미완료) 정상적으로 0번 돈다.
            log.info("[DailyMonitor] 기준시간이 0시대라 완결된 1시간 구간이 없어 백필을 건너뜁니다 - targetDate={}", targetDate);
        }

        for (int h = 1; h <= upToHour; h++) {
            LocalDateTime hourMark = LocalDateTime.of(targetDate, LocalTime.of(h, 0));
            String hourMarkText = hourMark.format(formatter);

            try {
                hourlyMonitorService.execute(hourMarkText);
            } catch (Exception e) {
                log.error("[DailyMonitor] {} {}시 1시간 배치 백필 실패 - 다음 시간대 계속 진행", targetDate, h, e);
            }
        }
    }
}
