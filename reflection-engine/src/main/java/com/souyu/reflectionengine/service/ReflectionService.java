package com.souyu.reflectionengine.service;

import com.souyu.common.dto.reflection.ReflectionRequest;
import com.souyu.common.dto.reflection.ReflectionResponse;

/**
 * Reflection Engine 服务接口。
 */
public interface ReflectionService {

    /**
     * 对当前段落进行 LLM-as-Judge 质量评判。
     *
     * @param request 包含段落上下文、当前摘要和本轮搜索结果的请求
     * @return 包含质量分、知识缺口、早停决策和补充搜索方向的响应
     */
    ReflectionResponse evaluate(ReflectionRequest request);
}
