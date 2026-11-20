# 项目开发规范

## 开始开发

修改前检查当前源码和工作区差异，复用已有实现，保留任务范围以外的用户修改。涉及前端架构、页面、API、认证、路由或主题时，先阅读 docs/frontend-architecture.md。涉及认证后端时，同时阅读 docs/student-login.md 并核对实际Java接口。

## 统一前端

- 正式前端位于 frontend/，学生社区、Agent与管理员区域在同一工程内按业务隔离。
- 使用 Vue 3 Composition API、script setup、Vite、Element Plus、Vue Router、Pinia、Axios及JavaScript ES Modules。依赖由package-lock.json锁定。
- 页面放在src/views对应业务目录，接口请求放在src/api，跨页面身份状态放在src/stores，路由权限放在src/router，布局放在src/layouts。
- App.vue承载路由出口。公共组件仅在真实复用时提取。未来模块按实际需求创建文件。
- 通过src/assets/styles/variables.css维护共享主题及Element Plus变量。页面只维护自身布局样式。
- 登录沿用HttpOnly Cookie Session和CSRF。身份通过服务器恢复，敏感凭据只用于当前请求，禁止将其写入浏览器持久存储或日志。
- 权限使用route.meta，回跳目标通过safeReturnPath校验。401处理身份失效，403呈现权限或安全校验反馈。

## 业务边界

保留Spring Boot 3.4.4、Java 21、根包com.example.wjyaiagent和现有认证体系。后端为权限执行边界。Agent与社区数据各自独立。已有AI页面、ApiKeyFilter、RateLimitFilter、RAG、向量表、Prompt和模型配置的变更需要相应任务明确授权。

现有原生HTML是回退入口。正式前端开发统一进入frontend/。数据库变更遵循版本化迁移，保留现有数据。

## 验证与交付

前端变更执行 npm --prefix frontend test 和 npm --prefix frontend run build，检查登录、CSRF、会话恢复及失效、退出和375至1920px响应式布局。涉及共享行为时运行 ./mvnw.cmd test。准确区分真实后端联调、模拟测试和未验证事项。仅在用户明确要求时提交Git或推送。

## 学生社区

涉及社区问题、回答、分类、收藏或审核时，先阅读 docs/features/student-community/03-api-contract.md，并同步相关验收记录。社区服务仅依赖学生身份与自身业务表，社区内容保持在社区业务边界内。

问题与回答由服务端固定创建为 PENDING，公共读取限制 APPROVED，本人记录通过 Session 身份筛选。管理员审核仅转换 PENDING，并持久化审核人和时间。分类发布与启停在事务内协调，收藏依赖用户与问题联合唯一约束实现幂等。

管理员沿用同一登录与实时角色校验。开发管理员使用显式 community-admin-dev 初始化和自选环境凭据，公开注册固定 STUDENT。Agent 路由允许有效 STUDENT 与 ADMIN，所有会话仍按当前用户ID隔离。

## 管理员后台

涉及内容删除恢复、学生启停、后台统计或操作审计时，先阅读 docs/features/admin-management/03-api-contract.md。业务变更和成功审计在同一事务提交，删除标记与审核状态独立。学生所有读取入口统一过滤自身删除和父问题删除，恢复保留原审核状态。账号管理仅允许 STUDENT，复用实时Session校验。

全系统UI遵循 docs/features/admin-management/02-ui-design.md，主题Token集中在variables.css，管理页面共用AdminLayout。Element Plus浮层采用官方样式入口以加载完整依赖，验收覆盖375、390、768、1024、1366、1920px、抽屉滚动恢复与键盘焦点。

## 本轮交互维护规则

管理与社区列表需要支持相同筛选再次查询，并在结果页数收缩后回到有效页。用户详情加载失败时先完成详情重试再开放状态操作。管理列表加载区域保留必要高度，图标导航和头像按钮具备可访问名称。分类数组接口在前端进行筛选后分页。
