# Devin最小适配提案（待批准，未激活）

针对会议室预约+报名的Java/Vue项目，提议新增四个Profile及两个实现Skill。既有global_coordinator/coordinator/planner继续协调与规划，不另建重复协调器；MyBatis-Plus与RabbitMQ实现归Java专家，Redis、API契约、测试质量、CI、安全、Docker、Git及文档复用插件专家。

## 新Profile

|名称|输入与职责|输出与验证|权限边界|
|---|---|---|---|
|java-spring-developer|故事及验收、OpenAPI、迁移、架构约定；实现Spring业务、MyBatis-Plus事务、认证、RabbitMQ及相关测试|代码及迁移、实际Maven测试结果、未验证项；交java-reviewer|读写backend及故事指定文档；执行Wrapper/项目检查；不合并、不部署、不改产品规则|
|vue-developer|故事、UI正常/加载/空/错误/无权限状态、OpenAPI；实现Vue/Element Plus页面、状态及组件测试|页面与测试、vue-tsc/构建结果及浏览器证据；交vue-reviewer|读写frontend及指定UI文档；执行项目npm检查；不改后端契约、不合并、不部署|
|java-reviewer|故事验收、后端diff、测试结果；查权限、事务、时间冲突、报名容量、会话撤销、消息可靠性与迁移|PASS/NEEDS_FIX，具体文件行号、风险和验证缺口|只读代码，允许执行Wrapper验证；不实现、不接受自身修改、不部署|
|vue-reviewer|故事/UI/API、前端diff；查请求错误、登录恢复、报名/踢人/删除反馈、界面状态与类型|PASS/NEEDS_FIX，文件行号与用户行为复现；必要浏览器检查|只读代码，执行项目类型检查/测试/构建；不改代码、不部署|

所有Profile继承用户已配置的默认子Agent模型，不写未验证GLM模型别名。allowed-tools仅授予必要read/grep/glob/exec，实现专家另有write/edit；审查仅执行已定义验证命令。Windows使用mvnw.cmd，CI/Linux使用./mvnw；命令范围由工程实际位置决定，禁用Exec(*)。不允许子Agent自行添加新专家或请求用户信息，由协调器统一汇总。

## 两个实现Skill

java-spring-developer与vue-developer的SKILL.md沿用原Python Skill的name、description、argument-hint、agent、triggers及Responsibilities/Testing/Code Quality/Scope/Output结构，调整语言内容，不复制Python命令。

- 输入：故事ID、文件所有权、权威需求/API/UI、验收条件、已批准依赖和标准检查命令。
- 步骤：读取输入→实现范围内功能与有意义测试→执行共享检查→记录实际PASS/NEEDS_FIX→交独立审查。
- 输出：修改摘要、验证命令及结果、需求关联、未验证项、后续审查对象。无Docker的本机不能把集成测试跳过写成通过。
- 失败：修复实际缺陷，不改测试绕过；连续三次同因失败且无进展交协调器。需求冲突先报告，不自行扩大功能。

## 原Profile适配

- global_coordinator识别pom.xml、*.java与Vue package.json混合项目，路由通用coordinator。
- coordinator/planner的路由补四个新Profile及目录所有权；验证链按后端/前端调用独立语言审查。
- api-specialist以OpenAPI契约与接口设计为主，Spring实现交Java专家，移除本项目FastAPI假设。
- qa-ci-agent/testing-guardian/security-auditor核对Maven、vue-tsc、前端测试、覆盖率与依赖扫描的实际命令和权限；CI强制完整集成测试。
- devops-docker使用独立开发/staging/prod配置；git-workflow保留人工合并；删除、迁移和部署遵循已确认边界。

## 放置与验收

优先项目级.devin/agents及目标Devin版本支持的Skill目录，实际路径先通过运行版本文档与加载验证确定；不直接覆盖用户全局配置或修改上游源目录。若平台不支持项目Skill，则使用项目附属Devin插件并验证manifest，而不是当作Codex插件安装。

批准后生成完整AGENT.md/SKILL.md并解析YAML、检查名称/职责/权限一致性；新会话确认可见；协调器分别委派一个最小Java/Vue任务，专家真实执行检查，独立审查返回结果。配置校验与实际运行分别记录。

本提案没有创建、安装或启用任何新Agent。插件subagent-recommender明确要求提案批准后由curator创建；当前Codex没有Devin run_subagent工具，后续若由Codex生成文件，必须标明这是文件适配，不能声称已调用Devin curator。
