# 范围与验收

学生登录后通过 `/agent/chat` 使用既有 KnowledgeChatService 流式对话；Agent 与社区内容隔离。支持会话分页、创建、重命名、删除、消息持久化、刷新恢复、安全Markdown、代码高亮和复制、真实引用、取消与重试。Cookie Session、CSRF、401清理、403提示与逐操作owner校验为必需。

验收区分离线Mock、真实PostgreSQL、真实模型/RAG和浏览器证据。不得以模拟结果替代真实联调。最终记录npm test/build、Maven回归、双用户IDOR、未认证/CSRF、流分帧与中断、375至1920布局。生产或目标环境未确认时不执行数据库迁移。

## 管理员扩展
管理员与学生均可使用同一AI工作台。管理员会话按本人用户ID保存，跨账号访问返回404。后台侧栏提供入口，沿用原Session/CSRF与SSE/RAG能力。
