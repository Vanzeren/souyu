package com.souyu.common.manager;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TaskMetadata {
    private String taskId;
    private String status; // RUNNING, COMPLETED, FAILED
    private String minioPath; // 在 MongoDB 模式下，这里存储 taskId
    private String errorMessage;
}
