<img src="logo.png" alt="sspOffer Logo" width="50%">
                     sspOffer面经助手 - 互联网面试准备平台<br>
                     Live Demo: http://8.138.47.162:8080<br>               
基于 LangChain4j 的面经整合与 AI 面试准备应用。支持面经搜索、结构化模拟面试（练习 / 真实模拟双模式）、面试复盘，并结合简历生成针对性深挖问题。


## 功能特性

- **面经搜索**：按公司、部门检索个人面经，支持手动录入或图片解析
- **结构化模拟面试**：根据目标公司/部门面经(RAG) + 你的简历生成分阶段面试计划，支持两种模式
  - **练习模式**：每题答完立刻给评价、纠错和参考答案，再问下一题
  - **真实模拟**：全程只追问或换题，不评价、不提示，结束后统一给逐题参考答案和综合评价
- **面试复盘**：上传真实面经，AI 深度复盘分析，给出改进建议
- **简历+投递**：简历管理、投递进度追踪
- **多租户账号**：注册即创建独立空间，面试记录、简历、投递、对话按租户隔离；面经库为所有账号共享

## 技术栈

- **后端**：Spring Boot 3.2 + LangChain4j（RAG）+ H2
- **前端**：React 18 + Vite
- **AI**：智谱 GLM（glm-4-flash、glm-4v-plus 等）/ DeepSeek

---

## 一、克隆后配置准备

1. **克隆项目**
   ```bash
   git clone https://github.com/alvinxx1123/sspOffer-interview-assistant.git
   cd sspOffer-interview-assistant
   ```

2. **配置智谱 API Key（AI 功能必需）**（可替换）  
   在 `src/main/resources/` 下创建 `application-local.yml`：
   
   ```yaml
   zhipu:
     apiKey: "你的智谱API_Key"
   ```
   
3. **配置管理员密码（可选）**  
   已由多租户登录取代，`app.admin-password` 不再参与接口校验；账号密码在首次注册时设置。  
   智谱 Key 也可用环境变量：`export ZHIPU_API_KEY=xxx`。

4. **启动**
   ```bash
   # 后端
   ./mvn spring-boot:run
   
   # 前端（另一终端）
   cd frontend
   npm install
   npm run dev
   ```
   访问 http://localhost:5173

---

## 二、阿里云部署教程（单机访问网站）

### 方式一：ECS + 单 jar 运行

1. **购买阿里云 ECS**（如 2 核 4G，CentOS 7/8 或 Ubuntu）

2. **在本地打包**
   ```bash
   cd frontend && npm install && npm run build && cd ..
   ./mvn package
   ```
   生成 `target/interview-assistant-1.0.0.jar`

3. **上传到 ECS**
   ```bash
   scp target/interview-assistant-1.0.0.jar root@你的公网IP:/opt/
   ```

4. **在 ECS 上运行**
   ```bash
   ssh root@你的公网IP
   cd /opt
   export ZHIPU_API_KEY=你的API_Key
   export APP_ADMIN_PASSWORD=你的密码
   nohup java -jar interview-assistant-1.0.0.jar --server.port=8080 > app.log 2>&1 &
   ```

5. **开放 8080 端口**  
   阿里云控制台 → 安全组 → 添加入方向规则：TCP 8080

6. **访问**  
   http://你的公网IP:8080

7. **开机自启**（可选）  
   使用 systemd 创建服务：
   ```ini
   # /etc/systemd/system/interview-assistant.service
   [Unit]
   Description=sspOffer Interview Assistant
   After=network.target
   
   [Service]
   User=root
   WorkingDirectory=/opt
   ExecStart=/usr/bin/java -jar /opt/interview-assistant-1.0.0.jar --server.port=8080
   Environment="ZHIPU_API_KEY=你的API_Key"
   Environment="APP_ADMIN_PASSWORD=你的密码"
   Restart=on-failure
   
   [Install]
   WantedBy=multi-user.target
   ```
   ```bash
   systemctl daemon-reload
   systemctl enable interview-assistant
   systemctl start interview-assistant
   ```

---

## 快速开始（本地开发）

### 1. 配置智谱 API Key（AI 功能必需）(可替换自定义大模型API Key)

从 [智谱开放平台](https://open.bigmodel.cn/) 获取 API Key，在 `src/main/resources/` 创建 `application-local.yml`：
```yaml
zhipu:
  apiKey: "你的API_Key"
```

### 2. 启动

```bash
# 后端
./mvn spring-boot:run

# 前端
cd frontend && npm install && npm run dev
```

访问 http://localhost:5173

---

## 多租户与账号

- 注册即创建一个独立租户（空间），当前为一个账号一个空间，表结构保留了成员扩展位：`tenants` / `app_users` / `auth_tokens`。
- 除注册、登录外，所有 `/api` 接口都需要 `Authorization: Bearer <token>`；前端未登录会跳转 `/login`。
- 个人数据（模拟面试、简历、投递、复盘记录、AI 面试会话、智能助手会话）按 `tenant_id` 行级隔离；面经库与算法题库为共享语料。
- 升级已有数据库时，启动日志会打印默认账号 `admin` 的随机密码（也可用 `APP_BOOTSTRAP_PASSWORD` 指定），历史数据自动归入「默认空间」。
- `app.auth.token-days` 控制令牌有效期，默认 30 天。

---

## 大模型配置（DeepSeek / 智谱）

登录后点右上角「模型」进入设置页，可以直接填写自己的 API：

| 供应商 | Base URL | 对话模型 | 计划/报告模型 |
|---|---|---|---|
| DeepSeek | `https://api.deepseek.com` | `deepseek-flash` | `deepseek-flash` |
| 智谱 GLM | `https://open.bigmodel.cn/api/paas/v4` | `glm-4-flash` | `glm-4-plus` |

- 保存后立即生效，不需要重启；面试出题、追问、评估报告、AI 面试、智能助手都会使用该配置。
- 不填则回落到 `application.yml` / 环境变量里的部署级默认模型。
- 图片解析：DeepSeek `deepseek-flash`（V4.1 Flash）原生支持图片，智谱用 `glm-4v-plus`；在设置页「图片模型」里填写即可。
- 面试对话采用 SSE 流式输出，首字返回后边生成边显示，底部有光标提示。

---

## 语音识别（语音输入回答）

模拟面试页的「🎤 语音」按钮把口头回答转成文字。**不再使用浏览器自带的 Web Speech API**
（它走 Google 服务器，国内经常 `network` 错误，且技术术语几乎必错、无法加热词），
改为前端采集 PCM 后上传后端，由云端 ASR 识别。

设置页「语音识别」支持四家，选一个填 Key 即可：

| 服务商 | Base URL | 模型 | 价格 | 免费额度 |
|---|---|---|---|---|
| 硅基流动 | `https://api.siliconflow.cn/v1` | `FunAudioLLM/SenseVoiceSmall` | 免费 | 有并发限速 |
| 阿里百炼 | `https://dashscope.aliyuncs.com/api/v1` | `qwen-audio-3.1-asr-flash` | 约 0.79 元/小时 | 10 小时/月 |
| 腾讯云 | `https://asr.tencentcloudapi.com` | `16k_zh_large` | 约 0.78 元/小时 | 5000 次/月 |
| 自定义 | 任意 OpenAI 兼容地址 | 自填 | 自定 | — |

- **Base URL 很宽容**：填到 `/v1` 或 `/api/v1` 即可，程序会自动补全端点；
  如果直接粘贴了完整端点（如百炼专属工作空间的
  `https://{WorkspaceId}.cn-beijing.maas.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation`），
  会原样使用、不再拼接。协议也会按 URL 形态自动识别，所以选「自定义」填百炼端点同样能跑。
- **腾讯云的 Key 填 `SecretId:SecretKey`**，中间用英文冒号；其余服务商填普通 Bearer Key。
- **热词表**：技术面试的术语（`Redis`、`分布式锁`、`缓存穿透`…）填进去能显著减少同音字错误，
  设置页已预填一批常见词，按岗位增删即可。腾讯云生效最明显（上限 128 个）。
- **术语纠错**：默认关闭。勾选后每段识别结果会用当前对话模型再过一遍，只修技术术语不动语气；
  也可以点「校对术语」手动触发。
- 未配置 Key 时语音按钮是禁用的，并在输入框上方提示前往设置页。

### 报错怎么读

错误提示按各家官方错误码文档映射，同一条提示会带上 `code`（和百炼/腾讯云的 `request_id`），
报障时把这两项一起发出来即可定位。百炼特别注意：**很多完全不同的错误都返回 HTTP 400**
（参数错误、欠费、内容审核、模型不存在），所以判断依据是业务码而不是状态码。

| 提示 | 常见原因 |
|---|---|
| 模型「xxx」不存在或未开通 | 模型名拼错（区分大小写），或控制台未开通、地域不对 |
| API Key 无效 | Key 不完整/有多余空格，或 Key 与 Base URL 不属于同一地域 |
| 账号还没开通百炼服务 | 首次使用需先在控制台开通（`AccessDenied.Unpurchased`） |
| 没有调用该模型的权限 | 子业务空间未授权该模型，改用主账号 Key 或补授权 |
| 请求太频繁被限流 | 触发 RPM/TPM，降频重试（免费额度并发很低） |
| 额度用完了 | 免费额度或套餐额度耗尽，充值或换服务商 |
| 阿里云账号欠费 | 充值后等几分钟（余额生效有延迟） |
| 这段音频被内容安全检测拦下了 | 触发了绿网审核，换个说法 |
| 连不上语音识别服务器 / 域名解析失败 | Base URL 拼错，或该专属域名只对特定网络开放 |
| 音频参数不被接受 | 模型的采样率/格式要求与 16kHz WAV 不符，换支持 WAV 的模型 |

腾讯云的错误码是 `ErrorCode.ErrorReason` 结构，已经逐个映射（签名、未开通、音频过长、
无人声、欠费、限流等），`FailedOperation.ErrorRecognize` 这类「识别无结果」的错误码
在「测试连接」时会被当作鉴权通过处理。

实现要点（改这块之前先看一眼）：

- 音频格式固定 **16kHz / 单声道 / 16bit WAV**，前端用 Web Audio 取 PCM 自己编码，
  不依赖 MediaRecorder（Chrome 只出 webm/opus，云 ASR 普遍不收）。
- 静音 **0.8 秒**自动切句，单段最多 45 秒，停顿即上屏，延迟 0.5–1 秒。
- 多段识别走串行队列，避免后一段先返回把前一段覆盖；发送回答时会丢弃在途结果。

---

## CI/CD

推送到 `main` 会自动构建并部署到 https://szk-everything.xyz/interview/ ：

1. GitHub Actions 构建前端（`VITE_BASE=interview`）并执行 `mvn package`（含测试）；
2. 只上传约 900 KB 的构建上下文（`src` + `pom.xml` + `frontend/dist`）；
3. 服务器解压后执行 `mvn clean package`，替换 `app.jar` 并重启 `ssp-offer`；
4. 本地健康检查通过才算成功，失败自动回滚到 `app.jar.prev`。

> 不在 CI 里直接上传 88 MB 的 jar，是因为 GitHub Runner 到国内服务器的跨境带宽实测只有十几 KB/s。

- 手动触发：仓库 Actions → Deploy to Production → Run workflow
- 服务器脚本：`scripts/remote-deploy.sh`（解压+构建）、`scripts/server-deploy.sh`（替换+重启+回滚）
- 部署用户：`deploy`，只被授予 `ssp-offer` 的 `systemctl restart/is-active` 权限

---

## 前端路径约定（部署在 `/interview/` 子路径）

- 接口：`import { API_BASE } from 'api/client'`，用 `` `${API_BASE}/...` ``，不要写死 `/api/...`
- 静态资源：`import { asset } from 'utils/asset'`，用 `asset('logo.png')`
- 页面跳转：react-router 的 `<Link to="/xxx">`（`to` 不需要加前缀）
- CI 会执行 `scripts/check-frontend-paths.sh`，发现硬编码根路径直接失败

---

## 项目结构

```
interview-assistant/
├── src/main/java/          # 后端
├── src/main/resources/
│   ├── application.yml     # 主配置（不含敏感信息）
│   └── application-local.yml  # 本地配置（gitignore）
├── frontend/               # 前端
└── pom.xml
```

---

## License

本项目采用 [MIT License](./LICENSE) 开源协议。
