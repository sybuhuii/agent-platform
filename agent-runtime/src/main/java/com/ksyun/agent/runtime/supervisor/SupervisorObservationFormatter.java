package com.ksyun.agent.runtime.supervisor;

import com.ksyun.agent.core.agent.AgentResult;
import com.ksyun.agent.core.agent.AgentTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Supervisor 观察消息格式化器，纯 Java 实现。
 * <p>
 * 将本轮 AgentTask 和 AgentResult 格式化为 Supervisor 下一轮可读取的安全观察消息。
 * 保持无状态和线程安全。
 */
public class SupervisorObservationFormatter {

    private static final Logger log = LoggerFactory.getLogger(SupervisorObservationFormatter.class);

    private static final int MAX_CONTENT_LENGTH = 4000;
    private static final int MAX_TOTAL_LENGTH = 12000;
    private static final int MAX_EVIDENCE_ITEMS = 5;

    /**
     * 格式化本轮子 Agent 任务和结果为观察消息。
     *
     * @param tasks   任务列表
     * @param results 结果列表
     * @return 格式化的观察消息文本
     */
    public String format(List<AgentTask> tasks, List<AgentResult> results) {
        if (tasks.size() != results.size()) {
            throw new IllegalArgumentException("Tasks and results count mismatch");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("子Agent执行结果：\n\n");

        boolean truncated = false;

        for (int i = 0; i < tasks.size(); i++) {
            AgentTask task = tasks.get(i);
            AgentResult result = results.get(i);

            sb.append("[").append(i + 1).append("] ");
            sb.append("taskId=").append(task.taskId());
            sb.append(", agentName=").append(task.agentName());
            sb.append(", success=").append(result.success());

            // 截断 content
            String content = result.content();
            if (content != null && content.length() > MAX_CONTENT_LENGTH) {
                content = content.substring(0, MAX_CONTENT_LENGTH) + "...(truncated)";
                truncated = true;
            }
            sb.append(", content=").append(content != null ? content : "");

            if (result.errorCode() != null) {
                sb.append(", errorCode=").append(result.errorCode());
            }

            // 有限 evidence
            List<String> evidence = result.evidence();
            if (evidence != null && !evidence.isEmpty()) {
                int limit = Math.min(evidence.size(), MAX_EVIDENCE_ITEMS);
                sb.append(", evidence=[");
                for (int j = 0; j < limit; j++) {
                    if (j > 0) sb.append("; ");
                    String ev = evidence.get(j);
                    if (ev != null && ev.length() > 200) {
                        sb.append(ev, 0, 200).append("...");
                        truncated = true;
                    } else {
                        sb.append(ev);
                    }
                }
                if (evidence.size() > MAX_EVIDENCE_ITEMS) {
                    sb.append("; ...(").append(evidence.size() - MAX_EVIDENCE_ITEMS).append(" more)");
                    truncated = true;
                }
                sb.append("]");
            }

            sb.append("\n");

            // 检查总长度
            if (sb.length() > MAX_TOTAL_LENGTH) {
                truncated = true;
                break;
            }
        }

        if (truncated) {
            sb.append("\n[部分内容已截断 truncated=true]");
        }

        return sb.toString();
    }
}
