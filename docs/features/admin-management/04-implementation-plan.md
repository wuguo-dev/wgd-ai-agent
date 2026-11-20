# 管理员后台与全系统UI实施计划

### Phase A 审查与冻结
**Status:** complete

复用现有专业Agent，Backend/Frontend/UI先并行审查，UI交付后释放槽位给QA。主Agent冻结03-api-contract，UI契约在02-ui-design维护。工作区已有修改保留，文件责任见接口契约。

### Phase B 业务与UI实施
**Status:** complete

后端实现V005、删除恢复、用户、统计和事务审计，前端实现六后台页面与独立布局、统一Token、社区/AI/Auth实质布局升级。设计只写文档，前端独占页面，QA独占独立测试和真实验收。

### Phase C 集成与真实验收
**Status:** complete

核对开发PG与Flyway历史及向量指纹后迁移。Chief统一Maven test/package和启动独立实例，QA执行权限/并发/删除关联可见性/Session/审计统计/浏览器六视口及AI回归。问题修复后复测并交付真实结果。


### Phase D 2026-11-19 复核与补齐
**Status:** complete

Backend独占AdminAuditRollbackTest.java审计故障回滚测试，Frontend独占前端六类修复，UI/UX独占02尺寸/Token及验收校准，QA独占独立验收脚本/05报告。Chief提供管理员开发启动脚本和文档，统一构建与独立18130/5180实例。复用V005与全部已有业务，冻结接口保持不变。

