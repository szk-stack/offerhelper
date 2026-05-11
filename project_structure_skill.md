# sspOffer Interview Assistant - Architecture & Code Map Skill

This document is a skill map for the `sspOffer-interview-assistant-main` project. It links functional modules directly to their source code locations to speed up development and debugging.

## 🎯 1. Core Module Deep Dive: Voice Mock Interview (`MockInterview`)
This is the flagship feature: a structured, multi-phase AI interviewer with voice/text interaction.

### ⚙️ Backend (Java Spring Boot)
- **State & Orchestration (`MockInterviewService.java`)**: 
  - Creates sessions, calls LLM to generate `MockInterviewPlan`, orchestrates the `startInterview()` and `answer()` multi-turn chat loops.
  - Contains large prompt templates like `PLAN_SYSTEM_PROMPT`.
- **Flow Engine (`InterviewFlowEngine.java`)**: 
  - Pure Java Logic (No LLM). Manages pointers for `currentPhaseIndex`, `currentQuestionIndex`, and `currentFollowUpCount`. Determines if the AI should ask the next question or follow up.
- **Reporting (`MockInterviewReportService.java`)**: 
  - Reads the entire dialogue transcript at the end and uses an LLM to generate a structured JSON report (scores, dimensions, feedback).
- **Entities (`entity/MockInterview*.java`)**:
  - `MockInterviewSession`: Primary tracker table (has `status`, `sessionId`, time).
  - `MockInterviewDialogue`: Logs the entire chat history row by row (`role`, `content`, `phaseType`).
- **Endpoints (`MockInterviewController.java`)**: 
  - Exposes REST APIs for session creation, submitting answers, and fetching results.

### 🎨 Frontend (React + Vite)
Located in `frontend/src/pages/MockInterview/`:
- **`MockInterview.jsx`**: Wrapper page holding state.
- **`components/InterviewSetup.jsx`**: Step 1 - Form where user inputs target company, department, and paste resume. Submits to create the `MockInterviewSession`.
- **`components/InterviewRoom.jsx`**: Step 2 - The actual Interview Interface. Contains the live chat UI, voice interaction toggles, and "End Interview" logic.
- **`components/InterviewReport.jsx`**: Step 3 - Post-interview screen rendering the AI-generated radar charts, dimensional scores, and granular question-by-question feedback.

---

## 🔍 2. RAG & Text-Based Simulations (`AIInterview` / `SmartAssistant`)
Modules focusing on chatting with面经 (past experiences) and generating specific problem lists.

### ⚙️ Backend (Java)
- **Agent Orchestrators**:
  - `InterviewAgentService.java`: Standard prompts (generate deep questions, replay interviews).
  - `InterviewAgentWithToolsService.java`: Advanced LLM wrapper with Function Calling enabled.
- **Tools / Capabilities**:
  - `InterviewAssistantTools.java`: The `@Tool` functions the LLM can call (e.g., search experiences, run code, search algorithms).
- **RAG Engine (`RagService.java`)**: 
  - Takes a query, embeds it (via Zhipu API), and searches the dockerized Qdrant Vector DB for related面经.

### 🎨 Frontend (React)
- **`AIInterview.jsx`**: The main interface for text-based chat with the AI interviewer vs standard prompt generation. 
- **`SmartAssistant.jsx`**: The floating/embedded Function-Calling widget where AI can execute tasks autonomously (like running Piston Python code).
- **`InterviewSearch.jsx`**: UI to manually search and browse the underlying Qdrant experience DB.

---

## 👨‍💻 3. Infrastructure & Resumes

- **Code Execution (`AlgorithmController.java`)**: Forwards code to the Piston API to run safely.
- **Resume Parsing (`ResumeParseService.java` & `ImageParseService.java`)**: Handles file uploads to extract baseline text for LLMs.
- **Admin & Passwords**: Controlled via `@Value("${app.admin-password}")` interceptor or filtering. 
  - *To Disable Admin Password*: Leave `app.admin-password: ""` empty in `src/main/resources/application-local.yml` or `application.yml`. Do not "search for admin"; it's a direct config binding.

---

## ⚡ Quick Action Playbooks

1. **"I want to change the Mock Interview Chat Rules / System Prompt"**
   👉 Go to `MockInterviewService.java` (`buildInterviewerSystemPrompt` or `PLAN_SYSTEM_PROMPT`).
   
2. **"I want to alter the UI layout of the mock interview room"**
   👉 Go to `frontend/src/pages/MockInterview/components/InterviewRoom.jsx`.

3. **"I want to add a completely new Tool for the AI to use autonomously"**
   👉 Go to `InterviewAssistantTools.java`, add a `@Tool` annotated method, and implement the logic.

4. **"The Mock Interview report criteria is lacking, add more dimensions"**
   👉 Go to `MockInterviewReportService.java` (`REPORT_SYSTEM_PROMPT`).

*Read this KI first every time a structural task is requested.*
