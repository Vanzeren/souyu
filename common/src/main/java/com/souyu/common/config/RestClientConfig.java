package com.souyu.common.config;

import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

import java.time.Duration;

@Configuration
public class RestClientConfig {

    @Bean
    public RestClientCustomizer restClientCustomizer() {
        return restClientBuilder -> restClientBuilder
                .requestFactory(new SimpleClientHttpRequestFactory() {{
                    setConnectTimeout(60000); // 60秒连接超时
                    setReadTimeout(300000);   // 5分钟读取超时 (适应 Function Calling 的长耗时)
                }});
    }
}
