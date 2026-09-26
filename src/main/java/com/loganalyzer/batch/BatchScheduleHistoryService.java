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
     */
    public List<BatchHistoryEntry> getRecentHistory(String jobName, int limit) {
        log.debug("[getRecentHistory] 조회 시작 - jobName={}, limit={}", jobName, limit);

        List<BatchHistoryEntry> history = new ArrayList<>();
        int start = 0;

        while (history.size() < limit) {
            List<JobInstance> instances = jobExplorer.getJobInstances(jobName, start, INSTANCE_PAGE_SIZE);
            if (instances.isEmpty()) {
                break;
            }

            for (JobInstance instance : instances) {
                List<JobExecution> executions = new ArrayList<>(jobExplorer.getJobExecutions(instance));
                executions.sort(Comparator.comparing(JobExecution::getCreateTime).reversed());

                for (JobExecution execution : executions) {
                    if (execution.getEndTime() == null) {
                        continue;
                    }
                    history.add(new BatchHistoryEntry(
                            execution.getStatus() == BatchStatus.COMPLETED
                                    ? BatchHistoryEntry.Status.SUCCESS
                                    : BatchHistoryEntry.Status.FAIL));
                    if (history.size() >= limit) {
                        break;
                    }
                }
                if (history.size() >= limit) {
                    break;
                }
            }
            start += INSTANCE_PAGE_SIZE;
        }

        log.debug("[getRecentHistory] 조회 완료 - jobName={}, count={}", jobName, history.size());
        return history;
    }
}
