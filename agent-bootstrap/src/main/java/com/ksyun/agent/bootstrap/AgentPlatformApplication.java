package com.ksyun.agent.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Agent Platform 启动类。
 * <p>
 * 作为唯一的 Spring Boot 入口，负责组装所有模块。
 */
@SpringBootApplication(scanBasePackages = "com.ksyun.agent")
public class AgentPlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentPlatformApplication.class, args);
    }
}
