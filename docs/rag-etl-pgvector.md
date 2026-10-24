# Markdown ETL 与 PostgreSQL 接入

## 当前配置

- `pom.xml` 使用手动接入依赖：`spring-boot-starter-jdbc`、运行时 PostgreSQL 驱动、`spring-ai-pgvector-store`。版本由现有 BOM 管理，PGVector 核心版本为 1.1.2。
- `application.yml` 默认启用 Spring 的 `local,pgvector` profiles。
- 数据库连接位于 `application-local.yml`，可通过 `PGVECTOR_JDBC_URL`、`PGVECTOR_USERNAME`、`PGVECTOR_PASSWORD` 环境变量覆盖。本地配置含私有凭证，不要将其作为共享示例。
- 向量模型、表和索引位于 `application-pgvector.yml`：DashScope `text-embedding-v2`、1536 维、`public.love_app_vector_store`、HNSW 和余弦距离。
- 激活 `pgvector` 时，原来的内存向量库配置退出，由 `LoveAppPgVectorStoreConfig` 的 `@Bean` 方法显式创建 PostgreSQL VectorStore。`doChatWithRag` 使用它；`doChatWithRagCloud` 仍使用百炼托管知识库。

## 手动初始化方式

`LoveAppPgVectorStoreConfig.loveAppVectorStore(...)` 接收 `JdbcTemplate` 和 `EmbeddingModel`，用 `PgVectorStore.builder(...)` 设置维度、schema、表名、索引类型、距离类型和初始化开关。参数显式读取自现有 `spring.ai.vectorstore.pgvector.*` 配置。

Bean 名称为 `loveAppVectorStore`，现有业务和 ETL 注入可以继续使用。Spring 会自动调用其 `afterPropertiesSet()` 执行可选的 schema 初始化，不要在配置方法里再手动调用一次。代码固定关闭删表。

仅 VectorStore 改为手动创建；DataSource、连接池和 JdbcTemplate 仍由 JDBC Starter 根据 `spring.datasource.*` 提供。

## 正常启动

在项目目录执行：

```powershell
mvn.cmd spring-boot:run
```

首次启动默认开启非破坏性 schema 初始化：创建缺失的扩展、表和索引，不删除已有表。账号需要具备相应权限；若缺少创建扩展权限，需要先由数据库管理员准备相应扩展。

完成初始化后，可通过环境变量关闭初始化：

```powershell
$env:PGVECTOR_INITIALIZE_SCHEMA = 'false'
```

## 导入 Markdown

`LoveAppRagEtlConfig` 读取 `src/main/resources/document/*.md`，过滤空白，按约 500 Token 的目标大小切片，保留来源元数据，并生成稳定 UUID 片段 ID。

默认不导入，避免普通启动反复计算 Embedding。需要首次导入时：

```powershell
$env:LOVE_APP_ETL_ENABLED = 'true'
mvn.cmd spring-boot:run
```

导入成功后，后续启动关闭导入：

```powershell
$env:LOVE_APP_ETL_ENABLED = 'false'
```

相同片段使用相同 ID，PgVectorStore 可以执行 upsert；但显式重跑 ETL 仍会重新计算 Embedding。修改、删除或重命名原文后的旧片段不会自动删除，更新前需按来源清理旧版本。

更换 Embedding 模型或维度时，应使用匹配的表结构并重新生成文档向量；只改聊天模型不需要重建向量。

参考：[Spring AI ETL](https://docs.spring.io/spring-ai/reference/api/etl-pipeline.html)、[Spring AI PgVector](https://docs.spring.io/spring-ai/reference/api/vectordbs/pgvector.html)、[阿里云 RDS pgvector](https://help.aliyun.com/zh/rds/apsaradb-rds-for-postgresql/pgvector-use-guide)。
