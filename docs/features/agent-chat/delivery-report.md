# AI Agent 模块交付报告

## 1. 功能完成清单

已实现学生独立 `/agent/chat` 页面、现有Cookie Session与CSRF整合、逐操作owner校验、分页历史、创建/切换/重命名/删除、POST SSE、取消、失败重试、安全Markdown、代码高亮与复制、引用展开及375–1920px响应式。具体实际验证及未验证边界以 [test-report.md](test-report.md) 为准，真实模型与Mock证据分别记录。

## 2. 多 Agent 协作

实际参与Backend、Frontend、UI/UX、QA四名专业子Agent，由Chief协调。环境并发容量为主Agent加三名子Agent，UI设计先交付后让出名额给QA，后续UI回访审查。使用实际spawn/followup/send机制，在同一工作区按文件边界协作，没有Git worktree或自动提交。

Backend负责Java/SQL/后端测试；Frontend独占frontend；UI负责设计规范和源码/截图审查；QA负责独立真实验收与报告；Chief冻结契约、复核代码、安全与时序、执行最终打包及集成修复。无文件覆盖冲突。Chief在前端交回后仅修复侧栏通用button样式覆盖主按钮的问题。新增项目级.codex角色配置供后续会话使用，模型继承父设置，配置来源见implementation-plan.md。

## 3. 代码变更

```text
src/main/java/com/example/wjyaiagent/
  agent/
    controller/AgentChatController.java, AgentExceptionHandler.java
    service/AgentChatService.java, AgentException.java
    repository/AgentRepository.java
    dto/AgentDtos.java
  auth/security/SecurityConfig.java        扩展学生安全链
  security/RateLimitFilter.java            新学生流IP限流
  service/KnowledgeChatService.java        复用流及保护私有namespace
src/main/resources/db/migration/V002__agent_chat.sql
src/test/java/com/example/wjyaiagent/
  agent/AgentChatServiceTest.java, AgentSecurityTest.java
  security/StudentStreamRateLimitTest.java
frontend/src/
  api/agent.js, request.js
  composables/useAgentStream.js
  stores/agent.js
  views/agent/AgentChatView.vue
  components/agent/ConversationSidebar.vue, ChatMessage.vue, CitationPanel.vue
  router/index.js, layouts/StudentLayout.vue
frontend/tests/agent-stream.test.js, agent-store.test.js
scripts/agent-chat-live-check.py, agent-chat-browser-check.cjs,
        agent-chat-restart-check.py
.codex/config.toml, agents/*.toml
docs/features/agent-chat/*.md
```

前端必要依赖markdown-it、DOMPurify、highlight.js已通过package-lock.json锁定。既有pom、登录/注册、应用配置、原生HTML和文档大部分是任务开始前的未提交成果，未把它们误计为本次新实现，也未提交Git或推送。

## 4. 数据库与记忆

V002新增public.agent_conversation和public.agent_message；user_id外键引用既有community_users，仅共享身份，不查询社区内容。消息有UUID和稳定递增sequence、角色/状态约束、JSONB引用、级联删除和分页索引。真实迁移目标已与文档确认的开发PostgreSQL核对；迁移/指纹/重启证据见验收报告。

PostgreSQL为历史唯一事实源。每次生成经过归属校验，再以服务端派生的student-UUID恢复已成功的成对问答到原ChatMemory，结束清除派生缓存，下次从SQL重建。失败/取消/重启中断有独立状态，不进入成功上下文。旧AI入口拒绝任意大小写及带空格的学生前缀，避免Windows不区分大小写的Kryo文件路径绕过。保留原Agent、RAG及旧API Key能力。

## 5. 接口

详见 [api-contract.md](api-contract.md)。实际路径均带/api容器前缀：

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| POST / GET | /api/agent/conversations | 创建 / 分页列表 |
| GET | /api/agent/conversations/{id}/messages | 分页历史 |
| PATCH / DELETE | /api/agent/conversations/{id} | 标题 / 删除 |
| POST | /api/agent/conversations/{id}/messages/stream | `{message}`，SSE message/done/error |
| POST | /api/agent/conversations/{id}/cancel | 取消生成 |

分页返回items/page/size/hasMore；对外chatId是会话UUID；message携带delta，done携带citations与elapsedMs，error携带code/message。所有写操作保留CSRF，跨学生统一404，401清理身份，403显示安全反馈。浏览器无需API Key。旧/ai/**规则保留，学生流按IP限流避免伪造X-API-Key换桶。

## 6. 测试

最终执行`./mvnw.cmd -q package`时31项测试全部通过、0失败/错误/跳过；随后repackage遇到Windows运行中jar文件锁。停止本次QA实例后，`./mvnw.cmd -q -DskipTests package`成功，最终新jar包含无效UUID/分页类型错误体修复。前端`npm --prefix frontend test`最终10项通过，`npm --prefix frontend run build`成功。`git diff --check`通过。

离线测试包含H2真实JDBC、Mock模型、MockMvc Session/CSRF/IDOR、成功轮次恢复、失败/取消/EOF/重启状态、namespace及限流Header绕过回归；前端包含SSE逐字节UTF-8/跨chunk/多行data、AbortController、身份和旧响应隔离及预流失败保留问题。真实PostgreSQL、模型、RAG、浏览器与重启证据统一见test-report.md，不能用上述离线测试替代真实验收。

## 7. 启动与验收

开发PostgreSQL和模型凭据沿用被Git忽略的application-local.yml或既有安全环境变量，交付文档不复制凭据。开发库本次已完成V002，日常启动不需要baseline。

```powershell
$env:STUDENT_COOKIE_SECURE='false'
# 复用现有向量库，联调时关闭ETL和schema初始化，保留RAG检索
java -jar target/wjy-ai-agent-0.0.1-SNAPSHOT.jar --server.port=18124 --knowledge-base.rag.etl.enabled=false --spring.ai.vectorstore.pgvector.initialize-schema=false
```

另开终端：

```powershell
$env:API_PROXY_TARGET='http://127.0.0.1:18124'
npm --prefix frontend run dev -- --host 127.0.0.1 --port 5175
```

访问http://127.0.0.1:5175/agent/chat。使用两个自选普通学生账号注册并登录；A创建会话后，在B会话携带自己的CSRF调用A的history/PATCH/DELETE/stream/cancel，应全部404，B的列表不出现A会话。退出后页面不可访问。真实自动验收脚本运行方式：

```powershell
python scripts/agent-chat-live-check.py --base-url http://127.0.0.1:18124/api
# 在已安装playwright环境中设置PLAYWRIGHT_MODULE后执行
node scripts/agent-chat-browser-check.cjs
```

RAG可提问“Spring Boot的Controller、Service、Repository应该如何分层”，展开实际引用；第二轮使用上一轮信息测试上下文，再刷新、退出重新登录和后端重启检查相同记录。测试脚本创建普通开发学生，不公布或持久化明文密码，不清理既有记录。生产部署沿用同源HTTPS和/api反向代理，保留Secure Cookie。

## 8. 剩余问题与范围

本次为单体应用，没有引入新Agent核心、微服务或额外基础设施。核心功能与三阶段真实集成已交付，未发现未解决P0/P1。最终后端18124、前端5175保持运行，原有进程未动；9个本轮普通开发测试学生及其数据保留，密码未保存。

未实测项：真实RAG无命中分支、手机软键盘/安全区域实机、完整浏览器分页/移动抽屉内部交互、真实网络断开、30分钟绝对超时，以及旧API再次调用真实模型。已用离线回归、浏览器viewport和模拟故障覆盖部分边界，不能视为上述真实测试均通过。不同操作系统的剪贴板权限仍需设备补充验收。本次未发现因外部凭据/数据库导致的开发阻塞。
