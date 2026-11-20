# 学生社区实施计划

## 目标

完成学生问答、审核、分类、搜索、个人记录及收藏闭环，保持社区与 AI 数据独立。

### Phase A 审查与契约
**Status:** complete

### Phase B 实现
**Status:** complete

Backend 独占 Java 社区、认证 ADMIN 增量及 V003/V004。Frontend 独占 frontend 社区页面、路由、API 与导航。UI/UX 独占 02-ui-design.md，完成后释放执行槽位供 QA。QA 独占独立验收脚本、测试报告，缺陷交 Chief 分配。Chief 独占契约、需求、计划、集成文档及 AGENTS.md。环境并发上限四个运行 Agent（含 Chief），四名专业 Agent 分时参与。现有 .codex 专业角色针对 Agent 会话，本次复用其职责边界并重新限定为社区。

### Phase C 集成与验收
**Status:** complete

运行 Maven、前端测试与构建，确认开发库再迁移，执行学生 A/B/ADMIN 审核隔离与浏览器闭环。真实模型、RAG 与模拟测试分别报告。


