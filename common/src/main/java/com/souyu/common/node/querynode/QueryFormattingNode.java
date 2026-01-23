package com.souyu.common.node.querynode;

import com.souyu.common.client.TimeContextChatClient;
import com.souyu.common.node.AbstractNode;
import com.souyu.common.prompt.DeepSearchPrompts;
import com.souyu.common.util.TextProcessing;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 报告格式化节点
 * 负责将最终研究结果格式化为美观的Markdown报告
 */
@Component
public class QueryFormattingNode extends AbstractNode<String, String> {

    protected final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 初始化报告格式化节点
     *
     * @param chatClient LLM客户端
     */

    public QueryFormattingNode(TimeContextChatClient chatClient) {
        super(chatClient, "ReportFormattingNode");
    }

    @Override
    public boolean validateInput(String inputData) {
        if (inputData == null) return false;
        try {
            List<Map<String, Object>> data = objectMapper.readValue(inputData, new TypeReference<>() {});
            return validateList(data);
        } catch (JsonProcessingException e) {
            return false;
        }
    }

    private boolean validateList(List<Map<String, Object>> data) {
        if (data == null || data.isEmpty()) {
            return false;
        }
        return data.stream().allMatch(item ->
                item != null && item.containsKey("title") && item.containsKey("paragraph_latest_state")
        );
    }

    @Override
    public String run(String inputData, Map<String, Object> kwargs) {
        try {
            if (!validateInput(inputData)) {
                throw new IllegalArgumentException("输入数据格式错误，需要包含title和paragraph_latest_state的列表JSON字符串");
            }

            logInfo("正在格式化最终报告...");

            // 检查 kwargs 中是否有自定义的 prompt
            String systemPrompt = DeepSearchPrompts.SYSTEM_PROMPT_REPORT_FORMATTING;
            if (kwargs != null && kwargs.containsKey("system_prompt")) {
                systemPrompt = (String) kwargs.get("system_prompt");
            }

            List<Message> messages = List.of(
                    new SystemMessage(systemPrompt),
                    new UserMessage(inputData)
            );
            Prompt prompt = new Prompt(messages);

            String response = chatClient.streamAndCollect(prompt).block();

            String processedResponse = processOutput(response);
            logInfo("成功生成格式化报告");
            return processedResponse;

        } catch (Exception e) {
            logError("报告格式化失败: " + e.getMessage());
            // Fallback to manual formatting
            try {
                List<Map<String, String>> paragraphsData = objectMapper.readValue(inputData, new TypeReference<>() {});
                return formatReportManually(paragraphsData, "深度研究报告");
            } catch (Exception manualEx) {
                logError("手动格式化也失败了: " + manualEx.getMessage());
                throw new RuntimeException("报告格式化和手动格式化均失败。", manualEx);
            }
        }
    }

    @Override
    public String processOutput(Object output) {
        if (!(output instanceof String)) {
            return "# 报告处理失败\n\n输出类型不是字符串。";
        }
        String outputStr = (String) output;
        try {
            String cleanedOutput = TextProcessing.removeReasoningFromOutput(outputStr);
            cleanedOutput = TextProcessing.cleanMarkdownTags(cleanedOutput);

            String trimmedOutput = cleanedOutput.trim();
            if (trimmedOutput.isEmpty()) {
                return "# 报告生成失败\n\n无法生成有效的报告内容。";
            }

            // Add a default H1 title if the content starts with H2 (or more) or doesn't start with a heading at all.
            if (trimmedOutput.startsWith("##") || !trimmedOutput.startsWith("#")) {
                return "# 深度研究报告\n\n" + trimmedOutput;
            }

            return trimmedOutput;
        } catch (Exception e) {
            logError("处理输出失败: " + e.getMessage());
            return "# 报告处理失败\n\n报告格式化过程中发生错误。";
        }
    }

    /**
     * 手动格式化报告（备用方法）
     *
     * @param paragraphsData 段落数据列表
     * @param reportTitle    报告标题
     * @return 格式化的Markdown报告
     */
    public String formatReportManually(List<Map<String, String>> paragraphsData, String reportTitle) {
        try {
            logInfo("使用手动格式化方法");
            StringBuilder reportBuilder = new StringBuilder();

            reportBuilder.append("# ").append(reportTitle).append("\n\n");
            reportBuilder.append("---\n\n");

            for (int i = 0; i < paragraphsData.size(); i++) {
                Map<String, String> paragraph = paragraphsData.get(i);
                String title = paragraph.getOrDefault("title", "段落 " + (i + 1));
                String content = paragraph.getOrDefault("paragraph_latest_state", "");

                if (content != null && !content.isBlank()) {
                    reportBuilder.append("## ").append(title).append("\n\n");
                    reportBuilder.append(content).append("\n\n");
                    reportBuilder.append("---\n\n");
                }
            }

            if (paragraphsData.size() > 1) {
                reportBuilder.append("## 结论\n\n");
                reportBuilder.append("本报告通过深度搜索和研究，对相关主题进行了全面分析。以上各个方面的内容为理解该主题提供了重要参考。\n\n");
            }

            return reportBuilder.toString();
        } catch (Exception e) {
            logError("手动格式化失败: " + e.getMessage());
            return "# 报告生成失败\n\n无法完成报告格式化。";
        }
    }
}
