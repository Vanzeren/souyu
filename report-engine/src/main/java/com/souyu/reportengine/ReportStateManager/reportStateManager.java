package com.souyu.reportengine.ReportStateManager;

import com.souyu.reportengine.ReportState.ReportState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

@Component
public class reportStateManager {

    private static final Logger logger = LoggerFactory.getLogger(reportStateManager.class);

    @Autowired
    private MongoTemplate mongoTemplate;

    /**
     * 初始化一个新的报告状态
     */
    public String initReportState(String query) {
        String taskId = "report_" + UUID.randomUUID().toString();
        ReportState state = new ReportState(taskId, query);
        saveState(state);
        logger.info("Initialized report state for task: {}", taskId);
        return taskId;
    }

    /**
     * 获取报告状态
     */
    public ReportState getReportState(String taskId) {
        return mongoTemplate.findById(taskId, ReportState.class);
    }

    /**
     * 保存报告状态
     */
    public void saveState(ReportState state) {
        mongoTemplate.save(state);
    }

    /**
     * 线程安全地执行状态更新操作 (利用 MongoDB 的原子性操作或乐观锁，这里简化为直接保存)
     * 实际生产中可能需要分布式锁，但考虑到 ReportEngine 的单任务特性，直接更新通常可行。
     * 如果需要严格并发控制，可以使用 common 中的 StateManager 类似的 Redisson 锁机制。
     * 这里为了简化，我们直接读取-修改-保存。
     */
    public void updateState(String taskId, Consumer<ReportState> action) {
        ReportState state = getReportState(taskId);
        if (state != null) {
            action.accept(state);
            saveState(state);
        } else {
            logger.warn("Report state not found for task: {}", taskId);
        }
    }

    /**
     * 更新状态并返回结果
     */
    public <R> R updateStateAndReturn(String taskId, Function<ReportState, R> action) {
        ReportState state = getReportState(taskId);
        if (state != null) {
            R result = action.apply(state);
            saveState(state);
            return result;
        } else {
            logger.warn("Report state not found for task: {}", taskId);
            return null;
        }
    }

    /**
     * 仅更新 HTML 内容 (优化性能，避免全量更新)
     */
    public void updateHtmlContent(String taskId, String htmlContent) {
        Query query = new Query(Criteria.where("_id").is(taskId));
        Update update = new Update().set("htmlContent", htmlContent);
        mongoTemplate.updateFirst(query, update, ReportState.class);
    }

    /**
     * 标记任务失败
     */
    public void markTaskFailed(String taskId, String errorMessage) {
        updateState(taskId, state -> state.markFailed(errorMessage));
    }
}
