# 웹 UI 버튼 구현 현황

## 화면 URL
`http://<EC2_HOST>:8080`

---

## 버튼 목록

| 버튼 | Job | 상태 | 비고 |
|------|-----|------|------|
| 초기 설정 - 실행 | 로그 위치 설정 | `config/setup.properties` 존재 시 COMPLETED | - |
| 1분 배치 - 실행 | 기준시간을 근거로 1분 내의 로그를 분석 (`MinuteMonitorService` 실행) | `MinuteMonitorService` 정상 종료 시 COMPLETED | - |
| 1시간 단위 분석 - 실행 | 기준시간을 근거로 1시간 내의 로그를 분석 (`HourlyMonitorService` 실행) | `HourlyMonitorService` 정상 종료 시 COMPLETED | - |
| 1일 배치 - 실행 | 기준시간을 근거로 1시간 단위 분석을 24회 이하로 진행 후 일간 분석 (`HourlyMonitorService` → `DailyMonitorService` 순서 실행, 선후관계 처리 중요) | `DailyMonitorService` 정상 종료 시 COMPLETED | 테스트 화면에서는 `testDailyMonitorJob` 호출 (hourly→daily 순서 확인용). 운영용 `dailyMonitorJob`은 daily Step만 포함하여 별도 분리 |
| 1월 배치 | 개발 보류 | - | 청킹/결과 병합 공수 이슈 |

---

## 초기 설정 상태 표시

페이지 로드 시 `config/setup.properties` 존재 여부를 확인하여 자동으로 상태 표시.

| 상태 | 조건 |
|------|------|
| COMPLETED | `config/setup.properties` 존재 |
| - (미표시) | 파일 없음 (설치 전) |
