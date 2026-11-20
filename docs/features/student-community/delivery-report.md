# 学生社区交付报告

## 功能范围

学生端已实现分类筛选、标题与正文搜索、稳定分页、发布问题、问题详情、回答、收藏/取消收藏及本人提问/回答/收藏。管理端已实现待审核问题与回答列表、详情、通过/驳回及分类新增、修改、排序、启停。发布内容固定 PENDING，审核通过后公开。

## 实际协作

本次实际启动 Backend、Frontend、UI/UX、QA 四个专业子 Agent。运行上限为 Chief 加三名子 Agent，UI/UX 交付后释放槽位运行 QA。Backend 独占社区 Java、认证管理员适配、迁移及迁移测试。Frontend 独占 frontend。UI/UX 负责设计文档。QA 负责独立集成测试和真实验收脚本。Chief 负责契约、集成、构建、文档及缺陷调度。既有角色配置针对 Agent 对话，本轮按相同职责边界限定社区任务。

## 核心变更目录

```text
src/main/java/com/example/wjyaiagent/
  community/
    controller/CommunityController.java
    controller/CommunityAdminController.java
    controller/CommunityExceptionHandler.java
    dto/CommunityDtos.java
    repository/CommunityRepository.java
    service/CommunityService.java
    service/CommunityException.java
  auth/{service/AuthService.java,security/SecurityConfig.java,security/StudentSessionFilter.java}
  user/{entity/User.java,repository/UserRepository.java,development/DevelopmentAdminInitializer.java}
src/main/resources/db/migration/
  V003__student_community.sql
  V004__community_rejection_reason.sql
src/test/java/com/example/wjyaiagent/
  community/CommunityIntegrationTest.java
  auth/UserMigrationTest.java
frontend/src/
  api/community.js
  composables/useCommunityPage.js
  components/community/{AdminNav,PageFeedback,QuestionList}.vue
  views/community/{HomeView,QuestionCreateView,QuestionDetailView}.vue
  views/profile/ProfileView.vue
  views/admin/{ReviewView,CategoryManageView}.vue
  views/ForbiddenView.vue
  layouts/StudentLayout.vue
  router/index.js
  assets/styles/community.css
frontend/tests/community.test.js
scripts/community-live-check.py
scripts/community-browser-check.cjs
```

页面通过 community API 与共享请求层连接，Controller 进行身份提取和校验，Service 执行业务与事务，Repository 负责参数化 JDBC。社区仅复用身份，AI 与知识库保持各自业务边界。问题和回答审核页共用 ReviewView，以路由 meta 决定审核资源。

其他增量文件包括登录页统一身份文案、前端架构与认证文档、根 AGENTS.md 及本目录五份规范/测试文档。工作区原有未提交内容保留。本轮没有 Git 提交或推送。

## 数据库与一致性

实际新增 public.community_category、community_question、community_answer、community_favorite 四表。用户外键复用 community_users BIGINT。分类名唯一，收藏(user_id,question_id)唯一。问题和回答具备状态、审核人、审核时间和审核原因约束。索引支持状态时间、分类状态时间、本人时间及问题回答读取。

发布问题与分类停用共同锁定分类行，回答/收藏校验已通过问题并锁定问题行。审核使用 WHERE status='PENDING' 条件更新，重复或并发失败返回409。收藏使用 ON CONFLICT DO NOTHING，并按当前身份删除。

开发中22:16启动的外部 IDEA 后端已自动执行早期 V003，后续完善审核约束时导致校验和差异。已恢复 V003 为数据库记录的校验值 -166552946，将驳回原因非空增强放入 V004。迁移历史保持原记录，修正采用新版本。

## API 与页面

完整 DTO、路径及权限见 [接口契约](03-api-contract.md)。所有社区读取要求登录，STUDENT/ADMIN 都可操作自己的社区数据。/admin 仅 ADMIN，/agent 仍仅 STUDENT。写入要求 CSRF。主要成功状态为200/201/204，参数错误400、匿名401、权限/CSRF403、不可见404、并发状态/分类冲突409、数据库不可用503。

| 页面路由 | 功能 |
| --- | --- |
| /community | 分类、搜索、列表与分页 |
| /community/questions/new | 发布待审核问题 |
| /community/questions/:id | 详情、公开回答、提交回答、收藏 |
| /profile | 本人提问、回答、收藏及审核反馈 |
| /admin/questions | 问题审核 |
| /admin/answers | 回答审核 |
| /admin/categories | 分类管理 |
| /forbidden | 保留身份的权限不足反馈 |

## 本地验收

先核对本地配置指向安全开发库，再启动后端。以下启动保留真实 RAG 检索、关闭本次 ETL 与向量建表。

```powershell
$env:STUDENT_COOKIE_SECURE='false'
./mvnw.cmd -q package
java -jar target/wjy-ai-agent-0.0.1-SNAPSHOT.jar --server.port=18126 --spring.profiles.active=local,pgvector --knowledge-base.rag.etl.enabled=false --spring.ai.vectorstore.pgvector.initialize-schema=false
```

另一个终端启动正式前端。

```powershell
$env:API_PROXY_TARGET='http://127.0.0.1:18126'
npm --prefix frontend run dev -- --host 127.0.0.1 --port 5176
```

管理员需通过显式开发初始化创建，自选账号和密码仅用于当前进程。

```powershell
$adminCredential = Get-Credential -Message '输入独立开发管理员账号与密码'
$env:DEV_ADMIN_USERNAME = $adminCredential.UserName
$env:DEV_ADMIN_PASSWORD = $adminCredential.GetNetworkCredential().Password
try {
    java -jar target/wjy-ai-agent-0.0.1-SNAPSHOT.jar --server.port=18126 --spring.profiles.active=local,pgvector,community-admin-dev --knowledge-base.rag.etl.enabled=false --spring.ai.vectorstore.pgvector.initialize-schema=false
} finally {
    Remove-Item Env:DEV_ADMIN_USERNAME,Env:DEV_ADMIN_PASSWORD -ErrorAction SilentlyContinue
}
```

同名账号保留已有角色，冲突时选独立账号。正式环境通过受控运维授权 ADMIN。开发初始化只在显式 profile 启用。

学生访问 http://127.0.0.1:5176/register 注册，再登录发布问题。管理员通过同一 /login 登录，进入 /admin/categories 建立启用分类，在 /admin/questions 审核。学生查看已通过问题，提交回答并收藏。管理员在 /admin/answers 审核回答。个人中心展示本人待审核、通过、驳回记录。

## 验证记录

实际测试、数据库证据、浏览器结果和剩余验证边界见 [验收报告](05-test-report.md)。最终验证完成后由该报告记录准确数量与结果。

## 最终交付状态

| 状态 | 范围 |
| --- | --- |
| 已实现并验证 | 问题分类/搜索/分页、发布/详情、回答双审核、收藏、个人记录、三身份权限、CSRF、并发状态与真实PG持久化、桌面浏览器闭环、35组响应式布局、ADMIN初始化、Agent普通与SSE真实模型/RAG回归 |
| 已实现但未独立验收 | 生产HTTPS Cookie部署、移动触屏完整点击、社区跨进程重启后的UI恢复 |
| 未实现 | 本次范围内功能无遗漏 |

全量JUnit35项通过，最新社区4项补跑通过，前端14项通过，最终生产构建通过。真实PG社区67项通过，浏览器7组流程与35视口组合通过。模拟503重试反馈与模拟已通过状态提示分别标注。当前受测范围无未解决P0/P1。

管理审核覆盖层缺失与已通过问题仍显示待审核提示均已修复。桌面与375px截图已由Chief目视复核。验收后端18126与前端5176保留运行，用户原8123与5173进程保留。测试数据以communityqa_及agentqa_前缀留在开发库供复查，随机凭据只用于运行进程。
