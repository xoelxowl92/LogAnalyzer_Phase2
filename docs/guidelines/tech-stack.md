# 기술 스택 가이드라인

## 언어 및 프레임워크

| 항목 | 내용 |
|------|------|
| 언어 | Java 8 |
| 프레임워크 | Spring Boot 2.7.x |
| 배치 | Spring Batch 4.x |
| AI 연동 | Dify Workflow |
| DB | 미정 |
| 배포 환경 | AWS EC2 |

> Spring Boot 3.x / Spring Batch 5.x는 Java 17+ 필수이므로 Java 8 환경에서 사용 불가.

## 배치 구현 방식

Spring Batch 사용 확정.

| 항목 | 내용 |
|------|------|
| Job 실행 이력 | Spring Batch 메타 테이블 (DB 필요) |
| 재시도 정책 | Spring Batch 내장 retry/skip 활용 |
| 스케줄링 | Spring @Scheduled |

## 웹 (모니터링)

배치 Job 트리거 및 실행 상태 모니터링 용도의 관리 화면.

| 항목 | 내용 |
|------|------|
| 목적 | 배치 수동 실행, 실행 이력 조회, 상태 모니터링 |
| 구현 방식 | Spring Boot Admin |

## 변경 이력

| 날짜 | 내용 |
|------|------|
| 2026-06-23 | 초안 완성 |
| 2026-06-26 | Java 8 / Spring Boot 2.7.x / Spring Batch 4.x / MySQL / Spring @Scheduled / Spring Boot Admin 전체 확정 |
