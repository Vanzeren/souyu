package com.souyu.common.state;

import lombok.Data;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Data
public class Research {
    private List<Search> searchHistory = new ArrayList<>();
    private String latestSummary = "";
    private int reflectionIteration = 0;
    private boolean isCompleted = false;

    public void addSearch(Search search) {
        this.searchHistory.add(search);
    }

    public void addSearchResults(String query, List<Map<String, Object>> results) {
        for (Map<String, Object> result : results) {
            Search search = new Search(
                query,
                (String) result.getOrDefault("url", ""),
                (String) result.getOrDefault("title", ""),
                (String) result.getOrDefault("content", ""),
                (Double) result.get("score"),
                null // Let constructor handle default timestamp
            );
            this.addSearch(search);
        }
    }

    public int getSearchCount() {
        return this.searchHistory.size();
    }

    public void incrementReflection() {
        this.reflectionIteration++;
    }

    public void markCompleted() {
        this.isCompleted = true;
    }
}
