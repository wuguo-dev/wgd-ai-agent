# wgd-ai-agent（恋爱大师 MCP 智能体）

Spring Boot 3.4.4 + Spring AI 1.1.2 + Spring AI Alibaba 的演示工程：把 DashScope 对话模型、PgVector 知识库
和本地/高德 MCP 工具装配成一个可多轮对话、可流式返回、可调用地图工具的「恋爱大师」智能体。

## 技术栈

| 层 | 选型 |
| --- | --- |
| 运行时 | Java 21、Spring Boot 3.4.4 |
| 模型 | DashScope（`spring-ai-alibaba-starter-dashscope`，默认 `qwen-plus` / `text-embedding-v2`） |
| Agent | `spring-ai-alibaba-agent-framework`（`ReactAgent`、检查点/记忆、工具回调） |
| MCP | `spring-ai-mcp` + `io.modelcontextprotocol.sdk:mcp:0.17.0`（手工装配，不用 MCP starter） |
| 知识库 | Spring AI PgVector（PostgreSQL + pgvector，1536 维） |
| 接口文档 | springdoc-openapi 2.8.9 自带 Swagger UI（原 Knife4j 4.5.0 因与 Spring Framework 6.2 不兼容已移除，见下） |
| 构建 | Maven 3.9（依赖走阿里云公共仓库镜像） |

## 快速开始

环境要求：JDK 21、Maven 3.9+。仅当使用高德 MCP 的 `stdio` 备选方案时才需要 Node/npx。

```powershell
mvn -DskipTests compile        # 首次构建（需要联网拉依赖）
mvn -o -DskipTests compile     # 离线门槛命令，后续所有波次都以它为基线
mvn test                       # 只跑离线用例（不联网、不调模型、不连数据库）
mvn spring-boot:run            # 启动应用（默认 8123 端口，context-path=/api）
```

打包运行：

```powershell
mvn -DskipTests package
java -jar target/wgd-ai-agent-0.0.1-SNAPSHOT.jar
```

## 聊天页

启动后访问 <http://localhost:8123/api/index.html>：流式打字机输出、同一会话复用 `chatId`、可填写 `X-API-Key`。

页面是单文件静态资源（`src/main/resources/static/index.html`），**零外链依赖**（无 CDN、无前端构建链），断网也能打开。

接口与页面共用的错误码：

| 场景 | HTTP | code |
| --- | --- | --- |
| 启用鉴权但 Key 缺失/错误 | 401 | `UNAUTHORIZED` |
| 超出限流额度（响应头带 `Retry-After`） | 429 | `RATE_LIMITED` |

## 依赖从哪来：阿里云镜像

**为什么必须配镜像**：本机到 Maven Central 的连接不可用（`curl` 返回 000），只有
`https://maven.aliyun.com/repository/public` 可达。不配镜像时任何需要下载依赖的构建都会失败。

三处配置各管一件事，缺一不可：

| 文件 | 作用 | 谁在用 |
| --- | --- | --- |
| `.mvn/settings.xml` | 声明 `aliyun-public` mirror 且 `mirrorOf=*`，把**所有**仓库（含 Central、插件仓库）重定向到阿里云 | 命令行 `mvn` |
| `.mvn/maven.config` | 一行 `-s.mvn/settings.xml`，让上面的 settings 自动对项目根目录下的每条 `mvn` 命令生效 | 命令行 `mvn` |
| `pom.xml` 的 `<repositories>` / `<pluginRepositories>` | 把同一镜像登记为项目级仓库 | IDE（IntelliJ 等默认不读 `maven.config`） |

注意 `.mvn/maven.config` 的写法：Maven 3.9 把该文件的**每一行当作一个整体参数**解析，所以
`-s` 与路径之间**不能有空格**，必须是 `-s.mvn/settings.xml`（等价于命令行里的 `-s .mvn/settings.xml`）。
写成两段的 `-s .mvn/settings.xml` 会被当成一个参数，路径变成带前导空格的非法路径，所有 `mvn` 命令直接失败。

不想依赖 `maven.config` 的场景（CI 脚本、其它目录、部分 IDE 内置 Maven），显式传参即可：

```powershell
mvn -s .mvn/settings.xml -o -DskipTests compile
```

验证镜像是否真的生效：

```powershell
mvn -o validate -X 2>&1 | Select-String "Reading user settings"   # 应指向 <项目根>\.mvn\settings.xml
```

另一个容易踩的点：本机依赖落在 Maven 安装目录 `conf/settings.xml` 指定的本地仓库
（`Maven home` 下配置的 `localRepository`），**不是** `~/.m2/repository`。排查「离线构建缺件」时别找错目录。

> 首次构建必须联网一次，把 `spring-ai-mcp`、`mcp:0.17.0` 等灌进本地仓库；之后再跑 `mvn -o ...` 才不会缺件。

## 环境变量

| 变量 | 必须 | 用途 |
| --- | --- | --- |
| `AMAP_MCP_KEY` | 生产必须 | 高德 MCP 凭据；未设置时不建连接、接口照常返回（降级路径） |
| `AMAP_MCP_ENABLED` | 否 | 设为 `false` 可禁用高德 MCP（灰度 / 排障） |
| `AMAP_MCP_PROTOCOL` | 否 | 传输方式，默认 `sse`；切 `stdio` 走 npx 本地进程 |
| `AMAP_MCP_BASE_URL` / `AMAP_MCP_SSE_PATH` | 否 | 指向代理或自建端点 |
| `LOVE_AGENT_MEMORY_TYPE` | 否 | Agent 记忆/检查点实现（如 `file`） |
| `LOVE_AGENT_MEMORY_DIR` | 否 | 检查点目录（默认 `chat-memory/`，已被 `.gitignore` 排除） |
| `PGVECTOR_JDBC_URL` / `PGVECTOR_USERNAME` / `PGVECTOR_PASSWORD` | 本地已有 | 知识库连接（既有配置） |
| `PGVECTOR_INITIALIZE_SCHEMA` | 否 | 是否在启动时创建 pgvector 扩展/表/索引，默认 `true` |
| `LOVE_APP_ETL_ENABLED` | 否 | 仅导入/更新知识库时置 `true`，否则每次启动都会重算 Embedding |
| `LOVE_APP_API_KEY` | 否 | 非空则启用 API Key 鉴权（只保护 `/ai/**`；`/hello`、接口文档、聊天页不受影响） |
| `LOVE_APP_RATE_LIMIT_PER_MINUTE` / `LOVE_APP_RATE_LIMIT_BURST` | 否 | 令牌桶限流（默认 30/30）；超限 429 + `Retry-After`；未启用鉴权时按客户端 IP 计数 |
| `LOVE_AGENT_LOG_REASONING` | 否 | 设 `true` 才打印框架推理日志（含 system prompt / 工具入参 / 答复全文），默认关闭 |

> MCP 工具白名单：高德托管端点实测暴露 15 个工具，默认屏蔽 3 个 `maps_schema_*` 元工具（暴露 12 个），
> 见 `love-app.agent.mcp.exclude-tools`（默认 `["maps_schema_*"]`）。匹配规则：`*` 结尾做前缀匹配，否则全名精确匹配；
> 该配置是**整体覆盖**，自定义时记得带上 `maps_schema_*`，否则元工具会重新暴露。

PowerShell 设置示例（只对当前进程生效）：

```powershell
$env:AMAP_MCP_KEY = '<your-key>'
mvn -Plive test
```

## 密钥规则（硬性要求）

- **密钥绝不可入库**。`AMAP_MCP_KEY`、DashScope key、数据库口令一律只从进程环境读取。
- `src/main/resources/application-local.yml` 已被 `.gitignore` 排除，用于放本地凭据；**不要 `git add -f`**，
  也不要把其中的值复制到任何被跟踪的文件（含 `application.yml`、测试、README、脚本）。
- 日志、异常 message、HTTP 响应体都不得出现 key 或其片段，包括 URL query 里的 key。
- 交付前自检：

```powershell
git grep -n "AMAP_MCP_KEY="
git grep -n "mcp.amap.com/sse?key="
```

两条命令的结果里都不允许出现真实 key。

## 测试分层

| 层 | 特征 | tag | `mvn test` 默认 |
| --- | --- | --- | --- |
| L1 纯单元 | 无 Spring 上下文、无网络，磁盘只用 `@TempDir` | `offline` | 执行 |
| L2 切片 | 手工构造上下文 + mock/假替身 | `offline` | 执行 |
| L3 集成 | 真模型、真 pgvector、真高德，需要环境变量且花钱 | `live` | **跳过** |

surefire 通过 `<excludedGroups>${surefire.excluded.groups}</excludedGroups>` 过滤，属性默认值为 `live`；
profile `live` 把它改成 `none` 并只跑 `groups=live`。

```powershell
# 门槛命令（离线，不联网 / 不调 LLM / 不连 RDS）
mvn -o -DskipTests compile
mvn -o test

# 真实联调（需要 AMAP_MCP_KEY、DashScope key、PGVECTOR_*，会花钱）
mvn -Plive test

# 只跑某一类
mvn -o test -Dtest='LoveApp*Test'
```

在 Windows PowerShell 里给 `-D` 参数加引号更稳（未加引号时 `-Dfoo=a.b.c` 可能被 shell 拆成两个参数，
表现为 `Unknown lifecycle phase ".b.c"`）：

```powershell
mvn -o dependency:tree "-Dincludes=io.modelcontextprotocol.sdk"
```

## 接口文档（Swagger UI）

应用启动后：

- Swagger UI：<http://localhost:8123/api/swagger-ui.html>（会跳转到 `/api/swagger-ui/index.html`）
- OpenAPI JSON：<http://localhost:8123/api/v3/api-docs>

也可以用 `curl` 自检：

```powershell
curl.exe -s -o NUL -w "%{http_code}`n" http://localhost:8123/api/v3/api-docs
curl.exe -s -o NUL -w "%{http_code}`n" http://localhost:8123/api/swagger-ui.html
```

端口与 context-path 来自 `src/main/resources/application.yml`（`server.port=8123`、`server.servlet.context-path=/api`）。
文档只扫描 `com.wgd.wgdaiagent.controller`（`springdoc.group-configs[0].packages-to-scan`），
新增 controller 必须落在该包下才会出现在文档里。

### 为什么不再用 Knife4j

原先依赖 `knife4j-openapi3-jakarta-spring-boot-starter:4.5.0`，它把 springdoc 钉在 2.3.0。
Boot 3.4.4 使用的 Spring Framework 6.2.x 已删除 `ControllerAdviceBean(Object)` 构造器，
于是 `/api/v3/api-docs`、`/api/doc.html` 必然 500（`NoSuchMethodError`）。实测的版本矩阵：

| springdoc | `getGroupConfigs()` 返回类型 | `GenericResponseService` 是否仍调 `ControllerAdviceBean(Object)` | 结果 |
| --- | --- | --- | --- |
| 2.3.0 | `List`（knife4j 4.5.0 的编译目标） | 是 | api-docs 500 |
| 2.5.0 / 2.6.0 | `Set` | 是 | api-docs 500 |
| 2.7.x | 阿里云仓库不存在（不可用） | — | — |
| 2.8.0 / 2.8.9 | `Set` | 否 | knife4j 自身 `NoSuchMethodError` |

结论：不存在能同时满足 knife4j 4.5.0 与 Spring Framework 6.2 的 springdoc 版本，且 knife4j 4.6.0
在仓库中不存在（404）。因此移除 knife4j、改用 springdoc 官方 Swagger UI，并用
`springdoc-openapi-bom:2.8.9` 锁住全部 springdoc 模块版本（`/api/doc.html` 因此不再存在）。

## 目录速览

```
.mvn/settings.xml            # 阿里云镜像（mirrorOf=*）
.mvn/maven.config            # 自动给每条 mvn 命令加 -s .mvn/settings.xml
src/main/java/com/wgd/wgdaiagent/
  app/                       # ChatClient 形态的示例应用（恋爱大师、销售分析）
  agent/                     # ReactAgent 内核、prompt 装载、流式输出映射
  tool/                      # 本地工具 + 高德 MCP 工具提供者
  rag/                       # 知识库文档加载、PgVector 配置、ETL
  chatmemory/                # 会话记忆实现
  advisor/                   # 自定义 Advisor（复读改写）
  controller/ service/ model/ exception/   # HTTP 接口层（/ai/love/agent/**）
src/main/resources/
  application.yml            # 端口、springdoc（Swagger UI）、重试策略、love-app.* 配置
  application-local.yml      # 本地凭据（gitignore，不入库）
  application-pgvector.yml   # 向量库参数、ETL 开关
  document/                  # 知识库 Markdown
  prompts/                   # Agent system prompt
src/test/java/               # offline / live 两层测试（@Tag）
```

## 排障

| 现象 | 常见原因 |
| --- | --- |
| `Could not transfer ... repo.maven.apache.org` | mirror 未生效：确认 `.mvn/maven.config` 存在且为 `-s.mvn/settings.xml`（无空格），或显式 `mvn -s .mvn/settings.xml ...` |
| `The specified user settings file does not exist: ...\ .mvn\settings.xml` | 就是把 `-s` 和路径写成了两段（多了空格） |
| `Unknown lifecycle phase ".xxx"` | `-Dkey=value` 被 shell 拆开，给整个参数加引号 |
| `mvn -o ...` 报缺依赖 | 本地仓库没预热：先跑一次不带 `-o` 的 `mvn -DskipTests compile` |
| `mvn -o test` 跑出一堆 live 用例 | 测试类漏了 `@Tag`，默认只需 `offline` / `live` 二选一 |
| `/api/v3/api-docs` 返回 500 且日志有 `NoSuchMethodError: ControllerAdviceBean` | springdoc 版本与 Spring Framework 6.2 不匹配；本仓库已锁 springdoc 2.8.9，**不要**再把 knife4j 4.5.0 加回来 |
| `/api/doc.html` 返回 404 | 正常：knife4j 已移除，用 `/api/swagger-ui.html` |
