package com.souyu.queryengine;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@EnableFeignClients(basePackages = "com.souyu.common.client")
@ComponentScan(basePackages = {"com.souyu.queryengine", "com.souyu.common"})
public class QueryEngineApplication {

    public static void main(String[] args) {
        SpringApplication.run(QueryEngineApplication.class, args);
    }

}
