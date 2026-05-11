package com.interview.assistant.repository;

import com.interview.assistant.entity.MockInterviewDialogue;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MockInterviewDialogueRepository extends JpaRepository<MockInterviewDialogue, Long> {

    List<MockInterviewDialogue> findBySession_IdOrderBySortOrderAscIdAsc(Long sessionId);

    List<MockInterviewDialogue> findBySession_SessionIdOrderBySortOrderAsc(String sessionId);

    int countBySession_Id(Long sessionId);
}
