# 学生社区 API 契约

管理员完整管理与逻辑删除增量以 [管理员契约](../admin-management/03-api-contract.md) 为准。管理列表现支持全部审核及删除状态，待审核调用显式指定status=PENDING和deleted=ACTIVE。学生所有查询统一隐藏删除问题、删除回答及删除问题关联内容。

## 身份与数据边界

所有路径以 /api 为前缀，复用 HttpOnly Session、CSRF、实时账号状态校验。STUDENT 与 ADMIN 都可访问社区、发布、回答、收藏与自己的记录。ADMIN 通过现有登录入口登录。Agent 路径允许 STUDENT 与 ADMIN。注册固定 STUDENT。管理员由显式 community-admin-dev profile、DEV_ADMIN_USERNAME 与 DEV_ADMIN_PASSWORD 初始化，同名账号保留原状。

## 数据与并发

V003 创建 public.community_category、community_question、community_answer、community_favorite，用户引用 community_users BIGINT。内容状态 PENDING、APPROVED、REJECTED。分类状态 ENABLED、DISABLED。审核记录 reviewed_by、reviewed_at。V004 追加驳回原因非空校验。所有列表 created_at DESC,id DESC，分类 sort_order ASC,id ASC。时间统一 ISO-8601 UTC。分类发布与停用事务锁定分类，审核仅条件更新 PENDING，收藏 UNIQUE(user_id,question_id) 与 ON CONFLICT DO NOTHING。社区领域包采用 controller → service → repository 分层，DTO 独立，无 AI 模块依赖。

## 数据结构

成功直接返回 DTO，沿用已有接口风格。错误沿用 ApiErrorResponse {code,message,chatId}。

- Category 为 {id,name,description,sortOrder,status,createdAt,updatedAt}。
- Question 为 {id,title,content,category:{id,name},author:{id,username},status,rejectReason,createdAt,updatedAt,favorited}。列表同结构，前端截取摘要。
- Answer 为 {id,questionId,questionTitle,content,author:{id,username},status,rejectReason,createdAt,updatedAt}。
- Page 为 {items,page,size,total}，page 从 1 开始，默认 1，size 默认 10，上限 50，非法值返回 400。
- 学生发布 QuestionInput 为 {title,content,categoryId}，title 原始长度最多 200，去除首尾空白后不能为空，content 原始长度最多 20000，去除首尾空白后不能为空。AnswerInput 为 {content}，1..10000。RejectInput 为 {reason}，1..1000。
- CategoryInput 为 {name,description,sortOrder}，name 1..80，description 最多 500，sortOrder 0..10000。StatusInput 为 {status}，仅 ENABLED/DISABLED。新分类 ENABLED。
- 全部写入 DTO 拒绝额外字段，用户、角色、审核状态由服务端决定。

## 学生端

| 方法 | 路径 | 行为与响应 |
| --- | --- | --- |
| GET | /community/categories | 所有分类数组，含停用分类供历史筛选，发布页仅选 ENABLED |
| GET | /community/questions | Page<Question>，支持 page,size,categoryId,keyword，keyword 最多 200，标题/正文参数化字面包含匹配，仅 APPROVED |
| POST | /community/questions | QuestionInput，201 Question，默认 PENDING |
| GET | /community/questions/{id} | Question，仅公开通过内容、本人未通过内容或 ADMIN 可见 |
| GET | /community/questions/{id}/answers | Page<Answer>，验证问题可见，只返回通过回答 |
| POST | /community/questions/{id}/answers | AnswerInput，201 Answer，仅已通过问题，默认 PENDING |
| POST | /community/questions/{id}/favorite | 204，幂等，仅通过问题 |
| DELETE | /community/questions/{id}/favorite | 204，幂等，身份固定当前用户 |
| GET | /community/me/questions | Page<Question>，本人所有状态 |
| GET | /community/me/answers | Page<Answer>，本人所有状态 |
| GET | /community/me/favorites | Page<Question>，本人收藏且问题通过 |

## 管理端

| 方法 | 路径 | 行为与响应 |
| --- | --- | --- |
| GET | /admin/questions | Page<Question>，仅 PENDING |
| POST | /admin/questions/{id}/approve | 200 Question，无请求体 |
| POST | /admin/questions/{id}/reject | RejectInput，200 Question |
| GET | /admin/answers | Page<Answer>，仅 PENDING，含对应问题标题 |
| POST | /admin/answers/{id}/approve | 200 Answer，无请求体，对应问题必须通过 |
| POST | /admin/answers/{id}/reject | RejectInput，200 Answer |
| GET | /admin/categories | Category 数组 |
| POST | /admin/categories | CategoryInput，201 Category |
| PUT | /admin/categories/{id} | CategoryInput，200 Category，状态经专用接口修改 |
| PATCH | /admin/categories/{id}/status | StatusInput，200 Category |

## 错误及前端

400 INVALID_REQUEST 参数或未知字段，401 UNAUTHORIZED 未登录/会话失效，403 FORBIDDEN 权限/CSRF，404 NOT_FOUND 不存在或不可见，409 CONFLICT 重复分类、重复审核或状态冲突，503 SERVICE_UNAVAILABLE 数据库不可用。审核通过与驳回仅 PENDING 可变更，失败保留原状态。正文通过文本插值渲染。

前端路由 /community、/community/questions/new、/community/questions/:id、/profile（tab=questions|answers|favorites）、/admin/questions、/admin/answers、/admin/categories。权限不足保留身份并展示反馈。共享导航包含社区、AI、个人中心，ADMIN 显示管理入口。响应式验证 375、390、768、1366、1920。



