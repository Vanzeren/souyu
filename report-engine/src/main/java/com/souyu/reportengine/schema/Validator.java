package com.souyu.reportengine.schema;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class Validator {

    private final String schemaVersion;

    public Validator() {
        this.schemaVersion = Schema.IR_VERSION;
    }

    public Validator(String schemaVersion) {
        this.schemaVersion = schemaVersion;
    }

    // ======== 对外接口 ========

    /**
     * 校验单个章节对象的必填字段与block结构
     *
     * @param chapter 章节对象 Map
     * @return 包含校验结果和错误列表的 Result 对象
     */
    public Result validateChapter(Map<String, Object> chapter) {
        List<String> errors = new ArrayList<>();
        if (chapter == null) {
            errors.add("chapter必须是对象");
            return new Result(false, errors);
        }

        String[] requiredFields = {"chapterId", "title", "anchor", "order", "blocks"};
        for (String field : requiredFields) {
            if (!chapter.containsKey(field)) {
                errors.add("missing chapter." + field);
            }
        }

        Object blocksObj = chapter.get("blocks");
        if (!(blocksObj instanceof List) || ((List<?>) blocksObj).isEmpty()) {
            errors.add("chapter.blocks必须是非空数组");
            return new Result(false, errors);
        }

        List<?> blocks = (List<?>) blocksObj;
        for (int idx = 0; idx < blocks.size(); idx++) {
            validateBlock(blocks.get(idx), "blocks[" + idx + "]", errors);
        }

        return new Result(errors.isEmpty(), errors);
    }

    // ======== 内部工具 ========

    private void validateBlock(Object blockObj, String path, List<String> errors) {
        if (!(blockObj instanceof Map)) {
            errors.add(path + " 必须是对象");
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> block = (Map<String, Object>) blockObj;

        Object typeObj = block.get("type");
        if (!(typeObj instanceof String)) {
            errors.add(path + ".type 必须是字符串");
            return;
        }
        String blockType = (String) typeObj;

        if (!Schema.ALLOWED_BLOCK_TYPES.contains(blockType)) {
            errors.add(path + ".type 不被支持: " + blockType);
            return;
        }

        switch (blockType) {
            case "heading" -> validateHeadingBlock(block, path, errors);
            case "paragraph" -> validateParagraphBlock(block, path, errors);
            case "list" -> validateListBlock(block, path, errors);
            case "table" -> validateTableBlock(block, path, errors);
            case "blockquote" -> validateBlockquoteBlock(block, path, errors);
            case "engineQuote" -> validateEngineQuoteBlock(block, path, errors);
            case "hr" -> {
                // hr block only needs type check which is already done
            }
            case "code" -> validateCodeBlock(block, path, errors);
            case "math" -> validateMathBlock(block, path, errors);
            case "figure" -> validateFigureBlock(block, path, errors);
            case "callout" -> validateCalloutBlock(block, path, errors);
            case "kpiGrid" -> validateKpiGridBlock(block, path, errors);
            case "widget" -> validateWidgetBlock(block, path, errors);
            case "toc" -> {
                // toc block only needs type check which is already done
            }
            default -> {
                // Should not happen due to ALLOWED_BLOCK_TYPES check
            }
        }
    }

    private void validateHeadingBlock(Map<String, Object> block, String path, List<String> errors) {
        if (!block.containsKey("level") || !(block.get("level") instanceof Integer)) {
            errors.add(path + ".level 必须是整数");
        }
        if (!block.containsKey("text")) {
            errors.add(path + ".text 缺失");
        }
        if (!block.containsKey("anchor")) {
            errors.add(path + ".anchor 缺失");
        }
    }

    private void validateParagraphBlock(Map<String, Object> block, String path, List<String> errors) {
        Object inlinesObj = block.get("inlines");
        if (!(inlinesObj instanceof List) || ((List<?>) inlinesObj).isEmpty()) {
            errors.add(path + ".inlines 必须是非空数组");
            return;
        }
        List<?> inlines = (List<?>) inlinesObj;
        for (int idx = 0; idx < inlines.size(); idx++) {
            validateInlineRun(inlines.get(idx), path + ".inlines[" + idx + "]", errors);
        }
    }

    private void validateListBlock(Map<String, Object> block, String path, List<String> errors) {
        Object listTypeObj = block.get("listType");
        if (listTypeObj == null || !Set.of("ordered", "bullet", "task").contains(listTypeObj.toString())) {
            errors.add(path + ".listType 取值非法");
        }

        Object itemsObj = block.get("items");
        if (!(itemsObj instanceof List) || ((List<?>) itemsObj).isEmpty()) {
            errors.add(path + ".items 必须是非空列表");
            return;
        }
        List<?> items = (List<?>) itemsObj;
        for (int i = 0; i < items.size(); i++) {
            Object itemObj = items.get(i);
            if (!(itemObj instanceof List)) {
                errors.add(path + ".items[" + i + "] 必须是区块数组");
                continue;
            }
            List<?> itemBlocks = (List<?>) itemObj;
            for (int j = 0; j < itemBlocks.size(); j++) {
                validateBlock(itemBlocks.get(j), path + ".items[" + i + "][" + j + "]", errors);
            }
        }
    }

    private void validateTableBlock(Map<String, Object> block, String path, List<String> errors) {
        Object rowsObj = block.get("rows");
        if (!(rowsObj instanceof List) || ((List<?>) rowsObj).isEmpty()) {
            errors.add(path + ".rows 必须是非空数组");
            return;
        }
        List<?> rows = (List<?>) rowsObj;
        for (int rIdx = 0; rIdx < rows.size(); rIdx++) {
            Object rowObj = rows.get(rIdx);
            if (!(rowObj instanceof Map)) {
                errors.add(path + ".rows[" + rIdx + "] 必须是对象");
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> row = (Map<String, Object>) rowObj;
            Object cellsObj = row.get("cells");
            if (!(cellsObj instanceof List) || ((List<?>) cellsObj).isEmpty()) {
                errors.add(path + ".rows[" + rIdx + "].cells 必须是非空数组");
                continue;
            }
            List<?> cells = (List<?>) cellsObj;
            for (int cIdx = 0; cIdx < cells.size(); cIdx++) {
                Object cellObj = cells.get(cIdx);
                if (!(cellObj instanceof Map)) {
                    errors.add(path + ".rows[" + rIdx + "].cells[" + cIdx + "] 必须是对象");
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> cell = (Map<String, Object>) cellObj;
                Object blocksObj = cell.get("blocks");
                if (!(blocksObj instanceof List) || ((List<?>) blocksObj).isEmpty()) {
                    errors.add(path + ".rows[" + rIdx + "].cells[" + cIdx + "].blocks 必须是非空数组");
                    continue;
                }
                List<?> blocks = (List<?>) blocksObj;
                for (int bIdx = 0; bIdx < blocks.size(); bIdx++) {
                    validateBlock(blocks.get(bIdx), path + ".rows[" + rIdx + "].cells[" + cIdx + "].blocks[" + bIdx + "]", errors);
                }
            }
        }
    }

    private void validateBlockquoteBlock(Map<String, Object> block, String path, List<String> errors) {
        Object blocksObj = block.get("blocks");
        if (!(blocksObj instanceof List) || ((List<?>) blocksObj).isEmpty()) {
            errors.add(path + ".blocks 必须是非空数组");
            return;
        }
        List<?> blocks = (List<?>) blocksObj;
        for (int idx = 0; idx < blocks.size(); idx++) {
            validateBlock(blocks.get(idx), path + ".blocks[" + idx + "]", errors);
        }
    }

    private void validateEngineQuoteBlock(Map<String, Object> block, String path, List<String> errors) {
        Object engineObj = block.get("engine");
        String engine = (engineObj instanceof String) ? ((String) engineObj).toLowerCase() : null;
        if (engine == null || !Set.of("insight", "media", "query").contains(engine)) {
            errors.add(path + ".engine 取值非法: " + engineObj);
        }

        Object titleObj = block.get("title");
        String expectedTitle = (engine != null) ? Schema.ENGINE_AGENT_TITLES.get(engine) : null;

        if (titleObj == null) {
            errors.add(path + ".title 缺失");
        } else if (!(titleObj instanceof String)) {
            errors.add(path + ".title 必须是字符串");
        } else if (expectedTitle != null && !titleObj.equals(expectedTitle)) {
            errors.add(path + ".title 必须与engine一致，使用对应Agent名称: " + expectedTitle);
        }

        Object blocksObj = block.get("blocks");
        if (!(blocksObj instanceof List) || ((List<?>) blocksObj).isEmpty()) {
            errors.add(path + ".blocks 必须是非空数组");
            return;
        }
        List<?> blocks = (List<?>) blocksObj;
        for (int idx = 0; idx < blocks.size(); idx++) {
            String subPath = path + ".blocks[" + idx + "]";
            Object subBlockObj = blocks.get(idx);
            if (!(subBlockObj instanceof Map)) {
                errors.add(subPath + " 必须是对象");
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> subBlock = (Map<String, Object>) subBlockObj;

            if (!"paragraph".equals(subBlock.get("type"))) {
                errors.add(subPath + ".type 仅允许 paragraph");
                continue;
            }

            Object inlinesObj = subBlock.get("inlines");
            if (!(inlinesObj instanceof List) || ((List<?>) inlinesObj).isEmpty()) {
                errors.add(subPath + ".inlines 必须是非空数组");
                continue;
            }
            List<?> inlines = (List<?>) inlinesObj;
            for (int rIdx = 0; rIdx < inlines.size(); rIdx++) {
                validateInlineRun(inlines.get(rIdx), subPath + ".inlines[" + rIdx + "]", errors);
                if (inlines.get(rIdx) instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> run = (Map<String, Object>) inlines.get(rIdx);
                    Object marksObj = run.get("marks");
                    if (marksObj == null) continue;
                    if (!(marksObj instanceof List)) {
                        errors.add(subPath + ".inlines[" + rIdx + "].marks 必须是数组");
                        continue;
                    }
                    List<?> marks = (List<?>) marksObj;
                    for (int mIdx = 0; mIdx < marks.size(); mIdx++) {
                        Object markObj = marks.get(mIdx);
                        String markType = null;
                        if (markObj instanceof Map) {
                            markType = (String) ((Map<?, ?>) markObj).get("type");
                        }
                        if (markType == null || !Set.of("bold", "italic").contains(markType)) {
                            errors.add(subPath + ".inlines[" + rIdx + "].marks[" + mIdx + "].type 仅允许 bold/italic");
                        }
                    }
                }
            }
        }
    }

    private void validateCalloutBlock(Map<String, Object> block, String path, List<String> errors) {
        Object toneObj = block.get("tone");
        if (toneObj == null || !Set.of("info", "warning", "success", "danger").contains(toneObj.toString())) {
            errors.add(path + ".tone 取值非法: " + toneObj);
        }
        Object blocksObj = block.get("blocks");
        if (!(blocksObj instanceof List) || ((List<?>) blocksObj).isEmpty()) {
            errors.add(path + ".blocks 必须是非空数组");
            return;
        }
        List<?> blocks = (List<?>) blocksObj;
        for (int idx = 0; idx < blocks.size(); idx++) {
            validateBlock(blocks.get(idx), path + ".blocks[" + idx + "]", errors);
        }
    }

    private void validateKpiGridBlock(Map<String, Object> block, String path, List<String> errors) {
        Object itemsObj = block.get("items");
        if (!(itemsObj instanceof List) || ((List<?>) itemsObj).isEmpty()) {
            errors.add(path + ".items 必须是非空数组");
            return;
        }
        List<?> items = (List<?>) itemsObj;
        for (int idx = 0; idx < items.size(); idx++) {
            Object itemObj = items.get(idx);
            if (!(itemObj instanceof Map)) {
                errors.add(path + ".items[" + idx + "] 必须是对象");
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> item = (Map<String, Object>) itemObj;
            if (!item.containsKey("label") || !item.containsKey("value")) {
                errors.add(path + ".items[" + idx + "] 需要label与value");
            }
        }
    }

    private void validateWidgetBlock(Map<String, Object> block, String path, List<String> errors) {
        if (!block.containsKey("widgetId")) {
            errors.add(path + ".widgetId 缺失");
        }
        if (!block.containsKey("widgetType")) {
            errors.add(path + ".widgetType 缺失");
        }
        if (!block.containsKey("data") && !block.containsKey("dataRef")) {
            errors.add(path + " 需要 data 或 dataRef 其一");
        }
    }

    private void validateCodeBlock(Map<String, Object> block, String path, List<String> errors) {
        if (!block.containsKey("content")) {
            errors.add(path + ".content 缺失");
        }
    }

    private void validateMathBlock(Map<String, Object> block, String path, List<String> errors) {
        if (!block.containsKey("latex")) {
            errors.add(path + ".latex 缺失");
        }
    }

    private void validateFigureBlock(Map<String, Object> block, String path, List<String> errors) {
        Object imgObj = block.get("img");
        if (!(imgObj instanceof Map)) {
            errors.add(path + ".img 必须是对象");
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> img = (Map<String, Object>) imgObj;
        if (!img.containsKey("src")) {
            errors.add(path + ".img.src 缺失");
        }
    }

    private void validateInlineRun(Object runObj, String path, List<String> errors) {
        if (!(runObj instanceof Map)) {
            errors.add(path + " 必须是对象");
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> run = (Map<String, Object>) runObj;

        if (!run.containsKey("text")) {
            errors.add(path + ".text 缺失");
        }

        Object marksObj = run.get("marks");
        if (marksObj == null) {
            return;
        }
        if (!(marksObj instanceof List)) {
            errors.add(path + ".marks 必须是数组");
            return;
        }
        List<?> marks = (List<?>) marksObj;
        for (int mIdx = 0; mIdx < marks.size(); mIdx++) {
            Object markObj = marks.get(mIdx);
            if (!(markObj instanceof Map)) {
                errors.add(path + ".marks[" + mIdx + "] 必须是对象");
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> mark = (Map<String, Object>) markObj;
            Object typeObj = mark.get("type");
            if (typeObj == null || !Schema.ALLOWED_INLINE_MARKS.contains(typeObj.toString())) {
                errors.add(path + ".marks[" + mIdx + "].type 不被支持: " + typeObj);
            }
        }
    }

    public static class Result {
        private final boolean valid;
        private final List<String> errors;

        public Result(boolean valid, List<String> errors) {
            this.valid = valid;
            this.errors = errors;
        }

        public boolean isValid() {
            return valid;
        }

        public List<String> getErrors() {
            return errors;
        }
    }
}
