package com.souyu.common.TaskStatus;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 任务状态实体类，用于 MongoDB 持久化存储
 */
@Data
@Document(collection = "task_status")
public class TaskStatus {

    @Id
    private String taskId;

    /**
     * 当前任务的整体状态
     */
    private Status status;

    /**
     * 各个 Worker (QueryEngine, MediaEngine) 的详细状态
     * Key: Engine Name (e.g., "query", "media")
     * Value: WorkerStatus (e.g., COMPLETED, FAILED)
     */
    private Map<String, WorkerStatus> workerStatus = new HashMap<>();

    /**
     * 论坛主持人 (Forum Host) 的状态
     */
    private WorkerStatus forumStatus;

    /**
     * 任务创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 状态最后更新时间
     */
    private LocalDateTime updatedAt;

    /**
     * 最终生成的报告 ID
     */
    private String reportId;

    /**
     * 错误信息（如果有）
     */
    private String errorMessage;

    /**
     * 任务整体状态枚举
     */
    public enum Status {
        CREATED,        // 任务已创建
        RESEARCHING,    // 研究中 (Worker 并行工作)
        SUMMARIZING,    // 总结中 (Forum Host 工作)
        GENERATING,     // 生成报告中 (Report Agent 工作)
        COMPLETED,      // 完成
        FAILED          // 失败
    }

    /**
     * 子任务/Worker 状态枚举
     */
    public enum WorkerStatus {
        PENDING,    // 等待中
        RUNNING,    // 运行中
        COMPLETED,  // 完成
        FAILED      // 失败
    }

    // 构造函数，初始化时间
    public TaskStatus() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
        this.status = Status.CREATED;
        this.forumStatus = WorkerStatus.PENDING;
    }
    
    public TaskStatus(String taskId) {
        this();
        this.taskId = taskId;
    }
}
