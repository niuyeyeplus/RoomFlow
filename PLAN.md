# RoomFlow 首版实施计划

**Date:** 2026-09-14
**Planner:** planner agent
**Coordinator:** coordinator agent
**Branch:** TBD — git-workflow 创建首个功能分支 `feat/slice-1-auth-and-meeting`

## Context

RoomFlow 是一个会议室预约管理学习/演示项目，采用 Java 21 / Spring Boot 3.5 后端 + Vue 3 / TypeScript 前端双栈架构。当前仓库处于纯规划阶段：已 `git init`（分支 `main`，远程 `origin`），但无任何业务源代码、无 `pom.xml`、无 `package.json`、无 CI 配置。需求基线已冻结于 `docs/product/requirements.md`，开发中间件（MySQL 8.4 / Redis 8.2 / RabbitMQ 4.3）已部署并通过验证。本计划将首版工作分解为可独立交付的纵向功能切片，每个切片对应一个分支、一个 PR，由对应专家 Agent 实现，经验证流水线门禁后由用户人工审查合并。

## Goals

- 从零搭建可重复构建的后端 Maven 骨架与前端 Vite 骨架
- 实现五张核心业务表与 Flyway 迁移
- 实现超过 10 个 REST API，覆盖认证、会议室、会议、报名、通知全流程
- 交付首个纵向切片：注册/登录 → 创建会议 → 分享报名 → 我的会议 → 站内通知
- 建立 GitHub Actions CI（lint/typecheck/test/build/security）与 GHCR 镜像发布
- 实现 staging 自动部署与 production 确认部署
- 关键业务单测分支覆盖率 ≥ 80%

## Impacted Components

- `backend/` — Java/Spring Boot 后端全量源码（新建）
- `frontend/` — Vue 3/TypeScript 前端全量源码（新建）
- `.github/workflows/` — CI/CD 流水线（新建）
- `docker/` — Dockerfile 与 Compose 编排（新建）
- `docs/` — OpenAPI 契约、ADR、部署文档（增量）
- `PLAN.md` — 本计划文件

---

## a. 后端和前端目录结构

### 后端目录结构（`backend/`）

```
backend/
├── mvnw.cmd / mvnw / .mvn/          # Maven 3.9.x Wrapper
├── pom.xml                           # 依赖与构建配置
├── src/
│   ├── main/
│   │   ├── java/com/roomflow/
│   │   │   ├── RoomFlowApplication.java          # 启动类
│   │   │   ├── config/
│   │   │   │   ├── SecurityConfig.java           # Spring Security 过滤链
│   │   │   │   ├── JwtConfig.java                # JWT 密钥/过期配置
│   │   │   │   ├── MyBatisPlusConfig.java        # 分页/自动填充（逻辑删除采用自定义方案，见 c 节 meeting 表说明）
│   │   │   │   ├── RedisConfig.java              # Redis 序列化/连接韧性
│   │   │   │   ├── RabbitMqConfig.java           # 队列/交换机/DLQ 声明
│   │   │   │   ├── CorsConfig.java               # 跨域配置
│   │   │   │   └── OpenApiConfig.java            # OpenAPI 文档
│   │   │   ├── common/
│   │   │   │   ├── exception/                    # 业务异常 + 全局异常处理器
│   │   │   │   ├── result/                       # 统一响应体 Result<T>
│   │   │   │   ├── enums/                        # 枚举（角色、会议状态等）
│   │   │   │   └── util/                         # 时间工具（北京时间）
│   │   │   ├── security/
│   │   │   │   ├── JwtAuthenticationFilter.java  # JWT 过滤器
│   │   │   │   ├── JwtTokenProvider.java         # Token 签发/验证/刷新
│   │   │   │   ├── SessionTokenStore.java        # Redis 会话 Token 存储/撤销
│   │   │   │   └── UserDetailsServiceImpl.java
│   │   │   ├── domain/                           # 实体/DTO/VO
│   │   │   │   ├── account/
│   │   │   │   ├── room/
│   │   │   │   ├── meeting/
│   │   │   │   ├── participant/
│   │   │   │   └── notification/
│   │   │   ├── mapper/                          # MyBatis-Plus Mapper 接口
│   │   │   ├── service/                          # 业务服务层
│   │   │   │   ├── AccountService.java
│   │   │   │   ├── RoomService.java
│   │   │   │   ├── MeetingService.java
│   │   │   │   ├── ParticipantService.java
│   │   │   │   └── NotificationService.java
│   │   │   ├── controller/                      # REST 控制器
│   │   │   │   ├── AuthController.java
│   │   │   │   ├── RoomController.java
│   │   │   │   ├── MeetingController.java
│   │   │   │   ├── ParticipantController.java
│   │   │   │   └── NotificationController.java
│   │   │   └── mq/
│   │   │       ├── NotificationProducer.java
│   │   │       └── NotificationConsumer.java
│   │   └── resources/
│   │       ├── application.yml                   # 主配置
│   │       ├── application-dev.yml               # 开发环境（SSH 隧道中间件）
│   │       ├── application-staging.yml
│   │       ├── application-prod.yml
│   │       └── db/migration/                     # Flyway 迁移脚本
│   │           ├── V1__init_schema.sql
│   │           └── V2__seed_rooms.sql
│   └── test/
│       ├── java/com/roomflow/
│       │   ├── unit/                             # JUnit 单元测试
│       │   ├── integration/                      # Testcontainers 集成测试
│       │   └── controller/                       # MockMvc 控制器测试
│       └── resources/
│           └── application-test.yml             # 测试专用配置
```

### 前端目录结构（`frontend/`）

```
frontend/
├── package.json
├── vite.config.ts
├── tsconfig.json
├── index.html
├── src/
│   ├── main.ts                       # 应用入口
│   ├── App.vue                       # 根组件
│   ├── router/
│   │   └── index.ts                  # Vue Router 路由 + 导航守卫
│   ├── stores/
│   │   ├── auth.ts                   # 认证状态（Pinia）
│   │   ├── meeting.ts                # 会议状态
│   │   ├── room.ts                   # 会议室状态
│   │   └── notification.ts           # 通知状态
│   ├── api/
│   │   ├── client.ts                 # Axios 实例 + 拦截器
│   │   ├── auth.ts                   # 认证 API
│   │   ├── room.ts                   # 会议室 API
│   │   ├── meeting.ts                # 会议 API
│   │   ├── participant.ts            # 报名 API
│   │   └── notification.ts           # 通知 API
│   ├── views/
│   │   ├── LoginView.vue             # 登录/注册页
│   │   ├── RoomListView.vue          # 会议室与空闲查询
│   │   ├── MeetingListView.vue       # 会议列表
│   │   ├── MeetingDetailView.vue     # 会议详情/分享报名
│   │   ├── MyMeetingsView.vue        # 我的会议与参会
│   │   ├── NotificationView.vue      # 站内通知
│   │   └── admin/
│   │       └── RoomManageView.vue    # 管理后台
│   ├── components/
│   │   ├── MeetingForm.vue           # 会议创建/编辑表单
│   │   ├── RoomForm.vue              # 房间创建/编辑表单
│   │   ├── ParticipantList.vue       # 参会者列表
│   │   ├── ConfirmDialog.vue         # 通用确认对话框
│   │   └── EmptyState.vue
│   ├── composables/
│   │   ├── useAuth.ts
│   │   └── useTimeSlot.ts
│   ├── types/
│   │   └── api.ts                    # API 响应类型定义
│   └── utils/
│       ├── time.ts                   # 北京时间工具
│       └── format.ts
├── tests/
│   ├── unit/                         # Vitest 组件/Store 单测
│   └── e2e/                          # Playwright E2E
│       ├── auth.spec.ts
│   │   └── meeting-flow.spec.ts
└── public/
```

---

## b. Java/Spring Boot 与 Vue 的模块边界

### 后端模块边界

| 模块 | 职责 | 对外暴露 | 依赖方向 |
|---|---|---|---|
| `security` | JWT 签发/验证、会话 Token 存储/撤销、密码加密 | `JwtTokenProvider`、`SessionTokenStore` | 依赖 `domain.account`、`config` |
| `domain.account` | 账户实体、注册/登录 DTO | 实体与 DTO 类 | 无外部依赖 |
| `domain.room` | 会议室实体、房间状态枚举 | 实体与 DTO 类 | 无外部依赖 |
| `domain.meeting` | 会议实体、会议状态枚举、时间校验 | 实体与 DTO 类 | 依赖 `domain.room` |
| `domain.participant` | 参会记录实体、踢人禁入标记 | 实体与 DTO 类 | 依赖 `domain.meeting`、`domain.account` |
| `domain.notification` | 站内通知实体、通知类型枚举 | 实体与 DTO 类 | 无外部依赖 |
| `mapper` | MyBatis-Plus 数据访问 | Mapper 接口 | 依赖 `domain.*` |
| `service` | 业务逻辑、事务边界、业务规则校验 | Service 类 | 依赖 `mapper`、`domain`、`security`、`mq` |
| `controller` | REST 端点、请求/响应映射、权限注解 | HTTP API | 依赖 `service` |
| `mq` | RabbitMQ 消息生产/消费、幂等处理 | Producer/Consumer | 依赖 `domain.notification`、`service` |
| `config` | 框架配置（Security/Redis/MQ/CORS/OpenAPI） | 配置类 | 被各模块引用 |
| `common` | 异常、统一响应体、枚举、工具 | 通用类 | 被各模块引用 |

**依赖方向规则：** `controller → service → mapper → domain`；`service → security / mq`；`domain` 不依赖任何上层模块；`controller` 不直接访问 `mapper`。

### 前端模块边界

| 模块 | 职责 | 依赖方向 |
|---|---|---|
| `api` | HTTP 请求封装、拦截器、Token 注入、错误处理 | 依赖 `types`、`stores/auth` |
| `stores` | Pinia 状态管理、业务状态缓存 | 依赖 `api`、`types` |
| `router` | 路由定义、导航守卫（鉴权） | 依赖 `stores/auth` |
| `views` | 页面级组件、UI 状态编排 | 依赖 `stores`、`components`、`api` |
| `components` | 可复用 UI 组件 | 依赖 `types`、`composables` |
| `composables` | 可复用逻辑（时间槽计算、鉴权） | 依赖 `stores`、`utils` |
| `types` | TypeScript 类型定义（API 契约映射） | 无依赖 |
| `utils` | 纯函数工具（时间格式化等） | 无依赖 |

**依赖方向规则：** `views → stores → api → types`；`components → composables → stores`；`utils` 和 `types` 为叶子模块，不依赖任何上层。

### 前后端契约边界

- **API 契约**由 `api-specialist` 先设计 OpenAPI 规范，作为前后端唯一共享契约
- 后端按 OpenAPI 实现 Controller，前端按 OpenAPI 生成 `types/api.ts`
- 前端不假设后端内部实现；后端不假设前端 UI 状态
- 统一响应体 `Result<T>`（code/message/data）由 OpenAPI 定义，前后端共用

---

## c. 五张核心业务表设计

### 表 1：`account`（账号）

| 列名 | 类型 | 约束 | 说明 |
|---|---|---|---|
| `id` | BIGINT | PK, AUTO_INCREMENT | 主键 |
| `username` | VARCHAR(50) | UNIQUE NOT NULL | 用户名，唯一 |
| `password_hash` | VARCHAR(100) | NOT NULL | BCrypt 加密密码 |
| `role` | VARCHAR(20) | NOT NULL DEFAULT 'USER' | 角色：`USER` / `ADMIN` |
| `status` | TINYINT | NOT NULL DEFAULT 1 | 1=正常, 0=禁用 |
| `created_at` | DATETIME | NOT NULL | 创建时间 |
| `updated_at` | DATETIME | NOT NULL | 更新时间 |

**索引：** `uk_username` (UNIQUE)。无邮箱列、无删除功能。

### 表 2：`room`（会议室）

| 列名 | 类型 | 约束 | 说明 |
|---|---|---|---|
| `id` | BIGINT | PK, AUTO_INCREMENT | 主键 |
| `name` | VARCHAR(100) | NOT NULL | 房间名称 |
| `location` | VARCHAR(200) | | 位置描述 |
| `capacity` | INT | NOT NULL, CHECK 1-100 | 容量 1~100 |
| `equipment` | VARCHAR(500) | | 设备固定选项多选（JSON 或逗号分隔） |
| `enabled` | TINYINT | NOT NULL DEFAULT 1 | 1=启用, 0=停用 |
| `created_at` | DATETIME | NOT NULL | |
| `updated_at` | DATETIME | NOT NULL | |

**索引：** `idx_enabled`。停用前校验无 status=ACTIVE 的会议；停用后（enabled=0）不可预约但保留历史关联。房间不物理删除，采用停用（enabled=0）作为软删除，保留历史会议的 room_id 关联完整性。

### 表 3：`meeting`（会议）

| 列名 | 类型 | 约束 | 说明 |
|---|---|---|---|
| `id` | BIGINT | PK, AUTO_INCREMENT | 主键 |
| `title` | VARCHAR(200) | NOT NULL | 会议名称 |
| `description` | TEXT | | 会议说明 |
| `room_id` | BIGINT | NOT NULL, FK → room.id（普通外键，无 ON DELETE CASCADE/SET NULL，因房间不物理删除，采用停用 enabled=0） | 房间外键 |
| `organizer_id` | BIGINT | NOT NULL, FK → account.id | 发起人外键 |
| `start_time` | DATETIME | NOT NULL | 开始时间（北京时间） |
| `end_time` | DATETIME | NOT NULL | 结束时间（北京时间） |
| `status` | VARCHAR(20) | NOT NULL DEFAULT 'ACTIVE' | `ACTIVE`/`ENDED`/`CANCELLED`/`DELETED` |
| `ended_early` | TINYINT | NOT NULL DEFAULT 0 | 是否提前结束 |
| `created_at` | DATETIME | NOT NULL | |
| `updated_at` | DATETIME | NOT NULL | |

**索引：** `idx_room_status_time` (room_id, status, start_time, end_time) — 用于房间冲突检测查询；`idx_organizer` (organizer_id)。

**业务约束：** `[start, end)` 半开区间不重叠；开始限未来滚动 72 小时；最短 15 分钟、15 分钟步长、最长 24 小时。

**删除策略（逻辑删除）：** 会议删除采用**逻辑删除**——将 `status` 置为 `DELETED`，而非物理删除行。理由：保留历史参会记录与通知关联，避免外键级联删除导致数据丢失，符合 MyBatis-Plus 逻辑删除配置。`status` 枚举已含 `DELETED` 状态，复用该状态作为逻辑删除标记，避免新增 `deleted` 字段造成双标记冲突。

> **MyBatis-Plus 逻辑删除与 status 枚举的配合：** 由于 `status` 为业务枚举（`ACTIVE`/`ENDED`/`CANCELLED`/`DELETED`）而非简单 0/1 标记，不能直接使用 `@TableLogic` 注解（`@TableLogic` 期望布尔/数值逻辑删除字段）。采用**自定义逻辑删除**方案：在 Mapper 查询中显式排除 `status='DELETED'` 的记录（或在 Service 层封装查询条件），而非依赖 `@TableLogic` 自动过滤。房间冲突检测查询基于 `status=ACTIVE` 条件，天然排除 `DELETED` 状态的会议，释放其占用时段。

### 表 4：`participant`（参会记录）

| 列名 | 类型 | 约束 | 说明 |
|---|---|---|---|
| `id` | BIGINT | PK, AUTO_INCREMENT | 主键 |
| `meeting_id` | BIGINT | NOT NULL, FK → meeting.id（普通外键，无 ON DELETE CASCADE） | 会议外键（会议逻辑删除不级联，保留参会历史） |
| `account_id` | BIGINT | NOT NULL, FK → account.id | 账号外键 |
| `is_organizer` | TINYINT | NOT NULL DEFAULT 0 | 是否发起人（自动加入） |
| `banned` | TINYINT | NOT NULL DEFAULT 0 | 是否被踢禁入 |
| `joined_at` | DATETIME | NOT NULL | 报名时间 |
| `left_at` | DATETIME | | 退出时间（NULL=未退出） |
| `leave_reason` | VARCHAR(20) | | 退出原因：`USER_LEFT`（主动退出）/ `KICKED`（被踢）/ NULL（未退出）。用于区分主动退出与被踢，避免被踢用户通过重新报名重置 banned |

**索引：** `uk_meeting_account` (UNIQUE, meeting_id, account_id) — 普通唯一约束（MySQL 8.4 不支持部分索引/partial index，故不加 WHERE 子句），保证同一用户对同一会议最多一条记录；`idx_meeting` (meeting_id)。

> **唯一约束语义说明：** `uk_meeting_account` 为普通唯一约束（无 WHERE 条件），同一用户对同一会议最多存在一条记录。报名/退出/被踢通过**更新该行**的 `left_at` / `banned` / `leave_reason` 字段表达状态，而非插入新行。这样唯一约束语义清晰，避免同一用户对同一会议出现多条历史记录。

**业务约束：**
- 满容量拒绝报名。
- 踢人设 `banned=1` 且 `leave_reason=KICKED`（而非仅设 `banned=1`），永久禁止该账号再次加入该会议，不重置 banned。
- 退出设 `left_at=now` 且 `leave_reason=USER_LEFT`（`banned` 保持 0）。
- 发起人自动创建一条 `is_organizer=1` 记录。
- **报名前查询（业务事务内，非数据库约束）：**
  - 若存在 `(meeting_id, account_id)` 且 `banned=1` 的记录 → 拒绝（被踢永久禁入，不重置 banned）。
  - 若存在 `(meeting_id, account_id)` 且 `left_at IS NULL` 的有效记录 → 拒绝（重复报名）。
  - 若存在 `(meeting_id, account_id)` 且 `leave_reason=USER_LEFT` 的历史记录 → 允许复用该行重新报名。
- **退出后重新报名（主动退出，`leave_reason=USER_LEFT`）：** 允许重新报名，采用**更新现有记录复用**策略——将现有记录的 `left_at` 置为 NULL、`leave_reason` 置为 NULL、`banned` 保持 0（不新增行），以保持唯一约束 `(meeting_id, account_id)` 语义清晰。
- **被踢后重新报名（`leave_reason=KICKED`，`banned=1`）：** 永久拒绝再次加入，不重置 banned、不重置 leave_reason。

### 表 5：`notification`（站内通知）

| 列名 | 类型 | 约束 | 说明 |
|---|---|---|---|
| `id` | BIGINT | PK, AUTO_INCREMENT | 主键 |
| `account_id` | BIGINT | NOT NULL, FK → account.id | 收件人 |
| `type` | VARCHAR(30) | NOT NULL | 通知类型（报名/退出/踢人/结束） |
| `title` | VARCHAR(200) | NOT NULL | 通知标题 |
| `content` | TEXT | NOT NULL | 通知内容 |
| `meeting_id` | BIGINT | | 关联会议（可空，普通外键无 ON DELETE 级联，会议逻辑删除不级联） |
| `is_read` | TINYINT | NOT NULL DEFAULT 0 | 0=未读, 1=已读 |
| `created_at` | DATETIME | NOT NULL | |

**索引：** `idx_account_read` (account_id, is_read, created_at DESC) — 查询用户未读通知列表。

**技术表（非核心业务表，单独列明）：** `session_token`（Redis 存储会话 Token，可选落库）、Flyway `schema_history`（框架管理）。不隐性增加核心业务表。

---

## d. API 分组与职责（超过 10 个 API）

### 认证组（AuthController）

| # | 方法 | 路径 | 职责 | 权限 |
|---|---|---|---|---|
| 1 | POST | `/api/auth/register` | 用户名+密码注册，返回 Token | 公开 |
| 2 | POST | `/api/auth/login` | 用户名+密码登录，返回 Access+Refresh Token | 公开 |
| 3 | POST | `/api/auth/logout` | 退出登录，撤销会话 Token | 已登录 |
| 4 | POST | `/api/auth/refresh` | 刷新 Access Token | 已登录（Refresh Token） |
| 5 | GET | `/api/auth/me` | 获取当前登录用户信息 | 已登录 |

### 会议室组（RoomController）

| # | 方法 | 路径 | 职责 | 权限 |
|---|---|---|---|---|
| 6 | GET | `/api/rooms` | 查询会议室列表（含启用状态筛选） | 已登录 |
| 7 | GET | `/api/rooms/{id}` | 查询会议室详情 | 已登录 |
| 8 | GET | `/api/rooms/{id}/availability` | 查询房间空闲时段（指定日期范围） | 已登录 |
| 9 | POST | `/api/rooms` | 创建会议室 | ADMIN |
| 10 | PUT | `/api/rooms/{id}` | 修改会议室信息 | ADMIN |
| 11 | PATCH | `/api/rooms/{id}/status` | 启用/停用会议室 | ADMIN |
| 12 | DELETE | `/api/rooms/{id}` | 停用会议室（enabled=0，软删除），停用前校验无未结束（status=ACTIVE）会议，停用后不可预约但保留历史关联 | ADMIN |

### 会议组（MeetingController）

| # | 方法 | 路径 | 职责 | 权限 |
|---|---|---|---|---|
| 13 | GET | `/api/meetings` | 查询会议列表（分页、按房间/日期/状态筛选） | 已登录 |
| 14 | GET | `/api/meetings/{id}` | 查询会议详情（含参会者列表） | 已登录 |
| 15 | POST | `/api/meetings` | 创建会议（校验时间规则、容量、房间冲突） | 已登录 |
| 16 | PUT | `/api/meetings/{id}` | 修改会议（开始前可改名称/说明/房间/时间） | 发起人或 ADMIN |
| 17 | PATCH | `/api/meetings/{id}/cancel` | 取消会议（保留取消状态） | 发起人或 ADMIN |
| 18 | DELETE | `/api/meetings/{id}` | 删除会议（逻辑删除，status 置为 DELETED，释放时段，对普通用户不可见，保留历史参会记录与通知关联） | 发起人或 ADMIN |
| 19 | PATCH | `/api/meetings/{id}/end-early` | 提前结束会议（释放剩余时段，停止报名） | 发起人或 ADMIN |

### 报名组（ParticipantController）

| # | 方法 | 路径 | 职责 | 权限 |
|---|---|---|---|---|
| 20 | POST | `/api/meetings/{id}/participants` | 报名会议（满容量拒绝，被踢禁入拒绝） | 已登录 |
| 21 | DELETE | `/api/meetings/{id}/participants/me` | 退出报名（设 left_at=now、leave_reason=USER_LEFT，banned 保持 0） | 已登录（自己） |
| 22 | DELETE | `/api/meetings/{id}/participants/{accountId}` | 踢人（设 banned=1 且 leave_reason=KICKED，永久禁止再加入） | 发起人或 ADMIN |
| 23 | GET | `/api/meetings/{id}/participants` | 查询参会者列表 | 已登录 |

### 通知组（NotificationController）

| # | 方法 | 路径 | 职责 | 权限 |
|---|---|---|---|---|
| 24 | GET | `/api/notifications` | 查询站内通知列表（分页，未读优先） | 已登录 |
| 25 | PATCH | `/api/notifications/{id}/read` | 标记单条通知已读 | 已登录（自己） |
| 26 | PATCH | `/api/notifications/read-all` | 全部标记已读 | 已登录 |

**总计 26 个 API**，覆盖认证、会议室管理、会议全生命周期、报名/踢人、站内通知全部业务流程。

> **会议到期自动结束（内部调度，非对外 API）：** 到期自动结束通过应用内调度任务实现（ShedLock + Redis 锁 + `@Scheduled` 轮询），无需新增对外 REST API。调度逻辑扫描 `end_time <= now` 且 `status=ACTIVE` 的会议并自动结束，详见 Subtask 8。

---

## e. 首个纵向功能切片

**切片名称：** 认证 + 会议核心流程

**切片目标：** 打通从注册/登录到创建会议、分享报名、查看我的会议、收到通知的完整业务闭环。

**切片范围：**

1. **后端骨架搭建** — `pom.xml`、Maven Wrapper、`RoomFlowApplication`、基础配置（dev/staging/prod profile）
2. **数据库迁移** — Flyway `V1__init_schema.sql`（五张核心表）+ `V2__seed_rooms.sql`（三个预置房间）
3. **安全层** — Spring Security 配置、JWT 签发/验证、会话 Token Redis 存储/撤销、密码 BCrypt 加密
4. **认证 API** — 注册、登录、退出、刷新、获取当前用户（API #1-5）
5. **会议室 API** — 查询列表、详情、空闲时段（API #6-8）；创建/修改/启停/停用（API #9-12）
6. **会议 API** — 创建、查询列表、查询详情（API #13-15）
7. **报名 API** — 报名、退出、踢人、查询参会者（API #20-23）
8. **通知 API** — 查询、标记已读（API #24-26，MQ 异步处理）
9. **前端骨架搭建** — `package.json`、Vite 配置、Vue Router、Pinia、Element Plus、API client
10. **前端页面** — 登录/注册页、会议室列表、会议列表、会议详情/分享报名、我的会议、站内通知
11. **E2E 测试** — Playwright 覆盖完整流程

**切片验收场景：**

1. 用户注册 → 登录 → 浏览会议室 → 查看空闲时段
2. 创建会议 → 复制分享链接 → 另一用户登录 → 通过链接报名
3. 发起人查看我的会议 → 看到参会者 → 踢人 → 被踢用户无法再次报名
4. 报名用户收到站内通知 → 标记已读
5. 时间边界校验：过去时间拒绝、72 小时外拒绝、15 分钟步长校验、房间冲突拒绝
6. 容量校验：满容量拒绝报名
7. 权限校验：普通用户不能取消他人会议、不能管理房间

---

## f. 测试计划

### 后端测试（JUnit + Testcontainers + JaCoCo）

| 测试类型 | 范围 | 工具 | 覆盖目标 |
|---|---|---|---|
| 单元测试 | Service 层业务逻辑（时间校验、容量校验、房间冲突、踢人禁入、权限判断） | JUnit 5 + Mockito | 关键业务分支覆盖率 ≥ 80% |
| 集成测试 | Mapper 层 CRUD、Flyway 迁移、Redis 会话存储/撤销、RabbitMQ 消息收发 | Testcontainers (MySQL/Redis/RabbitMQ) | 真实中间件交互验证 |
| 控制器测试 | REST API 请求/响应、状态码、权限注解、参数校验 | MockMvc + `@WebMvcTest` | 所有端点 happy path + error path |
| 安全测试 | JWT 签发/验证/过期/刷新、会话撤销、密码加密、权限拒绝 | Spring Security Test | 认证授权全路径 |

**关键测试用例清单：**

- 时间边界：过去时间拒绝、72h 外拒绝、<15min 拒绝、非 15min 步长拒绝、>24h 拒绝、跨天允许
- 房间冲突：同一房间重叠拒绝、相邻时段 `[start,end)` 允许连接、停用房间拒绝
- 容量：满容量拒绝、主动退出后可重新报名、被踢后永久拒绝报名（即使再次调用报名 API）、重新报名后 banned=0/left_at=NULL/leave_reason=NULL
- 权限：普通用户不能取消他人会议、不能管理房间、ADMIN 可管理任意会议
- 会话：关闭浏览器 30 天保持登录、续期、主动退出撤销 Token、刷新 Token 轮换
- MQ：消息重复消费幂等、消费失败重试与 DLQ
- 会议自动结束：到期后 status 从 ACTIVE 变为 ENDED、ended_early 保持 0、时段释放（房间冲突检测不再占用）、ShedLock 多实例锁互斥、到期发送结束通知给全体参会者

### 前端测试（Vitest）

| 测试类型 | 范围 | 工具 |
|---|---|---|
| 组件测试 | 关键组件渲染、表单校验、确认对话框、空状态/错误状态 | Vitest + @vue/test-utils |
| Store 测试 | Pinia store 状态变更、action 调用、API mock | Vitest |
| 类型检查 | 全量 TypeScript 类型零错误 | vue-tsc --noEmit |
| 构建验证 | 生产构建成功 | npm run build |

### E2E 测试（Playwright）

| 场景 | 步骤 |
|---|---|
| 认证流程 | 注册 → 登录 → 退出 → 重新登录 |
| 会议创建与报名 | 登录 → 创建会议 → 分享链接 → 切换用户 → 报名 |
| 踢人流程 | 创建会议 → 报名 → 踢人 → 验证被踢用户无法再加入 |
| 通知流程 | 报名 → 收到通知 → 标记已读 |
| 权限边界 | 普通用户访问管理页面被拒、尝试取消他人会议被拒 |
| 会议到期自动结束 | 创建会议（短结束时间）→ 等待调度轮询（短间隔配置）→ 验证状态变为 ENDED、时段释放、参会者收到结束通知 |

---

## g. Git 分支与 PR 拆分方式

### 分支命名规范

- 功能分支：`feat/slice-N-<描述>`（如 `feat/slice-1-auth-and-meeting`）
- 修复分支：`fix/<描述>`
- CI/基建分支：`ci/<描述>` 或 `chore/<描述>`

### PR 拆分策略

**原则：** 一个功能切片一个分支一个 PR，用户人工审查合并。

| PR | 分支 | 范围 | 依赖 |
|---|---|---|---|
| PR-0 | `chore/project-skeleton` | 后端 Maven 骨架 + 前端 Vite 骨架 + .gitignore + 基础配置 | 无 |
| PR-1 | `ci/github-actions` | GitHub Actions CI 流水线 + Dockerfile + GHCR | PR-0 |
| PR-2 | `feat/slice-1-auth-and-meeting` | 认证 + 会议室 + 会议 + 报名 + 通知（首个纵向切片） | PR-0, PR-1 |
| PR-3 | `feat/admin-room-management` | 管理后台房间 CRUD + 启停/停用（软删除） | PR-2 |
| PR-4 | `feat/meeting-lifecycle` | 会议修改/取消/删除/提前结束完整生命周期 | PR-2 |
| PR-5 | `feat/staging-deploy` | staging 自动部署 + 健康检查 + 回滚 | PR-2 |
| PR-6 | `feat/production-deploy` | production 确认部署 + 备份恢复 + 交接文档 | PR-5 |

### 合并规则

- 所有 PR 必须通过 CI 全部门禁
- 所有 PR 必须通过 `java-reviewer` / `vue-reviewer` 审查
- 合并到 `main` 前必须用户人工审查
- 合并后自动触发 staging 部署
- production 部署前用户确认

---

## h. GitHub Actions CI 阶段

```yaml
# .github/workflows/ci.yml 阶段设计
stages:
  - name: checkout
    description: 检出代码

  - name: backend-build-test
    description: 后端构建与测试
    commands:
      - cd backend && ./mvnw spotless:check spotbugs:check
      - ./mvnw clean test
      - ./mvnw jacoco:report
    gates:
      - Spotless 格式检查通过
      - SpotBugs 无高危告警
      - 编译通过
      - 全部单元测试通过
      - JaCoCo 关键业务分支覆盖率 ≥ 80%

  - name: backend-integration
    description: 后端集成测试（Testcontainers）
    needs: backend-build-test
    services:
      - docker (Testcontainers MySQL/Redis/RabbitMQ)
    commands:
      - cd backend && ./mvnw verify
    gates:
      - 集成测试全部通过

  - name: frontend-typecheck-test
    description: 前端类型检查与单测
    commands:
      - cd frontend && npm ci
      - npx eslint .
      - npx prettier --check .
      - npx vue-tsc --noEmit
      - npx vitest run
    gates:
      - ESLint 零错误
      - Prettier 格式检查通过
      - vue-tsc 零错误
      - Vitest 全部通过

  - name: frontend-build
    description: 前端构建验证
    needs: frontend-typecheck-test
    commands:
      - cd frontend && npm run build
    gates:
      - 构建成功

  - name: security-scan
    description: 安全扫描
    needs: [backend-build-test, frontend-build]
    tools:
      - dependency vulnerability scan (Maven + npm audit)
      - secret detection (trufflehog/gitleaks)
    gates:
      - 无高危漏洞
      - 无密钥泄漏

  - name: e2e-tests
    description: Playwright E2E 测试
    needs: [backend-integration, frontend-build]
    services:
      - docker (启动后端 + 前端容器)
    commands:
      - cd frontend && npx playwright test
    gates:
      - E2E 全部通过

  - name: docker-build-push
    description: 构建并推送 Docker 镜像到 GHCR
    needs: [e2e-tests, security-scan]
    outputs:
      - backend image: ghcr.io/niuyeyeplus/roomflow-backend:${{ sha }}
      - frontend image: ghcr.io/niuyeyeplus/roomflow-frontend:${{ sha }}
    gates:
      - 镜像构建成功并推送

  - name: deploy-staging
    description: 自动部署到 staging
    needs: docker-build-push
    trigger: main 分支合并
    gates:
      - 健康检查通过
```

---

## i. staging 和 production 部署阶段

### staging 部署

| 项目 | 说明 |
|---|---|
| 触发条件 | PR 合并到 `main` 后自动触发 |
| 部署方式 | Docker Compose 拉取 GHCR 按 commit SHA 锁定的镜像（与 CI 推送的 tag 一致，不可变） |
| 环境隔离 | 独立数据库/Redis/RabbitMQ，不与开发或 production 共享数据 |
| 健康检查 | Actuator `/actuator/health` + 前端页面可达 |
| 回滚方式 | 回退到上一个 commit SHA 对应的镜像 tag 重新部署 |
| 验证内容 | 核心流程上线验证：注册/登录/创建会议/报名/通知 |

### production 部署

| 项目 | 说明 |
|---|---|
| 触发条件 | 用户手动确认后触发（GitHub Actions `workflow_dispatch`） |
| 部署方式 | Docker Compose 拉取指定 tag 镜像（显式 tag 或 commit SHA，均不可变，与 staging 不可变镜像 tag 策略一致） |
| 环境隔离 | 独立数据库/Redis/RabbitMQ，与 staging 完全隔离 |
| 域名 | 部署前询问用户域名；当前不将 HTTPS 作为完成前提，后期可添加 |
| 备份恢复 | 数据库定期备份 + 恢复验证 |
| 回滚方式 | 回退镜像 tag + 数据库回滚验证 |
| 交付物 | 维护文档 + 最终交接文档齐全 |

---

## j. 子任务、Agent 分配、文件所有权与验收条件

### Subtask 1: 项目骨架搭建（后端 Maven + 前端 Vite）

- **Agent:** `java-spring-developer`（后端骨架）+ `vue-developer`（前端骨架）
- **Files:**
  - 后端：`backend/pom.xml`、`backend/mvnw.cmd`、`backend/mvnw`、`backend/.mvn/`、`backend/src/main/java/com/roomflow/RoomFlowApplication.java`、`backend/src/main/resources/application.yml`、`backend/src/main/resources/application-dev.yml`
  - 前端：`frontend/package.json`、`frontend/vite.config.ts`、`frontend/tsconfig.json`、`frontend/index.html`、`frontend/src/main.ts`、`frontend/src/App.vue`
- **Description:** 搭建可编译构建的后端 Maven 骨架与前端 Vite 骨架，配置依赖版本矩阵（Java 21、Spring Boot 3.5.16、MyBatis-Plus 3.5.17、Vue 3.5、Vite 8.1、TypeScript 5.9、Element Plus 2.14），配置开发环境 profile 指向 SSH 隧道中间件。后端集成 Maven Spotless（格式化检查）+ SpotBugs（静态分析）插件配置（pom.xml `<plugin>` 声明）；前端集成 ESLint + Prettier 配置文件（`.eslintrc.*`、`.prettierrc.*`）。
- **Acceptance criteria:**
  - `mvnw.cmd clean test` 编译通过（无业务代码时空测试通过）
  - `./mvnw spotless:check spotbugs:check` 通过（插件配置正确，无格式/静态分析错误）
  - `npx eslint .` 零错误、`npx prettier --check .` 通过（ESLint/Prettier 配置正确）
  - `npx vue-tsc --noEmit` 零错误
  - `npm run build` 构建成功
  - `application-dev.yml` 连接配置指向 `127.0.0.1:13306`（MySQL）、`127.0.0.1:16379`（Redis）、`127.0.0.1:15673`（AMQP）
- **Verification:** `swe-check`（POM/Vite 配置 bug）→ `java-reviewer` + `vue-reviewer`
- **Priority:** High
- **Risk:** Medium（依赖版本兼容性需验证）

### Subtask 2: OpenAPI 契约设计

- **Agent:** `api-specialist`
- **Files:** `docs/api/openapi.yaml`、`docs/api/README.md`
- **Description:** 设计全部 26 个 API 的 OpenAPI 3.0 规范，定义统一响应体 `Result<T>`、错误码、状态码、请求/响应 schema、认证方式（Bearer JWT）、权限标注。作为前后端唯一共享契约。
- **Acceptance criteria:**
  - OpenAPI 规范通过 lint（如 spectral）
  - 覆盖全部 26 个 API
  - 包含全部五张核心表的 schema 定义
  - 错误码与状态码文档完整
- **Verification:** `architecture-reviewer`（契约与需求一致性）
- **Priority:** High
- **Risk:** Low

### Subtask 3: 数据库迁移与实体层

- **Agent:** `java-spring-developer`
- **Files:** `backend/src/main/resources/db/migration/V1__init_schema.sql`、`backend/src/main/resources/db/migration/V2__seed_rooms.sql`、`backend/src/main/java/com/roomflow/domain/**`、`backend/src/main/java/com/roomflow/mapper/**`、`backend/src/main/java/com/roomflow/config/MyBatisPlusConfig.java`
- **Description:** 编写 Flyway 迁移脚本创建五张核心表 + 三个预置房间种子数据；实现全部实体类、MyBatis-Plus Mapper 接口、分页/自动填充配置。会议逻辑删除采用自定义方案（复用 status=DELETED，非 @TableLogic 注解，详见 c 节 meeting 表说明）。
- **Acceptance criteria:**
  - `mvnw.cmd clean test` 通过
  - Flyway 迁移在 Testcontainers MySQL 中执行成功
  - 五张表结构与设计一致
  - 三个预置房间种子数据正确
- **Verification:** `swe-check` → `java-reviewer`
- **Priority:** High
- **Risk:** Low

### Subtask 4: 安全层（JWT + Spring Security + 会话管理）

- **Agent:** `java-spring-developer`
- **Files:** `backend/src/main/java/com/roomflow/security/**`、`backend/src/main/java/com/roomflow/config/SecurityConfig.java`、`backend/src/main/java/com/roomflow/config/JwtConfig.java`、`backend/src/main/java/com/roomflow/config/RedisConfig.java`
- **Description:** 实现 Spring Security 过滤链、JWT 签发/验证/刷新、Redis 会话 Token 存储/撤销、BCrypt 密码加密、30 天长期登录与续期、主动退出撤销 Token。
- **Acceptance criteria:**
  - JWT 签发/验证/过期/刷新单元测试通过
  - 会话 Token Redis 存储/撤销集成测试通过
  - 密码 BCrypt 加密验证
  - 30 天续期与退出撤销测试通过
  - 关键分支覆盖率 ≥ 80%
- **Verification:** `security-auditor`（密钥/注入）→ `java-reviewer`
- **Priority:** High
- **Risk:** High（安全关键路径）

### Subtask 5: 认证 API 实现

- **Agent:** `java-spring-developer`
- **Files:** `backend/src/main/java/com/roomflow/controller/AuthController.java`、`backend/src/main/java/com/roomflow/service/AccountService.java`、`backend/src/main/java/com/roomflow/domain/account/**`、`backend/src/test/java/com/roomflow/**/Auth*Test.java`
- **Description:** 实现注册、登录、退出、刷新、获取当前用户 5 个 API。用户名唯一校验、密码 BCrypt 加密、JWT 签发、会话 Token 存储。
- **Acceptance criteria:**
  - 5 个 API 的 MockMvc 测试全部通过（happy path + error path）
  - 用户名重复注册拒绝
  - 密码错误登录拒绝
  - 退出后 Token 撤销验证
  - 分支覆盖率 ≥ 80%
- **Verification:** `java-reviewer` → `security-auditor`
- **Priority:** High
- **Risk:** Medium

### Subtask 6: 会议室 API 实现

- **Agent:** `java-spring-developer`
- **Files:** `backend/src/main/java/com/roomflow/controller/RoomController.java`、`backend/src/main/java/com/roomflow/service/RoomService.java`、`backend/src/main/java/com/roomflow/domain/room/**`、`backend/src/test/java/com/roomflow/**/Room*Test.java`
- **Description:** 实现会议室查询列表、详情、空闲时段查询、创建、修改、启停、停用（软删除）7 个 API。停用前校验无 status=ACTIVE 的未结束会议；房间不物理删除，采用停用（enabled=0）作为软删除，保留历史会议的 room_id 关联完整性。
- **Acceptance criteria:**
  - 7 个 API 测试全部通过
  - ADMIN 权限校验（普通用户操作被拒）
  - 停用房间前校验无 ACTIVE 会议（有未结束会议时拒绝停用）
  - 停用后（enabled=0）不可被预约（创建/修改会议时拒绝选用停用房间）
  - 停用后历史会议关联保留（meeting.room_id 仍指向该房间，不物理删除房间行）
  - 空闲时段查询正确（排除 ACTIVE 会议占用时段）
  - 分支覆盖率 ≥ 80%
- **Verification:** `java-reviewer`
- **Priority:** High
- **Risk:** Medium

### Subtask 7: 会议 API 实现

- **Agent:** `java-spring-developer`
- **Files:** `backend/src/main/java/com/roomflow/controller/MeetingController.java`、`backend/src/main/java/com/roomflow/service/MeetingService.java`、`backend/src/main/java/com/roomflow/domain/meeting/**`、`backend/src/test/java/com/roomflow/**/Meeting*Test.java`
- **Description:** 实现会议创建、查询列表、查询详情、修改、取消、删除、提前结束 7 个 API。包含全部时间规则校验、房间冲突检测、容量校验、权限校验。
- **Acceptance criteria:**
  - 7 个 API 测试全部通过
  - 时间边界全部测试：过去拒绝、72h 外拒绝、<15min 拒绝、非 15min 步长拒绝、>24h 拒绝、跨天允许
  - 房间冲突检测：重叠拒绝、相邻 `[start,end)` 允许
  - 停用房间拒绝预约
  - 权限：发起人管理自己会议，ADMIN 管理任意会议，普通用户不能取消他人会议
  - 删除会议后该时段释放、对普通用户不可见、历史参会记录保留（逻辑删除，status=DELETED，非物理删除行）
  - 分支覆盖率 ≥ 80%
- **Verification:** `java-reviewer` → `architecture-reviewer`（业务规则一致性）
- **Priority:** High
- **Risk:** High（核心业务逻辑）

### Subtask 8: 会议状态调度与自动结束

- **Agent:** `java-spring-developer`
- **Files:** `backend/src/main/java/com/roomflow/service/MeetingScheduler.java`、`backend/src/main/java/com/roomflow/config/SchedulingConfig.java`、`backend/src/main/java/com/roomflow/config/ShedLockConfig.java`（ShedLock + Redis 锁配置）、`backend/src/test/java/com/roomflow/**/MeetingScheduler*Test.java`
- **Description:** 实现会议到期自动结束的调度任务。使用 **ShedLock + Redis 分布式锁**（选型理由：staging/production 可能多实例部署，ShedLock 保证同一时刻仅一个实例执行调度，避免重复结束会议；Redis 已作为中间件部署，无需额外依赖）。通过 `@Scheduled` 固定延迟轮询扫描 `end_time <= now` 且 `status=ACTIVE` 的会议，在事务内将 `status` 从 `ACTIVE` 更新为 `ENDED`（`ended_early` 保持 0，区分自动结束与提前结束）。状态变为 `ENDED` 后，房间冲突检测查询（`idx_room_status_time` 基于 `status=ACTIVE`）自动不再把该时段视为占用，释放时段。到期自动结束**发送结束通知**给全体参会者（与手动提前结束一致），通过 RabbitMQ 发送结束通知消息。
- **Acceptance criteria:**
  - 调度任务使用 ShedLock + Redis 锁，多实例环境下仅一个实例执行（集成测试验证锁互斥）
  - 到期会议 `status` 从 `ACTIVE` 更新为 `ENDED`，`ended_early` 保持 0
  - 到期后房间冲突检测不再把该时段视为占用（时段释放验证）
  - 到期自动结束触发结束通知，通过 RabbitMQ 发送给全体参会者
  - 集成测试（Testcontainers MySQL/Redis/RabbitMQ）验证：到期后状态变为 ENDED、时段释放、通知发送
  - 测试可用短轮询间隔配置（如 `roomflow.scheduler.interval=2s`）或时间 Mock 验证，避免长等待
  - 分支覆盖率 ≥ 80%
- **Verification:** `java-reviewer` → `architecture-reviewer`（调度与业务规则一致性）
- **Priority:** High
- **Risk:** High（调度与并发、通知一致性）

### Subtask 9: 报名 API 实现

- **Agent:** `java-spring-developer`
- **Files:** `backend/src/main/java/com/roomflow/controller/ParticipantController.java`、`backend/src/main/java/com/roomflow/service/ParticipantService.java`、`backend/src/main/java/com/roomflow/domain/participant/**`、`backend/src/test/java/com/roomflow/**/Participant*Test.java`
- **Description:** 实现报名、退出、踢人、查询参会者 4 个 API。满容量拒绝、被踢禁入、发起人自动加入。报名前查询区分主动退出（USER_LEFT，可重新报名）与被踢（KICKED，永久禁入）。踢人设 banned=1 且 leave_reason=KICKED；退出设 left_at=now 且 leave_reason=USER_LEFT（banned 保持 0）。
- **Acceptance criteria:**
  - 4 个 API 测试全部通过
  - 满容量拒绝报名
  - 被踢用户禁止再次加入（banned=1 且 leave_reason=KICKED，永久拒绝，即使再次调用报名 API）
  - 主动退出后可重新报名（leave_reason=USER_LEFT，重新报名后 banned=0、left_at=NULL、leave_reason=NULL）
  - 重新报名后 banned=0、left_at=NULL、leave_reason=NULL（复用现有行，不新增行）
  - 发起人自动算参会者
  - 退出后可重新报名（未被踢）
  - 分支覆盖率 ≥ 80%
- **Verification:** `java-reviewer`
- **Priority:** High
- **Risk:** Medium

### Subtask 10: 通知 API + RabbitMQ 异步处理

- **Agent:** `java-spring-developer`
- **Files:** `backend/src/main/java/com/roomflow/controller/NotificationController.java`、`backend/src/main/java/com/roomflow/service/NotificationService.java`、`backend/src/main/java/com/roomflow/mq/**`、`backend/src/main/java/com/roomflow/config/RabbitMqConfig.java`、`backend/src/main/java/com/roomflow/domain/notification/**`、`backend/src/test/java/com/roomflow/**/Notification*Test.java`
- **Description:** 实现通知查询、标记已读、全部已读 3 个 API。RabbitMQ Producer/Consumer 异步处理通知消息，幂等消费，重试与 DLQ。
- **Acceptance criteria:**
  - 3 个 API 测试通过
  - MQ 消息生产/消费集成测试通过
  - 幂等消费验证（重复消息 ID 不重复处理）
  - DLQ 配置正确
  - 分支覆盖率 ≥ 80%
- **Verification:** `java-reviewer` → `security-auditor`
- **Priority:** Medium
- **Risk:** Medium（MQ 异步复杂度）

### Subtask 11: 前端 API 层与状态管理

- **Agent:** `vue-developer`
- **Files:** `frontend/src/api/**`、`frontend/src/stores/**`、`frontend/src/types/**`、`frontend/src/utils/**`、`frontend/src/router/index.ts`
- **Description:** 实现 Axios 客户端（拦截器、Token 注入、401 刷新、错误处理）、Pinia stores（auth/meeting/room/notification）、Vue Router 路由与导航守卫、TypeScript 类型定义。
- **Acceptance criteria:**
  - `npx vue-tsc --noEmit` 零错误
  - `npx vitest run` 全部通过
  - Store 测试覆盖状态变更与 API mock
  - 导航守卫正确拦截未认证访问
- **Verification:** `vue-reviewer`
- **Priority:** High
- **Risk:** Medium

### Subtask 12: 前端页面实现

- **Agent:** `vue-developer`
- **Files:** `frontend/src/views/**`、`frontend/src/components/**`、`frontend/src/composables/**`、`frontend/tests/unit/**`
- **Description:** 实现登录/注册页、会议室列表、会议列表、会议详情/分享报名、我的会议、站内通知、管理后台全部页面。包含表单校验、确认对话框、加载/空/错误状态处理。
- **Acceptance criteria:**
  - `npx vue-tsc --noEmit` 零错误
  - `npx vitest run` 全部通过
  - `npm run build` 构建成功
  - 关键组件有 Vitest 测试
  - 确认对话框覆盖取消/删除/踢人等破坏性操作
- **Verification:** `vue-reviewer`
- **Priority:** High
- **Risk:** Medium

### Subtask 13: Dockerfile 与容器化

- **Agent:** `devops-docker`
- **Files:** `docker/backend.Dockerfile`、`docker/frontend.Dockerfile`、`docker/docker-compose.yml`、`docker/docker-compose.staging.yml`、`docker/docker-compose.prod.yml`、`docker/.dockerignore`
- **Description:** 编写后端多阶段 Dockerfile（Maven 构建 + JRE 运行）与前端多阶段 Dockerfile（Vite 构建 + Nginx 运行）。编写开发/staging/production Compose 编排，环境隔离配置。
- **Acceptance criteria:**
  - 后端镜像构建成功
  - 前端镜像构建成功
  - Compose 编排启动成功
  - staging/production 环境隔离（独立数据库卷）
  - 镜像按摘要锁定
- **Verification:** `swe-check`（Docker 配置 bug）→ `security-auditor`
- **Priority:** Medium
- **Risk:** Medium

### Subtask 14: GitHub Actions CI 流水线

- **Agent:** `qa-ci-agent`
- **Files:** `.github/workflows/ci.yml`、`.github/workflows/deploy-staging.yml`、`.github/workflows/deploy-production.yml`
- **Description:** 实现 CI 流水线（后端 Spotless/SpotBugs lint + 构建测试、前端 ESLint/Prettier + 类型检查测试、集成测试、安全扫描、E2E、Docker 构建推送 GHCR）。配置 staging 自动部署与 production 手动确认部署 workflow。
- **Acceptance criteria:**
  - CI 在 PR 上实际跑通
  - 后端 Spotless 格式检查 + SpotBugs 静态分析门禁生效
  - 前端 ESLint 零错误 + Prettier 格式检查门禁生效
  - 全部门禁生效（测试/类型检查/覆盖率/安全）
  - GHCR 镜像成功推送
  - staging 自动部署 workflow 正确
  - production `workflow_dispatch` 触发正确
- **Verification:** `swe-check`（CI 配置 bug）→ `security-auditor`（密钥管理）
- **Priority:** High
- **Risk:** Medium

### Subtask 15: E2E 测试（Playwright）

- **Agent:** `playwright-testing`
- **Files:** `frontend/tests/e2e/**`、`frontend/playwright.config.ts`
- **Description:** 编写 Playwright E2E 测试覆盖完整业务流程：注册/登录、创建会议、分享报名、踢人、通知、权限边界。
- **Acceptance criteria:**
  - E2E 测试在 CI 中通过
  - 覆盖全部 6 个 E2E 场景（含会议到期自动结束）
  - 权限边界场景验证（普通用户被拒）
- **Verification:** `swe-check` → `java-reviewer`（后端交互正确性）
- **Priority:** Medium
- **Risk:** Medium

### Subtask 16: 安全扫描与密钥检测

- **Agent:** `security-auditor`
- **Files:** `.github/workflows/security.yml`（如需独立）、扫描配置文件
- **Description:** 配置依赖漏洞扫描（Maven + npm audit）、密钥检测（gitleaks/trufflehog）、注入风险扫描。确保服务密码不进入 Git。
- **Acceptance criteria:**
  - 无高危漏洞
  - 无密钥泄漏
  - 扫描集成到 CI 门禁
- **Verification:** `swe-check`
- **Priority:** High
- **Risk:** Medium

### Subtask 17: 文档与交接

- **Agent:** `documentation-agent`
- **Files:** `README.md`、`docs/api/README.md`、`docs/deployment.md`、`docs/handover.md`
- **Description:** 编写项目 README、API 文档、部署指南（staging/production）、维护文档与最终交接文档。
- **Acceptance criteria:**
  - README 包含构建/运行/测试说明
  - 部署文档包含 staging/production 配置
  - 交接文档包含备份恢复与回滚流程
- **Verification:** `architecture-reviewer`（文档与实现一致性）
- **Priority:** Low
- **Risk:** Low

### Subtask 18: Git 分支与 PR 流程验证

- **Agent:** `git-workflow`
- **Files:** Git 操作（无文件所有权，仅操作 git）
- **Description:** 基于已有初始提交（646fff7）创建首个功能分支、提交 PLAN.md 与后续骨架代码、创建 PR。验证 GitHub 权限与 PR 流程。本任务不负责仓库初始化（已完成），仅负责功能分支与 PR 流程验证。
- **Acceptance criteria:**
  - 分支创建成功
  - 功能分支上提交成功（PLAN.md 及后续骨架代码提交到功能分支）
  - PR 创建成功
  - GitHub 权限验证通过
- **Verification:** `swe-check`
- **Priority:** High
- **Risk:** Low

---

## Verification Pipeline

每个子任务完成后按以下流水线验证（来自 AGENTS.md）：

1. **`swe-check`** — 制品 bug 检测（Docker/CI/配置/POM 语法/Vite 配置）
2. **测试** — `mvnw.cmd test`（后端）、`npx vitest run`（前端）、Playwright（E2E）
3. **`security-auditor`** — 密钥检测、依赖漏洞、注入风险
4. **`qa-ci-agent`** — lint、typecheck、CI 门禁
5. **`java-reviewer` + `vue-reviewer`** — 并行语言专项审查（只读，不修复）
6. **`best-practices-reviewer`** — 跨语言约定审查（视范围）
7. **`architecture-reviewer`** — 结构一致性审查（视范围）
8. **人工审查** — 合并 `main` 前必需

失败自主修复；同一失败三次无进展时停止该任务并报告证据，不删除测试或降低门禁。

---

## Acceptance Criteria (Overall)

- 全部 18 个子任务完成
- 后端 `mvnw.cmd clean test` + `mvnw.cmd verify` 通过，JaCoCo 关键业务分支覆盖率 ≥ 80%
- 前端 `npx vue-tsc --noEmit` 零错误、`npx vitest run` 通过、`npm run build` 成功
- Playwright E2E 全部通过
- GitHub Actions CI 实际跑通，GHCR 镜像成功推送
- staging 自动部署验证通过
- production 部署前用户确认
- 安全扫描无高危问题
- `java-reviewer` + `vue-reviewer` 审查通过
- 用户人工审查合并
- 维护文档与交接文档齐全

---

## Open Questions（未决业务问题）

以下问题来自 `docs/product/requirements.md` 未决项，需用户确认后才能最终实现，当前计划按合理默认值推进：

1. **站内通知的最终事件、收件人及会议删除后的通知处理** — 报名/退出/踢人/结束的具体收件人及事件范围待确认。当前默认：报名通知发给发起人、退出通知发给发起人、踢人通知发给被踢人、结束通知发给全体参会者；会议删除不发通知（用户明确不需要修改/取消/删除通知）。

2. **三个预置房间的名称、位置、容量** — 当前默认：房间 A（301 会议室，容量 8）、房间 B（302 会议室，容量 12）、房间 C（多功能厅，容量 20）。需用户确认。

3. **初始 ADMIN 的创建方式与密码交付** — 当前默认：Flyway 种子数据创建 ADMIN 账号 `admin`，密码由用户首次登录后修改。需用户确认用户名与初始密码交付方式。

4. **长期会话续期的详细机制** — Token 轮换与存储方案由认证设计明确。当前默认：Access Token 短期有效 + Refresh Token 30 天，Redis 存储会话 Token 支持撤销，刷新时轮换 Refresh Token。

5. **删除/取消/结束边界** — 取消保留取消状态（链接显示已取消）、会议删除采用逻辑删除（status 置为 DELETED，物理行保留，链接显示不存在，保留历史参会记录与通知关联）、提前结束释放剩余时段、到期自动结束释放时段并发送结束通知。房间删除统一为逻辑停用（enabled=0，软删除），停用前校验无 status=ACTIVE 的未结束会议，停用后不可预约但保留历史会议 room_id 关联（不物理删除房间行）。需确认 UI 展示细节。

6. **UI/API 设计的详细契约** — OpenAPI 细节（分页参数、排序、筛选字段）待 `api-specialist` 设计后用户确认。

7. **插件加载与子 Agent 实际执行** — 自定义 Agent 属实验性能力，Profile 加载成功不等于运行时可调用，需经真实子任务验证。

8. **GitHub 写入与 CI 权限及 staging/production 部署环境** — GHCR 推送权限、部署服务器 SSH 密钥、staging/production 服务器资源待确认。

9. **域名与 HTTPS** — 部署前询问用户域名；当前不将 HTTPS 作为完成前提，后期可添加。

10. **设备固定选项** — 会议室设备使用固定选项多选，具体选项列表待用户确认（如投影仪、白板、视频会议设备、电话等）。

11. **Lint 工具选型待用户确认** — `CONVENTIONS.md` 未指定 lint/静态检查工具。当前计划固定选型：后端 Maven Spotless（格式化检查）+ SpotBugs（静态分析），命令 `./mvnw spotless:check spotbugs:check`；前端 ESLint + Prettier，命令 `npx eslint . && npx prettier --check .`。后端 spotless/spotbugs 配置归 `java-spring-developer`（Subtask 1 骨架），前端 `.eslintrc`/`.prettierrc` 归 `vue-developer`（Subtask 1 骨架）。若用户希望改用 checkstyle 或其他工具，需同步更新 CI 阶段命令与门禁。
