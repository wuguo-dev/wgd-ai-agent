# 执行计划

用户原有登录、注册、文档与frontend均为未提交内容，保留原状并增量实现，不提交或推送。

1. 已检查AGENTS及前后端架构，仓库初始无.codex配置。当前环境支持真实子Agent但并发总数为4，因此Backend、Frontend、UI先并行审查，UI完成后启动QA，实际共四名专业子Agent。
2. Chief冻结api-contract.md后通知后端、前端独立编码。后端独占Java/迁移/后端测试；前端独占frontend/；UI仅ui-design.md；QA独立验收报告及脚本，不与业务实现交叉写文件。
3. Chief复核契约、身份与记忆隔离、错误和取消时序，再执行构建、回归及可用环境联调。
4. 验收状态与限制写入test-report.md，未完成真实外部服务验证不得宣布全部门禁完成。

## 项目级 Agent 配置

已新增 `.codex/config.toml` 和 `.codex/agents/{backend,frontend,ui-ux,qa}.toml`，继承父模型。配置根据[官方Subagents文档](https://learn.chatgpt.com/docs/agent-configuration/subagents)核对 name、description、developer_instructions 与 agents.enabled 字段。Python tomllib解析5文件成功，当前codex-cli 0.166.0的配置读取命令成功。本次已运行的子Agent通过会话实际协作工具创建，配置文件为后续项目会话使用，未声称已运行的Agent由新文件自动加载。

## 集成审查修复

Chief审查推动后端修复取消生成锁顺序、异常释放、EOF状态和学生限流不可信Header换桶风险；前端修复历史迟到响应覆盖新消息、退出后CRUD回填、快速切换与预流失败丢失问题。UI与开发未交叉写Vue，QA与开发使用独立文件边界。
