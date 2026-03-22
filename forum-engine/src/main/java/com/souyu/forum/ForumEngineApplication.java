package com.souyu.forum;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@ComponentScan(
        basePackages = {"com.souyu.forum", "com.souyu.common.forum", "com.souyu.common.client","com.souyu.common.TaskStatus","com.souyu.common.manager","com.souyu.common.producer"}
)
public class ForumEngineApplication {
    public static void main(String[] args) {
        SpringApplication.run(ForumEngineApplication.class, args);
    }
}
