# 统一前端架构

## Agent 模块增量

正式 Agent 入口现为 `/agent/chat`，通过 StudentLayout 顶部导航访问，沿用 requiresAuth、STUDENT/ADMIN 权限、auth Store 与动态 CSRF。`src/api/agent.js` 调用 `/api/agent/conversations`，`composables/useAgentStream.js` 解析 POST SSE，`stores/agent.js` 管理对话与异步隔离，`views/agent/AgentChatView.vue` 和 `components/agent/` 实现聊天。Markdown 使用 markdown-it（关闭原生HTML）、DOMPurify 与 highlight.js。身份清除同步丢弃聊天内存，不写入浏览器持久存储。

接口、UI、实施及真实验收分别见 [Agent模块文档](features/agent-chat/requirements.md)、[接口契约](features/agent-chat/api-contract.md)、[UI规范](features/agent-chat/ui-design.md)和[验收报告](features/agent-chat/test-report.md)。后文“未来Agent页面”和首次Vue验证记录描述此前阶段，当前以本节及Agent模块文档为准。

## 技术栈与入口

正式前端位于 frontend/，以 Vue 3 Composition API 和 script setup 编写 JavaScript ES Modules。使用 Vite、Element Plus、Vue Router、Pinia、Axios，安装结果通过 package-lock.json 锁定。当前锁定 Vue 3.5.45、Vite 7.3.8、Element Plus 2.14.8、Vue Router 4.6.5、Pinia 3.0.4、Axios 1.21.1 及 plugin-vue 6.0.11。Node使用20.19+或22.12+，本次验证环境为24.18。

正式认证入口为/login和/register，登录落地/community。原 /api/login.html、/api/community.html、/api/index.html 作为回退入口继续由后端提供。

## 目录和职责

```text
frontend/
├── public/                         按需放置原样发布的静态资源
├── src/
│   ├── api/
│   │   ├── request.js              Axios、CSRF及HTTP错误
│   │   └── auth.js                 认证接口及UserInfo契约
│   ├── assets/styles/
│   │   ├── variables.css          共享设计与Element Plus变量
│   │   └── global.css             全局基础样式
│   ├── components/PasswordInput.vue 密码显隐复用组件
│   ├── layouts/
│   │   ├── AuthLayout.vue         登录分栏及品牌区域
│   │   └── StudentLayout.vue      学生Header、身份、退出及会话刷新
│   ├── router/index.js            路由、meta权限及安全回跳
│   ├── stores/auth.js             内存身份状态及认证操作
│   ├── views/
│   │   ├── auth/LoginView.vue     登录表单交互
│   │   ├── auth/RegisterView.vue  学生注册表单
│   │   ├── community/HomeView.vue 学生社区最小落地页
│   │   ├── agent/                未来Agent页面
│   │   └── admin/                未来管理页面
│   ├── App.vue                    路由出口
│   └── main.js                    应用装配与中文组件配置
├── tests/                         身份并发与回跳安全测试
├── .gitignore
├── index.html
├── vite.config.js
├── package.json
└── package-lock.json
```

public、agent和admin目前是预留目录，Git只记录其中有实际内容的文件。后续首次开发时按领域创建文件。社区页面只展示身份与内容占位。跨页面整体结构由layouts负责，页面组件不重复实现Header。PasswordInput用于登录、注册及确认密码，auth-form.css统一认证卡片样式。

## API与错误规范

全部后端请求经src/api发起，页面通过store或领域API调用。request实例固定baseURL=/api、withCredentials=true、超时15秒。Vite代理保留/api前缀，与后端server.servlet.context-path匹配。API_PROXY_TARGET是Vite配置端变量，默认http://localhost:8123，不进入浏览器构建产物。禁止在VITE_变量中保存密码、模型Key或认证密钥。

实际接口契约来自现有AuthController、AuthService、SecurityConfig、StudentSessionFilter、LoginRequest和AuthErrorWriter。

| 接口 | 请求及响应 |
| --- | --- |
| GET /api/auth/csrf | 返回headerName、token |
| POST /api/auth/register | 请求username、password、confirmPassword，201返回id、username、role，随后主动登录 |
| POST /api/auth/login | 请求username、password，返回id、username、role，Cookie下发会话 |
| GET /api/auth/me | 返回id、username、role，未认证返回401 |
| POST /api/auth/logout | 成功204，立即销毁当前会话 |

每次写请求前获取CSRF，使用服务端返回的headerName提交token。并发CSRF获取共享一个Promise，完成后释放，后续写请求重新获取，以适应登录轮换及跨标签页会话变化。CSRF只保留于当前请求内存，失败后由用户重试，客户端不自动重放写请求。

错误转换为包含message、status、code的最小Error，避免把Axios请求配置和密码传到UI。登录401显示后端统一错误，覆盖错误账号、错误密码、禁用账号。其他接口401清理Pinia身份并将受保护页面送回登录。403展示权限或安全校验反馈，保留当前登录状态。429展示限流提示，网络和超时提供可重试提示。后台保留原有CSRF与限流规则。

## 身份状态

Pinia auth管理user、isAuthenticated、initialized、initializationError、loggingIn和loggingOut。提供login、fetchCurrentUser、logout及clear。服务器会话为身份来源。初始化/me请求共享Promise，初始化成功后路由复用结果，页面刷新重新查询。generation防止退出后迟到的身份响应重新填充用户。

认证凭证由HttpOnly Cookie维护。浏览器本地持久存储无需保存账号、密码、会话ID或用户信息。学生布局每60秒检查身份，并在窗口重新聚焦或可见时检查。401立即清理状态并回跳，网络与403错误提供提示。退出以服务端204为成功依据，401视为会话已经失效。

登录输入使用Element Plus Form校验，密码按后端UTF-8最多72字节校验。提交前锁定表单，避免校验期间重复提交。密码显隐使用带中文无障碍标签、aria-pressed和44px点击区域的键盘可访问按钮。

## 路由与权限

当前路由为/login、/register和/community。注册页同样声明guestOnly。guestOnly控制登录页，requiresAuth和roles控制受保护页面。首页导航至社区，未知路径归入已定义导航。已登录访问登录页返回社区，未登录访问社区保存原fullPath并送往登录。

登录后调用safeReturnPath，只接收已注册、带requiresAuth标记的本地路径，保留query与hash。外部URL、协议相对路径、反斜线、空白字符、未知页、登录页及数组值统一回到/community，避免开放式重定向。权限由后端最终执行。

## 视觉与组件

共享颜色为主色#2563EB、Hover#1D4ED8、背景#F8FAFC、文字#0F172A、次要文字#64748B、边框#E2E8F0和白色卡片。variables.css同时定义Element Plus主题变量，各业务区域统一复用。global.css维护基础字体、焦点反馈及基础控件样式，页面使用scoped CSS控制自身布局。

字体采用Inter及系统中文字体回退，无外部字体下载依赖。Element Plus采用显式组件及样式导入，按路由懒加载。AuthLayout使用轻量渐变及CSS知识卡片视觉元素，移动端简化介绍区，保留表单。后续Agent和管理端可使用不同布局，并沿用同一组设计变量。

## 未来模块扩展

学生业务页面加入views/community，接口加入api下对应领域模块。跨页面业务状态确有必要时才建立store。Agent页面加入views/agent，保留其API Key、限流及接口结构，和社区业务数据独立。管理页面加入views/admin，路由声明管理员角色，配合真实后端管理权限实现。公共组件只在真实复用时提取。

当前AuthService支持有效STUDENT与ADMIN通过同一入口登录，管理员使用独立后台页面和ADMIN后端权限。

## 本地启动

先按docs/student-login.md配置开发业务库及测试学生账号，启动已有Spring Boot。纯HTTP开发需要STUDENT_COOKIE_SECURE=false，生产HTTPS保留后端默认Secure Cookie。

项目根目录执行已有开发启动脚本可初始化自选账号。此脚本显式开启student-dev，并为该次开发运行关闭RAG初始化。其他后端开发启动方式沿用项目现有配置。

```powershell
./scripts/start-student-dev.ps1
```

另开终端启动前端。

```powershell
cd frontend
npm install
npm run dev
```

访问 http://localhost:5173/login。默认代理到8123端口。若使用其他本地后端，启动Vite前设置环境变量。

```powershell
$env:API_PROXY_TARGET='http://localhost:18123'
npm run dev
```

```powershell
npm test
npm run build
npm run preview
```

preview在4173端口提供构建产物联调预览。开发时保持同一浏览器hostname，所有认证请求经/api代理。

## 生产部署

npm ci安装lockfile依赖，npm run build产出frontend/dist。将dist内容发布到现有站点的静态资源根目录，以/作为应用base。现有部署层将/api转发到Spring Boot，将前端路由回退到dist/index.html。/api应优先交给后端，保留其404及认证错误。后端原生页面仍通过/api下原地址访问。

生产采用同源HTTPS，使Cookie的/api路径及Secure属性生效。Vite Proxy只服务开发和本地预览，生产需由已有部署层完成API转发与前端history回退。当前提交不增加部署服务，也不自动复制dist覆盖后端静态目录。

## Vue架构首次实现阶段的验证结果

- npm install成功，安装审计报告0漏洞。依赖lockfile已生成。
- npm test通过4项测试，覆盖身份请求去重、初始化缓存、迟到响应、401与403处理及安全回跳。
- npm run build成功，4173端口的构建产物也通过实际登录、刷新恢复及退出浏览器联调。
- ./mvnw.cmd -q test通过15项测试，包含现有AI过滤器、聊天记忆、文档加载及认证测试。
- 真实浏览器通过Vite代理联调现有Spring Boot，使用独立H2数据库及临时学生账号。已验证正确账号、错误密码、禁用账号、表单必填、Enter提交、密码显隐、连续Enter只提交一次、Cookie、CSRF、刷新恢复、原始目标路径、外部回跳拒绝、已登录访问登录页、退出及匿名跳转。
- 刷新只触发一次/me，Cookie确认HttpOnly、SameSite=Lax、Path=/api。localStorage与sessionStorage均无认证数据。
- 无CSRF的实际退出请求返回403，随后/me仍为200，带正确CSRF的退出成功。另以模拟/me的403验证页面保留身份并显示反馈。
- 浏览器清除Cookie后通过真实/me的401验证会话失效回跳，后端集成测试覆盖绝对到期拒绝。未在浏览器实际等待30分钟。
- 1920、1366、768、390、375px宽度均无横向溢出，桌面为双栏，平板与手机为单列。检查了桌面与手机截图。
- 受测新前端没有JavaScript pageerror或失败的网络连接。匿名身份探测、错误凭据、CSRF拒绝及模拟403产生预期的401/403网络记录，页面已处理。浏览器仍会将HTTP错误状态显示在开发者工具中。
- 首次Vue升级阶段后端改动为0。后续注册任务已完成真实PostgreSQL迁移与登录联调，详见学生认证文档。真实模型和pgvector请求仍待AI环境验收。

## 参考

组件表单与代理配置遵循官方接口说明。[Element Plus Form](https://element-plus.org/en-US/component/form)、[Vite Server Options](https://vite.dev/config/server-options)。

## 注册功能验收

注册页沿用现有布局与Element Plus表单，页面本地管理注册过程，身份继续由auth Store管理。真实PostgreSQL迁移及注册登录重启联调结果见[学生认证文档](student-login.md)。

## 学生社区增量

正式社区入口 /community，发布与详情位于 /community/questions/new 和 /community/questions/:id，个人记录位于 /profile。管理端 /admin/questions、/admin/answers、/admin/categories 复用 StudentLayout 和同一 Session 登录。社区与个人页面允许 STUDENT、ADMIN，Agent 允许 STUDENT 与 ADMIN。角色拒绝展示反馈并保留有效身份。

社区 API 使用 api/community.js，正文通过 Vue 文本插值渲染。分页从 1 开始，接口及状态边界见 [社区契约](features/student-community/03-api-contract.md)，界面交互见 [社区 UI](features/student-community/02-ui-design.md)。

## 管理员完整后台与统一UI

管理路由采用独立AdminLayout，/admin重定向/admin/dashboard，六业务入口为dashboard、questions、answers、users、categories、operation-logs。现有ReviewView升级为全部内容管理并保留审核动作，业务请求集中api/admin.js。后台与学生布局复用身份恢复和退出生命周期。身份安全与请求层沿用现有Session和CSRF。

全局Design Tokens集中variables.css，覆盖字体、字号、间距、圆角、阴影、密度及内容宽度，Element Plus变量同步映射。社区改进主列表/辅助入口、编辑与阅读区、资料侧栏，AI工作台优化历史区、输入与代码/引用呈现，认证页统一品牌与表单布局。接口与视觉规范见 [管理接口](features/admin-management/03-api-contract.md) 和 [设计契约](features/admin-management/02-ui-design.md)。


全系统视觉和交互的当前统一入口为features/admin-management/02-ui-design.md，学生社区与Agent旧设计文档作为对应业务历史记录。管理分类仍复用数组接口，筛选后由前端按10条本地分页。

## 统一入口与角色登录落地

浏览器统一从同一前端地址访问/login、/community、/admin/dashboard和/agent/chat。当前统一入口为http://localhost:5173，兼容入口http://127.0.0.1:5180，两个入口的/api均同源代理到8123。历史5178、18130等地址属于独立验收实例。

登录成功、已登录访问登录/注册页、访问根路径时，ADMIN默认进入/admin/dashboard，STUDENT默认进入/community。safeReturnPath在本地受保护路由校验后，再校验当前服务器身份角色是否允许访问目标。允许的原目标保留，非法或无权限目标回退到角色首页。直接访问无权限路由继续展示权限不足页面。

## 管理员AI入口

ADMIN与STUDENT共用/agent/chat及原有Agent API。管理后台侧栏新增AI智能问答入口，进入共享工作台后可通过顶部社区管理返回后台。管理员登录默认首页仍为/admin/dashboard，显式AI回跳目标现在允许恢复。所有Agent会话继续按当前用户ID归属验证，ADMIN身份不授予其他用户会话读取权限。
