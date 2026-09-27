package com.loganalyzer.batch;

import com.loganalyzer.setup.SetupConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * 월간 배치 실행 시 필요하면 그 달 1일부터 기준일까지 일자별로(1시간 배치 백필 + 일일 배치)를
 * 먼저 채우는 오케스트레이션을 담당한다.
 * <p>
 * {@link MonthlyMonitorService}는 {@link DailyMonitorService}/{@link HourlyMonitorService}를 직접
 * 의존하지 않도록 유지하고, 이 여러 서비스를 조합해야 하는 오케스트레이션 로직만 별도 서비스로 분리한다
 * ({@link DailyBatchOrchestrationService}와 동일한 이유).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MonthlyBatchOrchestrationService {

    /** 완결된 하루 전체를 백필할 때 사용하는 마지막 정각 시(1~23시). 23:00~24:00 구간은 이 파일명 체계로 표현할 수 없어 제외된다. */
    private static final int FULL_DAY_LAST_HOUR = 23;

    private final HourlyMonitorService hourlyMonitorService;
    private final DailyBatchOrchestrationService dailyBatchOrchestrationService;
    private final MonthlyMonitorService monthlyMonitorService;

    /**
     * baseTime이 없으면(스케쥴 반복 실행) 기존 동작(전월 기준) 그대로 월간 배치를 실행한다.
     * baseTime이 있으면(웹 화면 1회성 실행) 기준일이 속한 월의 1일부터 기준일까지 날짜별로
     * {@link DailyBatchOrchestrationService#runDailyMonitor(LocalDate, int)}를 반복 호출해 채운다 —
     * 기준일 이전 날짜는 1~23시 전체를, 기준일 당일은 1시~기준시각까지만 백필한다. 그렇게 그 달의
     * 일자별 daily 결과를 만든 뒤, 마지막으로 그 월로 월간 배치를 실행한다.
     * baseTime 파싱에 실패하면 백필을 건너뛰고 스케쥴 실행과 동일하게(전월 기준) 처리한다.
     * <p>
     * 날짜 수만큼(최대 31일) 반복 호출하며 각 날짜마다 최대 23회의 1시간 배치 Dify 호출이 동반되므로
     * 전체 실행 시간이 상당히 길어질 수 있다 — 관리자가 화면에서 수동으로 실행하는 백필/테스트 용도임을
     * 전제로 한다.
     */
    public void runMonthlyMonitor(String baseTime) {

        if (baseTime == null || baseTime.trim().isEmpty()) {
            monthlyMonitorService.execute();
            return;
        }

        SetupConfig config = hourlyMonitorService.loadSetupConfig();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(config.getDateFormat(), Locale.ENGLISH);

        LocalDateTime referenceTime;
        try {
            referenceTime = LocalDateTime.parse(baseTime.trim(), formatter);
        } catch (Exception e) {
            log.error("[MonthlyMonitor] 기준시간 파싱 실패 - 백필 건너뛰고 전월 기준으로 대체: {}", baseTime, e);
            monthlyMonitorService.execute();
            return;
        }

        LocalDate referenceDate = referenceTime.toLocalDate();
        int referenceDay = referenceDate.getDayOfMonth();
        YearMonth targetYearMonth = YearMonth.from(referenceDate);

        for (int day = 1; day <= referenceDay; day++) {

            LocalDate date = targetYearMonth.atDay(day);
            boolean isReferenceDay = (day == referenceDay);
            int upToHour = isReferenceDay ? referenceTime.getHour() : FULL_DAY_LAST_HOUR;

            try {
                dailyBatchOrchestrationService.runDailyMonitor(date, upToHour);
            } catch (Exception e) {
                // runDailyMonitor 내부에서 시간대별/일일 배치 실패를 이미 격리하지만, 예기치 못한 오류로
                // 이 날짜 처리 전체가 실패해도 나머지 날짜 백필은 계속 진행하도록 한 번 더 감싼다.
                log.error("[MonthlyMonitor] {} 일자 백필 실패 - 다음 날짜 계속 진행", date, e);
            }
        }

        monthlyMonitorService.execute(targetYearMonth);
    }
}
