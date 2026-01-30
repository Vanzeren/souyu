package com.souyu.common.dto;

import java.util.List;

public record ReportStructure(List<ParagraphStructure> items) {
    public record ParagraphStructure(String title, String content) {}
}
