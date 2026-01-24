package com.souyu.reportengine.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.souyu.common.TaskStatus.TaskStatus;
import com.souyu.common.manager.StateManager;
import com.souyu.common.manager.TaskStatusManager;
import com.souyu.common.state.State;
import com.souyu.reportengine.client.MediaEngineClient;
import com.souyu.reportengine.client.QueryEngineClient;
import com.souyu.reportengine.angent.ReportAgent;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RestController
@RequestMapping("/api/v1/report")
@RequiredArgsConstructor
public class ReportController {

    private final QueryEngineClient queryEngineClient;
    private final MediaEngineClient mediaEngineClient;
    
    @Autowired
    private RedissonClient redissonClient;
    
    @Autowired
    private StateManager stateManager;
    
    @Autowired
    private TaskStatusManager taskStatusManager; // 注入新的状态管理器
    
    @Autowired
    private ReportAgent reportAgent;
    
    @Value("${report-engine.worker-count}")
    private int workerCount;
    
    private final ObjectMapper objectMapper;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    @PostMapping("/generate")
    public ResponseEntity<?> generateReport(@RequestBody Map<String, String> request) {
        String query = request.get("query");
        if (query == null || query.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Query cannot be empty"));
        }

        // 1. 初始化 Task ID (保留原有逻辑，用于 StateManager)
        String taskId = stateManager.initTask();
        
        // 2. 初始化 TaskStatus (新状态机)
        taskStatusManager.initTask(taskId);
        // 初始化 Worker 状态为 PENDING
        taskStatusManager.updateWorkerStatus(taskId, "query", TaskStatus.WorkerStatus.PENDING);
        taskStatusManager.updateWorkerStatus(taskId, "media", TaskStatus.WorkerStatus.PENDING);
        taskStatusManager.updateMainStatus(taskId, TaskStatus.Status.RESEARCHING);

        Map<String, String> engineRequest = Map.of(
                "query", query,
                "taskId", taskId
        );

        try {
            // 3. 异步调用 Worker
            // 更新状态为 RUNNING
            taskStatusManager.updateWorkerStatus(taskId, "query", TaskStatus.WorkerStatus.RUNNING);
            queryEngineClient.submitQuery(engineRequest);
            
            taskStatusManager.updateWorkerStatus(taskId, "media", TaskStatus.WorkerStatus.RUNNING);
            mediaEngineClient.submitMediaSearch(engineRequest);

            // 4. 初始化 Redis 计数器 (用于回调)
            RAtomicLong atomicCount = redissonClient.getAtomicLong("task:count:" + taskId);
            atomicCount.set(workerCount);
            atomicCount.expire(Duration.ofDays(1));
            
            return ResponseEntity.ok(Map.of(
                    "taskId", taskId,
                    "message", "Report generation started",
                    "status", "RESEARCHING"
            ));
            
        } catch (Exception e) {
            taskStatusManager.markTaskFailed(taskId, e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }
    
    @GetMapping("/{taskId}")
    public ResponseEntity<?> getReportStatus(@PathVariable String taskId) {
        // 优先返回新的 TaskStatus
        TaskStatus status = taskStatusManager.getTaskStatus(taskId);
        if (status != null) {
            return ResponseEntity.ok(status);
        }
        
        // 降级：返回旧的 State
        State state = stateManager.getState(taskId);
        if (state == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(state);
    }

    @PostMapping("/generate-stream")
    public SseEmitter generateReportStream(@RequestBody Map<String, String> request) {
        String query = request.get("query");
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("Query cannot be empty");
        }

        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);

        java.util.function.BiConsumer<String, Map<String, Object>> streamHandler = (eventType, payload) -> {
            try {
                emitter.send(SseEmitter.event()
                        .name(eventType)
                        .data(objectMapper.writeValueAsString(payload)));
            } catch (IOException e) {
                System.err.println("SSE stream error: " + e.getMessage());
            }
        };

        executor.execute(() -> {
            try {
                reportAgent.generateReport(
                        query,
                        Collections.emptyList(),
                        "",
                        null,
                        streamHandler
                );
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }
}
