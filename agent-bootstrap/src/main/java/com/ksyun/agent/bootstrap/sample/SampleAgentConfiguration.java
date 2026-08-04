package com.ksyun.agent.bootstrap.sample;

import com.ksyun.agent.core.agent.AgentProvider;
import com.ksyun.agent.core.sample.DemoRecordStore;
import com.ksyun.agent.core.supervisor.SupervisorProvider;
import com.ksyun.agent.core.tool.AgentTool;
import com.ksyun.agent.core.tool.ToolProvider;
import com.ksyun.agent.bootstrap.sample.tool.DeleteDemoRecordTool;
import com.ksyun.agent.bootstrap.sample.tool.ListDemoRecordsTool;
import com.ksyun.agent.bootstrap.sample.node.SampleNodeApprovalNode;
import com.ksyun.agent.bootstrap.sample.node.SampleNodeResumeHandler;
import com.ksyun.agent.bootstrap.sample.node.SampleNodeResumeDataCodec;
import com.ksyun.agent.core.approval.NodeResumeDataCodec;
import com.ksyun.agent.infrastructure.sample.InMemoryDemoRecordStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.ksyun.agent.runtime.hitl.node.NodeHitlInterruptService;
import com.ksyun.agent.runtime.react.ReactAgentGraphFactory;
import com.ksyun.agent.runtime.react.node.ReactPreExecutionNode;

import java.util.List;

/**
 * Sample Agent、Supervisor 和演示工具装配配置。
 * <p>
 * 通过已有 ProviderRegistrar 自动注册 Sample Agent、Supervisor 和工具。
 * 受 agent.sample.enabled 属性控制。
 * matchIfMissing 不得为 true，避免自动开启本批危险演示工具。
 * <p>
 * ADMIN 的 tool:*:invoke 只能通过 ACL，不能绕过审批。
 * VISITOR 无 delete 权限时必须由 ACL 拒绝，不创建 Checkpoint。
 * dev 通配权限可以到达审批，但不能绕过审批。
 */
@Configuration
@ConditionalOnProperty(name = "agent.sample.enabled", havingValue = "true")
public class SampleAgentConfiguration {

    @Bean
    public AgentProvider sampleAgentProvider() {
        return new SampleAgentProvider();
    }

    @Bean
    public SupervisorProvider sampleSupervisorProvider() {
        return new SampleSupervisorProvider();
    }

    // ---- Phase6 Batch2 演示工具 ----

    @Bean
    public DemoRecordStore demoRecordStore() {
        return new InMemoryDemoRecordStore();
    }

    @Bean
    public ToolProvider sampleDemoToolProvider(DemoRecordStore demoRecordStore) {
        List<AgentTool> tools = List.of(
                new ListDemoRecordsTool(demoRecordStore),
                new DeleteDemoRecordTool(demoRecordStore)
        );
        return new com.ksyun.agent.infrastructure.tool.builtin.BuiltinToolProvider(tools);
    }

    @Bean
    public ReactPreExecutionNode sampleNodeApprovalNode(
            NodeHitlInterruptService interruptService) {
        return new SampleNodeApprovalNode(interruptService);
    }

    @Bean
    public SampleNodeResumeHandler sampleNodeResumeHandler(
            ObjectProvider<ReactAgentGraphFactory> graphFactoryProvider) {
        return new SampleNodeResumeHandler(graphFactoryProvider::getObject);
    }

    @Bean
    public NodeResumeDataCodec<?> sampleNodeResumeDataCodec() {
        return new SampleNodeResumeDataCodec();
    }
}
