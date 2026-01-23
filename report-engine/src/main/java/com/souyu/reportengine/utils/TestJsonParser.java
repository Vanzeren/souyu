package com.souyu.reportengine.utils;

import java.util.List;
import java.util.Map;

public class TestJsonParser {

    public static void main(String[] args) {
        runManualTest();
    }

    public static void runManualTest() {
        System.out.println("=".repeat(60));
        System.out.println("开始测试RobustJSONParser");
        System.out.println("=".repeat(60));

        JsonParser parser = new JsonParser(null, false, 3);

        // 测试实际错误案例
        String testCase = """
                ```json
                {
                  "totalWords": 40000,
                  "tolerance": 2000,
                  "globalGuidelines": [
                    "重点突出技术红利分配失衡、人才流失与职业认同危机等结构性矛盾"
                    "详略策略：技术创新与传统技艺的碰撞"
                  ],
                  "chapters": []
                }
                ```""";

        System.out.println("\n测试案例：");
        System.out.println(testCase);
        System.out.println("\n" + "=".repeat(60));

        try {
            Map<String, Object> result = parser.parse(testCase, "手动测试", null, null);
            System.out.println("\n✓ 解析成功！");
            System.out.println("\n解析结果：");
            System.out.println(result);
        } catch (Exception e) {
            System.out.println("\n✗ 解析失败: " + e);
        }

        System.out.println("\n" + "=".repeat(60));
    }
}
