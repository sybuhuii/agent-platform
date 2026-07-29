package com.ksyun.agent.runtime.checkpoint.thread;

/**
 * 线程 Checkpoint stateData 键常量。
 * <p>
 * 不得在多个类散落字符串。
 * 不得使用可修改 public 集合。
 * 不得使用用户输入作为 StateKey。
 * 不得把 userId 作为 stateData 键。
 * 不得把消息正文拼接到键名。
 */
public final class ThreadCheckpointStateKeys {

    /** 线程会话状态键 */
    public static final String THREAD_CONVERSATION_STATE = "threadConversationState";

    private ThreadCheckpointStateKeys() {
        // 常量类，禁止实例化
    }
}
