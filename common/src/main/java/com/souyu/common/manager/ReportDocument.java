package com.souyu.common.manager;

import lombok.Data;
import org.springframework.data.annotation.Id;

@Data
public class ReportDocument {
    @Id
    private String id;
    private String name;
    private String content;
    private long timestamp;
}
