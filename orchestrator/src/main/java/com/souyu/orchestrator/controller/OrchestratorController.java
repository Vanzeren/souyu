package com.souyu.orchestrator.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.souyu.common.TaskStatus.TaskStatus;
import com.souyu.common.manager.StateManager;
import com.souyu.common.manager.TaskStatusManager;
import com.souyu.common.state.State;
import com.souyu.orchestrator.client.MediaEngineClient;
import com.souyu.orchestrator.client.QueryEngineClient;
import com.souyu.orchestrator.client.ReportEngineClient;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RestController
@RequestMapping("/api/v1/orchestrator")
@RequiredArgsConstructor
public class OrchestratorController {

    private final QueryEngineClient queryEngineClient;
    private final MediaEngineClient mediaEngineClient;
    private final ReportEngineClient reportEngineClient;
    
    @Autowired
    private StateManager stateManager;
    
    @Autowired
    private TaskStatusManager taskStatusManager;
    
    private final ObjectMapper objectMapper;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    @PostMapping("/generate")
    public ResponseEntity<?> generateReport(@RequestBody Map<String, String> request) {
        String query = request.get("query");
        if (query == null || query.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Query cannot be empty"));
        }

        // 1. 初始化 Task ID
        String taskId = stateManager.initTask();
        
        // 2. 初始化 TaskStatus
        taskStatusManager.initTask(taskId);
        taskStatusManager.updateWorkerStatus(taskId, "query", TaskStatus.WorkerStatus.PENDING);
        taskStatusManager.updateWorkerStatus(taskId, "media", TaskStatus.WorkerStatus.PENDING);
        taskStatusManager.updateMainStatus(taskId, TaskStatus.Status.RESEARCHING);

        Map<String, String> engineRequest = Map.of(
                "query", query,
                "taskId", taskId
        );

        try {
            // 3. 异步调用 Worker
            taskStatusManager.updateWorkerStatus(taskId, "query", TaskStatus.WorkerStatus.RUNNING);
            queryEngineClient.submitQuery(engineRequest);
            
            taskStatusManager.updateWorkerStatus(taskId, "media", TaskStatus.WorkerStatus.RUNNING);
            mediaEngineClient.submitMediaSearch(engineRequest);

            // 计数器逻辑已移除，完全依赖状态机驱动
            
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
        TaskStatus status = taskStatusManager.getTaskStatus(taskId);
        if (status != null) {
            return ResponseEntity.ok(status);
        }
        State state = stateManager.getState(taskId);
        if (state == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(state);
    }
}
