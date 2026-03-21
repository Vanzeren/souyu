package com.souyu.reflectionengine.controller;

import com.souyu.common.dto.reflection.ReflectionRequest;
import com.souyu.common.dto.reflection.ReflectionResponse;
import com.souyu.reflectionengine.service.ReflectionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reflection Engine HTTP Controller.
 *
 * <p>暴露 POST /reflect 端点，供 query-engine / media-engine 通过 Feign 同步调用。
 * 运行在 Java 21 虚拟线程上，每个并发请求独占一个虚拟线程，无阻塞风险。
 */
@Slf4j
@RestController
@RequestMapping("/reflect")
public class ReflectionController {

    @Autowired
    private ReflectionService reflectionService;

    /**
     * 对段落进行 LLM-as-Judge 质量评判。
     *
     * @param request 段落上下文、当前摘要和本轮搜索结果
     * @return 质量评判结果，包含是否早停、质量分和下一轮搜索方向
     */
    @PostMapping
    public ResponseEntity<ReflectionResponse> reflect(@RequestBody ReflectionRequest request) {
        long startTime = System.currentTimeMillis();
        log.info("[ReflectionController] 收到评判请求: taskId={}, 段落='{}', 轮次={}/{}, 搜索结果数={}",
                request.taskId(),
                request.paragraphTitle(),
                request.reflectionRound() + 1,
                request.maxReflections(),
                request.searchResults() != null ? request.searchResults().size() : 0);

        if (log.isDebugEnabled()) {
            log.debug("[ReflectionController] 请求详情: taskId={}, expected='{}', currentSummary长度={}",
                    request.taskId(),
                    request.paragraphExpected(),
                    request.currentSummary() != null ? request.currentSummary().length() : 0);
        }

        try {
            ReflectionResponse response = reflectionService.evaluate(request);

            long duration = System.currentTimeMillis() - startTime;
            log.info("[ReflectionController] 评判完成: taskId={}, 耗时={}ms, 质量分={:.2f}, 是否继续={}",
                    request.taskId(),
                    duration,
                    response.qualityScore(),
                    response.shouldContinue());

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("[ReflectionController] 评判失败: taskId={}, 耗时={}ms, 错误={}",
                    request.taskId(), duration, e.getMessage(), e);
            throw e;
        }
    }
}
