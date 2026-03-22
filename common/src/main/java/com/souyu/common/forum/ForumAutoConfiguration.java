package com.souyu.common.forum;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;

/**
 * Forum 模块自动配置类
 *
 * 注意：定时任务需要在应用主类中使用 @EnableScheduling 启用
 * 不在此处全局启用，避免与其他模块的 TaskScheduler 冲突
 */
@Configuration
@Slf4j
public class ForumAutoConfiguration {

    @PostConstruct
    public void init() {
        log.info("Forum auto configuration initialized");
    }
}
