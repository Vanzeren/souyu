package com.souyu.reportengine;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@EnableFeignClients
@ComponentScan(basePackages = {"com.souyu.reportengine", "com.souyu.common"})
public class ReportEngineApplication {
    public static void main(String[] args) {
        SpringApplication.run(ReportEngineApplication.class, args);
    }
}
