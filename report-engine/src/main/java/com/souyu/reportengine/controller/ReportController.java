package com.souyu.reportengine.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.souyu.common.manager.StateManager;
import com.souyu.common.state.State;
import com.souyu.reportengine.client.MediaEngineClient;
import com.souyu.reportengine.client.QueryEngineClient;
import com.souyu.reportengine.angent.ReportAgent;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
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
    private ReportAgent reportAgent; // 注入 ReportAgent
    @Value("${report-engine.worker-count}")
    private int workerCount;
    private final ObjectMapper objectMapper; // 用于序列化

    // 使用线程池来处理耗时任务，避免阻塞Web服务器的请求处理线程
    private final ExecutorService executor = Executors.newCachedThreadPool();

    @PostMapping("/generate")
    public ResponseEntity<?> generateReport(@RequestBody Map<String, String> request) {
        String query = request.get("query");
        if (query == null || query.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Query cannot be empty"));
        }

        // 1. 初始化一个共享的 Task ID
        String taskId = stateManager.initTask();

        // 2. 构造带有 taskId 的请求
        Map<String, String> engineRequest = Map.of(
                "query", query,
                "taskId", taskId // 传递 taskId 给下游引擎
        );

        // 3. 并行（或串行）调用 QueryEngine 和 MediaEngine
        // 注意：下游引擎的 Controller 需要修改以支持接收 taskId
        try {
            // 这里只是示例，实际可能需要异步调用或者等待结果
             queryEngineClient.submitQuery(engineRequest);
             mediaEngineClient.submitMediaSearch(engineRequest);

            // Master 执行初始化
            RAtomicLong atomicCount = redissonClient.getAtomicLong("task:count:" + taskId);
            atomicCount.set(workerCount); // 这里存入的是纯数字字符串，不会报错
            atomicCount.expire(Duration.ofDays(1)); // 建议设置过期时间，防止 key 堆积
            
            return ResponseEntity.ok(Map.of(
                    "taskId", taskId,
                    "message", "Report generation started (Logic pending downstream updates)"
            ));
            
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }
    
    @GetMapping("/{taskId}")
    public ResponseEntity<?> getReportStatus(@PathVariable String taskId) {
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

        // 1. 创建 SseEmitter，设置一个较长的超时时间，例如 30 分钟
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);

        // 2. 定义 BiConsumer 逻辑：将 Agent 的事件通过 SseEmitter 发送出去
        //    这就是将 Agent 的内部状态变化 "流" 向前端的关键
        java.util.function.BiConsumer<String, Map<String, Object>> streamHandler = (eventType, payload) -> {
            try {
                // SseEmitter.event() 是构建一个标准SSE事件的推荐方式
                emitter.send(SseEmitter.event()
                        .name(eventType) // 事件类型，如 "stage", "progress"
                        .data(objectMapper.writeValueAsString(payload))); // 事件数据，转为JSON字符串
            } catch (IOException e) {
                // 当客户端断开连接时，会抛出异常
                System.err.println("SSE stream error: " + e.getMessage());
                // 可以在这里中断 Agent 的执行，但为了简单起见，我们只记录日志
            }
        };

        // 3. 在后台线程中执行耗时的报告生成任务
        executor.execute(() -> {
            try {
                // 调用 Agent，并把我们刚刚定义的 streamHandler 传进去
                reportAgent.generateReport(
                        query,
                        Collections.emptyList(), // 示例：传入空的原始报告
                        "",                      // 示例：传入空的论坛日志
                        null,                    // 示例：不使用自定义模板
                        streamHandler            // 传入回调处理器
                );
                // 4. 任务成功完成，关闭 SSE 连接
                emitter.complete();
            } catch (Exception e) {
                // 5. 任务失败，向客户端发送错误信息并关闭连接
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }
}
