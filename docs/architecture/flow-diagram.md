# 시스템 흐름도

> 배치 간 데이터 흐름과 구축 서버 ↔ Dify ↔ MCP 통신 흐름을 나타낸다.

<!-- 시각화 방법
  1. GitHub / GitLab : 파일을 웹에서 열면 자동으로 렌더링됨
  2. VS Code : "Markdown Preview Mermaid Support" 익스텐션 설치 후 미리보기(Cmd+Shift+V)
  3. 온라인 : https://mermaid.live 접속 → 아래 코드블록 내용 붙여넣기
  4. 보고서용 이미지 : mermaid.live 우측 상단 Export → PNG / SVG 다운로드
-->

```mermaid
flowchart TD
    LogFile([로그 파일])

    subgraph Setup["F-01  시스템 설치 · 최초 1회"]
        S["로그 파일 분석\n인코딩 / 날짜 형식 / 타임존 탐지"]
        S_Dify["Dify\n날짜 형식 추론"]
        S --> S_Dify --> S
    end

    Config(["config/setup.properties"])

    subgraph Minute["F-02  실시간 장애 감지 · 1분"]
        M["1분 구간 로그 읽기"]
        M_Dify["Dify\n장애 판단"]
        M --> M_Dify
    end

    subgraph Hourly["F-03 / F-04  이상 패턴 분석 + 최적화 인사이트 · 1시간"]
        H["1시간 로그 읽기"]
        H_Dify_A["Dify\n이상 패턴 분석"]
        H_Dify_O["Dify\n최적화 인사이트"]
        H --> H_Dify_A & H_Dify_O
    end

    subgraph Monthly["F-08  월간 최적화 인사이트 보고 · 1월  ⚠️ 개발 보류"]
        M8["개발 보류\n청킹/결과 병합 공수 이슈"]
    end

    subgraph Daily["F-05 / F-06 / F-07  일간 보고 + 파일 정리 · 1일"]
        D["hourly .dat 취합"]
        D_Dify_A["Dify\n일간 이상 패턴 보고"]
        D_Dify_O["Dify\n일간 최적화 인사이트"]
        Cleanup["hourly 파일 7일 초과 삭제\n(anomaly / optimization)"]
        D --> D_Dify_A & D_Dify_O --> Cleanup
    end

    %% 설치 → 설정 파일
    LogFile --> Setup
    Setup --> Config

    %% 설정 파일 → 배치 활성화
    Config --> Minute
    Config --> Hourly

    %% Hourly 결과 저장
    H_Dify_A --> HAnomaly(["output/hourly/anomaly/\nyyyy-MM-dd_HH.dat"])
    H_Dify_O --> HOptim(["output/hourly/optimization/\nyyyy-MM-dd_HH.dat"])

    %% Hourly → Daily
    HAnomaly & HOptim --> Daily

    %% Daily 출력
    D_Dify_A --> MCP_Daily["MCP 서버"]
    MCP_Daily --> Mail["메일 발송\n(일간 보고서)"]
    D_Dify_A --> DAnomalyFile(["output/daily/anomaly/\nyyyy-MM-dd.dat"])
    D_Dify_O --> DailyFile(["output/daily/optimization/\nyyyy-MM-dd.dat"])

    %% Minute 장애 감지 출력
    M_Dify --> MCP_Minute["MCP 서버"]
    MCP_Minute --> Swing["Swing UI\n장애 알림"]

```

---

## 변경 이력

| 버전 | 날짜 | 내용 | 작성자 |
|------|------|------|--------|
| v1.0 | 2026-06-23 | 초안 완성 | |
