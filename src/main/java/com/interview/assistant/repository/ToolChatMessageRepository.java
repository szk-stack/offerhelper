package com.interview.assistant.repository;

import com.interview.assistant.entity.ToolChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ToolChatMessageRepository extends JpaRepository<ToolChatMessage, Long> {
    List<ToolChatMessage> findByToolSession_IdOrderBySortOrderAscIdAsc(Long sessionId);
}
