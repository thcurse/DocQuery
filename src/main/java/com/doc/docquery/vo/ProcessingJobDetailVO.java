package com.doc.docquery.vo;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

/** 选中任务及同一版本的完整 attempt 历史。 */
@Getter
@AllArgsConstructor
public class ProcessingJobDetailVO {
    private final ProcessingJobVO job;
    private final List<ProcessingJobAttemptVO> attemptHistory;
}
