package com.souyu.common.state;

import lombok.Data;

@Data
public class Paragraph {
    private String title = "";
    private String content = "";
    private Research research = new Research();
    private int order = 0;

    public boolean isCompleted() {
        return this.research.isCompleted() && this.research.getLatestSummary() != null && !this.research.getLatestSummary().isEmpty();
    }

    public String getFinalContent() {
        String finalContent = this.research.getLatestSummary();
        return (finalContent != null && !finalContent.isEmpty()) ? finalContent : this.content;
    }
}
