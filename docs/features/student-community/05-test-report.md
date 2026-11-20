# 学生社区测试报告

## 环境与结论

2026-11-18，在 Windows、Java 21、PostgreSQL 17.11、Chrome headless 上完成本轮验收。社区后端为 `http://127.0.0.1:18126/api`，Vite 为 `http://127.0.0.1:5176`，代理至18126，保留已有5173与用户IDE进程。

本轮已验证核心学生与管理员业务闭环，发现的管理弹窗覆盖层样式缺陷已修复并复测。当前受测范围内无未解决P0/P1。以下明确标注真实后端、H2、模拟错误与待验收范围。

## 自动测试及构建

| 验证 | 实际结果 | 性质 |
| --- | --- | --- |
| `./mvnw.cmd -q package` | 35项，0失败/错误，打包成功 | Chief执行，JUnit/H2、Mock模型与应用上下文 |
| 最新 `CommunityIntegrationTest` 单独复跑 | 4项通过，显式加载V004与事务管理 | Chief执行，MockMvc调用真实Service/JdbcTemplate，独立H2 |
| `npm --prefix frontend test` | 14项通过 | Frontend及Chief执行，Node前端逻辑测试 |
| `npm --prefix frontend run build` | 成功，弹窗修复后重新通过 | Frontend执行，Vite生产构建 |
| `python scripts/community-live-check.py --base-url http://127.0.0.1:18126/api` | 67项检查通过 | 真实HTTP、Session/CSRF、PostgreSQL |
| `node scripts/community-browser-check.cjs` | 7组流程检查与35组视口检查通过，pageErrors为空 | 真实Chrome、Vue、后端、PostgreSQL，503反馈单项模拟 |
| `python scripts/agent-chat-live-check.py --base-url http://127.0.0.1:18126/api --output target/community-agent-regression.json` | 全部脚本断言通过，SSE终态done，2条completed历史，包含引用 | 真实模型、真实RAG与PostgreSQL |

35是全量JUnit唯一测试数量，单独复跑的4项包含于35项。日志位于target/community-maven-final.log、community-live-run.log、community-browser-run.log、community-agent-regression.log。独立社区测试源码为src/test/java/com/example/wjyaiagent/community/CommunityIntegrationTest.java。

## 真实PostgreSQL及迁移

执行前只读核对数据库wjy_agent、用户wgd_agent、版本17.11，匹配项目文档已确认的开发库。最初只有baseline 0、V001、V002。基线保存在target/community-db-before.json。

开发期间用户IDE启动的应用PID8612于22:16:43运行，22:16:48执行当时的V003。后续源码中V003检查条件增量导致主验收后端Flyway校验报CRC不符。Backend复原V003已执行版本，CRC为-166552946，并新增V004__community_rejection_reason.sql补充驳回原因非NULL约束。最终迁移0、001、002、003、004全部success=true。历史修复操作次数为0，既有记录保留。

新增表为community_category、community_question、community_answer、community_favorite，均引用既有community_users。核对分类唯一名称、收藏联合唯一、各业务外键、审核状态组合CHECK、V004两个rejected_reason_required约束。社区开发库记录保留供复查。

迁移前后及真实模型回归后，knowledge_base_vector_store均47条，全行指纹均为40f50dac83c5829b4a9b74fe1267c34d。Agent及用户既有数据保留。

## 真实接口与安全验收

学生A、学生B、管理员通过现有注册/登录与Cookie Session进入，以下全部通过。

- 发布默认PENDING，作者可见、其他学生404，公共列表与个人列表隔离，写入userId额外字段400。
- 审核通过后公开。双并发管理员审核得到200与409，已审核内容继续操作冲突，审核人和时间持久化。
- 回答默认PENDING，公开列表不返回待审核内容，通过后公开，驳回原因仅本人记录出现。
- 重复和六并发收藏全部204，数据库仅一条记录，学生B收藏独立，取消收藏与再次取消均204。
- 分类新增、重复名称409、修改、停用通过。停用后的历史问题仍可读，新发布409。并发停用/发布均在事务锁下按合法顺序完成。
- 两页size=1稳定返回最新id顺序，分类筛选正确，百分号和下划线按字面搜索，page=0、size=0、size=51均400。
- 匿名读取/写入401，学生管理接口403，无CSRF写入403。账号DISABLED与角色变更均立即使已有Session失效并返回401。
- 登录刷新恢复、退出204及随后/me 401通过。

真实测试学生和管理员均使用communityqa_前缀，密码由脚本随机生成、仅留进程内存。接口脚本先注册专用测试账号，再用精确id与username条件将指定QA账号升级为ADMIN，角色撤销测试后该账号恢复STUDENT。浏览器脚本注册专用学生，通过精确username读取该QA学生的BCrypt哈希并INSERT同次QA管理员。其余QA记录与审核审计保留。

## 真实浏览器验收

学生通过表单发布问题，页面提示等待审核。管理员在问题详情弹窗确认审核。学生收藏、提交回答，管理员在回答弹窗确认通过，刷新后学生看到公开回答。安全测试内容包含img/onerror字符串，页面作为文本显示，无动态img节点与脚本执行。

问题详情刷新后仍保持Session及收藏状态。学生打开管理路由进入权限提示，/auth/me仍200。退出后返回登录。额外拦截一个问题列表GET返回503，页面呈现错误，解除拦截后点击重试恢复，此项属于模拟网络反馈。

以下七个页面在1920、1366、768、390、375px共35个组合下，等待网络空闲并检查documentElement.scrollWidth，均无页面横向溢出。管理表格使用内部滚动。

- /community
- /community/questions/new
- /community/questions/{id}
- /profile?tab=favorites
- /admin/questions
- /admin/answers
- /admin/categories

截图为target/community-desktop.png、target/community-mobile.png。QA目视375px截图确认导航、长标题、纯文本正文、回答、收藏及回答输入区可读可操作。实际点击业务闭环在桌面执行，移动端执行完整内容布局检查。

## 发现与修复

1. 管理审核Dialog确认按钮被底层表格拦截。原因是手动只导入el-dialog叶子CSS，遗漏overlay依赖。Frontend改为官方组件style/css.mjs入口导入，连同Select/Table/Pagination依赖一起补齐，随后真实双审核操作通过。该缺陷已关闭。
2. V003执行后源码CRC漂移，按上述恢复V003加V004方式处理，最终启动与迁移校验通过。该缺陷已关闭。
3. QA脚本早期ElementSelect内部input、回答表格正文、权限提示文字选择器不匹配，已按真实DOM修正。最终脚本完整exit0，保留overlay原失败日志target/community-browser-overlay-failure.log。

## Agent及认证回归

真实注册、登录、CSRF、刷新、退出、实时禁用与角色撤销通过。既有Agent脚本验证匿名401，双学生五种历史/改名/删除/流式/取消越权404，CSRF403，未知owner字段400。真实模型SSE HTTP200并终态done，历史两条completed，hasCitations=true。JUnit覆盖Agent服务、安全、聊天记忆、知识库文档加载、回答处理与限流原逻辑。

## 待验收范围

- community-admin-dev初始化profile已由Chief在独立18127进程实际启动验证，运行时随机DEV_ADMIN凭据，PostgreSQL新增communityqa_boot_1795013273为ADMIN/ACTIVE、BCrypt cost12，证据target/community-admin-bootstrap-result.json。该初始化测试未额外执行登录，ADMIN登录已由上述业务与浏览器闭环验证。Chief随后终止自启18127进程，保留18126与用户原进程。
- 生产HTTPS部署与Secure Cookie由实际部署环境验收。本轮为本地HTTP开发配置。
- 移动设备触屏端完整点击流程与跨进程重启后的社区UI恢复尚未单独执行，本轮已验证真实数据库持久化与浏览器刷新恢复。
- Chief补测旧 /ai/knowledge/chat 普通非流式真实模型请求，HTTP200，answer非空且包含citations。证据target/community-nonstream-regression.json。

## 复查命令

后端按交付报告启动18126，前端API_PROXY_TARGET指向该端口后启动5176。Python真实验收依赖psycopg及PyYAML，并读取本地已有开发数据源配置。浏览器脚本使用PLAYWRIGHT_MODULE指定可用Playwright模块，缺省使用target/community-browser-runtime/node_modules/playwright，浏览器为本地Chrome。

上述脚本会创建可识别QA记录并保留审计，适用于已确认的本地开发联调。本轮未执行Git提交或推送。

## 最终提示状态复测

Chief查看截图发现问题审核通过后，保留submitted=1的详情URL仍显示待审核提示。Frontend将提示条件限定为加载完成且状态PENDING。QA用独立Chrome页面拦截/me、问题详情与回答列表，模拟APPROVED状态访问/community/questions/1?submitted=1，待审核提示数量为0，回答输入区可见。该项为浏览器模拟API的状态渲染测试，P2缺陷已关闭。

