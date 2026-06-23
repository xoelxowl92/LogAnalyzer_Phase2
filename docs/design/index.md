# 함수 설계문서 목록

- **시스템 설치 설계서**: `docs/design/1_setup-design.md` — 최초 1회 실행
  1. `configureLogFilePath`
  2. `detectEncoding`
  3. `readSampleLog`
  4. `requestDateFormatToDify`
  5. `detectTimezone`
  6. `saveSetupConfig`

- **실시간 모니터링 배치 설계서**: `docs/design/2_minute-monitor-design.md` — 1분 단위 배치
  1. `loadSetupConfig`
  2. `readLastMinuteLog`
  3. `requestFaultCheckToDify`

- **시간 단위 배치 설계서**: `docs/design/3_hourly-monitor-design.md` — 1시간 단위 배치
  1. `loadSetupConfig`
  2. `readLastHourLog`
  3. `requestAnomalyAnalysisToDify`
  4. `saveAnomalyResult`
  5. `requestOptimizationAnalysisToDify`
  6. `saveOptimizationResult`

- **일 단위 배치 설계서**: `docs/design/4_daily-monitor-design.md` — 1일 단위 배치
  1. `readHourlyAnomalyResults`
  2. `readHourlyOptimizationResults`
  3. `requestDailyAnomalyToDify`
  4. `saveDailyAnomalyResult`
  5. `requestDailyOptimizationToDify`
  6. `saveDailyOptimizationResult`
  7. `deleteOldHourlyFiles`

- **Dify API 연동 스펙**: `docs/design/dify-api-spec.md` — 6개 워크플로우 요청/응답 스펙, 공통 인증/재시도 규칙

- **월 단위 배치 설계서**: `docs/design/5_monthly-monitor-design.md` — 1월 단위 배치 (**개발 보류 — 청킹/결과 병합 공수 이슈**)
  1. `readDailyResults`
  2. `requestMonthlyReportToDify`
  3. `saveMonthlyResult`
