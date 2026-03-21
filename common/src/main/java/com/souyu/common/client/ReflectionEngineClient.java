package com.souyu.common.client;

import com.souyu.common.dto.reflection.ReflectionRequest;
import com.souyu.common.dto.reflection.ReflectionResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * Feign Client for reflection-engine microservice.
 * <p>
 * reflection-engine 是同步的 LLM-as-Judge 服务，在每轮反思后被调用，
 * 负责评估当前段落质量、识别知识缺口并决定是否早停反思循环。
 * <p>
 * URL 可通过 {@code app.reflection-engine.url} 配置，默认为本地 8084 端口。
 */
@FeignClient(name = "reflection-engine", url = "${app.reflection-engine.url:http://localhost:8084}")
public interface ReflectionEngineClient {

    /**
     * 对当前段落进行质量评判。
     *
     * @param request 包含段落上下文、当前摘要和本轮搜索结果的请求体
     * @return 包含质量分、知识缺口、早停决策和下轮搜索方向的响应体
     */
    @PostMapping("/reflect")
    ReflectionResponse reflect(@RequestBody ReflectionRequest request);
}
