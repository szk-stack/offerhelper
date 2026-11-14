package com.interview.assistant.service;

/**
 * 模型用途：不同用途对能力/速度的要求不同，允许配置不同模型。
 */
public enum LlmPurpose {
    /** 通用对话 */
    GENERAL,
    /** 结构化输出：面试计划、评估报告 */
    PLAN,
    /** 面试追问、答疑等低延迟场景 */
    CHAT,
    /** 图片理解（只有多模态模型支持） */
    VISION
}
