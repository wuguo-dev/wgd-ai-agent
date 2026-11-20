# 管理员后台接口与状态契约

## 身份和兼容

所有接口以 /api 为前缀，沿用当前Session、CSRF、ApiErrorResponse与ADMIN后端权限。成功直接DTO，分页{items,page,size,total}，page默认1，size默认10、范围1..50。时间ISO8601 UTC。列表默认createdAt DESC,id DESC，sort=NEWEST|OLDEST，OLDEST使用双ASC。所有搜索参数最多200字符，参数化字面包含匹配（转义%/_）。未知写字段400，错误沿用400 INVALID_REQUEST、401、403、404 NOT_FOUND、409 CONFLICT、503 SERVICE_UNAVAILABLE。

GET /admin/questions、/admin/answers扩展为全部内容管理，默认包括全部审核及删除状态。待审核使用status=PENDING&deleted=ACTIVE。原有approve/reject及category路径保留。

## 数据与可见性

V005扩展现有问题与回答deleted_at、deleted_by、delete_reason，三字段全NULL或全非NULL且原因非空，保留原status。新增public.admin_operation_log，审计与业务写入同一事务。禁止修改已执行V001..V004。

学生端所有列表、搜索、详情、本人提问/回答/收藏都隐藏已删除问题。回答查询同时隐藏自身删除和父问题删除。本人回答若自身删除也隐藏。删除问题保留关联回答/收藏，不能新增回答收藏。取消收藏仍按本人幂等。管理员使用/admin详情查看删除信息，/community详情即使ADMIN也按删除不可见。恢复仅清除删除标记，保留原审核状态，不改变分类状态。删除、恢复、审核使用锁与条件更新，重复409。问题操作锁question，回答操作先question后answer，统一锁顺序。已删除资源不可审核，回答审核要求父问题未删除且APPROVED。恢复回答允许父问题已删除，公共可见性继续受父状态限制。

账号启停只允许STUDENT，禁止修改ADMIN和本人，角色状态校验在服务端。禁用后已有Session下次请求401，启用后需重新登录。重复状态写409，保留数据。

## DTO

Question沿用社区结构，管理响应附deletedAt(可NULL)、deletedBy(用户ID可NULL)、deleteReason(可NULL)。Answer沿用结构附相同删除信息与questionDeleted布尔。普通未删除记录删除字段为NULL。

User为{id,username,role,status,createdAt,updatedAt}，不含密码哈希或其他凭据。OperationLog为{id,operator:{id,username},action,targetType,targetId,reason,beforeState,afterState,createdAt}，beforeState/afterState为安全字段的JSON字符串，只含状态、删除元数据或分类配置。日志无修改删除接口。

Stats为{studentTotal,activeStudents,disabledStudents,questionTotal,answerTotal,pendingQuestions,pendingAnswers,deletedQuestions,deletedAnswers}。学生数仅STUDENT，总内容数包含已删除内容及已删除问题下的回答。待审核问题排除自身删除，待审核回答排除自身删除、父删除且要求父APPROVED。deletedAnswers仅计回答自身删除。无趋势统计。

DeleteInput={reason}，去首尾后不能为空，原始长度最多1000。UserStatusInput={status,reason?}，status仅ACTIVE/DISABLED，reason可选最多1000。Restore及approve无body。reject沿用{reason}必填。分类请求沿用现有字段与限制。

## 接口

| 方法 | 路径 | 请求/响应 |
| --- | --- | --- |
| GET | /admin/dashboard/stats | Stats |
| GET | /admin/questions | Page<Question>，page,size,sort,keyword标题,author用户名,categoryId,status=PENDING/APPROVED/REJECTED,deleted=ALL/ACTIVE/DELETED（默认ALL） |
| GET | /admin/questions/{id} | Question，含删除记录 |
| DELETE | /admin/questions/{id} | DeleteInput，200 Question |
| POST | /admin/questions/{id}/restore | 无body，200 Question |
| GET | /admin/answers | Page<Answer>，page,size,sort,questionId,keyword问题标题,author用户名,status,deleted同上 |
| GET | /admin/answers/{id} | Answer |
| DELETE | /admin/answers/{id} | DeleteInput，200 Answer |
| POST | /admin/answers/{id}/restore | 无body，200 Answer |
| GET | /admin/users | Page<User>，page,size,sort,keyword用户名,role=STUDENT/ADMIN,status=ACTIVE/DISABLED |
| GET | /admin/users/{id} | User |
| PATCH | /admin/users/{id}/status | UserStatusInput，200 User |
| GET | /admin/operation-logs | Page<OperationLog>，page,size,operatorId正整数,action枚举,startAt/endAt为ISO8601 Instant，含起止边界，开始晚于结束400 |

日志action为QUESTION_APPROVE/QUESTION_REJECT/QUESTION_DELETE/QUESTION_RESTORE、ANSWER_APPROVE/ANSWER_REJECT/ANSWER_DELETE/ANSWER_RESTORE、USER_ENABLE/USER_DISABLE、CATEGORY_CREATE/CATEGORY_UPDATE/CATEGORY_ENABLE/CATEGORY_DISABLE。targetType=QUESTION|ANSWER|USER|CATEGORY。已有审核和分类写入均追加审计，失败操作事务回滚不记成功。分类重复设置同一启停状态返回409。

## 前端与验收

/admin默认转/admin/dashboard。六菜单为dashboard、questions、answers、users、categories、operation-logs，统一独立AdminLayout。问题回答页整合已有审核。学生/Agent/Auth维持路由与流程，UI统一采用02-ui-design.md。危险操作二次确认，删除必填原因，失败保留输入。六视口1920/1366/1024/768/390/375，键盘焦点、移动抽屉滚动恢复、弹窗层及真实业务回归均验收。

## 文件边界

Backend独占Java、迁移及现有后端测试适配。Frontend独占frontend及其测试。UI/UX独占02-ui-design.md。QA独占AdminManagementIntegrationTest.java、scripts/admin-*验收脚本及05-test-report.md。Chief独占其他本目录文档、项目规则与集成。设计、接口改动先同步契约。
