# 管理员后台与全系统 UI 测试报告

## 结果与环境

2026-11-18，本轮管理员剩余业务与全系统界面升级在真实开发 PostgreSQL、真实 Spring Boot、Vue 与 Chrome 完成验收。后台18128，Vite5178代理18128。受测功能无未解决P0/P1。

| 项目 | 实际结果 | 验证性质 |
| --- | --- | --- |
| `./mvnw.cmd -q package` | 39项全部通过，最终打包成功 | Chief执行，JUnit、独立H2、MockMvc与Mock模型 |
| `npm --prefix frontend test` | 18项通过 | Frontend与Chief分别执行，前端逻辑回归 |
| `npm --prefix frontend run build` | 成功，1838模块 | Chief最终复核 |
| `python scripts/admin-live-check.py --base-url http://127.0.0.1:18128/api` | 100项检查通过 | 真实HTTP、Session/CSRF、PostgreSQL |
| `node scripts/admin-browser-check.cjs` | 桌面1366与手机375真实业务闭环，78页面/视口组合通过 | 真实Chrome、Vue、后端、PostgreSQL |
| `python scripts/admin-ai-normal-check.py --base-url http://127.0.0.1:18128/api` | HTTP200，真实answer与citations | 真实普通模型及RAG |
| `python scripts/agent-chat-live-check.py --base-url http://127.0.0.1:18128/api --output target/admin-agent-api-result.json` | SSE done，2条completed历史，含引用，隔离检查通过 | 真实模型、RAG、数据库 |
| `node scripts/admin-agent-browser-check.cjs` | 真实流式发送/停止、历史刷新、会话管理、手机输入与Drawer通过 | 真实模型/后端，403/429和Markdown攻击样本单项模拟 |

独立新增AdminManagementIntegrationTest四个JUnit方法，适配CommunityIntegrationTest四个方法加载V005及AdminRepository，并先清独立H2中的审计数据避免FK冲突。新旧测试均包含于全量39项。测试清理只作用于独立H2。

## 数据库环境、迁移与保留

迁移前只读核对已确认的开发库wjy_agent、用户wgd_agent、PostgreSQL17.11。baseline与V001..V004全部成功，校验和与上一轮一致。基线为target/admin-db-before.json。

最终迁移历史如下。

| 版本 | CRC | 状态 |
| --- | --- | --- |
| V001 | 611595117 | success |
| V002 | 672633997 | success |
| V003 | -166552946 | success |
| V004 | -1142351769 | success |
| V005 | -1361444717 | success |

V005为问题和回答添加逻辑删除元数据，新增admin_operation_log及约束索引。原迁移文件CRC保持一致。Flyway repair执行次数为0。原业务表、已有记录、Agent会话和向量数据保留。

原测试18126进程持有主jar造成首次repackage失败。Chief确认并停止自己创建的旧测试后端及残留bootstrap进程，保留用户8123进程。最终构建成功，复制为target/admin-serving.jar供18128运行，避免后续构建文件锁。验收中仅重启该独立测试实例以清理测试限流窗口并验证数据持久化。

重启后只读确认浏览器创建的问题15仍APPROVED且未删除，关联收藏1条、问题操作审计3条均保留。证据为target/admin-persistence-result.json。真实模型回归后知识向量仍47条，全行指纹40f50dac83c5829b4a9b74fe1267c34d，与迁移前相同。

## 真实管理员业务与安全

使用两个普通学生与两名测试ADMIN，全部复用现有注册、登录、Session与CSRF。脚本创建adminqa_或adminui_唯一前缀测试身份，管理员仅在确认开发库中精确升级本次QA账号，或从本次QA学生复制BCrypt哈希创建同次QA管理员。随机密码仅存在进程内存，结果文件不含凭据。测试记录保留供审查。

以下场景全部通过。

- 匿名管理接口401，学生管理接口403，无CSRF删除403，ADMIN自身及其他ADMIN状态操作403，安全用户DTO仅包含id、username、role、status、createdAt、updatedAt。
- 问题与回答管理查询、详情、审核、删除和恢复成功。重复删除或恢复返回409，原审核状态保留，REJECTED恢复后继续REJECTED且学生不可公开访问。
- 删除问题从公共列表、关键词搜索、详情、本人提问、本人回答、本人收藏全部隐藏，即使ADMIN走community详情也404。删除后新增回答与收藏404。原关联回答、收藏未物理删除。
- 删除回答后不进入公开列表，恢复回答时父问题仍删除则继续隐藏。恢复父问题后通过回答重新公开，收藏状态恢复可见。
- 两个真实ADMIN并发删除得到200/409，并发恢复得到200/409。删除与审核并发按锁顺序串行，最终已删除，审核仅合法顺序成功，审计数量与实际成功操作一致。
- 禁用学生后已有Session下次请求401，重新登录401。启用后旧Session仍401，重新登录成功。角色与身份安全规则沿用已有实时过滤器。
- 九项真实统计分别用SQL复核学生总数/启用/禁用、问题/回答总数、待审核问题/回答、已删除问题/回答，全部与契约口径匹配。
- 审计覆盖14类操作，包含本轮删除、恢复、用户启停以及原审核与分类新增/修改/启停。冲突失败未增加成功日志，按操作人及动作查询可用，参数上限与时间范围校验生效。
- 审计before/after只含安全状态，检查全部本次QA日志无随机密码、password_hash、passwordHash、Session ID或API Key字段。管理接口无日志修改与删除能力。

H2/MockMvc四个新测试另验证删除全入口、收藏保留、禁用Session、ADMIN保护、统计、日志只读、参数校验与原审核分类审计。

## 浏览器真实业务与响应式

桌面1366与手机375各完整执行一次以下点击流程。

1. 学生通过发布表单提交问题，管理员在详情管理弹窗审核通过。
2. 学生收藏问题、提交回答，管理员审核回答通过，学生刷新看到回答。
3. 管理员删除并恢复回答、删除并恢复问题，学生侧可见性同步。
4. 管理员通过用户详情禁用并启用学生，验证学生既有Session失效，学生重新登录恢复业务。

危险操作需选择动作、填写删除原因并点击确认操作。接口成功后弹窗关闭和列表刷新。正文攻击字符串被文本显示，无img节点和脚本执行。

六种宽度为1920、1366、1024、768、390、375。每种覆盖六个管理页面、社区首页、发布、问题详情、个人中心、AI工作台，以及匿名登录/注册，共78个页面组合。等待网络空闲后检查documentElement.scrollWidth，均不超过视口。pageErrors为空。宽表格采用内部滚动。

375px管理Drawer点击导航后关闭，键盘Enter打开与Escape关闭可用，焦点回菜单按钮，无残留可见overlay。真实手机点击删除恢复、用户禁启和表单提交已覆盖。

截图如下。

- target/admin-dashboard-1366.png、admin-dashboard-375.png
- target/admin-community-1366.png、admin-community-375.png
- target/admin-agent-1366.png、admin-agent-375.png，六宽空态布局截图
- target/admin-register-375.png
- target/admin-agent-chat-1920.png、admin-agent-chat-375.png，含真实问答内容

Chief已目视后台和社区桌面/375截图，QA目视375控制台。375主指标采用两列、辅助指标单列，Chief确认可读并接受，UI/UX同步设计文档实际规范。其余布局采用共享主题、独立后台导航、真实指标和完整状态标签。

## AI工作台与认证回归

普通非流式真实调用HTTP200，answer与citations均存在。Agent API脚本真实SSE终态done，历史两条completed且有引用。两学生之间历史读取、重命名、删除、发送和取消越权均404，匿名401、无CSRF403及未知owner字段400。

AI浏览器实际发送并显示流式回答，刷新前后均两条消息。真实再次发送后停止生成，页面最终已停止。重命名、新建、删除会话通过。头像菜单退出后受保护路由返回登录。

独立模拟流式403和429，页面呈现错误并提供重试。独立模拟历史中恶意Markdown与代码样本，净化后无脚本、图片或javascript链接，代码高亮生效，复制代码和复制回答的剪贴板内容均匹配。这些攻击与错误响应属于模拟测试，真实问答与停止为实际后端/模型。

AI内容六宽均无横向溢出，375历史Drawer可打开并Escape关闭，关闭后输入框top724.86、bottom782.86位于900px高视口，body overflow为visible，输入可填。localStorage与sessionStorage项数均为0，pageErrors为空。

## 执行中问题与处理

- 首次全量打包jar文件锁按上述独立测试进程及复制运行包处理，最终package通过。
- 首次PG统计复核与浏览器写入并发，API统计与随后SQL落在不同数据时点，待审核回答出现2与1。后续改为业务写入套件顺序执行，PG100项全部通过，九项统计逐一匹配。脚本头已注明与其他写套件串行执行。
- 浏览器初轮使用英文Dialog关闭名称，与中文locale不符，已采用组件关闭按钮选择器。分类加载前点击禁用Select导致QA超时，增加网络空闲等待后完整通过。此两项为验收脚本修正。
- 同IP登录限流维持原有10次/10分钟。多个套件顺序运行并由Chief重启独立18128实例形成新的验收窗口，业务限流配置保留原值。

测试脚本已通过Black/Prettier格式化，Node语法检查与Python编译检查通过，临时格式化与浏览器运行依赖位于target，项目生产依赖未新增。

## 验证边界

- 本轮为本地HTTP开发环境，生产HTTPS与Secure Cookie部署仍需在生产配置环境验收。
- 手机完整业务点击使用Chrome headless375px模拟视口，真实移动设备触屏与软键盘未单独验证。
- 审计事务正常、冲突及并发失败已验证，数据库连接中断等故障注入未单独执行。
- 分类新增修改启停与日志过滤通过真实HTTP及独立H2验证，六宽分类和日志页面完成真实浏览器布局验收。

## 证据与复跑

主要证据为target/admin-live-result.json、admin-browser-result.json、admin-ai-normal-result.json、admin-agent-api-result.json、admin-agent-browser-result.json、admin-persistence-result.json。日志对应admin-*-run.log与admin-maven-final.log。QA脚本位于scripts/admin-live-check.py、admin-browser-check.cjs、admin-ai-normal-check.py、admin-agent-browser-check.cjs。

Python真实验收依赖psycopg与PyYAML，读取已有本地开发数据源配置并断言开发库身份。Playwright脚本使用PLAYWRIGHT_MODULE指定模块，缺省使用target/community-browser-runtime/node_modules/playwright及本地Chrome。每个套件创建明确QA前缀数据并保留审计。运行全套时按本报告顺序执行，避免统计时点竞争及单IP登录限流干扰。

## 最终文案与格式化复核

Frontend最终将登录标题统一为账号登录、注册页为创建学生账号，AuthLayout与社区侧栏改为事实性功能说明。QA用新匿名浏览器会话在375和1366px重新打开登录与注册，全部无页面横向溢出，截图为target/admin-final-login-375.png、admin-final-login-1366.png、admin-final-register-375.png、admin-final-register-1366.png。

社区最终文案截图使用独立模拟/me、分类和问题列表响应，以免重复创建账号或触发登录限流。375与1366px均无溢出，截图为target/admin-final-community-mock-375.png、admin-final-community-mock-1366.png。该两张仅验证最终文案和布局，前述78组真实浏览器流程与布局验收结果仍独立有效。

新增AdminManagementIntegrationTest格式化后，Chief已单独补跑4项全部通过，日志target/admin-contract-final.log。最终前端18项测试及1838模块构建通过，均由Chief复核。

## 2026-11-19 交互维护与完整回归

本节为2026-11-19最新验收，前文2026-11-18结果作为历史记录保留。本轮复用V005已有管理业务，新增六项交互修复及审计失败回滚验证。

| 验证 | 本轮实际结果 | 性质 |
| --- | --- | --- |
| Chief最终Maven package | 41项JUnit全部通过并打包成功 | 独立H2、MockMvc、Mock模型及上下文 |
| 前端test/build | 20项通过，1838模块构建成功 | Chief独立执行 |
| 管理接口完整回归 | 100项通过 | 真实18130后端、PostgreSQL、Session/CSRF |
| 本轮六项交互专项 | 26项通过 | 真实浏览器/后端/数据库，详情503单项模拟 |
| 完整后台与学生浏览器 | 78个页面/视口组合及桌面/手机业务闭环通过 | 真实5180 Vue、18130后端、PostgreSQL |
| 普通AI | HTTP200，answer与citations均存在 | 真实模型与RAG |
| Agent API | SSE done、2条completed历史且有引用，双学生隔离通过 | 真实模型/RAG/PostgreSQL |
| Agent浏览器 | 发送/流式/停止/历史刷新/CRUD/退出/手机输入通过 | 真实模型，403/429与恶意Markdown单项模拟 |
| 开发启动脚本 | 模拟Maven成功与失败均恢复环境变量及cwd | Chief执行脚本控制流测试 |

本轮100项接口由Chief执行，结果target/admin-1119-live-result.json。新增两项AdminAuditRollbackTest覆盖审计INSERT失败后问题审核及审核元数据回滚、学生禁用回滚和重试，包含于41项JUnit。本轮未创建新迁移。

### 六项交互专项

脚本scripts/admin-interaction-check.cjs实际创建独有interqa_学生与管理员、11个QA分类及11个问题，在真实后端上执行以下检查。

- 问题管理、用户管理和社区使用相同条件连续查询两次，均重新产生GET且page=1。
- 问题管理ACTIVE筛选第二页仅一条记录，执行带原因删除后回到有效第一页并展示10条。
- 用户详情GET单独模拟503，弹窗显示错误与重试，启用/禁用按钮不存在。解除模拟后重试真实GET成功，状态操作按钮恢复。该故障反馈属于模拟测试，其余读取与状态均来自真实后端。
- 折叠后六项菜单与返回社区有完整可访问名称，管理员与学生头像按钮均有账号相关名称。
- 问题列表刷新请求延迟期间，加载区域实际高度由822px保持为822px。此请求最终仍发送至真实后端，延迟仅用于观察加载中布局。
- 分类数组筛选后在浏览器本地按10条分页，11条结果展示10+1。启用筛选末页唯一分类停用后回到有效页，收窄分类名称筛选重置第一页。

26项均通过，pageErrors为空，375px无页面横向溢出。结果为target/admin-1119-interaction-result.json，截图target/admin-1119-interactions-375.png。

### 全系统及AI复测

本轮再次运行scripts/admin-browser-check.cjs，在1366px及375px真实点击学生发布、问题审核、回答及审核、收藏、问题/回答删除恢复、学生禁用启用及重新登录。六宽1920/1366/1024/768/390/375覆盖六管理页面、社区/发布/详情/profile/Agent及匿名登录注册，共78组合，均无页面横向溢出，pageErrors为空。结果已保存为target/admin-1119-browser-result.json。截图补存target/admin-1119-dashboard-1366.png及admin-1119-dashboard-375.png。

普通AI结果为target/admin-1119-ai-normal-result.json。Agent真实API结果为target/admin-1119-agent-api-result.json，真实问答有引用，历史与五类跨学生越权校验通过。

本轮Agent浏览器再次真实发送并显示流式内容、停止生成、刷新持久历史、重命名/新建/删除与退出，模拟403/429及Markdown攻击样本的反馈、净化、高亮与代码/回答复制亦通过。六宽AI内容无溢出，localStorage/sessionStorage均0，运行异常为空。375px关闭历史Drawer后实际输入框top724.86、bottom782.86，位于900px高视口，最终body overflow为visible且无可见overlay。结果为target/admin-1119-agent-browser-result.json。

Drawer早期测试在hidden后立即采样，捕获body overflow=hidden。当时输入框已可填且位于视口内。Frontend核对Element Plus use-lockscreen清理逻辑有200ms延迟，QA据此等待实际滚动解锁并检查无overlay，上限5秒。最终额外等待57ms后通过，生产代码未因此修改。此项为测试过渡状态等待修正。

### 数据、运行环境与执行说明

前后均只读核对开发PG，V001..V005校验和分别611595117、672633997、-166552946、-1142351769、-1361444717，全部success，历史相同。向量仍47条，指纹40f50dac83c5829b4a9b74fe1267c34d。证据target/admin-1119-db-before.json与admin-1119-db-after.json。新增QA记录保留审计，密码仅存在进程内存。

本轮18130使用Chief独立运行包，前端5180代理该后端。用户原有进程保留。受测试登录次数限制，Chief仅重启自启18130实例形成后续验收窗口，业务限流值保持原配置。统计接口复核先完成，再执行写入密集浏览器套件。

执行过程中工具中断导致原5180会话退出，QA重新启动该自启Vite。专项早期断言在网络延迟路由期间过早读取Skeleton，已改为等待页面aria-busy=false后检查结果。一次脚本修改使用Python默认GBK读中文文件失败，随后已明确UTF-8并通过Prettier与Node语法检查。最终专项完整通过，上述均为测试环境或时序处理。

Chief另验证scripts/start-admin-dev.ps1模拟Maven成功/失败路径均恢复原环境与工作目录，证据target/admin-startup-smoke.ps1。此为启动脚本控制流模拟测试，真实后端与数据库联调由本节其他验收独立证明。

当前受测范围无未解决P0/P1。本地HTTP、Chrome模拟手机视口的验证边界继续适用，生产HTTPS与真实移动设备软键盘未单独验收。最终交付时18130后端与5180前端仍运行。

## 2026-11-19 同端口角色导航验证

实际修改router/index.js与LoginView.vue，新增角色首页规则并校验登录回跳目标的角色权限。根路径及未知路径经统一登录入口按当前身份落地。相应前端测试与两份既有管理员验收脚本的登录目标已同步。

npm test通过22项，生产build通过。真实Chrome连接同一5180前端及18130真实后端，临时学生与管理员通过真实表单登录，验证11个导航检查，包括默认角色落地、已登录访问根/登录、允许及禁止的原目标、学生直接管理页受限且身份保留，以及375px同源访问无溢出。pageErrors为空，证据target/role-routing-browser-result.json。QA临时账号以roleqa_标识，凭据仅在进程内使用。

## 2026-11-19 大学主题示例数据整理

按用户要求清理此前验收数据，事务按明确测试前缀识别64个测试账号，删除其56个问题、16条回答、11条收藏、46个测试分类、14个测试Agent会话及44条关联消息，并清理137条关联测试操作记录。保留已有wgd、wgd1账号及其2个Agent会话。

创建8个中文人名示例学生账号（林知远、陈思雨、周明轩、李沐晴、王子涵、刘浩然、赵欣怡、张宇航），新增课程学习、编程与技术、升学规划、校园生活、实习就业、科研与写作6分类，12个大学主题问题及16条回答。内容为示例，问题和回答通过审核并保留初始化审核记录，账号密码随机生成并仅保存BCrypt12哈希。

真实Chrome通过中文用户名表单登录，验证社区第1页10条、第2页2条、6分类、详情回答、关键词搜索及375px无溢出，pageErrors为空。数据最终核对为10个用户、12个APPROVED问题、16条APPROVED回答。知识库保持47条且指纹40f50dac83c5829b4a9b74fe1267c34d一致，Flyway历史保留原值。证据target/campus-content-refresh-result.json、campus-content-browser-result.json、campus-content-final-check.json，截图campus-community-desktop.png及campus-question-mobile.png。
