Dify 로그 분석기 테스트 샘플 10종

01_successful_application.log
- 대부분 정상 처리
- 일부 경미한 WARN 포함
- 정상 로그를 과도하게 장애로 판정하지 않는지 확인

02_error_heavy_mixed.log
- DB timeout, 결제 API 504, 반복 ERROR
- 오류 우선순위와 핵심 장애 분류 확인

03_exception_stacktraces.log
- NullPointerException, IllegalArgumentException 등
- Stack Trace 해석과 발생 위치 추출 확인

04_db_pool_exhaustion.log
- HikariCP Connection Pool 고갈
- 원인, 영향도, 해결방안 제시 확인

05_external_api_timeout.log
- 외부 결제 API timeout, retry, circuit breaker
- 장애 흐름과 재시도 실패 분석 확인

06_login_bruteforce_security.log
- 로그인 실패 급증 및 IP 차단
- 보안 이상징후 탐지 여부 확인

07_memory_leak_oom.log
- Heap 지속 증가, Full GC, OutOfMemoryError
- 치명적 심각도 판정 여부 확인

08_batch_partial_failure.log
- 배치 일부 실패 및 느린 쿼리
- 전체 실패와 부분 실패를 구분하는지 확인

09_was_thread_saturation.log
- Tomcat Thread Pool 포화, HTTP 5xx
- WAS 병목 및 사용자 영향 분석 확인

10_failure_and_recovery.log
- DB 장애, Failover, 서비스 복구
- 장애 발생부터 정상화까지의 타임라인 분석 확인

권장 테스트 항목
1. 오류 종류 및 발생 위치가 실제 로그와 일치하는지
2. 사실과 추정을 구분하는지
3. 해결 방안을 최소 3개 이상 작성하는지
4. 정상 로그를 치명적 장애로 잘못 판정하지 않는지
5. 반복 오류와 단일 오류의 우선순위를 구분하는지
6. 장애 복구 로그에서는 현재 상태가 정상화되었음을 인식하는지
