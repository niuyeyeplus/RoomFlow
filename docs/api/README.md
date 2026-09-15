# RoomFlow API 契约说明

本目录是前后端唯一共享契约：

- `openapi.yaml` — OpenAPI 3.0.3 规范，覆盖全部 26 个 REST API。
- 本文件 — 契约通用约定：统一响应体、错误码表、分页、权限矩阵、时区、遗留假设。

后端（Spring Boot）与前端（Vue 3 / TS）必须严格按契约实现；契约未覆盖的行为不得擅自扩展。契约变更必须先改 `openapi.yaml` 与本文件，再改实现。

## 1. 统一响应体 `Result<T>`

所有响应（含错误）均为 JSON：

```json
{ "code": 0, "message": "success", "data": { } }
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `code` | int | `0`=成功；非 0=业务错误码（见错误码表） |
| `message` | string | 人类可读提示（中文），错误响应不得含堆栈或内部细节 |
| `data` | T \| null | 业务数据；无数据操作返回 `null`；`code=40001` 时为 `{fieldErrors:[{field,message}]}` |

前端以 `code===0` 判断业务成功；HTTP 状态码用于传输层语义（401 触发刷新流程等），两者保持一致。

## 2. 错误码表

| code | HTTP | 含义 | 典型场景 |
|---|---|---|---|
| 0 | 2xx | 成功 | — |
| 40001 | 400 | 参数校验失败 | 字段长度/格式/枚举/必填校验不通过；`data.fieldErrors` 给出字段级明细 |
| 40002 | 400 | 请求格式错误 | JSON 解析失败、参数类型不匹配、缺请求体；亦覆盖传输层必填参数缺失/非法（含 `MissingServletRequestParameterException`、required query 参数缺失、path/query 参数类型转换失败） |
| 40101 | 401 | 未认证 / Token 无效 / 已撤销 | 缺 Bearer 头、签名错误、logout 后或账号禁用后复用 |
| 40102 | 401 | Access Token 过期 | 前端应调 `POST /api/auth/refresh` |
| 40103 | 401 | 用户名或密码错误 | 登录；不区分用户不存在与密码错误（防枚举） |
| 40104 | 401 | Refresh Token 无效 / 过期 / 已撤销 | 刷新；前端应回到登录页 |
| 40301 | 403 | 无权限 | 普通用户调 ADMIN 端点；非发起人/非 ADMIN 管理他人会议 |
| 40302 | 403 | 账号已禁用 | 禁用账号登录 |
| 40401 | 404 | 资源不存在或对当前用户不可见 | 房间/会议/参会记录/通知不存在；会议 DELETED；读他人通知（防枚举） |
| 40901 | 409 | 用户名已存在 | 注册 |
| 40902 | 409 | 会议时间冲突 | 与目标房间 status=ACTIVE 会议的 `[start,end)` 重叠 |
| 40903 | 409 | 容量已满 | 报名人数达房间容量；PUT 会议改到的房间容量 < 当前有效参会人数 |
| 40904 | 409 | 已被禁止报名 | 曾被踢出该会议（banned=1，KICKED，永久禁入） |
| 40905 | 409 | 时间规则违规 | 过去时间 / 开始超 72h 窗口 / 非 15min 对齐 / 时长 <15min 或 >24h |
| 40906 | 409 | 房间已停用 | 创建/修改会议选用 enabled=false 的房间 |
| 40907 | 409 | 房间存在进行中会议不可停用 | 停用/删除房间时仍有 status=ACTIVE 会议 |
| 40908 | 409 | 重复报名 | 已有有效参会记录（leftAt 为 null），含发起人 |
| 40909 | 409 | 会议状态不允许该操作 | 非 ACTIVE 的写操作；已开始会议改时间/房间、再报名、取消、发起人退出；未开始会议调 end-early；踢发起人 |
| 50000 | 500 | 服务器内部错误 | 未捕获异常；响应不含堆栈 |

未知错误一律按 50000 处理；`x-error-codes` 标注每个端点可能返回的业务码全集。

## 3. 认证与安全

- `securityScheme`: `bearerAuth`（HTTP Bearer + JWT）。全局默认要求，公开端点以 `security: []` 显式豁免。
- `register` / `login` / `refresh` 返回 `AuthTokenVO`：`accessToken` + `accessTokenExpiresIn`（秒）+ `refreshToken` + `refreshTokenExpiresIn`（秒，30 天=2592000）+ `tokenType=Bearer`。示例中的 1800 为示例值，实际有效期以服务端配置为准。
- `POST /api/auth/refresh` 不走 Bearer 头，凭证为请求体 `refreshToken`；成功即轮换，旧 Refresh Token 立即失效。
- `POST /api/auth/logout` 撤销当前会话（Access Token 及其 Refresh Token），幂等。
- 前端 401 处理：`40102`→尝试 refresh；`40101`/`40104`→清空本地会话回登录页。

## 4. 权限矩阵（x-permission）

| 端点 | 权限 |
|---|---|
| POST /api/auth/register | PUBLIC |
| POST /api/auth/login | PUBLIC |
| POST /api/auth/refresh | REFRESH_TOKEN（请求体凭证） |
| POST /api/auth/logout | AUTHENTICATED |
| GET /api/auth/me | AUTHENTICATED |
| GET /api/rooms、GET /api/rooms/{id}、GET /api/rooms/{id}/availability | AUTHENTICATED |
| POST /api/rooms、PUT /api/rooms/{id}、PATCH /api/rooms/{id}/status、DELETE /api/rooms/{id} | ADMIN |
| GET /api/meetings、GET /api/meetings/{id}、POST /api/meetings | AUTHENTICATED |
| PUT /api/meetings/{id}、PATCH /api/meetings/{id}/cancel、DELETE /api/meetings/{id}、PATCH /api/meetings/{id}/end-early | ORGANIZER_OR_ADMIN |
| POST /api/meetings/{id}/participants、GET /api/meetings/{id}/participants | AUTHENTICATED |
| DELETE /api/meetings/{id}/participants/me | SELF（本人退出；发起人不可退出，应 cancel） |
| DELETE /api/meetings/{id}/participants/{accountId} | ORGANIZER_OR_ADMIN（不可踢发起人） |
| GET /api/notifications、PATCH /api/notifications/read-all | AUTHENTICATED（仅本人数据） |
| PATCH /api/notifications/{id}/read | SELF（他人通知返回 40401） |

## 5. 时间与业务规则约定

- **时区**：所有时间字段为北京时间，ISO 8601 且必须带 `+08:00` 偏移（`2026-09-16T14:00:00+08:00`）。`format: date` 参数为北京时间日历日 `YYYY-MM-DD`。服务端不接受 `Z` 或其他偏移。
- **时段语义**：`[startTime, endTime)` 半开区间；相邻时段允许首尾相接。
- **时间规则**：开始时间须 ≥ now 且 ≤ now+72h（窗口只约束开始）；起止时间均按 15 分钟对齐（秒=0，分钟%15=0）；时长 15min–24h 含端点；允许跨天；endTime 可超出 72h 窗口。违反 → 40905。
- **冲突检测**：仅与同房间 status=ACTIVE 会议比较 `[start,end)` 重叠；重叠 → 40902。
- **会议状态**：`ACTIVE`（未开始+进行中）/ `ENDED`（到期 endedEarly=false 或提前结束 true）/ `CANCELLED` / `DELETED`（逻辑删除，所有查询不可见）。
- **报名口径**：有效参会者 = `leftAt 为 null` 的记录；`participantCount` 同口径且含发起人。主动退出（USER_LEFT）可重新报名（复用行）；被踢（KICKED + banned=1）永久禁入 → 40904。
- **开始后语义**：会议开始后停止报名；仅可改 title/description、踢人、提前结束；取消仅限未开始。
- **房间软删除**：DELETE 与 PATCH status(enabled=false) 等效，存在 ACTIVE 会议时拒绝（40907）；停用房间不可被新会议选用（40906），历史关联保留。

## 6. 分页约定

- 请求参数：`page`（int ≥1，默认 1）、`size`（int 1–100，默认 10）。越界 → 40001。
- 响应：`PageResult<T> = {records: T[], total: int64, page: int, size: int}`，page/size 回显请求值。
- 适用端点：`GET /api/meetings`（排序 startTime DESC、id DESC）、`GET /api/notifications`（排序 isRead ASC 未读优先、createdAt DESC、id DESC）。
- 不分页端点：`GET /api/rooms`（id 升序）、`GET /api/meetings/{id}/participants`（joinedAt、id 升序）。

## 7. availability 语义

`GET /api/rooms/{id}/availability?startDate=YYYY-MM-DD[&endDate=]` 返回与查询日期范围（北京时间日历日，跨度 ≤7 天）有交集的全部 status=ACTIVE 占用时段（`occupiedSlots`，startTime 升序）。**只返回已占用时段，空闲区间由前端计算**——理由：占用时段是唯一事实数据，空闲区间的粒度与边界属于展示逻辑，由前端用“范围 − 占用”推导可避免两端口径不一致。

## 8. 请求字段约束摘要

| 字段 | 约束 |
|---|---|
| username | `^[A-Za-z0-9_]{3,50}$` |
| password | 8–64 位（注册）；登录仅校验非空 ≤64 |
| room.name | 1–100；location ≤200 可空；capacity 1–100 |
| room.equipment | 枚举多选去重：`PROJECTOR`=投影仪 / `WHITEBOARD`=白板 / `VIDEO_CONFERENCE`=视频会议 / `PHONE`=电话 |
| meeting.title | 1–200；description ≤5000 可空 |
| notification.title | ≤200；type 枚举：`PARTICIPANT_JOINED` / `PARTICIPANT_LEFT` / `PARTICIPANT_KICKED` / `MEETING_ENDED` |

## 9. 已批准的默认假设（Open Questions 默认值，后续用户可推翻）

1. 通知收件人：报名/退出 → 发起人；踢人 → 被踢人；结束（含到期自动与提前结束）→ 全体有效参会者；会议修改/取消/删除不发通知。
2. 种子房间：301会议室(容量8)、302会议室(容量12)、多功能厅(容量20) —— 由 Flyway 创建，契约不约束。
3. 初始 ADMIN 账号 `admin` 由 Flyway 种子创建，密码交付走线下 —— 契约不涉及。
4. Token 方案：短期 Access Token + 30 天 Refresh Token，Redis 会话存储支持撤销，刷新即轮换。

## 10. 本契约内新增的决策点（实现双方均须遵守，如有异议先改契约）

- `refresh` 端点用请求体 refreshToken 作凭证，`security: []`。
- `GET /api/meetings` 增加 `onlyMine` 参数（语义：我发起 OR 我有 leftAt 为 null 的参会记录）。
- `GET /api/meetings/{id}/participants` 与会议详情返回**全部**参会记录（含已退出/被踢），由前端按 leftAt 过滤。
- 报名成功统一返回 200（含退出后复用行的重新报名）。
- 读他人通知、踢不存在/已退出参会者返回 40401（防枚举口径）。
- `PATCH /api/notifications/read-all` 返回 data=本次更新条数（int）。
- `MeetingVO.roomName`/`organizerUsername` 为展示冗余字段；房间改名后不保证历史会议快照回溯更新。

## 11. Lint

```bash
npx @stoplight/spectral-cli lint docs/api/openapi.yaml
# 备选：npx @redocly/cli lint docs/api/openapi.yaml
# 备选：python -c "import openapi_spec_validator; openapi_spec_validator.validate_file('docs/api/openapi.yaml')"
```

