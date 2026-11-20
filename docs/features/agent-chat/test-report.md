# Agent 对话验收报告

2026-11-15，独立 QA Agent 执行。Chief 负责最终 Maven 打包复核，Backend 和 Frontend 分别提供对应单元测试回归。以下记录真实执行结果。

## 环境与基线

- 真实开发库的主机、数据库名与 `docs/student-login.md` 已确认目标一致。只读连接成功，PostgreSQL 17.11，数据库 wjy_agent。
- 迁移前 Flyway 仅 baseline 0 与 V001，均成功。向量表47条，按id排序的全行JSON MD5聚合指纹为 `5f91e1a5d0132502661c697c22ba99e4`。探针算法与以前报告不同，后续使用同一算法比较。
- 初始已有8123、18123后端及5173、4174前端进程，本次验收使用独立18124与5175端口。
- 测试脚本 `scripts/agent-chat-live-check.py` 使用真实 Cookie、CSRF、注册、登录及API。临时密码仅在进程内生成，交付中不保存密码。

## 构建与离线回归

| 实际命令 | 结果 | 范围 |
| --- | --- | --- |
| `./mvnw.cmd -q test` | 首轮失败后已修复，完整28项通过 | H2 JSONB测试数据、迁移计数、离线上下文依赖修复 |
| `./mvnw.cmd -q package` | 最终31项全部通过 | 包括新增旧API大小写记忆namespace拒绝、限流身份桶及参数错误回归。最后重打包因运行中jar的Windows文件锁失败，停止QA实例后以 `./mvnw.cmd -q -DskipTests package` 成功生成最终jar |
| `npm --prefix frontend test` | 最终10项通过 | SSE逐字节UTF-8/CRLF/多行data、abort、会话切换、身份清理、预流失败重试、取消终态刷新竞态、原认证与回跳 |
| `npm --prefix frontend run build` | 通过 | QA曾独立构建，取消修复后Frontend再次成功构建 |

离线Java测试使用H2与Mock模型覆盖安全、服务生命周期及既有AI/RAG回归。此项与下列真实联调分别记录。

## 真实PostgreSQL与API

QA使用独立18124端口启动应用，启用既有local,pgvector，关闭本次ETL与向量schema初始化，避免导入改动知识库。已核对开发库后由Flyway实际执行V002，创建agent_conversation与agent_message。数据库只读元数据确认owner外键、会话级联消息、状态/角色约束、UUID唯一约束和分页索引。

`python scripts/agent-chat-live-check.py --base-url http://127.0.0.1:18124/api` 成功。通过真实注册与登录建立两名学生的独立Cookie上下文，验证以下结果。

- 匿名会话列表401，无CSRF创建403，写入userId未知字段400。
- 学生A创建、列出、读取和重命名自己的会话成功。
- 学生B读取A消息、重命名、删除、发送和取消均404。
- 真实模型流HTTP 200，终态done，历史两条消息均completed，引用非空。
- V002迁移后向量仍47条，完整行内容指纹仍为 `5f91e1a5d0132502661c697c22ba99e4`，与同算法前探针完全一致。证据保存在target/agent-db-before.json及agent-db-after.json。

## 真实模型、RAG与重启

模型与embedding沿用现有本地配置，未将Key写入前端或报告。真实提问选用知识库已有的Spring Boot Controller/Service/Repository分层内容，真实回答完成并返回5条引用。

`python scripts/agent-chat-restart-check.py` 在进程内保存临时凭据。第一轮让模型在分层解释后附上随机代号，第二轮仅询问上一轮代号，模型准确返回，4条消息全部completed且第二轮引用5条。此结果验证了真实多轮上下文，非仅收到done。

QA停止自己启动的18124实例，使用Chief新打包jar重启。旧Session失效401，同一账号重新登录200，同一会话4条消息与重启前完整响应一致。旧 `/ai/knowledge/chat` 和 `/ai/knowledge/chat/stream` 使用 `Student-<UUID>` 均返回400，覆盖Windows路径大小写边界。证据为target/agent-restart-result.json。

## 真实浏览器与模拟故障

浏览器MCP因现有profile占用无法启动，QA改用已安装Playwright与独立headless Chrome上下文，实际执行 `node scripts/agent-chat-browser-check.cjs`。PLAYWRIGHT_MODULE指向本机缓存中的Playwright模块。真实Vite 5175代理真实后端18124。

- 未登录访问/agent/chat回跳登录，登录后恢复原目标。
- 页面真实创建会话、发送知识库问题并流式生成，刷新前后均恢复两条消息。
- 等待真实stream响应头后点击停止，服务端保存cancelled，页面显示已停止并恢复发送按钮。
- 重命名、创建第二会话、删除第二会话、自动恢复首会话均成功。
- 实际系统剪贴板验证复制代码和复制回答成功。
- 1920、1366、768、390、375px宽度均无document横向溢出，输入区域存在，桌面和375px截图已检查。截图为target/agent-chat-1920.png与agent-chat-375.png。
- 退出后受保护页面重新回跳登录，localStorage和sessionStorage均为空，pageerror为0。
- 通过Playwright拦截流请求分别模拟403及429，页面显示错误并保留重试按钮。这两项故障注入属于模拟响应。
- 通过Playwright拦截历史接口注入恶意Markdown，浏览器无脚本执行，渲染区无img/script/javascript链接，代码高亮成功。此项为恶意测试数据注入，数据库没有写入该内容。

取消测试曾发现abort后历史查询早于cancel落库导致UI读到generating，已由Frontend修复。最终浏览器复测通过，并补充对应第10项前端回归。另一个脚本起始条件已修正为等待stream响应头后取消，以区分CSRF阶段取消与服务器已开始生成。

最终浏览器JSON证据为target/agent-browser-result.json。本轮共保留9个测试学生，前缀agentqa_、agentbrowser_或agentrestart_，测试会话保留于开发库，密码仅在测试进程中存在。未修改其他学生记录。

最终18124已使用包含全部修复的新jar重新启动，5175 Vite继续可用。最终真实登录后，无效UUID、page=abc、size=abc均验证为400与 `INVALID_REQUEST` 标准错误体。

## 验证边界

- 真实无命中RAG分支、移动端软键盘弹出、安全区域实机行为尚未独立验证。移动布局使用桌面Chrome viewport模拟。
- 多页数据库历史与更早消息按钮的完整浏览器翻页、真实网络断开、30分钟绝对超时未逐一实测。已有离线分帧/abort/身份失效测试及浏览器模拟故障覆盖对应部分行为。
- 旧API真实模型问答未重复消耗调用。旧接口回归使用既有Mock单测，私有namespace拒绝已真实验证。

## 2026-11-19 管理员AI对话验收

本轮将/agent/**及/agent/chat权限扩展为STUDENT与ADMIN，并在AdminLayout侧栏增加入口，StudentLayout向两类角色展示AI导航。复用原会话服务、用户ID归属、SSE及RAG实现，数据库结构沿用V002。

./mvnw.cmd -q package成功，全量42项JUnit通过，AgentSecurityTest新增1项管理员闭环测试，覆盖管理员创建/列表/重命名/流式历史/取消/删除、CSRF拒绝、禁用失效，以及管理员与学生、不同管理员之间5类越权404。模型在此项使用Mock。

前端22项测试和生产构建通过，角色回跳测试已更新为管理员允许/agent/chat。真实Chrome使用5180前端代理18130后端完成26项检查，包含管理员从后台导航进入AI、真实模型SSE HTTP200、两条completed消息持久化、RAG引用、刷新恢复、会话管理、双角色5类越权404、CSRF和375px布局。pageErrors为空，Chief已目视手机截图。

证据target/admin-agent-live-result.json、admin-agent-mobile.png、admin-agent-maven.log。本次临时创建的2个验证账号及会话在finally中清理，最终保持10用户、12问题、16回答，知识库47条与指纹40f50dac83c5829b4a9b74fe1267c34d一致。清理证据target/admin-agent-cleanup-result.json，未保留新测试账号。

最新后端运行target/admin-agent-serving.jar，端口18130；前端5180。生产HTTPS与实机软键盘验证边界沿用既有报告。

## 2026-11-20 管理员AI实际入口鉴权排查

用户报告权限或安全校验失败后，对当前实际入口进行差分验证。源码已有STUDENT/ADMIN许可，但5173前端连接的8123仍是此前启动的旧实例。同一批临时账号在5173登录/Agent访问被拒绝，在5180至18130链路可正常登录并创建会话。此前只验收5180，漏验了常用5173路径。

本次停止旧8123实例，以已通过42项JUnit及22项前端测试的最新admin-agent-serving.jar在8123启动，保留ETL关闭及向量初始化关闭配置。先复测5173恢复管理员读取200、带CSRF创建201，再将5180也连接8123并关闭旧独立18130后端。当前统一使用localhost:5173，127.0.0.1:5180作为兼容入口。

真实Chrome从localhost:5173登录管理员，经后台AI入口发送真实模型请求，26项检查通过，包括HTTP200 SSE、两条completed历史、RAG引用、刷新、管理员自身重命名取消删除、跨学生权限404、CSRF403及375px布局，pageErrors为空。原最小探针对5173与5180再次执行全部通过。诊断期间临时账号及会话均已清理，最终仍为10用户、12大学问题、16回答，知识库47条及指纹一致。证据target/admin-access-probe-result.json、admin-ai-final-probe.log、admin-agent-live-result.json、admin-ai-default-browser.log、admin-agent-cleanup-result.json。

本次修复运行实例与代理一致性，未放宽CSRF或会话所有权限制。原会话因后端更新失效，需要重新登录。新测试未保留账号或更改已有用户凭据。
