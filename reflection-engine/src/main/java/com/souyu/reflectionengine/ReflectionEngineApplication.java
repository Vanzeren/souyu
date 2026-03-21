package com.souyu.reflectionengine;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.mongo.MongoDataAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * Reflection Engine - LLM-as-Judge 微服务入口。
 *
 * <p>运行在 Java 21 虚拟线程模式下（通过 spring.threads.virtual.enabled=true 配置），
 * 提供 POST /reflect 端点供 query-engine / media-engine 同步调用。
 *
 * <p>排除不需要的 MongoDB 和 Redis 自动配置（本服务无状态，无需持久层）。
 */
@SpringBootApplication(
        scanBasePackages = {"com.souyu.reflectionengine", "com.souyu.common.client",
                           "com.souyu.common.tavily", "com.souyu.common.bocha"},
        exclude = {
                MongoAutoConfiguration.class,
                MongoDataAutoConfiguration.class,
                RedisAutoConfiguration.class
        }
)
@EnableFeignClients(basePackages = "com.souyu.common.client")
public class ReflectionEngineApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReflectionEngineApplication.class, args);
    }
}
