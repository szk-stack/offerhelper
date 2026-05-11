# AI 面试官模块 — 企业级 Agent 架构设计与补充分析

## 1. 现有系统分析

| 维度 | 现状 |
|------|------|
| **后端** | Spring Boot 3.2 + LangChain4j 1.1 + 智谱 GLM (OpenAI-compatible API) |
| **向量库** | Qdrant（面经 RAG 检索） |
| **数据库** | H2（开发环境），JPA 持久层 |
| **前端** | Vite + React (JSX)，已有 5 个页面 |
| **已有能力** | 简历解析(PDF/图片)、面经 RAG 检索、深挖问题生成、面试对话(文本)、面试复盘 |

> 你的需求核心是 **结构化面试 Agent**，与现有的 `InterviewChatService`（文本对话）是不同场景。作为**独立模块**新建，避免侵入已有代码。

---

## 2. 需求拆解

| # | 需求 | 状态 |
|---|------|------|
| 1 | 根据简历 + 面经生成结构化面试流程 | 可复用 `InterviewAgentService` + RAG |
| 2 | 按流程提问，项目/实习深挖追问 | 需要有状态 Agent + 流程引擎 |
| 3 | 语音输入（STT） | Phase 2 实现 |
| 4 | AI 文字显示 + 语音回复（TTS） | Phase 2 实现 |
| 5 | 后台保存完整对话文本 | 可参考现有持久化模式 |
| 6 | 面试结束生成量化评估报告 | 需新增 |

---

## 3. 架构设计

### 3.1 面试流程状态机

```
┌─────────┐    ┌──────────┐    ┌──────────┐    ┌──────────┐    ┌─────────┐
│ 流程生成 │───▶│ 开场寒暄 │───▶│ 自我介绍 │───▶│ 分模块   │───▶│ 总结/   │
│ (Plan)  │    │ (Warm-up)│    │ (Intro)  │    │ 提问     │    │ 反问环节│
└─────────┘    └──────────┘    └──────────┘    │(Sections)│    └────┬────┘
                                                └──────────┘         │
                                                                     ▼
                                                              ┌──────────┐
                                                              │ 生成报告 │
                                                              └──────────┘
```

每个 Section 内部：
```
主问题 → 候选人作答 → 判断是否需要追问（最多 N 轮）→ 下一题
```

`InterviewFlowEngine` 管理：
- 当前阶段 (phase) & 当前问题索引
- 追问深度计数器（避免无限追问）
- 跳过/回退能力
- 超时处理

### 3.2 数据库设计

| 表名 | 说明 |
|------|------|
| `mock_interview_sessions` | 面试会话：关联简历、面经、状态、总时长 |
| `mock_interview_plans` | 结构化面试流程（JSON 存储各阶段问题） |
| `mock_interview_dialogues` | 逐条对话记录（role、content、timestamp、phase） |
| `mock_interview_reports` | 量化评估报告 |

### 3.3 结构化面试流程 JSON 设计

```json
{
  "phases": [
    {
      "phaseId": 1,
      "type": "WARM_UP",
      "title": "开场",
      "questions": [
        { "id": "q1", "content": "简单做个自我介绍吧", "maxFollowUps": 1 }
      ]
    },
    {
      "phaseId": 2,
      "type": "PROJECT",
      "title": "项目经历深挖",
      "source": "resume",
      "questions": [
        {
          "id": "q2",
          "content": "你在简历中提到了 XXX 项目，能详细说说你在其中负责什么？",
          "maxFollowUps": 3,
          "followUpStrategy": "DEEP_DIVE"
        }
      ]
    },
    {
      "phaseId": 3,
      "type": "INTERNSHIP",
      "title": "实习经历",
      "source": "resume",
      "questions": [...]
    },
    {
      "phaseId": 4,
      "type": "EXPERIENCE_BASED",
      "title": "面经考点",
      "source": "interview_experience",
      "questions": [...]
    },
    {
      "phaseId": 5,
      "type": "WRAP_UP",
      "title": "结束",
      "questions": [
        { "id": "qN", "content": "你有什么想问我的吗？", "maxFollowUps": 0 }
      ]
    }
  ]
}
```

### 3.4 量化评估报告 JSON 设计

```json
{
  "overallScore": 78,
  "overallLevel": "B+（有基础，部分深度不足）",
  "dimensions": [
    { "name": "项目深度", "score": 85, "comment": "能清晰描述项目架构..." },
    { "name": "实习产出", "score": 70, "comment": "缺少量化成果..." },
    { "name": "八股理论", "score": 80, "comment": "..." },
    { "name": "大模型知识", "score": 65, "comment": "..." },
    { "name": "表达逻辑", "score": 82, "comment": "..." },
    { "name": "应变能力", "score": 75, "comment": "..." }
  ],
  "questionDetails": [
    {
      "questionId": "q2",
      "question": "XXX 项目中你遇到的最大技术挑战是什么？",
      "candidateAnswer": "...",
      "score": 7,
      "feedback": "回答了问题但缺少量化数据...",
      "suggestion": "建议补充具体的性能指标..."
    }
  ],
  "improvementPlan": [
    "1. Redis 集群方案需要深入学习...",
    "2. 项目经历建议准备 STAR 法则..."
  ]
}
```

### 3.5 Prompt 策略

1. **面试计划生成 Prompt** — 输出结构化 JSON
2. **面试官人设 Prompt** — 区分面试风格（温和型 / 压力面 / 技术极客型）
3. **追问决策 Prompt** — AI 判断回答是否需要追问（输出 JSON `{needFollowUp, reason, followUpQuestion}`）
4. **评分 Prompt** — 逐题打分 + 整体评价

---

## 4. 后端文件清单

### Entity 层
- `MockInterviewSession.java` — 面试会话实体（含状态枚举）
- `MockInterviewPlan.java` — 结构化面试计划实体
- `MockInterviewDialogue.java` — 对话记录实体
- `MockInterviewReport.java` — 量化评估报告实体

### Repository 层
- `MockInterviewSessionRepository.java`
- `MockInterviewPlanRepository.java`
- `MockInterviewDialogueRepository.java`
- `MockInterviewReportRepository.java`

### Service 层
- `InterviewFlowEngine.java` — 面试流程状态机
- `MockInterviewService.java` — 面试会话管理
- `MockInterviewReportService.java` — 量化报告生成

### Controller 层
- `MockInterviewController.java` — REST 端点

### Config 层
- `LlmConfig.java` — 新增面试官专用 ChatModel Bean

---

## 5. 前端文件清单

```
📁 pages/MockInterview/
├── MockInterview.jsx          # 主页面
├── MockInterview.css
├── components/
│   ├── InterviewSetup.jsx     # 面试配置（选简历、选面经、选风格）
│   ├── InterviewRoom.jsx      # 面试进行中界面
│   ├── InterviewProgress.jsx  # 面试进度条
│   ├── DialoguePanel.jsx      # 对话面板
│   └── InterviewReport.jsx    # 面试报告展示
```

---

## 6. 实施路线

### Phase 1 — MVP 核心功能（当前）
- 结构化面试计划生成（JSON 输出）
- 面试状态机 + 文本对话面试流程
- 后台完整对话记录持久化
- 面试结束生成量化报告
- 前端 MockInterview 页面（纯文本版本）

### Phase 2 — 语音能力集成（后续）
- 前端 Web Speech API (STT) 集成
- 后端 TTS API 集成
- WebSocket 通信层
- 语音控制 UI 组件

### Phase 3 — 生产加固
- PostgreSQL 迁移 + Flyway
- Spring Security + JWT
- LLM 调用重试 + 熔断
- 监控 & 日志增强

### Phase 4 — 体验优化
- 面试官风格可选
- 流式 AI 回复
- 面试报告 PDF 导出
- 面试历史对比分析
