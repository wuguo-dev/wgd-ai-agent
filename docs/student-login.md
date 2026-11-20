# 学生登录模块

## Agent 集成增量

学生安全链现同时覆盖 `/agent/**`，沿用相同Session、STUDENT角色、实时身份校验与CSRF；正式Vue入口为 `/agent/chat`。浏览器调用 `/api/agent/conversations`，旧 `/api/ai/**` 保留API Key入口。Agent会话通过独立V002表与user_id绑定，不读取社区业务内容。完整接口、安全边界及真实验收见 [Agent交付报告](features/agent-chat/delivery-report.md) 和 [验收报告](features/agent-chat/test-report.md)。下文“仅匹配/auth、/community、/admin”描述认证首次建设阶段，以本节为当前增量。

正式Vue前端入口及后续开发规范见 [统一前端架构](frontend-architecture.md)。本文中的原生页面继续作为回退入口。

## 现状与兼容性

项目沿用 Spring Boot 3.4.4、Java 21、Spring AI 原版本、JdbcTemplate 和原生静态 HTML。仓库及上级目录未发现适用 AGENTS.md。源码无已有用户实体、用户仓储或身份认证。已对配置的 PostgreSQL 执行只读表元数据检查，未发现名称包含 user、student、account、member 的业务表。未读取业务记录，未修改实际数据库。

原 index.html 是 AI 对话页面。新增 community.html 作为学生社区身份首页，只显示当前学生和退出入口。登录页为 /api/login.html。正式Vue前端现支持学生注册，社区落地页用于身份验证。

## 认证与安全边界

使用 Spring Security 6 的服务端 HttpSession，凭证为 STUDENT_SESSION HttpOnly Cookie，SameSite=Lax。默认要求 HTTPS，纯 HTTP 本地开发需设置 STUDENT_COOKIE_SECURE=false。认证凭证不返回 JSON，不写入 localStorage 或 sessionStorage。密码以 BCrypt cost 12 哈希存储，输入最大72个UTF-8字节。

会话空闲有效期30分钟，认证同时具有30分钟绝对有效期。每个受保护请求重新检查用户存在性、学生角色与 ACTIVE 状态。退出立即 invalidate 当前服务端会话并删除 Cookie。应用重启使内存会话失效。多实例部署需要粘性会话或后续共享会话存储。退出仅影响当前会话，其他设备的会话继续按有效期工作。

无 JWT，无签名密钥配置需求。禁用 Boot 默认内存用户自动配置，避免生成额外默认凭据。

Spring Security 链仅匹配 /auth/**、/community/**、/admin/**。注册、登录和 CSRF 获取匿名可访问，其他认证及社区接口要求 STUDENT，管理员路径要求 ADMIN。学生登录入口只接受有效学生账号。未认证返回401，认证后的权限或CSRF失败返回403。登录无效凭据、缺失用户、禁用用户使用相同401响应。表单验证返回400，登录限流返回429，数据库故障返回503。错误复用现有 ApiErrorResponse 的 code、message、chatId 结构。

现有 ApiKeyFilter 与 RateLimitFilter 保持原文件及执行顺序，分别为 HIGHEST_PRECEDENCE+10 和 +20，早于 Spring Security 的 -100。/ai/** 不进入新增会话认证链，继续执行原 API Key、限流和原接口。没有更改 Agent、RAG、向量表、ChatMemory、Advisor、Prompt 或模型配置。

登录与退出受 CSRF 保护。先 GET /api/auth/csrf 获取 headerName 和 token，再在 POST 请求头携带。登录成功会轮换会话及 CSRF，退出前应重新获取。前端自动处理。GET /api/auth/me 返回 id、username、role。POST /api/auth/logout 成功返回204。

登录按照服务端直接观察的客户端IP限制每10分钟10次尝试，包含成功尝试。内存表最多10000项，满时拒绝新增来源。限流状态在重启后清空。反向代理场景当前按代理连接地址计数，共享出口可能共享额度。此实现适用于当前单体部署。

Swagger UI 保持 /api/swagger-ui.html，可查看认证端点、Cookie方案和失败状态。CSRF请求头需通过客户端携带。

## 数据库迁移与开发账号

项目现已集成Spring Boot 3.4.4管理版本的Flyway Core和PostgreSQL支持模块，版本10.20.1。启动时自动检查并执行classpath:db/migration中的SQL。默认开启，可通过COMMUNITY_MIGRATIONS_ENABLED=false显式关闭。clean已禁用。

最初缺表的原因是项目只有V001 SQL文件，尚无Flyway依赖或自动迁移执行器，原部署也未手动执行SQL。修复前只读检查确认实际开发库可连接，当前用户拥有public的USAGE、CREATE及数据库CREATE权限，所有非系统Schema均无用户表和flyway_schema_history。用户确认该实例为允许迁移的开发库后才执行数据库写入。

实际实例为pgm-bp1i6noe7ngu0r67co.pg.rds.aliyuncs.com:5432，数据库wjy_agent，PostgreSQL 17.11，当前用户wgd_agent，search_path为"$user", public。学生业务表为public.community_users，迁移历史表为public.flyway_schema_history。RAG继续使用public.knowledge_base_vector_store，社区和RAG复用现有物理数据源、保持表及业务数据独立。

V001在确认无执行历史后，将用户表显式定位public，并交由Flyway管理事务。本次已在实际开发库成功执行版本0 baseline及V001，两个历史条目的success均为true。V001现在已有真实迁移记录，后续结构变更必须创建V002等新版本。

首次接管已有非空Schema需要显式开启baseline。默认COMMUNITY_BASELINE_ON_MIGRATE=false，baseline-version=0，保证V001仍会执行。仅在已确认目标环境和审查现有表后使用以下首次启动命令。日常启动使用默认配置，已有历史后不再需要baseline选项。

```powershell
$env:STUDENT_COOKIE_SECURE='false'
./mvnw.cmd spring-boot:run '-Dspring-boot.run.profiles=local,pgvector' '-Dspring-boot.run.arguments=--spring.flyway.baseline-on-migrate=true --knowledge-base.rag.enabled=false'
```

以上关闭RAG仅用于认证联调启动，避免该次启动触发向量初始化。正式AI启动配置沿用项目配置。空数据库Schema可直接正常启动自动执行迁移。

只读验收命令可在已安全配置连接环境的psql中执行。

```sql
SELECT current_database(), current_schema(), current_user;
SELECT installed_rank, version, type, script, success
FROM public.flyway_schema_history ORDER BY installed_rank;
SELECT column_name, data_type, character_maximum_length
FROM information_schema.columns
WHERE table_schema = 'public' AND table_name = 'community_users'
ORDER BY ordinal_position;
SELECT indexname, indexdef FROM pg_indexes
WHERE schemaname = 'public' AND tablename = 'community_users';
```

数据库已具备用户名唯一索引community_users_username_key、主键、60字符BCrypt字段、STUDENT/ADMIN角色约束、ACTIVE/DISABLED状态约束及创建更新时间。数据访问显式使用public.community_users，避免search_path漂移。

开发环境可继续运行scripts/start-student-dev.ps1，交互输入自选账号和密码。Flyway在初始化器之前执行，开发初始化仍采用ON CONFLICT DO NOTHING，保留已有同名账号。本次正式注册也依赖同一用户名唯一约束，初始化与注册相互兼容。

## 学生注册流程

正式Vue入口为/register，登录页提供立即注册链接。POST /api/auth/register请求三个字段，成功返回201及id、username、role，学生随后主动登录。

```json
{
  "username": "student001",
  "password": "用户自选密码",
  "confirmPassword": "与密码一致"
}
```

用户名为3至32位ASCII字母、数字或下划线，并以字母开头，区分大小写。密码至少6个字符，最多72个UTF-8字节，无大小写字母、数字或符号组合要求。确认密码必须完全一致。两次密码都按敏感输入处理，不进入日志或浏览器持久存储。

注册DTO只接受上述三个字段，额外的role、status、password_hash等字段返回400。服务端固定写入STUDENT和ACTIVE，BCrypt cost 12哈希后通过参数化JDBC单条INSERT持久化，数据库唯一冲突统一返回409 USERNAME_EXISTS。不存在先查后插的并发窗口。

注册匿名可访问，沿用GET /api/auth/csrf及动态CSRF请求头。服务端复用LoginThrottle，为register加独立键前缀，同一来源每10分钟最多10次有效参数的注册尝试，超限429并返回Retry-After。登录限流键独立。注册不创建认证身份，会话/me在主动登录之前返回401。

前端注册状态留在RegisterView本地，成功清空两次密码并跳转/login?registered=1显示成功提示。PasswordInput在登录、注册和确认密码输入中复用可访问的显隐交互，共享auth-form.css保持原有布局与主题。身份管理继续使用现有Pinia auth Store。

## 本次注册与迁移实际验收

- Maven测试20项全部通过，包括注册成功、重复用户名、空用户名、格式错误、弱密码、两次密码不一致、超长UTF-8密码、角色/状态/哈希注入、BCrypt验证、注册后主动登录、注册限流、CSRF拒绝及并发注册。原登录、安全过滤器、文档加载、聊天记忆和回答处理测试通过。
- UserMigrationTest通过Flyway在独立H2库验证非空Schema baseline 0、V001执行及重跑，已有哨兵表和用户记录保留。应用上下文离线测试明确关闭Flyway，避免测试运行写入真实库。
- npm test通过4项前端回归，npm run build成功。生产构建通过4174端口与真实PostgreSQL后端联调，后端在18123端口启动。
- 真实浏览器验证注册→成功提示→主动登录→/me 200→社区→刷新→退出→/me 401。错误密码401、重复账号409、学生访问管理员403、无CSRF注册拒绝均已验证。
- 真实PostgreSQL的并发注册结果为201与409，数据库最终仅保留一个同名用户。带role、status、password_hash额外字段的真实请求均400，所有联调用户均为STUDENT、ACTIVE、BCrypt cost 12。
- 后端进程停止并重新启动后，Flyway校验V001且无需新增迁移。使用重启前注册的regtest_1794676438722再次登录和/me查询成功，验证PostgreSQL持久化。
- 1920、1366、768、390、375px注册布局无横向溢出，注册按钮高度48px，输入实时展示用户名、密码与确认密码错误。
- 本次新前端浏览器脚本未发现JavaScript运行异常。匿名探测、错误凭据和权限校验产生预期HTTP错误反馈。
- 迁移前后RAG均47条，全行内容指纹均40f50dac83c5829b4a9b74fe1267c34d。探针只读RAG数据，迁移SQL仅创建用户表及Flyway历史。未进行真实模型调用或真实RAG请求，AI回归使用现有Mock模型测试。
- 开发库保留3个联调账号，regtest_1794676308740、regtest_1794676438722、race_1794676441392。联调密码由测试运行时生成，交付不公布密码，不新增管理员账号，不清理任何既有业务记录。

## 核心目录

```text
auth/
  controller/  AuthController, AuthExceptionHandler
  dto/         LoginRequest, RegisterRequest, RegisterResponse
  service/     AuthService
  security/    SecurityConfig, StudentPrincipal, StudentSessionFilter,
               LoginThrottle, AuthErrorWriter
user/
  entity/      User
  dto/         UserInfo
  repository/  UserRepository
  development/ DevelopmentStudentInitializer
resources/
  db/migration/V001__community_users.sql
  static/login.html
  static/community.html
  static/js/auth.js
  static/js/login.js
  static/js/community.js
  static/css/login.css
```

| 核心类 | 职责 |
| --- | --- |
| AuthController | 认证HTTP端点、参数验证和OpenAPI说明 |
| AuthService | 验证学生凭据、建立会话、当前用户和退出 |
| AuthExceptionHandler | 认证业务错误和数据库故障转换 |
| SecurityConfig | URL权限、CSRF、密码编码及会话安全链 |
| StudentSessionFilter | 会话绝对过期及账号实时有效性检查 |
| StudentPrincipal | 会话内最小用户身份和到期时间 |
| LoginThrottle | 有界、并发安全的登录频率限制 |
| AuthErrorWriter | 安全过滤阶段统一JSON错误 |
| LoginRequest | 登录输入约束和敏感字段屏蔽 |
| User | 用户内部数据模型，密码哈希禁止JSON输出 |
| UserInfo | 对外及会话内最小用户信息 |
| UserRepository | 参数化JDBC用户查询和幂等开发账号插入 |
| DevelopmentStudentInitializer | 显式开发profile的临时凭据初始化 |

依赖为 AuthController → AuthService → UserRepository → 数据库及User。SecurityConfig 装配 StudentSessionFilter，后者仅调用 UserRepository。UserInfo 是用户模块提供的数据边界，用户模块没有反向依赖 auth。当前查询规模直接使用仓储，无需增加 UserService。未生成Service接口与空实现、重复Controller、重复Service、循环依赖、未使用新增类或无关跨模块业务依赖。

## 登录首次实现阶段的验证记录

- Maven clean test曾用于清除历史target内残留的已删除Agent类，随后完整 mvnw.cmd -q test 通过，15项测试、0失败、0错误。新增 AuthIntegrationTest 有8个测试方法，覆盖正确登录、错误密码、未知用户、禁用用户、空值、错误JSON、匿名401、会话过期、实时禁用、学生访问管理员403、获取身份、退出、会话轮换、CSRF与暴力破解防护。
- 测试以 H2 PostgreSQL兼容模式执行真实 JDBC 查询与 BCrypt。迁移SQL连续执行两次成功。未将H2加入生产运行依赖。
- AI回归以Mock模型服务验证原 /ai/knowledge/chat 响应结构及API Key和限流，验证流式路径原API Key拒绝行为。原文档加载、聊天记忆、回答处理与应用上下文测试全部通过。
- mvnw.cmd -q -DskipTests package 编译及打包成功，最终再次运行 mvnw.cmd -q package 通过全部15项测试并成功打包。
- Node --check 验证三个新增JavaScript模块成功。原生回退页面无独立构建命令，新增Vue工程的构建方式见统一前端架构文档。
- 在真实嵌入式Tomcat和独立H2测试数据库启动完整应用，通过Playwright验证错误密码提示、Enter登录、身份展示、刷新恢复、退出和匿名回跳。实际Cookie具有HttpOnly及SameSite=Lax，localStorage与sessionStorage均无认证数据。
- 390×844移动端检查无横向溢出，密码显示与隐藏正常。浏览器测试使用临时测试账号及虚拟模型Key，未调用实际模型。
- 首次登录阶段对PostgreSQL只进行了只读元数据检查。后续注册阶段已完成实际迁移、注册持久化和重启联调，详见上文本次验收记录。真实模型调用仍待AI环境验收。

## 手动验收

1. 确认开发库连接配置。当前开发库已经完成Flyway迁移，其他环境按上文首次接管流程执行。
2. 启动后端和Vue前端，访问/register注册自选学生账号。原生/api/login.html继续提供登录回退。
3. 验证重复注册、错误密码、成功登录、刷新、身份显示和退出。重启应用后注册账号应仍可登录。
4. 用有权限的数据库工具将测试账号status设为DISABLED并更新updated_at，已登录会话下次请求应返回401。恢复ACTIVE后可重新登录。
5. 等待30分钟绝对到期后调用 /api/auth/me，应返回401，社区页面下次状态检查回到登录页。
6. 生产使用HTTPS并保留默认Secure Cookie，关闭student-dev。使用既有AI环境配置分别验收普通与流式问答以及真实RAG引用。

## 学生社区管理员增量

本轮社区允许 ACTIVE 的 STUDENT 与 ADMIN 通过相同登录端点认证，Session 每次请求核对当前账号状态与角色。认证端点与社区允许两种角色，/admin 仅 ADMIN，/agent 允许 STUDENT 与 ADMIN。注册接口固定创建 STUDENT。

开发管理员初始化需显式启用 community-admin-dev，并设置 DEV_ADMIN_USERNAME、DEV_ADMIN_PASSWORD。初始化保留已有同名账号及其角色，运维应检查启动反馈。社区数据库与审核行为详见 [社区契约](features/student-community/03-api-contract.md)。

## 开发管理员创建入口

在项目根目录运行脚本，通过凭据窗口输入新的管理员用户名和自选密码。

```powershell
./scripts/start-admin-dev.ps1 -Port 18130
```

用户名为字母开头的3至32位字母、数字或下划线，密码至少6字符且最多72个UTF-8字节。脚本启动local、pgvector、community-admin-dev，并使用现有数据源将新账号保存为ADMIN/ACTIVE，密码BCrypt加密。已有同名账号保留原角色和密码，请选独立用户名。脚本退出后恢复原环境变量和目录。

运行前结束占用所选端口的本地后端，或通过-Port选择空闲端口。前端API_PROXY_TARGET指向对应端口。同一数据库创建完成的管理员可在正式Vue /login登录并访问/admin/dashboard。此入口用于开发环境初始化。

## 同一登录入口按角色导航

学生与管理员均访问同一前端/login。登录成功后默认ADMIN进入/admin/dashboard、STUDENT进入/community。目标由服务端返回的role与路由meta共同决定，登录前保存的原目标仅在当前角色有权限时恢复。

## 管理员AI权限增量

/agent/**安全规则允许ACTIVE的STUDENT和ADMIN，沿用同一Session、CSRF和实时角色状态校验。管理员与学生均只可访问本人Agent会话。现有/ai/**接口的API Key入口继续执行原配置。
