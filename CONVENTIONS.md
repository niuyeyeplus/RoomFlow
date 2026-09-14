# RoomFlow 项目约定

本文件是子 Agent 的权威约定入口，内容来自 `docs/product/requirements.md` 与 `docs/operations/middleware.md`。冲突时以 `docs/product/requirements.md` 为准。未决项不得当作已批准规则。

## 技术栈

- **后端**：Java 21、Spring Boot 3.5.16、MyBatis-Plus 3.5.17、Spring Security、JWT、MySQL 8.4、Redis 8.2、RabbitMQ 4.3、Flyway、Maven 3.9.x Wrapper
- **前端**：Vue 3.5、Vite 8.1、TypeScript 5.9、Element Plus 2.14、Vue Router、Pinia、Vitest、vue-tsc
- **测试**：JUnit / Testcontainers / JaCoCo（后端）、Vitest（前端）、Playwright（E2E）
- **CI/CD**：GitHub Actions、GHCR、Docker

## 构建与检查命令

后端（Maven Wrapper，Windows 用 `mvnw.cmd`，Linux 用 `./mvnw`）：

```bash
mvnw.cmd clean test          # Windows
./mvnw clean test            # Linux
mvnw.cmd verify
mvnw.cmd jacoco:report
```

前端：

```bash
npx vue-tsc --noEmit         # 类型检查，零错误
npx vitest run               # 单测
npm run build                # 构建验证
```

关键业务单测分支覆盖率至少 80%。

## 业务规则（审查与实现必须遵守）

- 实体会议室是有限资源，初始三个房间；ADMIN 可创建、修改、启停、删除房间。
- 所有登录用户可发起会议、查看会议与报名；发起人只管理自己的会议，ADMIN 可管理任意会议。普通用户不能取消别人的会议。
- 时间使用北京时间。开始限未来滚动 72 小时；最短 15 分钟、15 分钟步长，最长 24 小时，允许跨天；不得预约过去。
- 同一房间有效会议不得重叠，相邻时段可连接，使用 `[start, end)` 半开区间。只能预约启用的房间。
- 发起人自动算一名参会者，人数由报名名单计算。容量 1～100，报名无需审批，满容量拒绝；用户可退出。
- 踢人移除报名并禁止该账号再次加入该会议。
- 开始前发起人可改名称、说明、房间及时间（校验时间规则、容量及房间冲突）；开始后仅可改名称、说明、踢人或提前结束，且停止报名。
- 提前结束立即释放剩余时段，到期自动结束。取消保留取消状态；删除物理清除会议及参会记录，释放时段。
- 删除尚有未结束会议的房间时拒绝，需先处理相关会议。
- 用户名唯一，用户名+密码注册登录；不采集邮箱，不发邮件；无忘记密码功能。
- 关闭浏览器后保持登录 30 天并续期；主动退出清除并撤销登录。保留刷新 Token 机制。禁止明文存储密码。
- 站内通知经 RabbitMQ 异步处理；用户不需要会议修改/取消/删除通知。报名、退出、踢人、结束的收件人及事件范围待最终确认。
- 最多五张核心业务表：账号、会议室、会议、参会记录、站内通知。

## 用户可见操作（用于 UI 反馈与确认对话框）

- 注册、登录、退出
- 报名、退出报名
- 踢人（移除报名并禁止再加入）
- 会议取消、会议删除、提前结束
- 房间创建、修改、启停、删除（ADMIN）

注意：本项目**没有**"删除用户"功能。不要在 UI 或 API 中实现删除用户。

## 开发中间件连接

经 SSH 隧道连接服务器 20.205.103.75:2222 上的开发中间件（仅开发，不与 staging/production 共享数据）：

- MySQL：`127.0.0.1:13306`，库/用户 `roomflow`
- Redis：`127.0.0.1:16379`
- AMQP：`127.0.0.1:15673`，用户/vhost `roomflow`
- RabbitMQ 管理页：`http://127.0.0.1:15672`

服务密码仅存服务器 `.env`，不提交到 Git、不复制到公开对话。

## 治理

- 一个功能切片一个分支一个 PR，用户人工审查合并。
- 合并后自动部署 staging，production 发布前用户确认。
- staging/production 隔离开发数据。
- 失败自主修复；同一失败三次无进展时停止该任务并报告证据，不删除测试或降低门禁。
