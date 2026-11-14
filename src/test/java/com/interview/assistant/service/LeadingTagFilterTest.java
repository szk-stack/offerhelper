package com.interview.assistant.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 流式输出的隐式标签过滤：标签可能被拆成多个 token，必须缓冲后再决定是否丢弃。
 */
class LeadingTagFilterTest {

    @Test
    void stripsTagSplitAcrossTokens() {
        MockInterviewService.LeadingTagFilter filter = new MockInterviewService.LeadingTagFilter();
        StringBuilder out = new StringBuilder();
        out.append(filter.accept("[FOLL"));
        out.append(filter.accept("OW_UP] 你好"));
        out.append(filter.accept("，继续"));
        assertEquals(" 你好，继续", out.toString());
    }

    @Test
    void stripsTagInSingleToken() {
        MockInterviewService.LeadingTagFilter filter = new MockInterviewService.LeadingTagFilter();
        assertEquals("下一题", filter.accept("[NEXT]下一题"));
    }

    @Test
    void passesThroughPlainText() {
        MockInterviewService.LeadingTagFilter filter = new MockInterviewService.LeadingTagFilter();
        assertEquals("你好", filter.accept("你好"));
        assertEquals("，继续", filter.accept("，继续"));
    }

    @Test
    void keepsNonTagBrackets() {
        MockInterviewService.LeadingTagFilter filter = new MockInterviewService.LeadingTagFilter();
        assertEquals("[产品]怎么设计", filter.accept("[产品]怎么设计"));
    }

    @Test
    void waitsForShortPrefix() {
        MockInterviewService.LeadingTagFilter filter = new MockInterviewService.LeadingTagFilter();
        assertEquals("", filter.accept("["));
        assertEquals("", filter.accept("NEX"));
        assertEquals("题", filter.accept("T]题"));
    }

    @Test
    void finishFlushesBufferedText() {
        MockInterviewService.LeadingTagFilter filter = new MockInterviewService.LeadingTagFilter();
        filter.accept("[NEXT");
        assertEquals("[NEXT", filter.finish());
    }
}
