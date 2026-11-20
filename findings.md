# 现状发现

## 技术栈与入口
- Spring Boot 3.4.4、Java 21、Spring AI Alibaba 1.1.2、DashScope、PostgreSQL + pgvector。
- 主入口是 `LoveAgentController`：`POST /api/ai/love/agent/chat` 和 `/chat/stream`；前端固定调用 SSE 地址 `/api/ai/love/agent/chat/stream`。
- 生产问答实际走 `LoveAgent -> ReactAgent -> LoveLocalTools.loveKbSearch`，知识库检索由 Agent 自主决定。
- `LoveApp` 另有直接 RAG 路径（`RetrievalAugmentationAdvisor`）、云知识库 Advisor 和普通对话路径，但没有对应 HTTP Controller，形成两套未统一的问答链路。

## 已有可复用能力
- Markdown 读取、TokenTextSplitter 切片、DashScope Embedding、SimpleVectorStore/PGVectorStore 双实现。
- ETL 已使用稳定 UUID（filename + chunk text）并支持 pgvector 模式下显式导入。
- 同步/SSE 输出、会话记忆、统一异常处理、API Key/限流过滤器、OpenAPI 配置。
- 百炼云知识库检索器已经有条件化接入点，可作为托管检索实现或迁移参考。

## 关键问题
- 领域命名和提示词全面绑定恋爱：`LoveAgent`、`LoveApp`、`love-agent-system.txt`、`loveKbSearch`、控制器路径、前端标题和知识库表名均是 love 专用。
- 知识库只有 classpath 下 3 个 Markdown 文件；没有上传、目录、版本、删除、重建、进度、失败重试、权限隔离和来源管理。
- Document 元数据只有 filename；没有 kbId/documentId/title/category/tenant/version/chunkIndex/hash/sourceUri 等检索与引用所需字段。
- ETL 在应用启动/配置开关时直接做向量化，规模化后会阻塞启动；没有按文档版本删除旧切片，也没有可观测的 ingestion job。
- 检索参数散落在代码中（阈值 0.5、topK 5/检索器参数），没有统一配置、混合检索、重排、过滤或去重策略。
- 回答不返回引用来源、分数或可验证证据；空上下文时允许模型继续回答，容易出现无依据回答。
- 查询重写固定调用模型，所有问题都重写；没有短路、超时、失败降级和对比评估。
- Agent 的地图 MCP、时间工具和恋爱工具与通用知识库产品耦合，应该拆成可选工具插件；知识库问答本身不需要 ReAct 才能工作。
- `LoveApp` 中存在硬编码 system prompt，与 `love-agent-system.txt` 形成两套提示词来源；自定义 `QuestionAnswerAdvisor` 是空类，说明历史代码未清理。
- 只有一个 `contextLoads` 测试，没有检索、切片、引用、权限、SSE、降级和索引更新测试。
- `src/main/resources/application-local.yml` 当前被 Git 跟踪且包含 DashScope API Key、高德 Key、PG 密码默认值；这些凭据必须立即轮换并彻底移出仓库。
- Maven wrapper 在本机尝试编译时因无法写入用户 Maven 缓存目录失败；本次分析未以编译结果作为结论依据。

## 目标系统应具备的核心对象
- KnowledgeBase：知识库配置、租户/权限、Embedding/检索策略。
- KnowledgeDocument：原文件、版本、状态、checksum、来源、更新时间。
- KnowledgeChunk：稳定 chunkId、正文、标题路径、页码/段落、向量、全文索引字段。
- IngestionJob：解析、切片、向量化、发布、失败原因、重试次数。
- ChatSession/Question：会话、查询、检索证据、答案、反馈和审计信息。
# 追加约束与 Codex 工作流结论

## 用户确认的改造约束
- 目标是通用知识库问答系统，存在专门的知识问答 RAG 文档。
- `application-local.yml` 中现有 Key 和密码按用户要求保留，不在本次改造中替换。
- 所有 Love 领域命名、提示词、接口、前端文案和恋爱专用能力都要改造或删除。
- 不使用高德 MCP；高德相关依赖、配置、工具和连接逻辑应删除或彻底隔离。
- 计划使用 vibe coding，仓库需要持久化的 `AGENTS.md` 与项目级 Codex 配置。

## Codex 文件位置结论
- 持久化仓库指令使用根目录 `AGENTS.md`。
- Codex 项目级 TOML 的标准位置是 `.codex/config.toml`，不是仓库根目录裸 `config.toml`；裸文件不会被项目自动发现，除非通过命令行显式指定。
- 子代理角色建议放在 `.codex/agents/*.toml`，由 `.codex/config.toml` 的 `[agents.<role>]` 通过 `config_file` 引用。
- 项目配置只在项目被信任时加载；个人默认配置仍在 `~/.codex/config.toml`。
- 子代理模型优先级：显式 spawn 模型 > `[agents]` 默认模型 > 父代理模型；角色文件可进一步指定 `model` 和 `model_reasoning_effort`。
- 项目配置不应放供应商、认证、外部凭据或个人路径；这些属于用户级配置。

## 学生社区审查
现有社区为身份占位，JdbcTemplate与V001/V002可复用。ADMIN角色已有表约束，登录和Session过滤需增量支持。社区与Agent保持数据隔离。环境同时最多运行主Agent加3子Agent，UI交付后启动QA。

- 并行开发期间用户IDE启动后端，已执行尚在完善的V003。最终必须保留已执行迁移，约束改动通过V004追加，后续重新跑全部测试并启动验收。

- 管理后台开发基线PG V001..004均校验成功，向量47条指纹不变。V005在首次执行前已完整冻结，避免前轮IDE提前执行导致CRC漂移。
