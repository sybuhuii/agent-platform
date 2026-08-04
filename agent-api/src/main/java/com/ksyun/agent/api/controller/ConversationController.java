package com.ksyun.agent.api.controller;

import com.ksyun.agent.api.dto.ConversationMessageResponse;
import com.ksyun.agent.api.dto.ConversationThreadResponse;
import com.ksyun.agent.api.dto.RenameThreadRequest;
import com.ksyun.agent.api.security.AuthenticatedSessionAttributes;
import com.ksyun.agent.application.conversation.ConversationHistoryApplicationService;
import com.ksyun.agent.core.conversation.ConversationMessage;
import com.ksyun.agent.core.conversation.ConversationThread;
import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.security.UserSession;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 会话历史 Controller。
 * <p>
 * 受认证保护。操作者身份来自已验证 UserSession。
 * <p>
 * 接口：
 * - GET  /api/conversations                              列出会话
 * - GET  /api/conversations/{threadId}/messages          列出消息
 * - PUT  /api/conversations/{threadId}/rename            重命名
 * - PUT  /api/conversations/{threadId}/pin               置顶
 * - PUT  /api/conversations/{threadId}/unpin             取消置顶
 * - PUT  /api/conversations/{threadId}/archive           归档
 */
@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    private final ObjectProvider<ConversationHistoryApplicationService> serviceProvider;

    public ConversationController(ObjectProvider<ConversationHistoryApplicationService> serviceProvider) {
        this.serviceProvider = serviceProvider;
    }

    @GetMapping
    public List<ConversationThreadResponse> listThreads(
            @RequestAttribute(AuthenticatedSessionAttributes.SESSION) UserSession session,
            @RequestParam(required = false) String cursorThreadId,
            @RequestParam(required = false) Long cursorLastMessageAt,
            @RequestParam(required = false, defaultValue = "50") Integer pageSize) {
        ConversationHistoryApplicationService service = requireService();
        List<ConversationThread> threads = service.listThreads(
                session, cursorThreadId, cursorLastMessageAt, pageSize);
        return threads.stream().map(this::toThreadResponse).toList();
    }

    @GetMapping("/{threadId}/messages")
    public List<ConversationMessageResponse> listMessages(
            @RequestAttribute(AuthenticatedSessionAttributes.SESSION) UserSession session,
            @PathVariable String threadId,
            @RequestParam(required = false) Long beforeSequence,
            @RequestParam(required = false, defaultValue = "50") Integer pageSize) {
        ConversationHistoryApplicationService service = requireService();
        List<ConversationMessage> messages = service.listMessages(
                session, threadId, beforeSequence, pageSize);
        return messages.stream().map(this::toMessageResponse).toList();
    }

    @PutMapping("/{threadId}/rename")
    public ResponseEntity<ConversationThreadResponse> rename(
            @RequestAttribute(AuthenticatedSessionAttributes.SESSION) UserSession session,
            @PathVariable String threadId,
            @RequestBody RenameThreadRequest request) {
        ConversationHistoryApplicationService service = requireService();
        return service.rename(session, threadId, request.title())
                .map(t -> ResponseEntity.ok(toThreadResponse(t)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{threadId}/pin")
    public ResponseEntity<ConversationThreadResponse> pin(
            @RequestAttribute(AuthenticatedSessionAttributes.SESSION) UserSession session,
            @PathVariable String threadId) {
        ConversationHistoryApplicationService service = requireService();
        return service.setPinned(session, threadId, true)
                .map(t -> ResponseEntity.ok(toThreadResponse(t)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{threadId}/unpin")
    public ResponseEntity<ConversationThreadResponse> unpin(
            @RequestAttribute(AuthenticatedSessionAttributes.SESSION) UserSession session,
            @PathVariable String threadId) {
        ConversationHistoryApplicationService service = requireService();
        return service.setPinned(session, threadId, false)
                .map(t -> ResponseEntity.ok(toThreadResponse(t)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{threadId}/archive")
    public ResponseEntity<ConversationThreadResponse> archive(
            @RequestAttribute(AuthenticatedSessionAttributes.SESSION) UserSession session,
            @PathVariable String threadId) {
        ConversationHistoryApplicationService service = requireService();
        return service.archive(session, threadId)
                .map(t -> ResponseEntity.ok(toThreadResponse(t)))
                .orElse(ResponseEntity.notFound().build());
    }

    // ---- DTO ----

    private ConversationThreadResponse toThreadResponse(ConversationThread t) {
        return new ConversationThreadResponse(
                t.threadId(),
                t.title(),
                t.pinned(),
                t.archived(),
                t.agentName(),
                t.createdAt().toEpochMilli(),
                t.lastMessageAt().toEpochMilli()
        );
    }

    private ConversationMessageResponse toMessageResponse(ConversationMessage m) {
        return new ConversationMessageResponse(
                m.messageId(),
                m.sequenceNo(),
                m.role().name(),
                m.content(),
                m.createdAt().toEpochMilli()
        );
    }

    private ConversationHistoryApplicationService requireService() {
        ConversationHistoryApplicationService service = serviceProvider.getIfAvailable();
        if (service == null) {
            throw new AgentFrameworkException(AgentErrorCode.INTERNAL_ERROR,
                    "Conversation service is not available");
        }
        return service;
    }
}
