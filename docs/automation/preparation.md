# 自动化准备与状态

更新时间：2026-09-14。此文件记录事实与计划，不代表准备工作已全部完成。

## 已验证

- Windows本机Java21.0.9、Node24.13.0可运行。Maven3.6.3与目标3.9.x不一致，待Wrapper统一。
- 服务器20.205.103.75:2222可密码SSH登录。Ubuntu24.04、约8GB内存、约121GB可用磁盘，Docker29.8.0、Compose5.5.1，无Swap。
- 开发中间件已安装：MySQL8.4.11、Redis8.2.4、RabbitMQ4.3.5；健康检查、认证读写与消息发布/读取通过。持久化、自动重启、本机绑定和摘要锁定已配置。见../operations/middleware.md。
- 本地插件源码位于C:/Users/Administrator/devin-agents/plugins/devin-agents，版本1.0.0，commit 2a2e2c17c3740035e0c449a7724407bf3c38cdc9。
- 插件提供22个Agent与29个Skill，已有Python实现/审查，缺Java/Vue专门覆盖。协调器未专门识别Java，QA命令权限缺Maven。
- 找到AppData/Local/devin和AppData/Roaming/Devin安装数据，但当前PATH无devin；没有确认插件加载或子Agent实际可运行。

## 准备顺序与退出条件

|阶段|工作|退出证据|
|---|---|---|
|需求基线|解决通知、初始账号及房间信息，补业务验收场景|明确产品规则，未决项不影响首版实现|
|插件适配|四个新职责提案审核，适配协调/API/QA，最小Skill增量|新会话可见Profile及Skill，委派真实小任务并返回验证结果|
|依赖与仓库|完整版本矩阵、Wrapper、前端锁文件；检查远程仓库后保留现有内容|空骨架构建测试成功，GitHub权限与PR流程验证|
|环境|本地SSH隧道、专用开发配置，CI Docker与Playwright；专用部署SSH密钥|本地能连接开发服务，CI集成测试能执行，秘密不进入仓库|
|治理与设计|权威文档、OpenAPI、迁移、ADR、UI状态、共享检查命令|每故事有输入/输出/文件所有权/验收条件，CI与Skill调用同一检查|
|交给Devin|首个纵向切片：登录→创建会议→分享报名→我的会议→通知|可恢复任务说明、独立分支、测试证据、人工审查PR|

服务器资料曾记录每天19:00自动关机；用户表示会关闭，实际状态尚未核查。开发中间件不是staging/production，部署时需按资源核查配置隔离环境，不能复用开发数据。Testcontainers优先在有Docker的CI执行；Windows本机不因本次准备而安装Docker或开放Docker TCP。

## Devin使用与加载验证

以下命令来自选定插件README，应在可调用Devin的终端执行，当前机器未执行验证：

```text
devin plugins install C:/Users/Administrator/devin-agents/plugins/devin-agents
devin plugins list
devin plugins info devin-agents
```

若Desktop内置终端也无法调用命令，先查实际安装入口和版本；使用Desktop插件管理入口安装时，来源必须指向plugins/devin-agents子目录，不能假定当前UI与旧README相同。修改后使用新会话核查内容。

新会话先交给Devin以下验证任务：

> 打开RoomFlow，仅执行插件能力检查，不写业务代码。列出可用Profile和Skill，验证global_coordinator委派到coordinator，再由其安排planner读取需求并产出一个测试计划。返回真实调用结果、权限失败及输出路径；不得仅声称Profile可用。尚未批准的新Profile不得创建。

之后才进行Java/Vue小任务验证，分别执行构建/类型检查并交给独立reviewer。插件validate-agent.sh仅做粗略字段检查，且要求model的旧警告与继承默认模型说明矛盾，不作为加载成功证据。

复用grilling、code-review、diagnosing-bugs、handoff、testing-guardian、qa-ci-agent等流程；新实现Skill按python-developer结构适配。实现完成→swe-check→测试→security-auditor→qa-ci-agent→Java/Vue审查→必要架构审查→用户审查合并。失败自主修复，同一失败三次无进展时停止该任务并报告证据，不删除测试或降低门禁。

## 当前状态

准备阶段；无业务骨架、无业务代码、无CI执行记录、无Devin子Agent运行证据。下一步：审核插件最小职责增量并确认少量剩余产品规则。外部插件原目录未修改，未创建或安装新Profile，未提交或推送仓库。
