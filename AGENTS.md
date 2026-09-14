# RoomFlow Agent 路由与约定

本文件是项目级路由说明，Devin 在本仓库工作时优先按此分派任务。Agent Profile 定义见 `.devin/agents/`，Skill 见 `.devin/skills/`。项目约定见 `CONVENTIONS.md`，需求基线见 `docs/product/requirements.md`。

## 任务路由

| 任务类型 | 委派目标 |
|---|---|
| Java / Spring Boot 后端实现 | `java-spring-developer` |
| Vue 3 / TypeScript 前端实现 | `vue-developer` |
| Java / Spring Boot 后端代码审查 | `java-reviewer` |
| Vue / TypeScript 前端代码审查 | `vue-reviewer` |
| API 契约设计（REST 约定、状态码、OpenAPI） | `api-specialist`（插件内置） |
| Redis 数据工程（连接韧性、序列化、回退） | `redis-engineer`（插件内置） |
| Docker / Compose / 部署配置 | `devops-docker`（插件内置） |
| CI/CD 门禁、lint、typecheck | `qa-ci-agent`（插件内置） |
| 安全扫描、密钥检测、依赖漏洞 | `security-auditor`（插件内置） |
| 制品 bug 检测（Docker、CI、配置、脚本） | `swe-check`（插件内置） |
| 跨语言约定审查（命名、DRY、函数长度） | `best-practices-reviewer`（插件内置） |
| 结构一致性（模块边界、依赖方向） | `architecture-reviewer`（插件内置） |
| 测试质量与覆盖（Python 中心） | `testing-guardian`（插件内置） |
| E2E 测试（Playwright） | `playwright-testing`（插件内置） |
| Git 分支、提交、合并验证 | `git-workflow`（插件内置） |
| 文档（README、API 文档、迁移指南） | `documentation-agent`（插件内置） |

## 路由规则

1. 后端任务（`*.java`、`pom.xml`、Spring/MyBatis-Plus/Security/JWT/RabbitMQ）→ `java-spring-developer`，不要用 `python-developer` 或 `api-specialist` 做实现。
2. 前端任务（`*.vue`、`*.ts`、`package.json`、Vite/Pinia/Vue Router/Element Plus）→ `vue-developer`，不要用 `streamlit-expert`。
3. 后端审查 → `java-reviewer`；前端审查 → `vue-reviewer`。两者均为只读，不实现修复，修复回派对应开发 Agent。
4. API 契约先由 `api-specialist` 设计，再由 `java-spring-developer` 在 Spring 中实现，再由 `java-reviewer` 审查。
5. Docker 相关改动一律委托 `devops-docker`，开发 Agent 不运行 Docker 命令。
6. `global_coordinator` 当前不识别 Java；本仓库按上表手动路由，待插件扩展后自动生效。

## 验证流水线（RoomFlow）

1. `swe-check` — 制品 bug（Docker/CI/配置/POM 语法/Vite 配置）
2. 测试 — `mvnw.cmd test`（后端）、`npx vitest run`（前端）、Playwright（E2E）
3. `security-auditor` — 密钥、依赖、注入
4. `qa-ci-agent` — lint、typecheck、CI 门禁
5. `java-reviewer` + `vue-reviewer` — 并行语言专项审查
6. `best-practices-reviewer` — 跨语言约定（视范围）
7. `architecture-reviewer` — 结构一致性（视范围）
8. 人工审查 — 合并 `main` 前必需

## Skill 范围说明

本项目在 `.devin/skills/` 创建了 4 个 Skill：

| Skill | 用途 | 是否原提案计划 |
|---|---|---|
| `java-spring-developer` | `/java-spring-developer` 斜杠命令，后端实现 | 是 |
| `vue-developer` | `/vue-developer` 斜杠命令，前端实现 | 是 |
| `java-reviewer` | `/java-reviewer` 斜杠命令，后端审查 | 否（范围扩展，功能合理） |
| `vue-reviewer` | `/vue-reviewer` 斜杠命令，前端审查 | 否（范围扩展，功能合理） |

两个 reviewer Skill 属于原提案之外的范围扩展，保留是因为斜杠命令便于直接触发审查。维护时注意与对应 AGENT.md 内容保持一致，避免重复或漂移。

## 当前状态与未决项

- 本仓库已 `git init`（分支 `main`，远程 `origin`），**尚未首次提交**。首次提交内容由用户决定。
- 插件 `global_coordinator` 尚未识别 Java，`coordinator` 专家列表尚未含四个新 Profile；在插件扩展前按本文件手动路由。
- `qa-ci-agent`、`security-auditor` 权限尚未扩展 Maven/vue-tsc/Vitest，待后续单独处理。
- 自定义 Agent 属实验性能力，Profile 加载成功不等于运行时可调用；每个 Agent 需经真实子任务验证。
