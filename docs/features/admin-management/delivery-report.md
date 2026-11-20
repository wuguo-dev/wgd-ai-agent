# 管理员后台与全系统UI交付报告

## 2026-11-19 当前增量交付

本轮以已完成的管理员业务、V005及统一UI为基线，实际修复同筛选重查、末页回退、用户详情失败操作门控、图标导航可访问名、列表加载高度和分类本地分页。对应10个前端文件及新增回归用例，原业务接口与数据库结构继续复用。

新增AdminAuditRollbackTest.java，两项H2故障注入验证审核与学生禁用在审计INSERT失败时整事务回滚，解除故障后重试仅记录一条成功日志。全量41项JUnit与package通过，前端20项及生产build通过。真实PG与浏览器本轮复核记录追加在05测试报告。

本轮验收地址为后端18130、前端5180。后端可按原命令将server.port设为18130，前端API_PROXY_TARGET设为http://127.0.0.1:18130，Vite端口5180。下方前轮文件目录和功能说明保留用于追溯。

创建自选开发管理员可在结束占用该端口的后端后运行以下命令。

```powershell
./scripts/start-admin-dev.ps1 -Port 18130
```

脚本通过凭据窗口读取用户名与密码，沿用现有community-admin-dev初始化，账号写入当前开发数据库，已有同名账号保留原角色及密码。启动脚本以模拟Maven的成功/失败路径验证环境变量与工作目录恢复，真实初始化能力沿用此前已验收实现。本轮无新增数据库迁移，V001..V005保持执行历史与CRC。

本轮新增文件为scripts/start-admin-dev.ps1、src/test/java/com/example/wjyaiagent/community/AdminAuditRollbackTest.java及QA交互专项验收脚本。前端修改PageFeedback.vue、useCommunityPage.js、ReviewView.vue、UserManageView.vue、CategoryManageView.vue、OperationLogView.vue、HomeView.vue、AdminLayout.vue、StudentLayout.vue和tests/community.test.js。

## 实现范围与协作

本轮复用既有 Backend、Frontend、UI/UX、QA 四个专业子 Agent。UI/UX 完成设计契约后释放并发槽位供 QA，主 Agent 负责契约冻结、代码集成、独立构建、迁移启动、结果复核及文档。所有 Agent 在共享工作区按独占文件责任实施。

Backend 负责 Java 管理业务、社区可见性、事务审计、V005及迁移测试。Frontend 负责 frontend 全部实际页面与样式。UI/UX 负责02-ui-design，QA负责独立集成测试、真实验收脚本和05-test-report。主 Agent保留本轮开始前全部工作区修改，按本轮范围增量开发。

## 核心目录与文件

```text
src/main/java/com/example/wjyaiagent/
  admin/
    controller/AdminManagementController.java
    dto/AdminDtos.java
    repository/AdminRepository.java
    service/AdminService.java
  common/request/RequestValidation.java
  community/
    controller/{CommunityAdminController,CommunityExceptionHandler}.java
    dto/CommunityDtos.java
    repository/CommunityRepository.java
    service/CommunityService.java
src/main/resources/db/migration/V005__admin_management.sql
src/test/java/com/example/wjyaiagent/
  community/{AdminManagementIntegrationTest,CommunityIntegrationTest}.java
  auth/UserMigrationTest.java
frontend/src/
  api/admin.js
  layouts/{AdminLayout,StudentLayout,AuthLayout}.vue
  composables/useSessionLifecycle.js
  views/admin/{DashboardView,ReviewView,UserManageView,CategoryManageView,OperationLogView}.vue
  views/community/{HomeView,QuestionCreateView,QuestionDetailView}.vue
  views/profile/ProfileView.vue
  views/agent/AgentChatView.vue
  components/agent/{ConversationSidebar,ChatMessage,CitationPanel}.vue
  assets/styles/{variables,global,community,admin,auth-form}.css
  router/index.js
frontend/tests/admin.test.js
scripts/admin-live-check.py
scripts/admin-browser-check.cjs
```

管理问题与回答共用ReviewView，以路由meta决定资源类型。既有CommunityAdminController扩展内容管理及审核分类操作，新增AdminManagementController提供用户、统计、日志，避免重复审核服务。AdminRepository使用用户安全投影、真实统计及追加审计，CommunityRepository保留社区持久化。两个Service共用RequestValidation，校验依赖集中。

## 删除、恢复与关联可见性

问题和回答的删除字段与原审核状态独立。管理员删除必填原因，成功写入deleted_at/by/reason并追加审计。重复删除、恢复、审核或用户重复启停返回409，失败事务回滚。恢复清除删除标记并保留原审核状态及分类。

学生所有问题、搜索、详情、本人提问、本人回答、收藏及公开回答入口过滤删除内容，回答同时过滤父问题删除。删除问题保留关联回答与收藏，禁止新增回答/收藏。恢复回答时即使父问题仍删除，公共读取仍隐藏该回答。

问题操作锁question，回答操作先锁question再answer。条件更新与统一锁顺序保证删除、审核和恢复并发时状态及成功日志一致。管理员通过专用详情接口查看删除内容、原因和审计记录。

## 用户、统计与审计

用户接口仅返回id、username、role、status、createdAt、updatedAt。账号启停仅允许普通STUDENT，后端拒绝对ADMIN和本人操作，注册角色保持固定。禁用后已有Session下次受保护请求401，启用后重新登录建立身份。

统计一次数据库查询返回九项指标。学生总数仅STUDENT，内容总数包含删除记录及删除问题下的回答。待审核问题排除自身删除，待审核回答排除自身和父问题删除且父问题APPROVED。删除回答数仅计回答自身删除。界面展示五主卡、四辅助卡及真实待办入口。

admin_operation_log记录14类审核、删除、恢复、学生启停及分类操作。成功审计与业务同事务提交，before/after使用明确安全状态DTO，无密码或密钥。日志仅ADMIN读取，支持操作人、类型、时间范围与分页，管理API不提供修改或删除日志。

## 数据库迁移

V005为现有问题/回答增加三字段删除元数据及组合CHECK、用户外键和删除查询索引，新增admin_operation_log与时间/操作人/类型/目标索引。V001至V004保留已执行内容和校验值。执行前核对开发库、Flyway与向量基线，实际结果见验收报告。

## 实际UI升级

| 区域 | 实际改动 |
| --- | --- |
| 全局 | 集中颜色、字体字号、间距、圆角、阴影、控件高度、表格行高及内容宽度，映射Element Plus变量 |
| AdminLayout | 独立可折叠侧栏、六业务菜单、顶栏面包屑、头像账号菜单、移动Drawer与焦点恢复 |
| 控制台 | 九项真实指标、语义图标、待办快捷入口、加载失败反馈 |
| 问题/回答管理 | 全部内容筛选、排序、分页、详情、原有审核、删除原因二次确认与恢复 |
| 用户管理 | 搜索、角色/状态筛选、详情、学生启停确认及状态反馈 |
| 分类/日志 | 分类筛选编辑及启停确认，日志类型/操作人/时间查询与安全详情 |
| 社区 | 主问题列表与辅助入口、检索分类层级、发布编辑与说明分区、详情阅读区、个人资料侧栏与记录页签 |
| AI | 历史侧栏、学习问题空态卡片、消息与输入容器、代码语言标签/高亮/复制及独立来源区域，手机历史Drawer |
| 登录注册 | 统一品牌、真实功能介绍、表单密度、错误与加载反馈、移动布局 |

保留认证请求、Session、CSRF与Agent状态/SSE流程，模型配置和知识库业务沿用现有实现。具体六视口规则见02-ui-design。

## 本地启动与验收

先核对本地配置指向开发库，再执行以下命令。首次启动由Flyway事务执行V005。HTTP开发关闭Secure Cookie，生产HTTPS使用原默认配置。

```powershell
./mvnw.cmd -q package
$env:STUDENT_COOKIE_SECURE='false'
java -jar target/wjy-ai-agent-0.0.1-SNAPSHOT.jar --server.port=18128 --spring.profiles.active=local,pgvector --knowledge-base.rag.etl.enabled=false --spring.ai.vectorstore.pgvector.initialize-schema=false
```

另一个终端启动正式前端。

```powershell
$env:API_PROXY_TARGET='http://127.0.0.1:18128'
npm --prefix frontend run dev -- --host 127.0.0.1 --port 5178
```

管理员沿用现有ADMIN账号，同一/login入口。开发初始化可使用既有community-admin-dev profile与DEV_ADMIN_USERNAME、DEV_ADMIN_PASSWORD自选环境凭据，方法见学生社区交付报告。通过/admin/dashboard进入，依次检查问题、回答、用户、分类和日志。学生通过/community发布、回答、收藏，配合管理员审核、删除、恢复和启停验证。测试账号凭据仅运行时使用。

## 实际验证

最终测试数量、真实PostgreSQL/模型与模拟故障区分、六视口截图和剩余验收边界统一记录在 [测试报告](05-test-report.md)。接口和DTO见 [API契约](03-api-contract.md)。

## 本轮前端文件变更

新增8个文件为api/admin.js、composables/useSessionLifecycle.js、layouts/AdminLayout.vue、assets/styles/admin.css、views/admin/DashboardView.vue、UserManageView.vue、OperationLogView.vue及tests/admin.test.js。

修改20个文件为api/community.js、router/index.js、layouts/StudentLayout.vue、AuthLayout.vue、assets/styles/variables.css、global.css、auth-form.css、community.css、views/admin/ReviewView.vue、CategoryManageView.vue、views/community/HomeView.vue、QuestionCreateView.vue、QuestionDetailView.vue、views/profile/ProfileView.vue、views/agent/AgentChatView.vue、components/agent/ChatMessage.vue、CitationPanel.vue、ConversationSidebar.vue、views/auth/LoginView.vue、RegisterView.vue。其中global.css与CitationPanel.vue仅多行格式整理，独立来源功能继续复用原实现。

移除原components/community/AdminNav.vue，导航职责统一由AdminLayout承载。package.json和package-lock.json保留原依赖，统计图标复用已有锁定的官方图标。

新增四个可复用验收脚本为scripts/admin-live-check.py、admin-browser-check.cjs、admin-ai-normal-check.py、admin-agent-browser-check.cjs。

## 最终验收状态

| 范围 | 状态 |
| --- | --- |
| 管理业务与权限、逻辑删除恢复、用户启停、九统计、十四类审计 | 已实现并通过真实PG100项检查 |
| 六后台页面、社区、AI及认证UI | 已实现并通过78页面/视口组合检查，桌面和375px实际业务操作通过 |
| AI工作台流式/停止/历史/复制/手机输入与Drawer | 真实与明确标记的模拟回归通过 |
| Maven、前端测试与生产构建 | 全量39项JUnit、18项前端测试及构建通过，新测试格式化后另补跑4项通过 |
| 生产HTTPS与实机触屏软键盘 | 环境专项验收待执行 |

UI/UX与Chief实际复核桌面及手机截图。375px主统计采用双列、辅助与待办单列，已按实际可读性结果更新设计契约。后台、前端当前分别保留18128和5178运行，原用户IDE8123和前端5173保留。

本轮发生的打包文件锁由清理Chief旧验收进程并复制运行jar解决，最终完整打包成功。统计首次SQL比对与其他写测试并发引起时点差异，串行复测100项通过。当前受测范围无未解决P0/P1。

V005成功且旧迁移CRC一致，向量仍47条且指纹一致。测试账号以adminqa_/adminui_/agentqa_/agentbrowser_前缀标识并保留开发库审计，凭据仅在执行进程使用。本轮未提交或推送Git。

最后一轮事实文案收敛后的登录、注册375/1366真实截图及社区375/1366模拟内容布局截图已复核，具体性质与路径见05测试报告。原真实业务与78组合验收结果继续保留。


本轮最终真实验收完成，PG100项、交互专项26项、78页面视口组合通过，真实普通AI与SSE/RAG/历史隔离回归、聊天浏览器操作通过。专项脚本为scripts/admin-interaction-check.cjs，详情503为明确模拟，其余专项操作连接真实开发后端。AI抽屉滚动锁检查已等待组件实际解锁，输入可达、无可见遮罩，未改AI生产代码。

本轮受测无未解决P0/P1/P2。生产HTTPS与真实移动设备软键盘仍为环境验收边界。复查结果见05测试报告2026-11-19章节，证据位于target/admin-1119-*。本轮开发库保留可识别QA记录与审计，无新迁移，无Git提交推送。
