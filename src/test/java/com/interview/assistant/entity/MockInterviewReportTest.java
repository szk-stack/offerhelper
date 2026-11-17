package com.interview.assistant.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MockInterviewReportTest {

    @Test
    void newReportHasPlaceholderJson() {
        // reportJson 是非空列，新建实体必须有占位值，否则 GENERATING 状态无法入库
        assertEquals("{}", new MockInterviewReport().getReportJson());
    }
}
