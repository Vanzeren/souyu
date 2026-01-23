package com.souyu.common.state;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Search {
    private String query = "";
    private String url = "";
    private String title = "";
    private String content = "";
    private Double score;
    private String timestamp = Instant.now().toString();
}
