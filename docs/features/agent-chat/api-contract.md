# Agent 对话接口契约

所有路径以下包含容器前缀 `/api`，业务路径 `/agent/**`。有效 STUDENT 或 ADMIN Session 可访问；写操作使用现有 `/api/auth/csrf` 动态 Header。浏览器不接触 API Key。旧 `/ai/**` 保留 API Key 与限流，禁止访问学生私有 memory namespace。身份来自 SecurityContext，任何 DTO 不接受 userId。

## REST

会话与消息 ID 均为 UUID 字符串。时间为 ISO-8601。分页从 0 开始，size 默认20，最大100。

| 方法 | 路径 | 输入 | 输出 |
| --- | --- | --- | --- |
| POST | /api/agent/conversations | `{ "title": "新对话" }`（title可省略） | 201 Conversation |
| GET | /api/agent/conversations?page=0&size=20 | 无 | Page<Conversation> |
| GET | /api/agent/conversations/{id}/messages?page=0&size=20 | 无 | Page<Message> |
| PATCH | /api/agent/conversations/{id} | `{ "title": "标题" }` | Conversation |
| DELETE | /api/agent/conversations/{id} | 无 | 204 |
| POST | /api/agent/conversations/{id}/messages/stream | `{ "message": "问题" }` | text/event-stream |
| POST | /api/agent/conversations/{id}/cancel | `{}` | 204，幂等 |

Conversation: `{id,title,createdAt,updatedAt}`。Page: `{items,page,size,hasMore}`。
Message: `{id,role,content,status,citations,createdAt}`，role为user/assistant，status为completed/generating/failed/cancelled/interrupted。会话按updatedAt倒序；消息分页取最新页，页内按时间升序，加载更早页时前置。title为1至100字符；message为1至2000字符。拒绝未知请求字段。

## SSE

命名事件，每个data是完整JSON，空行分帧：

```text
event: message
data: {"chatId":"会话UUID","delta":"文本增量"}

event: done
data: {"chatId":"会话UUID","elapsedMs":123,"citations":[]}

event: error
data: {"chatId":"会话UUID","code":"AI_UNAVAILABLE","message":"模型服务暂时不可用，请稍后重试"}

```

引用复用Citation: `{documentId,documentVersion,chunkIndex,titlePath,sourceName,snippet,score}`。不编造页码；服务端净化路径，客户端按文本展示。仅done视为成功，EOF无done视为中断。cancel与断开取消订阅并保存部分输出；客户端取消后重新读取历史。每会话仅一条生成，冲突409，生成时禁止修改/删除。

## 安全与错误

错误JSON沿用 `{code,message,chatId}`。401身份失效，403权限/CSRF，404不存在或不属于当前用户（避免枚举），400输入无效，409会话忙，429限流（Retry-After），503数据库不可用；模型流中失败使用error事件。所有操作先验证owner，memory key仅服务端派生 `student-<UUID>`。发送前从SQL completed轮次重建ChatMemory，失败和取消清除派生记忆，下次从SQL恢复；SQL为唯一历史事实源。重启将未完成状态标记interrupted。旧API不得使用该namespace。

## 数据库

Flyway V002，仅新增 public.agent_conversation 与 public.agent_message，不读写社区业务内容或向量表。conversation.user_id引用public.community_users.id；删除会话级联消息。message.citations为JSONB，稳定排序字段由后端补足。索引覆盖user_id/updated_at和conversation_id/顺序。保留既有V001与数据。

## 文件边界

Backend Agent负责Java业务、安全最小适配、V002和后端测试；Frontend Agent独占frontend/；UI Agent独占ui-design.md；QA负责独立验收脚本与test-report.md；Chief负责契约、需求、计划、集成报告及项目Agent配置。接口调整先同步本契约。
