package com.interview.assistant.repository;

import com.interview.assistant.entity.MockInterviewDialogue;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface MockInterviewDialogueRepository extends JpaRepository<MockInterviewDialogue, Long> {

    List<MockInterviewDialogue> findBySession_IdOrderBySortOrderAscIdAsc(Long sessionId);

    List<MockInterviewDialogue> findBySession_SessionIdOrderBySortOrderAsc(String sessionId);

    int countBySession_Id(Long sessionId);

    /** 列表页批量统计对话条数 */
    interface DialogueCount {
        Long getSessionId();
        Long getCnt();
    }

    @Query("select d.session.id as sessionId, count(d) as cnt " +
           "from MockInterviewDialogue d where d.session.id in :ids group by d.session.id")
    List<DialogueCount> countGroupBySessionIds(@Param("ids") Collection<Long> ids);

    /** 每场会话最后一次发言时间，用于判定僵尸会话 */
    interface LastActivity {
        Long getSessionId();
        java.time.LocalDateTime getLastAt();
    }

    @Query("select d.session.id as sessionId, max(d.createdAt) as lastAt " +
           "from MockInterviewDialogue d where d.session.id in :ids group by d.session.id")
    List<LastActivity> findLastActivity(@Param("ids") Collection<Long> ids);
}
