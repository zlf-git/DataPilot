package com.zlf.datapilot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * DataPilot（数据领航员）启动类。
 * 借鉴 Spring AI Alibaba DataAgent 的最小 Text2SQL Agent。
 */
@SpringBootApplication
public class DataPilotApplication {

    public static void main(String[] args) {
        SpringApplication.run(DataPilotApplication.class, args);
    }
}
