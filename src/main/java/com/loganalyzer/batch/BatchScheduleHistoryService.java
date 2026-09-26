package com.loganalyzer.batch;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Spring Batch 메타 테이블(JobExplorer)을 조회하여 배치 스케줄링 모달에 표시할 최근 수행 이력을 제공한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BatchScheduleHistoryService {

    private static final int INSTANCE_PAGE_SIZE = 10;

    private final JobExplorer jobExplorer;

    /**
     * jobName의 최근 수행 이력을 최신순으로 최대 limit건 조회한다.
     * 아직 종료되지 않은 실행(endTime == null)은 성공/실패를 확정할 수 없으므로 이력에서 제외한다.
     * <p>
     * getJobInstances()가 반환하는 JobInstance 순서(최신 생성순)에 의존하지 않고, 후보 JobExecution을
     * 충분히 모은 뒤 createTime 기준으로 한 번에 정렬한다 - 이전 JobInstance가 재시작(restart)되어
     * 더 최근에 종료된 경우에도 순서가 뒤집히지 않는다.
     */
    public List<BatchHistoryEntry> getRecentHistory(String jobName, int limit) {
        log.debug("[getRecentHistory] 조회 시작 - jobName={}, limit={}", jobName, limit);

        List<JobExecution> candidates = new ArrayList<>();
        int start = 0;

        while (candidates.size() < limit) {
            List<JobInstance> instances = jobExplorer.getJobInstances(jobName, start, INSTANCE_PAGE_SIZE);
            if (instances.isEmpty()) {
                break;
            }

            for (JobInstance instance : instances) {
                for (JobExecution execution : jobExplorer.getJobExecutions(instance)) {
                    if (execution.getEndTime() != null) {
                        candidates.add(execution);
                    }
                }
            }
            start += INSTANCE_PAGE_SIZE;
        }

        List<BatchHistoryEntry> history = candidates.stream()
                .sorted(Comparator.comparing(JobExecution::getCreateTime).reversed())
                .limit(limit)
                .map(execution -> toHistoryEntry(jobName, execution))
                .collect(Collectors.toList());

        log.debug("[getRecentHistory] 조회 완료 - jobName={}, count={}", jobName, history.size());
        return history;
    }

    /**
     * COMPLETED가 아니면 전부 FAIL로 매핑한다. 다만 ABANDONED/UNKNOWN은 일반적인 FAILED와 달리
     * 배치 자체의 비정상 종료(재시작 처리 누락, JVM 강제 종료 등)를 나타낼 수 있어 운영 확인용으로
     * 별도 로그를 남긴다.
     */
    private BatchHistoryEntry toHistoryEntry(String jobName, JobExecution execution) {
        BatchStatus status = execution.getStatus();

        if (status == BatchStatus.ABANDONED || status == BatchStatus.UNKNOWN) {
            log.warn("[getRecentHistory] 비정상 종료 상태 감지 - jobName={}, executionId={}, status={}",
                    jobName, execution.getId(), status);
        }

        return new BatchHistoryEntry(
                status == BatchStatus.COMPLETED ? BatchHistoryEntry.Status.SUCCESS : BatchHistoryEntry.Status.FAIL);
    }
}
