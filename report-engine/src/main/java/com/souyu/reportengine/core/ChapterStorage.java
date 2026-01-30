package com.souyu.reportengine.core;

import com.souyu.reportengine.model.ChapterModel;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.annotation.Id;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.AggregationUpdate;
import org.springframework.data.mongodb.core.aggregation.StringOperators;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.Writer;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 章节JSON写入与清单管理 (MongoDB原生版)。
 * <p>
 * 负责：
 * - 管理 Manifest 和 Chapter 集合；
 * - 提供流式写入器用于实时更新 MongoDB；
 * - 摒弃文件系统抽象，直接使用 reportId 和 chapterId。
 */
@Component
public class ChapterStorage {

    private static final Logger logger = LoggerFactory.getLogger(ChapterStorage.class);
    private final MongoTemplate mongoTemplate;

    @Autowired
    public ChapterStorage(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    // ======== 会话与清单 ========

    /**
     * 创建或更新 Manifest 记录。
     *
     * @param reportId 任务ID。
     * @param metadata Report元数据。
     */
    public void createManifest(String reportId, Map<String, Object> metadata) {
        Query query = new Query(Criteria.where("reportId").is(reportId));
        Update update = new Update()
                .setOnInsert("createdAt", nowIso())
                .set("updatedAt", nowIso())
                .set("metadata", metadata);
        
        mongoTemplate.upsert(query, update, Manifest.class);
        logger.info("Manifest created/updated for report: {}", reportId);
    }

    /**
     * 初始化章节记录，标记为 streaming 状态。
     *
     * @param reportId    报告ID。
     * @param chapterMeta 包含 chapterId/title/slug/order 的元数据。
     */
    public void initChapter(String reportId, Map<String, Object> chapterMeta) {
        String chapterId = (String) chapterMeta.get("chapterId");
        String slug = (String) chapterMeta.getOrDefault("slug", chapterId);
        String title = (String) chapterMeta.get("title");
        int order = (int) chapterMeta.getOrDefault("order", 0);

        Query query = new Query(Criteria.where("reportId").is(reportId).and("chapterId").is(chapterId));
        Update update = new Update()
                .set("slug", slug)
                .set("title", title)
                .set("order", order)
                .set("status", "streaming")
                .set("updatedAt", nowIso())
                .setOnInsert("rawContent", "");

        mongoTemplate.upsert(query, update, Chapter.class);
    }

    /**
     * 章节生成完毕后保存最终 Payload 并更新状态。
     *
     * @param reportId    报告ID。
     * @param chapterMeta 章节元信息。
     * @param payload     校验通过的章节JSON (结构化对象)。
     * @param errors      错误列表。
     */
    public void saveChapter(String reportId, Map<String, Object> chapterMeta, ChapterModel.ChapterContent payload, List<String> errors) {
        String chapterId = (String) chapterMeta.get("chapterId");
        String status = (errors == null || errors.isEmpty()) ? "ready" : "invalid";

        Query query = new Query(Criteria.where("reportId").is(reportId).and("chapterId").is(chapterId));
        Update update = new Update()
                .set("status", status)
                .set("updatedAt", nowIso())
                .set("payload", payload);
        
        if (errors != null && !errors.isEmpty()) {
            update.set("errors", errors);
        }

        mongoTemplate.updateFirst(query, update, Chapter.class);
        updateManifestTimestamp(reportId);
        logger.info("Chapter saved: {}/{} (Status: {})", reportId, chapterId, status);
    }

    /**
     * 加载指定报告的所有章节 Payload。
     */
    public List<ChapterModel.ChapterContent> getChapters(String reportId) {
        Query query = new Query(Criteria.where("reportId").is(reportId));
        query.with(Sort.by(Sort.Direction.ASC, "order"));
        
        List<Chapter> chapters = mongoTemplate.find(query, Chapter.class);
        List<ChapterModel.ChapterContent> payloads = new ArrayList<>();
        
        for (Chapter chapter : chapters) {
            if (chapter.getPayload() != null) {
                payloads.add(chapter.getPayload());
            }
        }
        return payloads;
    }

    // ======== 流式写入 ========

    /**
     * 获取一个用于流式更新 MongoDB rawContent 的 Writer。
     */
    public Writer getStreamWriter(String reportId, String chapterId) {
        return new MongoStreamWriter(mongoTemplate, reportId, chapterId);
    }

    // ======== 内部工具 ========

    private void updateManifestTimestamp(String reportId) {
        Query query = new Query(Criteria.where("reportId").is(reportId));
        Update update = new Update().set("updatedAt", nowIso());
        mongoTemplate.updateFirst(query, update, Manifest.class);
    }

    private String nowIso() {
        return LocalDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_DATE_TIME) + "Z";
    }

    // ======== 实体类 ========

    @Data
    @NoArgsConstructor
    @Document(collection = "manifests")
    public static class Manifest {
        @Id
        private String id;
        private String reportId;
        private String createdAt;
        private String updatedAt;
        private Map<String, Object> metadata;
    }

    @Data
    @NoArgsConstructor
    @Document(collection = "chapters")
    public static class Chapter {
        @Id
        private String id;
        private String chapterId;
        private String reportId;
        private int order;
        private String slug;
        private String title;
        private String status;
        private String updatedAt;
        private String rawContent;
        private ChapterModel.ChapterContent payload;
        private List<String> errors;
    }

    /**
     * 缓冲式 MongoDB 写入器，使用聚合管道实现高效追加。
     */
    public static class MongoStreamWriter extends Writer {
        private final MongoTemplate mongoTemplate;
        private final String reportId;
        private final String chapterId;
        private final StringBuilder buffer = new StringBuilder();

        public MongoStreamWriter(MongoTemplate mongoTemplate, String reportId, String chapterId) {
            this.mongoTemplate = mongoTemplate;
            this.reportId = reportId;
            this.chapterId = chapterId;
        }

        @Override
        public void write(char[] cbuf, int off, int len) {
            buffer.append(cbuf, off, len);
        }

        @Override
        public void flush() {
            if (buffer.length() > 0) {
                appendRawContent(buffer.toString());
                buffer.setLength(0); // 清空缓冲区
            }
        }

        @Override
        public void close() throws IOException {
            flush();
        }
        
        private void appendRawContent(String content) {
            Query query = new Query(Criteria.where("reportId").is(reportId).and("chapterId").is(chapterId));
            
            // 使用 AggregationUpdate 实现 $concat 追加
            // 相当于: [ { $set: { rawContent: { $concat: [ "$rawContent", content ] } } } ]
            AggregationUpdate update = AggregationUpdate.update()
                    .set("rawContent")
                    .toValue(StringOperators.Concat.valueOf("rawContent").concat(content));

            mongoTemplate.updateFirst(query, update, Chapter.class);
        }
    }
}
