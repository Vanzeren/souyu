package com.souyu;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
@EnableFeignClients(basePackages = "com.souyu.common.client")
@ComponentScan(basePackages = { "com.souyu.mediaengine", "com.souyu.common"})
public class MediaEngineApplication {

    public static void main(String[] args) {
        SpringApplication.run(MediaEngineApplication.class, args);
    }

}
