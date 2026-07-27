package com.ksyun.agent.infrastructure.tool.builtin;

import com.ksyun.agent.core.tool.AgentTool;
import com.ksyun.agent.core.tool.ToolProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 内置工具 Spring 装配配置。
 * <p>
 * 工具实例由 Provider 内部持有，不声明为独立 AgentTool Bean，
 * 确保所有工具只通过 BuiltinToolProvider 注册一次。
 */
@Configuration
public class BuiltinToolConfiguration {

    @Bean
    public ToolProvider builtinToolProvider() {
        List<AgentTool> tools = List.of(
                new CalculatorTool(),
                new CurrentTimeTool(),
                new EchoTool(),
                new TextSearchTool(),
                new FileDeleteTool()
        );
        return new BuiltinToolProvider(tools);
    }
}
