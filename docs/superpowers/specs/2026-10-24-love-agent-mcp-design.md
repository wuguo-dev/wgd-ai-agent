# 恋爱大师 MCP 智能体最小闭环 设计规格

> 状态：已定稿，可直接派活实施（含第二轮技术核查修正）
> 日期：2026-10-24
> 适用仓库：`wgd-ai-agent`（项目根 `D:\Dev\Code\Project\wgd-ai-agent-master`）
> 读者：实现本需求的编码 agent 与 reviewer。本文自包含，不依赖任何对话上下文。
> 关键词：Spring Boot 3.4.4、Spring AI 1.1.2、spring-ai-alibaba 1.1.2.0、ReactAgent、FileSystemSaver、MCP Client、高德地图、工具调用、降级

本文把「恋爱大师」从**单轮知识问答**升级为**会调工具的智能体**，并且只做最小闭环：一个 ReAct 内核、两个本地工具、一个外部 MCP 工具源、两个 HTTP 接口、一套可离线验证的测试分层。功能面刻意收窄，工程面（记忆、降级、密钥、测试、交付边界）写死。

---

## 1. 背景与目标

### 1.1 现状（已核实）

- 技术栈：Spring Boot 3.4.4 / Java 21 / Spring AI 1.1.2 / spring-ai-alibaba 1.1.2.0；服务端口 `8123`，`server.servlet.context-path=/api`。
- `com.wgd.wgdaiagent.app.LoveApp` 是**纯 ChatClient 单轮问答**：`doChat`、`doChatWithReport`、`doChatWithRag`、`doChatWithRagCloud`。类中**没有任何 `@Tool`，没有工具调用循环**。
- 知识库链路可用：Markdown ETL（`LoveAppRagEtlConfig`，500 token 切片）→ `PgVectorStore`（`LoveAppPgVectorStoreConfig`，1536 维、HNSW、余弦，表 `public.love_app_vector_store`）。
- 已知缺陷（本规格要求修，见 §10 S3）：
  1. `doChatWithRag` 同时挂 `QuestionAnswerAdvisor`(0.8/topK6) 与 `RetrievalAugmentationAdvisor`(0.5) → **双检索、双上下文注入**。
  2. `LoveApp` 用 `@Resource private VectorStore pgVectorVectorStore;`，但 Bean 名是 `loveAppVectorStore`，仅靠类型兜底才没炸 → **隐式耦合**。
  3. `FileBasedChatMemory`：`static Kryo` 非线程安全；读-改-写无锁；`conversationId` 直接拼文件名 → **路径穿越风险**。
- 接口层只有 `helloController`（`GET /api/hello` 返回 `success`）；Knife4j 已开但无业务接口。
- 依赖侧：`spring-ai-alibaba-agent-framework` 已引入（**1.1.2.0**，由 `spring-ai-alibaba-bom` 管理），jar 内含 `com.alibaba.cloud.ai.graph.agent.ReactAgent`。**关键事实：`Builder` 没有任何 `advisors(...)` 方法，`AgentLlmNode` 只 `.advisors(空列表)`**——即 agent 路径没有「把 `ChatMemory.CONVERSATION_ID` 传进 advisor」的通路，`MessageChatMemoryAdvisor` 那套在 agent 上不可用。会话记忆必须走 checkpoint saver（见 D10）。
- 测试侧：`LoveAppRateLimitTest`（真离线 Mock，5 例）、`LoveAppPgVectorStoreConfigTest`、`LoveAppRagEtlConfigTest`、`ReReadingAdvisorTest` 为离线；`WgdAiAgentApplicationTests`、`LoveAppTest`、`SalesAnalysisAppTest`、`LoveAppDocumentLoaderTest`、`LoveAppPgVectorIntegrationTest` 真连 LLM + 云 RDS，慢且烧钱，且**当前未打任何 tag**，`mvn test` 会无差别全跑。

### 1.2 本轮目标

构建**最小闭环**，四个能力缺一不可：

1. **工具循环**：模型能自主决定「查知识库 / 查现在几点 / 查地点天气路线」，一轮内多步调用，直到给出最终答复。
2. **外部工具接入**：以 MCP Client 身份接入高德地图 MCP（官方托管 SSE 端点为主，npm stdio 为备），「搜索」能力由高德 POI/周边搜索承担。
3. **可服务化**：同步接口 + 真流式 SSE 接口，带 `chatId` 会话管理（跨进程重启不丢）。
4. **不炸**：外部 MCP 不可用、单个工具异常、知识库不可用时，接口仍返回可用答复（不返回 5xx），只打 WARN。

### 1.3 验收标准（每条都可执行）

| 编号 | 验收标准 | 判定方式 |
| --- | --- | --- |
| AC1 | `POST /api/ai/love/agent/chat` 返回 200，JSON 含 `chatId`、`answer` | `curl` 冒烟；离线 `LoveAgentControllerTest` |
| AC2 | `POST /api/ai/love/agent/chat/stream` 返回 `text/event-stream`，先多个 `message` 后 `done` | `curl -N` 冒烟；离线 `AgentOutputMapperTest` + `LoveAgentChatServiceTest` |
| AC3 | 问「北京周末适合约会的地方」时日志出现 MCP 工具调用行（`tool=<启动日志里出现过的地图工具名>`） | 冒烟 + `LoggingToolCallback` INFO 日志 |
| AC4 | 同一 `chatId` 第二轮能引用第一轮内容；进程重启后仍能引用 | 离线 `LoveAgentSessionTest`（新建 saver 实例） |
| AC5 | 未设置 `AMAP_MCP_KEY` 时应用能启动，接口 200，仅一条 WARN，无 5xx | 离线 `AmapMcpToolProviderTest` + 无 key 冒烟 |
| AC6 | 单个工具抛异常时循环不中断，模型收到可读中文错误文本 | 离线 `LoveToolExceptionProcessorTest`、`LoveAgentLoopTest` |
| AC7 | `mvn -o test` 全绿且不触碰网络/LLM/RDS | CI 命令；surefire 报告无 `live` 用例 |
| AC8 | 仓库与日志中不存在高德 key 明文 | `git grep` 检查（§8.4）；日志脱敏评审 |
| AC9 | 每个波次交付前 `mvn -o -DskipTests compile` 退出码 0 | 门槛命令（本日基线实测为 0） |

---

## 2. 已确认决策

| 编号 | 决策 | 落地点 |
| --- | --- | --- |
| D1 | 定位：把恋爱大师从「知识问答」升级为「会调工具的智能体」，本轮只做**最小闭环**，不堆功能 | §1.2、§11 |
| D2 | MCP 角色：**只做 MCP Client**，不实现 MCP Server | §4、§11 |
| D3 | 工具来源：**高德地图 MCP**。官方托管 SSE `https://mcp.amap.com/sse?key=${AMAP_MCP_KEY}` 为主，npm 包 `@amap/amap-maps-mcp-server` 走 stdio 为备；「搜索」由高德 POI/周边搜索承担，不接网页搜索 | §5.2、§8.2 |
| D4 | 运行时：**ReactAgent**（spring-ai-alibaba-agent-framework），不用 ChatClient 裸循环 | §4.2、§6.2 |
| D5 | 本地工具（`@Tool`）：`currentTime`（当前时间/时区，免网络）、`loveKbSearch`（把现有 pgvector 知识库包装成工具，由 agent 自主决定何时检索，**取代「每轮强制检索」**） | §5.1、§6.5 |
| D6 | 接口层：`POST /api/ai/love/agent/chat`（同步）、`POST /api/ai/love/agent/chat/stream`（SSE），带 `chatId` 会话管理 | §6.2、§6.3、§7.3 |
| D7 | 降级：MCP 连不上 → 仅用本地工具继续服务并打 WARN，**不返回 5xx**；工具异常 → 转成模型可读错误文本，**不打断循环** | §7 |
| D8 | 密钥：`AMAP_MCP_KEY` 走环境变量，**绝不入库**；没有 Key 时 CI 仍能跑（用 stub） | §8、§9 |
| D9 | **不用** `spring-ai-starter-mcp-client` 的自动配置；MCP 客户端在 `tool/` 内自建，工具回调通过 **try-catch + 条件化 Bean** 注入 | MCP starter 的 `McpClientAutoConfiguration`（默认 `initialized=true`）会在 Bean 创建时 `client.initialize()`，连不上直接启动失败；即使设 `initialized=false`，只要 `ReactAgent.builder().toolCallbackProviders(...)`，`gatherLocalTools()` 在 `build()` 时就会 `getToolCallbacks()`→`listTools()` 再次联网。只有自管生命周期能满足 D7 |
| D10 | 会话记忆走 **`saver(FileSystemSaver)` + `RunnableConfig.threadId(chatId)`**；`FileBasedChatMemory` 不再承担 agent 记忆，只保留给 `LoveApp` 旧 ChatClient 路径（并修其线程安全/路径穿越缺陷） | 1.1.2.0 无 advisor 通路（§1.1），saver 是唯一官方持久化路径；`FileSystemSaver` 落在 `chat-memory/agent/`，保住「重启不丢」 |
| D11 | MCP 工具**全量暴露**（12 个），不做 `McpToolFilter` | 少一个可配置面；token 成本见 §5.2 |
| D12 | 工具执行**串行**（不显式开启 `parallelToolExecution`），图递归上限 8 | 日志可读、结果确定、防模型死循环 |
| D13 | `document/**` 本轮**不改内容**（仅允许修错别字），避免为一句话重算 Embedding；检索质量靠 prompt 策略兜住 | YAGNI；ETL 重跑成本与云端写入风险大于收益（§12 R8） |
| D14 | 系统提示词用 **`Builder.systemPrompt(...)`**，不用 `instruction(...)` | `instruction` 会成为首条 `UserMessage` 并被当 `PromptTemplate` 渲染，正文里的 `{` `}` 会被当占位符报错 |
| D15 | SSE 用**真流式**：`agent.stream(...)` 的 `StreamingOutput.chunk()`，只取 `OutputType.AGENT_MODEL_STREAMING` | 不做伪流式。**修正（源码核实）**：`Agent.doInvoke` 无条件用 `buildNonStreamConfig(config)`，其中硬写 `addMetadata("_stream_", false)` → **`agent.call()` 永远非流式**，调用方传 `_stream_` 也无效；只有 `agent.stream(...)` 走 `buildStreamConfig(...)` 才是真流。因此同步与流式是两条物理路径，异常行为也不同（见 §3.4 的 `AgentLlmNode` 吞异常事实与 R17） |
| D16 | **工具名以运行时 `listTools()` 为准**，不写进配置或提示词 | 默认前缀生成器会把非字母数字换成 `_`、重名追加序号、超 64 字符截断；工具集在 `build()` 时固化，增减需重建 agent |
| D17 | 上下文窗口由 `HistoryWindowHook`（`MessagesModelHook`）在**每次模型调用前**裁剪到最近 20 条，且不切断 `Assistant(toolCalls)`/`ToolResponse` 配对 | saver 会持久化整段会话，不裁剪会无限膨胀 |

---

## 3. 环境约束与事实

### 3.1 构建与运行时

| 事实 | 观测 | 影响 |
| --- | --- | --- |
| Maven 3.9.6，runtime Java 21.0.12 | `mvn -v` | 支持 `.mvn/maven.config` |
| 基线离线编译通过 | `mvn -o -q -DskipTests compile` 退出码 0 | 门槛命令可直接使用 |
| 全局无 Maven mirror（`~/.m2/settings.xml` 不存在，`conf/settings.xml` 未配 mirror） | 检查两处 settings | 新依赖必须自建镜像配置（§8.3） |
| `.mvn/` 下只有 `wrapper/` | 目录列举 | `settings.xml`/`maven.config` 是新增文件 |
| 根目录无 `README.md` | 目录列举 | S1 新建 |
| 无 `spring-boot-starter-validation` | `pom.xml` | S1 需补，否则 `@Valid` 不生效 |

### 3.2 网络与仓库可达性（实测）

| 目标 | 结果 | 结论 |
| --- | --- | --- |
| Maven Central | 连接失败（curl 000） | 不能作为依赖来源 |
| `https://maven.aliyun.com/repository/public` | 可达；`spring-ai-mcp:1.1.2`、`spring-ai-autoconfigure-mcp-client-common/-httpclient:1.1.2`、`spring-ai-starter-mcp-client:1.1.2` 均 200 | 必须走镜像拉依赖 |
| `api.open-meteo.com` | 200 | 备选天气源（本轮不接） |
| `restapi.amap.com` | 200 | 高德 Web 服务可达 |
| `mcp.amap.com` | 200 | MCP 端点可达 |
| `registry.npmjs.org` | 200；`@amap/amap-maps-mcp-server` 最新 `0.0.8` | stdio 备选可用；Node/npx 在位 |
| `duckduckgo.com`、`www.google.com` | 不可达 | 免 key 网页搜索 MCP 不可用，这是选高德的原因 |
| `https://mcp.amap.com/sse?key=invalid-test-key` | HTTP 200 + `Content-Type: application/json` + `{"status":"0","info":"INVALID_USER_KEY","infocode":"10001"}`，响应头 `Connection: close` | **失败不是连接错误，而是「200 + 非 SSE 响应」**。降级必须捕获 `initialize()`/`listTools()` 异常与超时，不能只看 TCP 连通 |

### 3.3 依赖现状与版本冲突（关键）

- 本地仓库已有：`org.springframework.ai:spring-ai-model:1.1.2`、`spring-ai-alibaba-agent-framework:1.1.2.0/1.1.2.1`、`spring-ai-alibaba-graph-core:1.1.2.0`、`io.modelcontextprotocol.sdk:mcp-core:0.14.0`（有 jar）、`mcp-json-jackson2:0.17.0`（有 jar）。编写本规格时 `spring-ai-mcp` 与 `mcp:0.17.0` 的 jar 不在本地；S1 施工后已完成拉取（`dependency:tree` 确认 0.17.0 全链到位），**首次构建仍需联网一次**。
- **本机 localRepository 不在 `~/.m2/repository`**：由 Maven 安装目录 `conf/settings.xml` 的 `localRepository` 指定（实测为 Maven home 下配置的路径）。排查「离线构建缺件」时先确认 `<localRepository>` 实际指向，别默认去 `~/.m2` 找（S1 施工记录）。
- 版本仲裁方向：`spring-ai-mcp:1.1.2` 的 POM 声明 `io.modelcontextprotocol.sdk:mcp:0.17.0`（本项目里是 depth 3）；`spring-ai-alibaba-agent-framework:1.1.2.0` 的 POM 声明 `mcp:0.14.0`（depth 2）→ **nearest-wins 会选 0.14.0**，而 `spring-ai-bom` 不管理该 SDK。**必须在 `dependencyManagement` 钉 `io.modelcontextprotocol.sdk:mcp:0.17.0`**，否则 `SyncMcpToolCallback` 运行期 `NoSuchMethodError`。
- `spring-ai-mcp` 只需一个直接依赖：`mcp:0.17.0` 是聚合包（空 jar），会带入 `mcp-core` 与 `mcp-json-jackson2`。
- `spring-ai-starter-mcp-client:1.1.2` 的 POM 声明 `spring-boot-starter:3.5.8`；本项目父 POM 是 3.4.4，混用需依赖 `dependencyManagement` 降级。**本轮不引入 starter（D9），从根上回避该错配**。
- 传输类在 `mcp-core`：`HttpClientSseClientTransport`、`StdioClientTransport`；`io.modelcontextprotocol.util.Utils.resolveUri` 用 `URI.resolve` 拼接 → **base 只到 host，端点写 `/sse?key=...`**（query 放 base 会被丢弃）。
- Spring AI 1.1.2 的 `McpSseClientProperties.SseParameters` 只有 `url`、`sseEndpoint` 两个字段，**1.1.2 没有 per-connection headers 配置**，所以高德鉴权只能走 query 字符串。

### 3.4 Agent 运行时事实（反编译核实）

- `ReactAgent extends BaseAgent`；`ReactAgent.builder()` 返回 `Builder` 的默认实现，`build()` 产出 `ReactAgent`。
- `Builder` 无 `advisors(...)`；`AgentLlmNode` 内部 `.advisors(空列表)` → **没有 ChatMemory advisor 通路**（D10 的根因）。
- `Builder` 关键方法：`model`、`chatClient`、`systemPrompt(String)`、`instruction(String)`、`tools(List<ToolCallback>)`、`methodTools`、`toolCallbackProviders`、`toolNames`、`hooks(...)`、`saver(BaseCheckpointSaver)`、`compileConfig`、`toolExecutionExceptionProcessor`、`enableLogging`、`parallelToolExecution`、`toolExecutionTimeout`。
- 同步：`AssistantMessage call(String | UserMessage | List<Message> | Map, RunnableConfig)`，声明 `throws GraphRunnerException`。
- 流式：8 个 `stream(...)` 重载返回 `Flux<NodeOutput>`，另有 `streamMessages(...)` 返回 `Flux<Message>`；`StreamingOutput.chunk()` 是 token 级切片，`getOutputType()` 区分 `AGENT_MODEL_STREAMING`/`AGENT_MODEL_FINISHED`/`AGENT_TOOL_STREAMING`/`AGENT_TOOL_FINISHED`/`AGENT_HOOK_*`/`GRAPH_NODE_*`。
- 检查点：`graph-core:1.1.2.0` 提供 `MemorySaver`、`FileSystemSaver`、`PostgresSaver`、`RedisSaver`、`MysqlSaver`、`MongoSaver`、`OracleSaver`。`FileSystemSaver.builder().targetFolder(Path).stateSerializer(StateSerializer).build()`，文件名 `thread-<threadId>.saver`，扩展名常量 `EXTENSION`，另有 `deleteFile(RunnableConfig)`。**序列化器用 `com.alibaba.cloud.ai.graph.serializer.plain_text.jackson.SpringAIJacksonStateSerializer`**（ReactAgent 自身的默认）；不要混用 std 包下的 `SpringAIStateSerializer`，同一批 checkpoint 混用两套序列化器会错配（S3 实测修正）。
- 图递归上限：`CompileConfig.builder().recursionLimit(n).build()`。
- **⚠ saver 必须放进 `CompileConfig`（源码核实，2026-10-24）**：`Builder.buildConfig()` 的实现是 `if (compileConfig != null) return compileConfig;` —— 也就是说一旦调用了 `.compileConfig(...)`，`.saver(...)` 会被**静默忽略**。正确写法：`CompileConfig.builder().recursionLimit(n).saverConfig(SaverConfig.builder().register(saver).build()).build()`，**不要**同时调 `.saver(...)` 并指望它生效。（S3 实测 + sources jar 双向确认）
- **⚠ 递归上限不抛异常（源码核实）**：graph-core 的 `MainGraphExecutor` 命中 `isMaxIterationsReached()` 时只是 `handleCompletion()` 优雅结束，不会抛。所以「循环耗尽」必须靠语义判定：`call()` 返回的最后一条 `AssistantMessage` 仍 `hasToolCalls()` → 视为 `AGENT_LOOP_LIMIT`。
- **⚠ 模型异常会被 `AgentLlmNode` 吞成文本（源码核实，第 234/268 行）**：两个分支都是 `catch (Exception e) → new AssistantMessage("Exception: " + e.getMessage())`。后果：**同步路径上的 429/401 不一定以异常形式冒出来，可能变成一段文本 + HTTP 200**，直接冲击 §7.3。`LoveAgent.chat()` 必须对此兜底（优先让异常真冒泡：`chat()` 也走流式，并在 `translate(...)` 里沿 cause 链下钻识别 `TransientAiException`/`NonTransientAiException`；若无法恢复异常对象，则用 `"Exception: "` 哨兵 + 状态码分类，且**绝不把该文本当答复返回**）。校验用例：假模型抛 `TransientAiException` → `chat()` 必须抛 `TransientAiException`（或 `LoveAgentException(AGENT_UNAVAILABLE)`），不得返回文本。
- 消息钩子：`MessagesModelHook` 的 `beforeModel(List<Message>, RunnableConfig)` 默认返回 `new AgentCommand(messages)`；可返回 `new AgentCommand(list, UpdatePolicy.REPLACE)` 替换消息；`UpdatePolicy` 取值 `REPLACE`/`APPEND`。官方已有 `SummarizationHook`（`maxTokensBeforeSummary`/`messagesToKeep`/`keepFirstUserMessage`，会额外调模型做摘要）。
- 工具异常：`ToolExecutionException#getToolDefinition()`（可取工具名）与 `#getCause()`；`DefaultToolExecutionExceptionProcessor(false)` 会把异常转文本回喂模型。
- 工具名规范化：默认 `McpToolNamePrefixGenerator` 对工具名做 `McpToolUtils.format`（非字母数字换 `_`）、重名追加序号、超长截断；`tools(List)` 在 `build()` 时固化。
- 流式不需要 WebFlux：`reactor-core` 已随 `spring-ai-model` 在 classpath（Boot 3.4.4 管理版本），Spring MVC 用 `SseEmitter` 即可。

### 3.5 代码库现状（实现时必须绕开的坑）

- `LoveApp` 构造器为 `LoveApp(ChatModel)`，`LoveAppRateLimitTest` 直接 `new LoveApp(model)` 并调用 `doChatWithReport`。**该构造器与该方法的行为不得改变**，否则离线测试失效。
- `FileBasedChatMemory` 的 `get(conversationId, lastN)` 是扩展方法，`ChatMemory` 接口无 `lastN` 参数；它只服务旧 ChatClient 路径，本轮不负责 agent 记忆。
- Knife4j 扫描包固定为 `com.wgd.wgdaiagent.controller`，新 controller 必须落在该包。
- `application-local.yml` 被 gitignore 排除（内含 DashScope key 与 RDS 口令，属既有技术债，见 §12 R9）。
- `.gitignore` 已排除 `chat-memory/`，agent 检查点目录天然不入库。

---

## 4. 架构与组件职责

### 4.1 组件图

```
        POST /api/ai/love/agent/chat            POST /api/ai/love/agent/chat/stream
                       │                                     │
                       ▼                                     ▼
            ┌────────────────────────────────────────────────────────┐
            │ controller/LoveAgentController（S4）                    │
            └───────────────────────┬────────────────────────────────┘
                                    ▼
            ┌────────────────────────────────────────────────────────┐
            │ service/LoveAgentChatService（S4）                      │
            │ chatId 规范化 → 调 agent → 组装响应 / SSE 帧            │
            └───────────────────────┬────────────────────────────────┘
                                    │ chat(chatId, message) / stream(chatId, message)
                                    ▼
            ┌────────────────────────────────────────────────────────┐
            │ agent/LoveAgent（S3，单例）                              │
            │ systemPrompt + tools + hooks + saver + CompileConfig    │
            │ chatId → RunnableConfig.threadId                        │
            └───────┬───────────────────────┬────────────────────────┘
                    │ 工具集                 │ 检查点
                    ▼                       ▼
     ┌──────────────────────────┐   ┌──────────────────────────────────┐
     │ tool/（S3）               │   │ FileSystemSaver                  │
     │ currentTime / loveKbSearch│   │ chat-memory/agent/thread-*.saver │
     │ AmapMcpToolProvider→MCP   │   └──────────────────────────────────┘
     └───────┬──────────┬────────┘
             │          │
             ▼          ▼
   PgVectorStore    mcp.amap.com（或 cmd /c npx …）
```

依赖方向严格单向：`controller → service → agent → tool`；`agent → chatmemory(saver)` 由框架内部完成。`tool` 不依赖 `service`/`controller`；`agent` 不依赖 Spring MVC。

### 4.2 组件职责

| 组件 | 类型 | 职责 | 不做 | 波次 |
| --- | --- | --- | --- | --- |
| `agent/LoveAgent` | `@Component` | 启动期装配唯一 `ReactAgent`（model + `systemPrompt` + tools + hooks + saver + recursionLimit + 工具异常处理器）；对外只暴露 `chat(chatId,msg)`、`stream(chatId,msg)`、`isMcpAvailable()`、`toolNames()`；内部把 `chatId` 映射为 `threadId` | 不碰 HTTP、不做参数校验、不读写 `FileBasedChatMemory` | S3 || `agent/AgentProperties` | `@ConfigurationProperties("love-app.agent")` | 绑定窗口、长度上限、MCP 端点/超时/凭据、记忆目录/类型、stdio 参数 | 不发请求 | S3 |
| `agent/PromptLoader` | `@Component` | 从 `classpath:prompts/love-agent-system.txt` 读 UTF-8 文本；缺失/空白 → 启动失败 | 不做模板渲染 | S3 |
| `agent/HistoryWindowHook` | `MessagesModelHook` 子类 | `beforeModel` 裁剪到最近 `history-window` 条，保持消息配对，返回 `AgentCommand(list, REPLACE)` | 不做摘要、不调模型 | S3 |
| `agent/AgentOutputMapper` | 普通类（纯函数） | `NodeOutput` → 增量文本/兜底全文：只认 `AGENT_MODEL_STREAMING` 的 `chunk()`；`AGENT_MODEL_FINISHED` 的 `message().getText()` 作兜底 | 不持有状态 | S3 |
| `tool/LoveLocalTools` | `@Component` | `currentTime`、`loveKbSearch` 两个 `@Tool` 方法 | 不持有会话状态 | S3 |
| `tool/LoggingToolCallback` | 普通类 | 装饰任意 `ToolCallback`：INFO 打印 `tool=<name>`、耗时、结果长度；结果与异常原样透传 | 不打入参/正文（隐私） | S3 |
| `tool/LoveToolExceptionProcessor` | `ToolExecutionExceptionProcessor` | 工具异常 → 中文可读文本回喂模型，WARN 一次，隐藏堆栈与 SQL | 不吞 LLM 异常 | S3 |
| `tool/McpTransportFactory` | 普通类 | 按协议造 `McpClientTransport`：sse（host + `/sse?key=`，URL 编码）/ stdio（Windows `cmd /c npx ...`） | 不负责连接/重试 | S3 |
| `tool/AmapMcpToolProvider` | `@Component` | 自管 `McpSyncClient`：连 → `initialize()` → `listTools()` → `SyncMcpToolCallback`；任何失败只 WARN 并返回空工具集；INFO 打印实际工具名清单 | 不做热重连、不缓存 POI 结果 | S3 |
| `tool/McpToolset` | `record` | `(List<ToolCallback> callbacks, boolean available, String status)` | — | S3 |
| `chatmemory/FileBasedChatMemory` | 类（修） | 仅为 `LoveApp` 旧 ChatClient 路径服务：Kryo 实例非共享、每会话读改写加锁、原子写、会话 ID 白名单 | 不再承担 agent 记忆 | S3 |
| `app/LoveApp` | 类（修） | 修 `@Resource` 注入匹配 Bean 名；`doChatWithRag` 收敛为单检索；新增 `doChatWithAgent` 委托 `LoveAgent` 便于本地调试 | 不改构造器与 `doChatWithReport` 行为 | S3 |
| `rag/*` | 类（修） | `doChatWithRag` 只保留一个检索 advisor | 不改 ETL/向量库配置 | S3 |
| `service/LoveAgentChatService` | `@Service` | `chatId` 规范化与生成、调 agent、组响应、SSE 分片与终止事件、异常翻译 | 不拼 prompt、不直接调工具、不碰 graph 类型 | S4 |
| `controller/LoveAgentController` | `@RestController` | 两端点、Bean Validation、`SseEmitter` 生命周期、OpenAPI 注解 | 不含业务逻辑 | S4 |
| `model/*` | `record` | 请求/响应/SSE 事件/错误体 | — | S4 |
| `exception/LoveAgentException` + `exception/AgentErrorCode` | 类 | `LoveAgent` 的业务异常与错误码（`INVALID_REQUEST`/`AGENT_UNAVAILABLE`/`AGENT_ERROR`/`AGENT_LOOP_LIMIT`/`INTERNAL_ERROR`），unchecked | 不含 HTTP 语义 | S3（编排调整：`LoveAgent` 需要它，避免与 S4 循环依赖） |
| `exception/GlobalExceptionHandler` | `@RestControllerAdvice` | 异常 → HTTP 状态与错误体（§7.3） | 不改其它路径错误结构 | S4 |
| 提示词资产 | 资源 | `prompts/love-agent-system.txt` 是唯一 system prompt 源 | 不在 Java 里硬编码 prompt | S2 |

### 4.3 与外部世界的四个契约

1. **HTTP 契约**：§6.2/§6.3 的 JSON 与 SSE 事件；状态码见 §7.3。
2. **模型契约**：system prompt 让模型知道「有哪些能力、什么场景用哪类工具、失败怎么说」（§5.4）。
3. **MCP 契约**：高德 MCP 的 12 个工具与入参（§5.2），**工具名运行时以 `listTools()` 为准**（D16）。
4. **持久化契约**：`chatId` ↔ `threadId` ↔ `chat-memory/agent/thread-<chatId>.saver`；`chatId` 白名单是三重保护（HTTP 校验、`LoveAgent` 断言、`FileBasedChatMemory` 校验）的共同前提。

### 4.4 会话与记忆策略（D10/D17）

- `chatId` 由客户端提供或服务端生成，格式 `^[A-Za-z0-9_-]{1,64}$`。`LoveAgent` 把它直接作为 `RunnableConfig.threadId`。
- 每轮请求**只传本轮** `UserMessage`：`agent.call(new UserMessage(msg), RunnableConfig.builder().threadId(chatId).build())`。历史由 `FileSystemSaver` 按 threadId 自动恢复，**不能**再额外传完整历史（否则会重复追加）。
- 持久化：`FileSystemSaver.builder().targetFolder(Path.of(user.dir, "chat-memory", "agent")).stateSerializer(new SpringAIStateSerializer()).build()`。目录已被 `.gitignore` 排除，重启不丢。
- 降级：`FileSystemSaver` 构建/写入抛异常 → WARN 并退回 `new MemorySaver()`（同一进程内仍可用），不阻断启动与请求。
- 窗口：`HistoryWindowHook` 在每次模型调用前裁剪。裁剪规则（可单测）：
  1. 取最近 `window` 条；
  2. 若首条不是 `UserMessage`，向前扩展到最近的 `UserMessage`（保持「一问」起步）；
  3. 若某条 `ToolResponseMessage` 的前一条不是携带 toolCalls 的 `AssistantMessage`，继续向前保留该 `AssistantMessage`，避免产生孤儿工具结果。
- 并发：同一 `chatId` 的请求在 service 层用 `ReentrantLock`（`ConcurrentHashMap<String, ReentrantLock>`）串行化，避免同一 threadId 的检查点竞争。
- 失败语义（重要）：检查点是**按节点**写入的，失败轮次可能留下已完成的中间状态（最坏情况是「assistant toolCall + toolResponse」这样自洽的一对）。因此约定：**失败轮次不主动回滚**，同一 `chatId` 下次调用会从该状态继续；接口层只负责把失败如实报给客户端。这与「只在成功时落盘」的旧记忆模型不同，是有意的取舍（§12 R7）。
- `FileBasedChatMemory` 与 `chat-memory/*.kryo` 只服务 `LoveApp` 的旧 ChatClient 方法，与 agent 记忆**物理隔离**（不同子目录），互不读取。

### 4.5 为什么不用 MCP starter 自动配置（D9）

`spring-ai-starter-mcp-client:1.1.2` 的行为链是：`McpClientAutoConfiguration` 建 `McpSyncClient`；默认 `spring.ai.mcp.client.initialized=true` → Bean 创建时即 `initialize()`；`McpToolCallbackAutoConfiguration` 再建 `SyncMcpToolCallbackProvider`（构造期 `listTools()`）。任一步失败都会让 Bean 创建失败 → 上下文启动失败，与 D7 直接冲突。

即使把 `initialized` 设为 `false`（懒连接），只要在 `@Bean` 里写 `ReactAgent.builder().toolCallbackProviders(...).build()`，`gatherLocalTools()` 在 `build()` 时就会调用 `getToolCallbacks()` → `listTools()`，仍然在启动期联网失败。

因此本方案：`AmapMcpToolProvider` 自己 `new` 客户端、自己 `initialize()`、自己 `listTools()`，全部包在 try/catch 里，再把 `List<ToolCallback>` 交给 `LoveAgent`。代价是要自己钉 MCP SDK 版本（§8.2），收益是「连不上也能启动」这件事由代码保证，而不是靠配置碰运气。

---

## 5. 工具契约清单

工具名即模型可见名（`@Tool(name=...)` 或 MCP 原始名经规范化后的名字）。所有工具返回 `String`（工具结果最终进入对话上下文）。

### 5.1 本地工具（`tool/LoveLocalTools`）

#### T-LOCAL-1 `currentTime`

| 项 | 内容 |
| --- | --- |
| 用途 | 回答「今天几号/现在几点/星期几/某个时区现在几点」，免网络 |
| 入参 | `timeZone`（`String`，可选）：IANA 时区，如 `Asia/Shanghai`；空/空白 → `Asia/Shanghai` |
| 返回 | `2026-10-24T18:11:46+08:00 Saturday (Asia/Shanghai)`（ISO-8601 偏移 + 英文星期，避免模型自行换算） |
| 异常策略 | 非法时区**不抛异常**：返回 `时区 "xxx" 无效，已使用 Asia/Shanghai：<结果>`，保证循环不断 |
| 幂等 | 同一秒内幂等 |
| 测试点 | 默认时区、指定时区、非法时区三分支；不 mock 时间，断言格式与星期映射 |

#### T-LOCAL-2 `loveKbSearch`

| 项 | 内容 |
| --- | --- |
| 用途 | 检索恋爱知识库（单身/恋爱/已婚三类常见问题与建议），由模型自主决定何时调用 |
| 入参 | `query`（`String`，必填）：自然语言问题，如「异地恋怎么维持」 |
| 检索参数 | `SearchRequest.builder().query(query).similarityThreshold(0.5).topK(5).build()` |
| 返回 | 按序编号：`1. [filename=<来源> score=0.83] <正文，最多 500 字符>`；最多 5 条（约 2.5k 字符） |
| 空结果 | `知识库中未找到与该问题相关的内容。` |
| 知识库不可用 | `VectorStore` 经 `ObjectProvider<VectorStore>.getIfAvailable()` 获取；为空 → `恋爱知识库当前不可用，请基于通用经验回答并说明这一限制。`，不抛异常 |
| 异常策略 | 捕获 `RuntimeException`，WARN 记异常类型（不记 SQL/URL），返回 `恋爱知识库检索失败（<异常简单类名>），请基于通用经验回答。` |
| 幂等 | 是（只读） |

设计要点：`loveKbSearch` **取代**「每轮强制检索」——agent 请求不再挂 `QuestionAnswerAdvisor`，检索只发生在模型主动调用时（省一次 embedding、省上下文）。

### 5.2 MCP 工具（高德）

来源：`@amap/amap-maps-mcp-server@0.0.8` 的 `build/index.js`（实读）。托管端点预期暴露同名工具，一致性见 §12.2 V3。入参均为 `string`（高德 MCP 的 schema 未用数字类型）。

| # | 工具名（npm 包内名） | 用途 | 必填入参 | 可选入参 |
| --- | --- | --- | --- | --- |
| 1 | `maps_text_search` | 关键词搜 POI | `keywords` | `city`、`types` |
| 2 | `maps_around_search` | 周边搜 POI | `location`（`经度,纬度`） | `keywords`、`radius` |
| 3 | `maps_search_detail` | 按 POI ID 查详情 | `id` | — |
| 4 | `maps_geo` | 结构化地址 → 经纬度 | `address` | `city` |
| 5 | `maps_regeocode` | 经纬度 → 行政区划地址 | `location` | — |
| 6 | `maps_ip_location` | IP 定位 | `ip` | — |
| 7 | `maps_weather` | 城市天气 | `city`（名称或 adcode） | — |
| 8 | `maps_distance` | 距离测量 | `origins`、`destination` | `type`（`1` 驾车/`0` 直线/`3` 步行） |
| 9 | `maps_direction_driving` | 驾车路线 | `origin`、`destination` | — |
| 10 | `maps_direction_walking` | 步行路线（≤100km） | `origin`、`destination` | — |
| 11 | `maps_direction_transit_integrated` | 公交/地铁综合路线 | `origin`、`destination`、`city`、`cityd` | — |
| 12 | `maps_bicycling` | 骑行路线（≤500km） | `origin`、`destination` | — |

坐标格式统一「经度,纬度」（如 `116.481488,39.990464`），prompt 必须写明，避免模型传成「纬度,经度」。

**命名与固化（D16）**：以上是 npm 包内的原始名。经 Spring AI 的 `McpToolNamePrefixGenerator` 规范化后，模型看到的名字可能不同（非字母数字→`_`、重名追加序号、超 64 字符截断）。因此：

- `AmapMcpToolProvider` 启动时 INFO 打印 `高德 MCP 工具: [<name1>, <name2>, ...]`，**这就是唯一事实来源**；
- prompt、配置、测试断言一律**不写死工具名**；
- `ReactAgent.build()` 时工具列表固化，MCP 工具增减必须重启（热更新属非目标）。

token 成本：12 个工具定义约 1.2k–1.8k tokens/轮；若实测单轮输入超 8k，按 §12.3 用 `McpToolFilter` 收敛（本轮不做）。

### 5.3 工具暴露与装配

```java
// S3 在 LoveAgent 构造期（语义契约，非最终代码）
List<ToolCallback> local = Arrays.stream(ToolCallbacks.from(loveLocalTools))
        .map(LoggingToolCallback::new).toList();
List<ToolCallback> mcp = mcpToolProvider.toolset().callbacks().stream()
        .map(LoggingToolCallback::new).toList();
List<ToolCallback> all = new ArrayList<>(local); all.addAll(mcp);

ReactAgent.builder()
        .name("love_agent")
        .model(dashscopeChatModel)
        .systemPrompt(promptLoader.text())              // D14：不是 instruction
        .tools(all)                                     // 顺序：本地 2 → MCP N
        .hooks(new HistoryWindowHook(window))           // D17
        .saver(checkpointSaver)                         // D10
        .toolExecutionExceptionProcessor(new LoveToolExceptionProcessor())
        .compileConfig(CompileConfig.builder().recursionLimit(8).build())
        .enableLogging(true)
        .build();
```

规则：

1. 工具顺序固定：本地 2 个在前，MCP 按 `listTools()` 返回顺序在后（日志与测试可断言数量与顺序）。
2. 每个 callback 套 `LoggingToolCallback`，日志格式固定 `tool=%s elapsedMs=%d resultChars=%d`。
3. 不用 `toolNames(...)`/`toolCallbackProviders(...)`——直接给 `tools(List)`，避免 `gatherLocalTools()` 在 `build()` 时触发额外联网（§4.5）。
4. `parallelToolExecution` 保持默认关闭（D12）。

### 5.4 工具选择策略（prompt 契约，S2 交付）

`src/main/resources/prompts/love-agent-system.txt`（UTF-8）必须覆盖以下条目，缺一条视为 S2 未交付：

1. **身份**：深耕恋爱心理的顾问，先共情再给方案，不说教。
2. **能力边界**：你可以查询实时地图信息（地点搜索、周边搜索、地址与坐标互转、天气、距离与路线）和本地恋爱知识库。
3. **场景路由**：涉及具体地点/约会场所/出行/天气 → 用地图类工具；涉及情感、沟通、心理、相处模式 → 优先用知识库工具。
4. **坐标格式**：调用地图工具时，坐标一律「经度在前、纬度在后」，格式 `经度,纬度`。
5. **时间**：涉及「现在/今天/星期几/时区」→ 调 `currentTime`，不自行推算日期。
6. **禁止编造**：不得虚构店名、距离、天气、评分；工具没返回的数据就说没有，并注明「信息来自地图服务，可能过期」。
7. **工具失败**：若工具返回错误文本，向用户说明该信息暂时查询不到，改用常识性建议；同一失败工具不重复调用超过一次。
8. **范围**：只聊恋爱/关系/约会相关；无关请求礼貌拒绝。
9. **长度**：中文回答，默认 200 字以内，用户要求详细时再展开。

补充约束：**prompt 里不出现具体工具名**（D16），只描述能力与场景；工具名由运行时工具定义提供。prompt 文本可能包含 `{}`，因为走 `systemPrompt` 不再被当模板渲染（D14），但为可读性仍建议少用。

prompt 加载失败（文件缺失/为空）→ 启动期抛 `IllegalStateException("prompt 资产缺失: prompts/love-agent-system.txt")`，快速失败（配置错误，不属于运行时降级）。

---

## 6. 数据流

### 6.1 启动期

1. `PromptLoader` 读 prompt → 失败即启动失败。
2. `AgentProperties` 绑定配置；`FileSystemSaver` 初始化（目录不存在则创建）；失败 → WARN + `MemorySaver` 降级。
3. `AmapMcpToolProvider`：
   - `mcp.enabled=false` 或 `key` 为空 → WARN `高德 MCP 未启用（原因：...），仅本地工具可用`，不建连接，`available=false`，返回空列表；
   - 否则 `McpTransportFactory` 造 sse 传输（`connectTimeout=3s`），`McpClient.sync(transport).requestTimeout(20s).build()` → `initialize()` → `listTools()`；
   - 成功 → 包装成 `SyncMcpToolCallback`，INFO 打印工具名清单与数量，`available=true`；
   - 任一步异常/超时 → WARN `高德 MCP 不可用（<异常简单类名>），仅本地工具可用`，`close()`，返回空列表。**绝不上抛**。
4. `LoveAgent` 用「本地 2 + MCP N」装配 `ReactAgent`；N=0 时照常构建。
5. 校验 `toolset.available()` 与工具数一致，INFO 打印 `agent 就绪：本地工具=2，MCP 工具=N`。

启动期不做重连、不做后台探活（§11）。

### 6.2 同步请求时序

```
Client → Controller: POST /api/ai/love/agent/chat {chatId?, message}
Controller: Bean Validation（message 非空、≤2000；chatId 格式）
Controller → Service.chat(req)
Service: chatId 规范化（空 → UUID v4 去横线）
Service → LoveAgent.chat(chatId, message)                      # 阻塞，最长受 LLM 重试策略约束
LoveAgent: config = RunnableConfig.builder().threadId(chatId).build()
LoveAgent → ReactAgent.call(new UserMessage(message), config)
  loop: LLM ⇄ tools（本地/高德），最多 recursionLimit=8 步
  （saver 在每个节点后写入检查点）
ReactAgent → AssistantMessage(finalText)
LoveAgent → Service: finalText
Service → Controller: LoveAgentChatResponse(chatId, answer, elapsedMs)
Controller → Client: 200 application/json
```

要点：**只传本轮消息**，历史由 saver 恢复（§4.4）。service 不接触 `Message`/`RunnableConfig`/`NodeOutput` 等 graph 类型。

### 6.3 SSE 请求时序（D15）

```
Client → Controller: POST .../chat/stream {chatId?, message}
Controller: 同步校验（失败直接 400 JSON，不建流）
Controller: SseEmitter emitter = new SseEmitter(60_000L)；异步线程执行
Service → LoveAgent.stream(chatId, message) → Flux<String>（增量文本，已由 LoveAgent 内部用 AgentOutputMapper 完成过滤与兜底）
  订阅：
    每个 delta 非空 → fullText += delta；send event:message {"chatId","delta"}
    （AGENT_MODEL_STREAMING 的过滤、AGENT_MODEL_FINISHED 的零 chunk 兜底、AGENT_TOOL_* 的忽略，全部在 LoveAgent 内部完成）
    完成 → send event:done {"chatId","elapsedMs"} → emitter.complete()
    异常 → send event:error {"chatId","code","message"} → emitter.complete()
    客户端断开（IOException）→ 取消订阅（checkpoint 已由 framework 写入）
emitter.onTimeout/onError → complete
```

> 编排调整（主 agent 决定）：`LoveAgent.stream` 返回 **`Flux<String>`** 而非 `Flux<NodeOutput>`，以满足 §10.2「service 不得出现 graph 类型」。S4 只负责把字符串包成 SSE 帧，不做输出类型判定。

SSE 帧：

```
event: message
data: {"chatId":"abc123","delta":"北京"}

event: done
data: {"chatId":"abc123","elapsedMs":4210}
```

约定：`message` 可多次；`done`/`error` 有且仅有一次且为最后一帧；`data` 一律单行 JSON（`SseEmitter.event().name(...).data(json, MediaType.APPLICATION_JSON)`）。`AGENT_TOOL_STREAMING`/`AGENT_MODEL_FINISHED` 的文本**不直接**当 delta（避免重复），只用于兜底。

### 6.4 记忆与检查点

- 目录：`chat-memory/agent/`（`user.dir` 下），文件 `thread-<chatId>.saver`，由 `FileSystemSaver` 管理版本备份（`thread-<chatId>-v<n>.saver`）。
- 恢复：同一 `threadId` 再次 `call/stream` 时，saver 自动加载状态，模型看到完整历史（受 `HistoryWindowHook` 裁剪）。
- 清理：本轮不暴露删除接口（§11）；排障时可手工删 `chat-memory/agent/thread-<chatId>*.saver`。
- 旧路径：`LoveApp` 的 `doChat*` 仍用 `FileBasedChatMemory`（`chat-memory/*.kryo`），与 agent 存储分离，互不影响。

### 6.5 知识库检索

`loveKbSearch` 被调用时：`PgVectorStore.similaritySearch(0.5, topK=5)` → 拼编号文本 → 回喂模型。ETL 链路不变（`document/*.md` → 500 token 切片 → `love_app_vector_store`）。agent 请求不再挂 RAG advisor，即「检索只在模型要的时候发生」。

---

## 7. 错误处理与降级

### 7.1 分级原则

| 级别 | 处理方式 | 是否影响 HTTP 状态 |
| --- | --- | --- |
| 用户输入错误 | 400 + 明确字段提示 | 是 |
| 外部工具不可用（MCP 未连/断连、知识库未就绪、单个工具异常） | 降级为文本，循环继续；WARN | **否**，仍 200 |
| 检查点不可用 | WARN + `MemorySaver` 降级（进程内仍可多轮） | 否 |
| 模型调用暂时失败（429/5xx/网络） | 复用现有 `spring.ai.retry`（4 次，10s→40s）后失败 | 是，503 |
| 模型调用永久失败（400/401/403） | 不重试 | 是，502 |
| 图执行超限/未知异常 | 服务端记录堆栈，响应通用文案 | 是，500 |
| 流式过程中失败 | 已发 200 无法改状态，发 `event:error` 后结束 | 否（流内表达） |

### 7.2 失败场景矩阵

| 场景 | 检测点 | 行为 | 日志 |
| --- | --- | --- | --- |
| 未配置 `AMAP_MCP_KEY` | 启动期 key 为空 | 不建连接，仅本地工具 | 1 条 WARN（含原因，不含 key） |
| MCP 返回 `INVALID_USER_KEY`（HTTP 200 + JSON） | `initialize()` 抛异常/超时 | 同「未配置」 | 1 条 WARN（异常简单类名） |
| MCP 不可达/超时 | connect/request timeout | 同上，启动最多 +3s | 1 条 WARN |
| `listTools()` 返回空 | 工具数 0 | 视作不可用，WARN | 1 条 WARN |
| 运行期 MCP 工具调用失败 | `ToolCallback.call` 抛异常 | `LoveToolExceptionProcessor` 返回 `工具 <name> 调用失败：<原因类别>。请基于已有信息继续回答，并如实告知用户该信息暂时无法获取。` | 每次 WARN |
| 工具返回 `isError=true`（协议级） | 不抛异常 | 结果文本交给模型 | 仅 INFO（LoggingToolCallback） |
| `loveKbSearch` 抛异常 | tool 内部 catch | 返回检索失败文本，循环继续 | WARN（异常类型） |
| `VectorStore` 缺失 | `getIfAvailable()` 为空 | 返回「知识库不可用」文本 | 每次 WARN（同会话 60s 一条） |
| `FileSystemSaver` 初始化/写入失败 | 构造或 `call` 抛异常 | 启动降级 `MemorySaver`；运行期 WARN 并继续 | WARN |
| 模型 429 | `TransientAiException` | 503 `AGENT_UNAVAILABLE` | ERROR（chatId） |
| 模型 400/401/403 | `NonTransientAiException` | 502 `AGENT_ERROR` | ERROR |
| 递归上限 8 耗尽 | graph 异常 | 500 `AGENT_LOOP_LIMIT` | ERROR（chatId） |
| 请求体非法 / `chatId` 非法 | Bean Validation / service 校验 | 400 `INVALID_REQUEST` | INFO |
| SSE 客户端断开 | `send` 抛 `IOException` | 取消订阅（检查点保持） | DEBUG |

### 7.3 HTTP 契约

**同步** `POST /api/ai/love/agent/chat`

```json
{ "chatId": "optional-uuid", "message": "北京周末适合约会的地方？" }
```

`chatId` 省略/空白 → 服务端生成；`message` 必填、trim 后非空、长度 ≤ 2000。

成功 `200`：

```json
{ "chatId": "3f2a...", "answer": "……", "elapsedMs": 4210 }
```

**流式** `POST /api/ai/love/agent/chat/stream`：请求体同上；`TEXT_EVENT_STREAM` + 事件 `message`/`done`/`error`。校验失败**不建流**，直接 400 JSON。

**错误体**（同步与流建立前）：

```json
{ "code": "INVALID_REQUEST", "message": "message 不能为空", "chatId": null }
```

| code | HTTP | 触发 |
| --- | --- | --- |
| `INVALID_REQUEST` | 400 | 参数/校验失败 |
| `AGENT_UNAVAILABLE` | 503 | 模型暂时不可用（重试耗尽） |
| `AGENT_ERROR` | 502 | 模型永久性拒绝/鉴权失败 |
| `AGENT_LOOP_LIMIT` | 500 | 工具循环超限 |
| `INTERNAL_ERROR` | 500 | 兜底 |

**不出现 5xx 的场景**（D7 硬约束，评审必检）：MCP 未配置/连不上/中途断连、工具抛异常、知识库缺失或检索失败、工具参数不合法、检查点降级。这些一律通过工具结果文本 + 模型自然语言表达。

### 7.4 日志与脱敏

- 禁止打印含 `key=` 的完整 URL；MCP 日志只打 host、工具数、耗时、异常简单类名。
- 禁止打印用户消息原文（隐私）：只打 `chatId`、字符数、耗时。
- 工具日志只打工具名、耗时、结果字符数（§5.3）。
- 堆栈只在 `INTERNAL_ERROR`/`AGENT_LOOP_LIMIT` 时以 ERROR 完整记录（服务端），响应体只给通用文案。
- 会话串统一前缀 `[chatId=xxx]`，便于 grep。

---

## 8. 配置与密钥管理

### 8.1 配置项（前缀 `love-app.agent`）

| 属性 | 类型 | 默认 | 环境变量 | 说明 |
| --- | --- | --- | --- | --- |
| `history-window` | int | `20` | — | `HistoryWindowHook` 每次模型调用前保留的消息条数 |
| `max-message-length` | int | `2000` | — | 单条用户消息上限 |
| `sse-timeout` | Duration | `60s` | — | `SseEmitter` 超时 |
| `memory.type` | enum | `file` | `LOVE_AGENT_MEMORY_TYPE` | `file`=FileSystemSaver，`memory`=MemorySaver |
| `memory.dir` | String | `chat-memory/agent` | `LOVE_AGENT_MEMORY_DIR` | 相对 `user.dir` |
| `mcp.enabled` | boolean | `true` | `AMAP_MCP_ENABLED` | `false` 时完全不建 MCP 连接 |
| `mcp.protocol` | enum | `sse` | `AMAP_MCP_PROTOCOL` | `sse` / `stdio` |
| `mcp.key` | String | 空 | `AMAP_MCP_KEY` | **唯一**凭据来源，不得有默认明文 |
| `mcp.base-url` | String | `https://mcp.amap.com` | `AMAP_MCP_BASE_URL` | 只到 host（不含 path/query） |
| `mcp.sse-path` | String | `/sse` | `AMAP_MCP_SSE_PATH` | 代码拼 `?key=`，故默认值不带 query |
| `mcp.connect-timeout` | Duration | `3s` | — | HttpClient 连接超时 |
| `mcp.request-timeout` | Duration | `20s` | — | MCP 请求超时 |
| `mcp.stdio.command` | String | `cmd` | — | Windows 直接执行 `npx`(.cmd) 会失败，故用 `cmd` |
| `mcp.stdio.args` | List | `/c`,`npx`,`-y`,`@amap/amap-maps-mcp-server` | — | stdio 备选命令 |
| `mcp.stdio.env-key-name` | String | `AMAP_MAPS_API_KEY` | — | 传给子进程的环境变量名（npm 包约定） |

### 8.2 配置片段（`application.yml`）

```yaml
love-app:
  agent:
    history-window: 20
    max-message-length: 2000
    sse-timeout: 60s
    memory:
      type: ${LOVE_AGENT_MEMORY_TYPE:file}
      dir: ${LOVE_AGENT_MEMORY_DIR:chat-memory/agent}
    mcp:
      enabled: ${AMAP_MCP_ENABLED:true}
      protocol: ${AMAP_MCP_PROTOCOL:sse}
      key: ${AMAP_MCP_KEY:}
      base-url: ${AMAP_MCP_BASE_URL:https://mcp.amap.com}
      sse-path: ${AMAP_MCP_SSE_PATH:/sse}
      connect-timeout: 3s
      request-timeout: 20s
      stdio:
        command: cmd
        args: ["/c", "npx", "-y", "@amap/amap-maps-mcp-server"]
        env-key-name: AMAP_MAPS_API_KEY
```

说明：

- YAML 里只有环境变量名，没有 key 值；`key:` 默认空串是刻意设计（无 key 即降级，不报错）。
- 不得把 key 写进 `application-local.yml`；单一来源 = 进程环境。
- SSE 的 query 由代码拼接并 URL 编码：`ssePath + "?key=" + encode(key)`。原因：`Utils.resolveUri` 走 `URI.resolve`，base 带 query 会被丢弃；且 Spring AI 1.1.2 的 `SseParameters` 只有 `url`/`sseEndpoint` 两字段，没有 header 配置，鉴权只能走 query。
- 若将来改用 starter 自动配置（本规格不用），等价写法是 `spring.ai.mcp.client.sse.connections.amap.url=https://mcp.amap.com` + `sse-endpoint: "/sse?key=${AMAP_MCP_KEY}"`，并可用 `spring.ai.mcp.client.initialized=false` 走懒连接——但如上所述仍会在 `build()` 时联网，**不能**作为降级手段。

### 8.3 Maven 镜像配置（S1 新增两文件 + pom 兜底）

`.mvn/settings.xml`：

```xml
<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0">
  <mirrors>
    <mirror>
      <id>aliyun-public</id>
      <name>Aliyun Public Mirror</name>
      <url>https://maven.aliyun.com/repository/public</url>
      <mirrorOf>*</mirrorOf>
    </mirror>
  </mirrors>
</settings>
```

`.mvn/maven.config`：

```
-s.mvn/settings.xml
```

**重要（S1 实测修正，勿按直觉改回）**：Maven 3.9.6 把 `maven.config` 的**每一行当作一个整体参数**解析，不按空白切分。所以文件内 `-s` 与路径之间**不能有空格**：

- `-s.mvn/settings.xml` → commons-cli 识别为「选项 `-s` + attached value `.mvn/settings.xml`」；`mvn -o validate -X` 实测输出 `[DEBUG] Reading user settings from <项目根>\.mvn\settings.xml`。
- `-s .mvn/settings.xml`（带空格）→ 整行成为一个 token，值变成带前导空格的 `" .mvn/settings.xml"`，报 `The specified user settings file does not exist`，**所有 mvn 命令失败**。

注意区分两个场景：**命令行**里 `-s` 与路径是两个独立参数（`mvn -s .mvn/settings.xml ...`，必须有空格）；**只有 `maven.config` 文件内**才合并成一个 token。

`pom.xml` 兜底（IDE 默认不读 `maven.config`）：

```xml
<repositories>
  <repository>
    <id>aliyun-public</id>
    <url>https://maven.aliyun.com/repository/public</url>
    <releases><enabled>true</enabled></releases>
    <snapshots><enabled>false</enabled></snapshots>
  </repository>
</repositories>
<pluginRepositories>
  <pluginRepository>
    <id>aliyun-public</id>
    <url>https://maven.aliyun.com/repository/public</url>
  </pluginRepository>
</pluginRepositories>
```

依赖变更（S1）：

```xml
<dependencyManagement>
  <dependencies>
    <!-- 保留现有 3 个 BOM，顺序不变 -->
    <dependency>
      <groupId>io.modelcontextprotocol.sdk</groupId>
      <artifactId>mcp</artifactId>
      <version>0.17.0</version>
    </dependency>
  </dependencies>
</dependencyManagement>

<dependencies>
  <dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-mcp</artifactId>
  </dependency>
  <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-validation</artifactId>
  </dependency>
</dependencies>
```

不引入 `spring-ai-starter-mcp-client`（D9）。`spring-ai-mcp` 版本由 `spring-ai-bom:1.1.2` 管理；`mcp` 必须钉 0.17.0（§3.3）。

落地与验收：一次在线 `mvn -DskipTests compile` 拉全依赖 → `mvn -o -DskipTests compile` 必须为 0；`mvn dependency:tree -Dincludes=io.modelcontextprotocol.sdk` 只能出现 `0.17.0`；`mvn dependency:tree -Dincludes=org.springframework.boot` 不得出现 `3.5.x`。若 `maven.config` 的路径解析导致所有 mvn 命令失败，删掉它、改用显式 `mvn -s .mvn/settings.xml ...` 并写入 `README.md`。**S1 实测验收已通过**：`mvn -v`=0、`mvn -o -DskipTests compile`=0、`mvn -o test` 18/18 绿且只跑 offline、`mvn dependency:get` 日志出现 `Downloading from aliyun-public`（镜像真生效）、`dependency:tree` 只有 `mcp:0.17.0` 与 Boot 3.4.4。

### 8.4 密钥规则

1. `AMAP_MCP_KEY` 只从进程环境读取，禁止写进任何被 git 跟踪的文件。
2. 日志、异常 message、HTTP 响应体都不得包含 key 或其片段（含 URL query）。
3. 无 key 时：不建连接、不报错、接口照常 200（AC5）。
4. CI 一律不设 key；所有离线测试不依赖 key（MCP 相关测试全部用 stub）。
5. 交付前检查：`git grep -n "AMAP_MCP_KEY="`、`git grep -n "mcp.amap.com/sse?key="` 两条命令的结果中不得出现真实 key。

### 8.5 环境变量清单

| 变量 | 必须 | 用途 |
| --- | --- | --- |
| `AMAP_MCP_KEY` | 生产必须 | 高德 MCP 凭据 |
| `AMAP_MCP_ENABLED` | 否 | `false` 禁用 MCP（灰度/排障） |
| `AMAP_MCP_PROTOCOL` | 否 | 切 `stdio` 备选 |
| `AMAP_MCP_BASE_URL` / `AMAP_MCP_SSE_PATH` | 否 | 指向代理/自建端点 |
| `LOVE_AGENT_MEMORY_TYPE` / `LOVE_AGENT_MEMORY_DIR` | 否 | 记忆实现与目录 |
| `PGVECTOR_JDBC_URL` / `PGVECTOR_USERNAME` / `PGVECTOR_PASSWORD` | 本地已有 | 知识库连接（既有） |
| `LOVE_APP_ETL_ENABLED` | 否 | 仅导入知识库时置 `true`（既有） |

---

## 9. 测试策略

### 9.1 分层与 tag

| 层 | 特征 | tag | 默认执行 | 依赖 |
| --- | --- | --- | --- | --- |
| L1 纯单元 | 无 Spring 上下文、无网络；磁盘仅限 `@TempDir` | `offline` | 是 | 无 |
| L2 切片 | `@WebMvcTest`/手工构造 + mock 协作者 | `offline` | 是 | 无 |
| L3 集成（真外部） | 真 LLM、真 RDS、真高德 | `live` | **否** | 环境变量 + 花钱 |
| L4 人工冒烟 | curl / Knife4j | — | 手动 | 同 L3 |

规则：

- 既有 5 个 live 用例加 `@Tag("live")`：`WgdAiAgentApplicationTests`、`LoveAppTest`、`SalesAnalysisAppTest`、`LoveAppDocumentLoaderTest`、`LoveAppPgVectorIntegrationTest`。
- **例外（审查补充）**：`LoveAppDocumentLoaderTest.loadMarkdowns()` 本身完全离线（自己 `new` Loader 读 classpath），只因同类还有 `doChatWithRag()` 才被 `@SpringBootTest` 拖下水。S1 应**拆类**而非整类标 `live`：把 `loadMarkdowns()` + `markdownFilesOnClasspath()` 迁到新建的 `rag/LoveAppDocumentLoaderOfflineTest`（`@Tag("offline")`，去掉 `@SpringBootTest` 与 `loveApp` 字段），原类只留 `doChatWithRag()` 标 `@Tag("live")`。这样知识库文档回归仍留在 CI 里。
- 既有 4 个 offline 用例（`LoveAppRateLimitTest`、`LoveAppPgVectorStoreConfigTest`、`LoveAppRagEtlConfigTest`、`ReReadingAdvisorTest`）建议加 `@Tag("offline")`。
- surefire：`excludedGroups=${surefire.excluded.groups}`，默认属性 `live`；profile `live` 把它设为 `none` 并设 `groups=live`。
- `offline` 用例禁止：真发 DashScope 请求、`@SpringBootTest`（会拉起数据源/LLM Bean）、任何出站 HTTP。**注意 `FileSystemSaver` 是纯本地文件，允许出现在 offline 用例中（用 `@TempDir`）。**

### 9.2 命令

```powershell
# 门槛（每波次）
mvn -o -DskipTests compile
mvn -o test                      # 只跑 offline
# 真实联调（需要 key、RDS、DashScope；会花钱）
$env:AMAP_MCP_KEY = '<your-key>'
mvn -Plive test
# 单独跑某一类
mvn -o test -Dtest='LoveAgent*Test'
```

### 9.3 用例矩阵

| ID | 类（新） | 层 | 断言要点 | 覆盖 |
| --- | --- | --- | --- | --- |
| T1 | `tool/LoveLocalToolsTest` | L1 | `currentTime` 默认 `Asia/Shanghai`；输出匹配 `\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}[+-]\d{2}:\d{2} \w+ \(Asia/Shanghai\)`；指定 `America/New_York` 生效；非法时区返回「无效」文本且不抛 | D5 |
| T2 | `tool/LoveLocalToolsTest` | L1 | 假 `VectorStore` 捕获 `SearchRequest`：阈值 0.5、topK 5、query 透传；结果含编号/`filename=`/`score=`；每条 ≤500 字符；≤5 条 | D5 |
| T3 | `tool/LoveLocalToolsTest` | L1 | `VectorStore` 为空 → 「不可用」文本；`similaritySearch` 抛异常 → 「检索失败」文本且不抛 | D7 |
| T4 | `tool/AmapMcpToolProviderTest` | L1 | key 空 / `enabled=false` → `available=false`、callbacks 空、无异常、不建连接 | D7/D8 |
| T5 | `tool/AmapMcpToolProviderTest` | L1 | 覆写创建客户端的方法返回「`initialize()` 必抛」的 stub → 捕获、`available=false`、空列表、`close()` 被调用 | D7/D9 |
| T6 | `tool/McpTransportFactoryTest` | L1 | sse：host=`https://mcp.amap.com`、path=`/sse`、key=`a b` → `/sse?key=a+b`；stdio：`cmd` + `["/c","npx","-y","@amap/amap-maps-mcp-server"]` + env 名 `AMAP_MAPS_API_KEY` | D3 |
| T7 | `tool/LoveToolExceptionProcessorTest` | L1 | 抛 `ToolExecutionException(def, new RuntimeException("secret jdbc url"))` → 中文可读文本，含工具名，不含 `secret jdbc url`、不含堆栈 | D7 |
| T8 | `tool/LoggingToolCallbackTest` | L1 | `getToolDefinition()`/`call()` 原样委托；异常原样抛出 | D7 |
| T9 | `agent/HistoryWindowHookTest` | L1 | 30 条输入 → 输出 ≤20；首条是 `UserMessage`；`ToolResponseMessage` 前必有带 toolCalls 的 `AssistantMessage`；`UpdatePolicy.REPLACE`；输入 ≤ 窗口时原样返回 | D17 |
| T10 | `agent/AgentOutputMapperTest` | L1 | `AGENT_MODEL_STREAMING` 的 chunk 依次输出；`AGENT_TOOL_STREAMING` 忽略；`AGENT_MODEL_FINISHED` 仅在「零 chunk」时兜底输出全文；空 chunk 不产生事件 | D15 |
| T11 | `agent/PromptLoaderTest` | L1 | classpath 文件可读且非空；缺失路径 → `IllegalStateException`（用不存在的 classpath 构造） | S2/D14 |
| T12 | `agent/LoveAgentLoopTest` | L1 | 假 `ChatModel`：首轮返回 `toolCall(loveKbSearch)`，次轮返回最终文本；工具真实执行 1 次；最终文本等于次轮内容；`call` 正常返回 | D4/D5 |
| T13 | `agent/LoveAgentLoopTest` | L1 | 工具抛异常 → 次轮模型收到的 tool 消息含「调用失败」中文文本；最终仍有答复 | D7 |
| T14 | `agent/LoveAgentLoopTest` | L1 | 假模型永远返回 toolCall → 抛 `LoveAgentException(AGENT_LOOP_LIMIT)`（recursionLimit=8） | D12 |
| T15 | `agent/LoveAgentLoopTest` | L1 | 捕获发送给模型的 `Prompt`：system 文本**逐字等于**构造时传入的 prompt（用含 `{}` 的合成 prompt，证明未被模板渲染）；工具定义数 = 2 + stub MCP 数 | D14/D16 |
| T16 | `agent/LoveAgentSessionTest` | L1 | `@TempDir` + `FileSystemSaver`：同一 `chatId` 连调两轮，第二轮模型收到的消息数 > 第一轮（历史恢复）；用同目录新建 `LoveAgent` 再调第三轮，历史仍在（重启不丢） | D10 |
| T17 | `agent/LoveAgentSessionTest` | L1 | 不同 `chatId` 互不串历史；非法 `chatId`（`../x`）被拒绝 | D10/安全 |
| T18 | `agent/LoveAgentConcurrencyTest` | L1 | 同一 `LoveAgent` 并发 8 次不同 `chatId` 的 `chat`，结果互不串台；同 `chatId` 8 次并发不抛异常（service 层锁在 T21 覆盖） | D4 |
| T19 | `chatmemory/FileBasedChatMemoryTest` | L1 | 10 线程 × 10 次 add 后内容不丢（条数=100）；新实例可读回（持久化）；损坏文件 → 空历史不抛 | D10 |
| T20 | `chatmemory/FileBasedChatMemoryTest` | L1 | `../../etc/passwd` → `IllegalArgumentException`；`chat_id-1` 合法；`clear()` 后文件不存在 | 安全 |
| T21 | `service/LoveAgentChatServiceTest` | L2 | 空 `chatId` → 生成并回填；调用 `LoveAgent.chat(chatId,msg)` 一次；成功响应含 `answer`/`elapsedMs` | D6 |
| T22 | `service/LoveAgentChatServiceTest` | L2 | agent 抛 `TransientAiException` → `AGENT_UNAVAILABLE`；抛其它 → `AGENT_ERROR`/`INTERNAL_ERROR` 按表映射 | D7 |
| T23 | `service/LoveAgentChatServiceTest` | L2 | 流式：假 agent 返回 `Flux.just("A","B")` → 2 个 delta + 1 个 done；中途 error → 1 个 error 事件且无 done | D6/D15 |
| T24 | `service/LoveAgentChatServiceTest` | L2 | 同 `chatId` 并发 8 次 → 锁生效（用计数的假 agent 断言最大并发为 1） | D10 |
| T25 | `controller/LoveAgentControllerTest` | L2 | `@WebMvcTest` + mock service：200 结构；message 空白/2001 字/非法 chatId → 400；service 抛 `AGENT_UNAVAILABLE` → 503 | D6/D7 |
| T26 | `controller/LoveAgentControllerTest` | L2 | SSE：`asyncStarted()`、`Content-Type: text/event-stream`、最终 200；不断言帧顺序（帧内容由 T23 覆盖） | D6 |
| T27 | `exception/GlobalExceptionHandlerTest` | L1 | `LoveAgentException` → 对应状态；`MethodArgumentNotValidException` → 400；`Exception` → 500 且响应无堆栈 | D7 |
| T28 | `app/LoveAppRagSingleRetrievalTest` | L1 | 假 `VectorStore` + 假 `ChatModel`：一次 `doChatWithRag` 只触发 1 次 `similaritySearch`；`LoveApp` 构造器仍为 `LoveApp(ChatModel)` | 缺陷修复 |
| T29 | `app/LoveAgentLiveTest` | L3 `live` | 真模型 + 真高德：问「北京朝阳区适合约会的咖啡馆」→ 答复非空且日志有启动期打印过的地图工具名；再问「现在几点了」→ 答复含当前年份 | AC3/AC4 |

验收映射：AC1←T25；AC2←T10/T23/T26；AC3←T29；AC4←T16/T29；AC5←T4/T5+冒烟；AC6←T7/T13；AC7←`mvn -o test`；AC8←§8.4；AC9←§9.2。

### 9.4 不可离线覆盖的部分（明确接受）

- 真实高德的 `initialize()`/`listTools()`/返回结构（需 key 与外网）→ T29 + 人工冒烟。
- 托管端点与 npm 包的工具名/入参是否完全一致（§12.2 V3）。
- 真实模型的工具选择倾向 → prompt + T29 三条固定问题（地点/时间/情感）。
- 云端 pgvector 实际召回质量 → 人工看 `loveKbSearch` 日志与答复。

### 9.5 测试替身约定

- **假 `ChatModel`**：实现 `ChatModel`，按「消息中是否已有 `ToolResponseMessage`」决定返回 `toolCall` 还是最终文本；记录每次收到的 `Prompt` 供断言；不联网。**注意（源码核实后的修正）**：`agent.call()` 固定走非流式分支（`buildNonStreamConfig` 硬写 `_stream_=false`），所以 T12–T18 的假模型**只需 `call()` 可用即可**；但**假模型同时实现 `stream(...)` 更稳**，因为流式用例与「零 chunk 兜底」路径会走 `ChatClient.stream()`。另：若假模型在 `call()` 里抛异常，异常会被框架吞成 `Exception: ...` 文本，需要 `LoveAgent` 的记录器/哨兵还原（见 R17），测试要按这个机制写断言。
- **假 `VectorStore`**：实现 `VectorStore`，`similaritySearch(SearchRequest)` 记录请求并返回固定 `Document`。
- **假 MCP**：`AmapMcpToolProvider` 把「创建客户端」抽成包可见方法 `McpSyncClient createClient(McpClientTransport)`，测试覆写返回 stub；`initialize()`/`listTools()` 抛异常即可模拟失败。
- **假 `NodeOutput`**：`StreamingOutput` 非 final，Mockito 可 mock；`getOutputType()`/`chunk()`/`message()` 打桩。
- 目录一律 `@TempDir`；禁止写仓库根 `chat-memory/`。

---

## 10. 交付波次与文件所有权

### 10.1 波次表

| 波次 | 角色 | 拥有（可改/可建） | 交付物 | 门槛 |
| --- | --- | --- | --- | --- |
| 1（并行） | **S1 工程基线** | `pom.xml`、`.mvn/settings.xml`(新)、`.mvn/maven.config`(新)、`README.md`(新)、`src/test/**`（**仅**加 `@Tag`/surefire 配置，不改测试逻辑） | 阿里云镜像可用；`spring-ai-mcp` + `mcp:0.17.0` 钉版 + validation 依赖；surefire 分组；README 写清构建/运行/密钥/stdio 备选 | 在线拉依赖一次 → `mvn -o -DskipTests compile`=0；`mvn -o test` 全绿且无 live；`dependency:tree` 只有 mcp 0.17.0、无 Boot 3.5.x |
| 1（并行） | **S2 知识库与提示词资产** | `src/main/resources/prompts/**`(新)、`src/main/resources/document/**`（本轮仅错别字，默认不动） | `prompts/love-agent-system.txt` 满足 §5.4 全部 9 条，且不含具体工具名 | 文件存在、UTF-8、9 条覆盖（T11 在 S3 落地后校验）；不改 `document/**` 则无需重跑 ETL |
| 2 | **S3 工具层与 Agent 内核** | `src/main/java/.../tool/**`、`agent/**`、`chatmemory/**`、`rag/**`、`app/LoveApp.java`、`exception/LoveAgentException.java`、`exception/AgentErrorCode.java`；新增 `src/test/java/.../tool/**`、`agent/**`、`chatmemory/**`、`app/LoveAppRagSingleRetrievalTest`、`app/LoveAgentLiveTest` | T1–T20、T28 全绿（T29 属 live，随 S3 一并提交但不进默认门槛）；「2 本地 + 0 MCP」下工具循环可跑通；saver 记忆跨实例可恢复；`LoveApp` 注入与双检索修复 | `mvn -o -DskipTests compile`=0；`mvn -o test` 全绿；`git commit` |
| 3 | **S4 接口层** | `src/main/java/.../controller/**`、`model/**`、`service/**`、`exception/GlobalExceptionHandler.java`；新增 `src/test/java/.../controller/**`、`service/**`、`exception/**` | 同步 + SSE 端点、`chatId` 会话、统一异常；T21–T27 全绿 | 同上 + `mvn -o test -Dtest='*ControllerTest'` 全绿；`git commit` |
| 4 | **Reviewer（独立子 agent）** | 只读；产出审查报告（不提交代码） | 按 `code-review` 口径双轴报告：**标准轴**（命名、分层、日志、测试分层、无死代码）+ **规格轴**（逐条对照 D1–D17、AC1–AC9、§7 降级矩阵） | 报告为「无阻断项」或列出阻断项退回 owner |

### 10.2 所有权边界（防冲突）

1. `pom.xml`、`.mvn/**`、`README.md` **只有 S1 能改**；S3/S4 需要新依赖时向 S1 提需求。
2. 既有测试文件的 `@Tag` 只有 S1 加；S3/S4 只新增自己包下的测试。
3. `agent/`、`tool/`、`chatmemory/`、`rag/` 只有 S3 写；`service/` 只调 `LoveAgent` 公开方法，**不得出现** `ReactAgent`/`ToolCallback`/`NodeOutput`/`RunnableConfig` 类型。`exception/` 按类拆分所有权：`LoveAgentException` + `AgentErrorCode` 归 S3（`LoveAgent` 依赖它们），`GlobalExceptionHandler` 归 S4。
   - 对外签名（S4 照此编码，不得更改）：`String chat(String chatId, String message)`、`Flux<String> stream(String chatId, String message)`、`boolean isMcpAvailable()`、`List<String> toolNames()`。
   - 异常语义：`TransientAiException`/`NonTransientAiException` 原样上抛；`GraphRunnerException` 在 `LoveAgent` 内包成 `LoveAgentException`（循环耗尽 → `AGENT_LOOP_LIMIT`，其它 → `AGENT_ERROR`）；未预期异常 → `LoveAgentException(INTERNAL_ERROR)`。
4. prompt 文件只有 S2 写；S3 只读。
5. 波次 1 的 S1/S2 零路径重叠，可完全并行；波次 2 依赖 S1 的依赖与 S2 的 prompt；波次 3 依赖 S3 的公开签名（§4.2/§6.2 已定死）；波次 4 依赖前三者提交完成。

### 10.3 门槛（每波次全过才算交付）

```powershell
mvn -o -DskipTests compile     # 必须 [exit code: 0]
mvn -o test                    # 必须全绿，且无 live 用例
git add -A; git commit -m "<type>(<scope>): <中文摘要>"
```

额外检查（波次 3 完成后）：清空 `AMAP_MCP_KEY` 启动 → 命中两次 `/chat` → 确认 200、日志仅 1 条 MCP WARN、答复正常。

### 10.4 提交约定

`<type>(<scope>): <中文摘要>`，scope 取 `build`/`prompt`/`tool`/`agent`/`api`/`test`。示例 `feat(tool): 接入高德 MCP 并支持无 key 降级`。S1 与 S2 各自一个提交，禁止混合。

---

## 11. 非目标

本轮明确不做，评审时不得以「缺这个」为阻断项：

1. **不做 MCP Server**（D2）。
2. **不接网页搜索**（DuckDuckGo/Google 不可达；高德 POI 已覆盖地点类需求）。
3. **不做多 agent、子图、handoff、A2A**。
4. **不做对话摘要（`SummarizationHook`）**；窗口裁剪用 `HistoryWindowHook`，不额外调模型。
5. **不做 MCP 热重连/后台探活/熔断**；启动失败即本进程内用本地工具，靠重启恢复。
6. **不做工具级 SSE 事件**（不向前端暴露「正在调用某工具」）。
7. **不做前端页面**；验收用 curl + Knife4j。
8. **不做鉴权/限流/多租户**。
9. **不改向量库 schema、不换 embedding 模型、不重跑 ETL**（D13）。
10. **不重构 `SalesAnalysisApp`/demo 包**；不删除既有 `doChat*` 方法。
11. **不引入 WebFlux**（MVC + `SseEmitter`）。
12. **不做工具筛选（`McpToolFilter`）与白名单**（D11）。
13. **不做 LLM 多供应商 fallback**。
14. **不暴露会话删除/列表接口**；排障手工删 `.saver` 文件。

---

## 12. 风险与未决项

### 12.1 风险与缓解

| ID | 风险 | 影响 | 概率 | 缓解 | 验证手段 |
| --- | --- | --- | --- | --- | --- |
| R1 | `spring-ai-mcp` 要 `mcp:0.17.0`（depth 3），agent-framework 带 `mcp:0.14.0`（depth 2），nearest-wins 选 0.14.0 | `NoSuchMethodError`，启动或首次工具调用即崩 | 高 | `dependencyManagement` 钉 `mcp:0.17.0`（§8.3） | `mvn dependency:tree -Dincludes=io.modelcontextprotocol.sdk` 只有 0.17.0；S3 启动冒烟 |
| R2 | 高德 SSE 端点对 `/sse?key=` 的处理与 `URI.resolve` 组合不符预期 | MCP 恒不可用 | 中 | 拼接集中在 `McpTransportFactory`，`AMAP_MCP_SSE_PATH`/`AMAP_MCP_BASE_URL` 可覆盖；备选 stdio | T6 断言拼接；有 key 时 `curl -N "https://mcp.amap.com/sse?key=$env:AMAP_MCP_KEY"` 应返回 `event: endpoint`；否则 `AMAP_MCP_PROTOCOL=stdio` |
| R3 | `instruction` 被当模板渲染导致 `{}` 报错 | 启动即失败 | 已消除 | 改用 `systemPrompt`（D14） | T15 用含 `{}` 的 prompt 断言逐字透传 |
| R4 | `systemPrompt` 与 `model` 的组合未被 framework 正确转成 system message | prompt 失效 | 中 | T15 直接断言 model 收到的 system 文本；若不符，改用 `.chatClient(ChatClient.builder(model).defaultSystem(prompt).build())` | T15 失败即暴露，回退只改 `LoveAgent` 一处 |
| R5 | 模型不肯调工具或乱调（坐标顺序错） | 「会调工具」名不副实 | 中 | prompt 场景路由 + 坐标格式条目（§5.4） | T29 三条固定问题 |
| R6 | 12 个工具 + 20 条历史导致 token 超预算/首字慢 | 成本、体验 | 低 | 全量暴露先跑通 | 冒烟记录单次 `usage`；单轮输入 >8k 即按 §12.3 收敛 |
| R7 | saver 检查点按节点写入：失败轮次留下中间状态，与「只在成功时落盘」不同 | 语义差异、可能续跑未完成回合 | 中 | 明确写入 §4.4；失败不回滚；中间状态通常自洽（toolCall + toolResponse 成对） | T16/T17 验证历史正确；T13 验证工具失败不破坏状态 |
| R8 | 若验收发现知识库覆盖不足需增补文档，必须重跑 ETL | 计划外成本 | 中 | D13：本轮不改 `document/**`；prompt 要求未命中时说明 | 冒烟看 `loveKbSearch` 命中率；确需增补时单独开波次，`$env:LOVE_APP_ETL_ENABLED='true'; mvn spring-boot:run`，完成后立即关回 `false` |
| R9 | `application-local.yml` 内含明文 DashScope key / RDS 口令（已 gitignore，属既有债） | 泄漏面 | 中 | 本规格不引入新明文；README 提醒改用环境变量并轮换 | `git ls-files | Select-String application-local` 应为空 |
| R10 | `.mvn/maven.config` 全局生效，写错会阻塞所有 mvn 命令 | 阻塞全员 | 低 | 内容仅一行 `-s.mvn/settings.xml`（**不能有空格**，见 §8.3 实测说明）；落地后立即用 `mvn -o validate -X` 验证 `Reading user settings from` 指向本项目 `.mvn/settings.xml` | `mvn -o -DskipTests compile`=0；失败即删除并改用显式 `mvn -s .mvn/settings.xml ...`（命令行两参数、有空格） |
| R11 | 高德 SSE 长连接被代理缓冲/断开 | 地点类问题间歇降级 | 中 | `request-timeout=20s`；§7 矩阵兜住；部署层需关 SSE 缓冲 | 连续 20 次「查地点」冒烟统计失败率；>10% 排查代理或切 stdio |
| R12 | SSE 客户端断开后仍消耗额度 | 成本 | 中 | `onError/onCompletion` 取消订阅 | 人工 `curl` 中途 Ctrl+C 后看无后续工具调用 |
| R13 | `FileBasedChatMemory` 线程安全修复引入回归 | 旧接口异常 | 低 | 每会话锁 + 原子写 | T19/T20 |
| R14 | `chatId` 作为 `threadId` 进入 saver 文件名（`thread-<chatId>.saver`） | 路径穿越 | 中 | 白名单正则三重校验（HTTP/service、`LoveAgent`、旧记忆类） | T17/T20 |
| R15 | Knife4j 未扫描到新接口 | 文档缺失 | 低 | controller 放已配置的扫描包 | 启动后 `/api/doc.html` 可见两个端点 |
| R16 | 检查点文件随会话增长无上限（仅上下文被裁剪） | 磁盘增长 | 低 | 单会话文本量小；目录可手工清理 | 长会话压测观察目录大小；超 10MB 再引入清理策略（非本轮） |
| R17 | `AgentLlmNode` 把模型异常吞成 `"Exception: ..."` 文本（§3.4 源码事实），同步接口可能 200 + 错误文本 | 错误码失真、用户看到英文异常 | **高** | `LoveAgent.chat()` 兜底：优先走流式让异常冒泡 + `translate()` 沿 cause 链识别 `TransientAiException`；退路是哨兵 + 状态码分类，且绝不把该文本当答复 | 假模型抛 `TransientAiException` 的离线用例：`chat()` 必须抛异常或 `AGENT_UNAVAILABLE`；加一条「答复不含 `Exception: ` 前缀」断言 |
| R18 | 裸 `ToolCallback`（非 `@Tool`/非 `SyncMcpToolCallback`）抛非 `ToolExecutionException` 时，`AgentToolNode` 会把 `e.getMessage()` 原样回喂模型（`AgentToolNode.executeSyncTool` 源码） | 异常信息泄漏进上下文 | 低（真实两条工具链都包成 `ToolExecutionException`） | 本轮沿用现状；新增工具必须走 `@Tool` 或经过包装 | reviewer 检查是否新增了裸 `ToolCallback`；如新增，要求包装成 `ToolExecutionException` |

### 12.2 待验证项（依赖真实环境，当前无法离线确认）

| ID | 待验证项 | 为什么现在验不了 | 验证手段 | 不成立的应对 |
| --- | --- | --- | --- | --- |
| V1 | MCP SDK 版本最终落到 0.17.0，且 `spring-ai-mcp` 与该版本 API 兼容 | 本地无 `mcp:0.17.0`/`spring-ai-mcp` 的 jar，只有 POM；必须联网解析 | 一次在线 `mvn -DskipTests compile`；`mvn dependency:tree -Dincludes=io.modelcontextprotocol.sdk` 只出现 0.17.0；S3 启动后完成一次 `initialize()+listTools()`（有 key 时）或无 key 启动不报 `NoSuchMethodError` | 若 0.17.0 与 Spring AI 1.1.2 不兼容，退到 0.14.0 并改用裸 `io.modelcontextprotocol.sdk` API（自行把工具转成 `ToolCallback`），仍保持「不依赖 starter」 |
| V2 | starter 编译于 Boot 3.5.8 的包在 Boot 3.4.4 上是否可用 | 本方案不引入 starter，无法从本仓库验证；只能验证「没被带进来」 | `mvn dependency:tree -Dincludes=org.springframework.boot` 不得出现 `3.5.x`；`mvn dependency:tree -Dincludes=org.springframework.ai` 中 `spring-ai-*` 全部为 1.1.2 | 若将来必须引入 starter，先降级其传递的 Boot 版本，或整体升 Boot 到 3.5.x（单独评估） |
| V3 | 高德托管端点实际暴露的工具名/入参与 npm 包 0.0.8 的 12 个工具是否一致 | 无 `AMAP_MCP_KEY`，且无效 key 只返回 `INVALID_USER_KEY`；任何探测都拿不到工具清单 | 有 key 时读启动日志 `高德 MCP 工具: [...]`，与 §5.2 对照；`LoveAgentLiveTest` 只断言语义不断言工具名 | 不一致时以日志为准更新 §5.2 与 prompt 的能力描述；若工具缺失（如无周边搜），退到 `maps_geo` + `maps_text_search` 组合 |
| V4 | 托管端点的 SSE 连接稳定性（代理、心跳、长连超时） | 无 key 无法建立有效流 | 有 key 时连续 20 次工具调用冒烟，统计失败率与首包延迟 | 失败率 >10% → 切 `AMAP_MCP_PROTOCOL=stdio`（`cmd /c npx`），或加部署层 SSE 透传配置 |
| V5 | `systemPrompt` + `model`（不传 `chatClient`）时 `ReactAgent` 的 system 消息装配方式 | 只能靠离线假模型观察最终 `Prompt`，真实链路未跑 | T15 断言 + 有 key 冒烟观察模型是否遵守 prompt | 不符则改用 `.chatClient(...)` 分支（R4） |

### 12.3 本轮已定但可调整的项（按此实施，有新证据再改）

| 项 | 本轮结论 | 调整触发条件 | 调整方式 |
| --- | --- | --- | --- |
| MCP 工具是否筛选 | 全量 | 单轮输入 >8k tokens 或首字延迟 >5s | 加 `McpToolFilter` 白名单 5 个（搜索/周边/详情/天气/驾车） |
| 上下文窗口 | 20 条 | 真实对话出现「忘了上一轮」 | 调 `love-app.agent.history-window`，不改代码 |
| 记忆实现 | `FileSystemSaver` | R16 磁盘增长或跨机部署 | `LOVE_AGENT_MEMORY_TYPE=memory` 临时降级；或换 `PostgresSaver`（依赖 RDS，需另评） |
| 摘要能力 | 不做（只裁剪） | 长会话裁剪后上下文断裂明显 | 引入官方 `SummarizationHook`（会多一次模型调用） |
| MCP 传输默认 | `sse` | V4 被证伪 | `AMAP_MCP_PROTOCOL=stdio`，不改代码 |
| 递归上限 | 8 | 复杂问题被截断（日志见 `AGENT_LOOP_LIMIT`） | 调到 12，同时观察成本 |
| 流式兜底 | 零 chunk 时补发全文 | 前端要求严格逐字 | 改走 `streamMessages` 并在 `LoveAgent` 聚合，HTTP 契约不变 |

---

## 自审

自审对象：本文件（`docs/superpowers/specs/2026-10-24-love-agent-mcp-design.md`）。检查项与结论：

| 检查项 | 方法 | 结论 |
| --- | --- | --- |
| 占位符 | 全文检索未完成标记（英文三字母缩写、中文「待补充/待确定」、TODO 注释） | **0 处**。`../../etc/passwd`、`<your-key>`、`<异常简单类名>` 是示例/格式化占位，不是未决占位符 |
| 前后矛盾 | 逐节交叉核对 D1–D17 ↔ §5/§7 ↔ §10 ↔ §12 | 无矛盾。重点核对 6 组易冲突点：(a) D7「MCP 不可用不 5xx」与 §7.3 表一致；(b) D9「不用 starter」与 §8.3 依赖清单一致（只加 `spring-ai-mcp`）；(c) D10「saver 记忆」与 §4.4/§6.4 一致，且明确 `FileBasedChatMemory` 仅服务旧路径；(d) D14「systemPrompt」与 §5.3 伪代码、T15 一致；(e) D15「真流式」与 §6.3、T10 一致；(f) D13「不改 document」与 §10.1 S2 行一致 |
| 决策覆盖 | 每条 D 是否有落地位置 | D1–D17 全部有「落地点」列，并在 §9.3 有用例或验收映射；AC1–AC9 与用例的对应关系在 §9.3 末尾写死 |
| 技术事实准确性 | 反编译/jar 列举/实网探测逐条核对 | 核查并修正了首版两处错误：(1) 首版称 graph-core 只有内存 saver——实际存在 `FileSystemSaver`（`checkpoint/savers/file/`）、`PostgresSaver`、`RedisSaver` 等，已改为 saver 方案；(2) 首版用 `instruction(...)`，已改 `systemPrompt(...)`。另补充 `Builder` 无 `advisors()`、`OutputType` 常量、`SseParameters` 字段、starter 依赖 Boot 3.5.8、工具名规范化与固化等事实 |
| 范围收敛（YAGNI） | 对照 D1 与 §11 | 收敛。14 条非目标覆盖多 agent、摘要、热重连、工具事件、前端、工具筛选、WebFlux、多供应商、会话管理接口等诱惑 |
| 测试可执行 | 每条 T 都写「层 + 断言 + 是否离线」 | 29 条中 28 条可完全离线：假 `ChatModel`/`VectorStore`/`McpSyncClient`/`NodeOutput`、`@TempDir` 的 saver 与记忆目录、`@WebMvcTest` 切片均已给出实现约定（§9.5） |
| 门槛可执行 | §10.3 命令 | 3 条确定性命令；基线 `mvn -o -DskipTests compile` 本日实测退出码 0 |
| 密钥安全 | §8.4 + 检索命令 | 仓库内无 key 值；yml 仅 env 占位；日志脱敏规则与 AC8 检查命令闭环；无 key 启动路径在 T4/T5 与冒烟中覆盖 |
| 可独立阅读 | 从零阅读：术语（ReactAgent/POI/ETL/saver/threadId）首次出现即解释；路径均为仓库内真实路径 | 通过。新建文件已标「新」，既有文件为真实路径 |
