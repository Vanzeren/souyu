package com.souyu.common.state;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
public class State {
    private String query = "";
    private String reportTitle = "";
    private List<Paragraph> paragraphs = new ArrayList<>();
    private String finalReport = "";
    private boolean isCompleted = false;
    private String createdAt = Instant.now().toString();
    private String updatedAt = Instant.now().toString();

    public int addParagraph(String title,String content) {
        int order = this.paragraphs.size();
        Paragraph paragraph = new Paragraph();
        paragraph.setTitle(title);
        paragraph.setContent(content);
        paragraph.setOrder(order);
        this.paragraphs.add(paragraph);
        updateTimestamp();
        return order;
    }

    public Paragraph getParagraph(int index) {
        if (index >= 0 && index < this.paragraphs.size()) {
            return this.paragraphs.get(index);
        }
        return null;
    }

    @JsonIgnore
    public long getCompletedParagraphsCount() {
        return this.paragraphs.stream().filter(Paragraph::isCompleted).count();
    }

    @JsonIgnore
    public int getTotalParagraphsCount() {
        return this.paragraphs.size();
    }

    @JsonIgnore
    public boolean isAllParagraphsCompleted() {
        if (this.paragraphs.isEmpty()) {
            return false;
        }
        return this.paragraphs.stream().allMatch(Paragraph::isCompleted);
    }

    public void markCompleted() {
        this.isCompleted = true;
        updateTimestamp();
    }

    public void updateTimestamp() {
        this.updatedAt = Instant.now().toString();
    }

    @JsonIgnore
    public Map<String, Object> getProgressSummary() {
        long completed = getCompletedParagraphsCount();
        int total = getTotalParagraphsCount();
        double progressPercentage = (total > 0) ? ((double) completed / total * 100) : 0;

        Map<String, Object> summary = new HashMap<>();
        summary.put("total_paragraphs", total);
        summary.put("completed_paragraphs", completed);
        summary.put("progress_percentage", progressPercentage);
        summary.put("is_completed", this.isCompleted);
        summary.put("created_at", this.createdAt);
        summary.put("updated_at", this.updatedAt);
        return summary;
    }

    public String toJson(boolean pretty) throws JsonProcessingException {
        ObjectMapper mapper = new ObjectMapper();
        if (pretty) {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(this);
        }
        return mapper.writeValueAsString(this);
    }

    public static State fromJson(String json) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        return mapper.readValue(json, State.class);
    }

    public void saveToFile(String filepath) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        mapper.writerWithDefaultPrettyPrinter().writeValue(new File(filepath), this);
    }

    public static State loadFromFile(String filepath) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        return mapper.readValue(new File(filepath), State.class);
    }
}
