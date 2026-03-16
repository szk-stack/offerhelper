package com.interview.assistant.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interview.assistant.entity.InterviewExperience;
import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 小红书爬虫集成服务。
 * <p>
 * 调用 Python FastAPI 爬虫微服务（Spider_XHS-master/server.py）搜索小红书笔记，
 * 然后用 LLM 将非结构化笔记正文提取为结构化面经数据，最终存入数据库 + RAG 索引。
 * <p>
 * 数据流：
 * 用户自然语言 → AI Agent 调用 @Tool
 * → 本服务.searchNotes() 调用 Python 爬虫 HTTP API
 * → 本服务.extractInterviewFromNote() 用 LLM 提取结构化数据
 * → 存入 InterviewExperience 数据库
 * → 异步建 RAG 向量索引
 */
@Service
public class XhsSpiderService {

    private static final Logger log = LoggerFactory.getLogger(XhsSpiderService.class);

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private XhsAsyncExtractor asyncExtractor;

    public XhsSpiderService(@Qualifier("chatModel") ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public void setAsyncExtractor(@org.springframework.context.annotation.Lazy XhsAsyncExtractor asyncExtractor) {
        this.asyncExtractor = asyncExtractor;
    }

    public void extractAndSaveFromMcpOutput(String mcpOutput) {
        // Since MCP returns a formatted markdown string, we parse it into entities
        // and let AsyncExtractor handle saving and embeddings.
        if (mcpOutput == null || mcpOutput.isBlank())
            return;

        List<Map<String, Object>> mockNotes = new ArrayList<>();

        // Very basic parsing to simulate the old behaviour, adapting to MCP's markdown
        // format
        String[] sections = mcpOutput.split("#### \\[");
        for (int i = 1; i < sections.length; i++) {
            String section = sections[i];
            try {
                int titleEnd = section.indexOf("]");
                int urlStart = section.indexOf("(");
                int urlEnd = section.indexOf(")");

                if (titleEnd != -1 && urlStart != -1 && urlEnd != -1) {
                    Map<String, Object> note = new HashMap<>();
                    note.put("title", section.substring(0, titleEnd));
                    note.put("note_url", section.substring(urlStart + 1, urlEnd));
                    note.put("desc", section); // Store the rest of the text as desc
                    mockNotes.add(note);
                }
            } catch (Exception e) {
                log.warn("Failed to parse MCP markdown section: {}", e.getMessage());
            }
        }

        if (!mockNotes.isEmpty()) {
            asyncExtractor.asyncExtractAndSave(mockNotes);
        }
    }

    // ====================================================================
    // 2. 用 LLM 从笔记正文提取结构化面经
    // ====================================================================

    /**
     * 将一条小红书笔记的标题+正文用 LLM 提取为结构化面经数据。
     * 如果内容不是面经（比如是广告、鸡汤），返回 null。
     */
    public InterviewExperience extractInterviewFromNote(String title, String desc, String noteUrl) {
        String noteContent = (title != null ? title : "") + "\n" + (desc != null ? desc : "");
        if (noteContent.trim().length() < 30) {
            log.info("⏭️ 笔记内容太短，跳过: {}", title);
            return null;
        }

        String prompt = """
                请从以下小红书笔记中提取面试/面经信息。如果该笔记不是面经内容（比如是广告、心灵鸡汤、非技术内容），请直接返回 null（不要返回JSON）。

                如果是面经，请以如下 JSON 格式返回（只返回 JSON，不要多余文字）：
                {
                  "company": "公司名称",
                  "department": "部门（未提及填空字符串）",
                  "position": "岗位（如：后端开发、Java开发）",
                  "type": "校招/社招/实习",
                  "content": "面经概要（保留关键信息，150字以内）",
                  "baguQuestions": "八股题列表，每行一题，只写简短题目（如：TCP三次握手），最多10题，不要写答案",
                  "algorithmQuestions": "算法题（只写题目名，如：最长回文子串），多题换行分隔，最多5题"
                }

                重要：JSON 必须完整，总长度控制在500字以内。baguQuestions 只写简短题目，不要写完整的面试官问题原文。

                笔记标题：""" + title + """

                笔记正文：
                """ + desc;

        try {
            String response = chatModel.chat(prompt);
            if (response == null || response.trim().equalsIgnoreCase("null")
                    || response.trim().isEmpty()) {
                log.info("⏭️ LLM 判断非面经内容: {}", title);
                return null;
            }

            // 提取 JSON（LLM 可能返回 ```json ... ``` 包裹的内容）
            String json = response.trim();
            if (json.contains("```json")) {
                json = json.substring(json.indexOf("```json") + 7);
                json = json.substring(0, json.indexOf("```"));
            } else if (json.contains("```")) {
                json = json.substring(json.indexOf("```") + 3);
                json = json.substring(0, json.indexOf("```"));
            }
            json = json.trim();

            // 用 Map<String, Object> 解析，因为 LLM 可能返回数组（如 "algorithmQuestions": []）
            Map<String, Object> extracted = objectMapper.readValue(json, new TypeReference<>() {
            });

            InterviewExperience exp = new InterviewExperience();
            exp.setSource("小红书");
            exp.setCompany(safeStr(extracted, "company", "未知"));
            exp.setDepartment(safeStr(extracted, "department", ""));
            exp.setPosition(safeStr(extracted, "position", "未知"));
            exp.setType(safeStr(extracted, "type", ""));
            exp.setContent(safeStr(extracted, "content", noteContent));
            exp.setBaguQuestions(safeStr(extracted, "baguQuestions", ""));
            exp.setAlgorithmQuestions(safeStr(extracted, "algorithmQuestions", ""));

            log.info("✅ 提取成功: {} - {} - {}", exp.getCompany(), exp.getPosition(), title);
            return exp;

        } catch (Exception e) {
            log.warn("❌ LLM 提取失败 ({}): {}", title, e.getMessage());
            return null;
        }
    }

    /**
     * 安全地从 Map 中取字符串值。
     * LLM 可能返回字符串、数组、null 等各种类型，统一处理。
     */
    private String safeStr(Map<String, Object> map, String key, String defaultVal) {
        Object val = map.get(key);
        if (val == null)
            return defaultVal;
        if (val instanceof String s)
            return s;
        if (val instanceof List<?> list) {
            // LLM 可能返回 "algorithmQuestions": ["题目1", "题目2"]
            StringBuilder sb = new StringBuilder();
            for (Object item : list) {
                if (sb.length() > 0)
                    sb.append("\n");
                sb.append(String.valueOf(item));
            }
            return sb.toString();
        }
        return String.valueOf(val);
    }
}
