# AGENTS.md — mall 项目工作说明

> 本文件会被 AI 编码代理在**第一次请求前自动加载**。只写"必须知道"的，细节在 `docs/`。

## 这个项目是什么

基于开源项目 **macrozheng/mall** 做的深度复健与工程改造（Spring Boot 3.5.14 + JDK 17，7 个 Maven 模块）。
工作方式不是"照着教程写一遍"，而是：**找缺陷 → 证明它存在 → 改造 → 复测拿数字**。

## 模块与端口

| 模块 | 说明 | 本地端口 |
|---|---|---|
| `mall-admin` | 后台管理 API | 18086 |
| `mall-portal` | 前台 / 会员 API | 18087 |
| `mall-search` | ES 搜索 | 18088 |
| `mall-mbg` | MBG 生成的 Model / Mapper（**生成物，见下方约定**） | — |
| `mall-common` / `mall-security` | 公共组件 / 鉴权 | — |

中间件靠 Docker：MySQL 宿主 **3307**（root/root，库 `mall`）· Redis **6379** · RabbitMQ **5672**（mall/mall，vhost `/mall`）· ES **9200**。
**ES 必须装 IK 分词器**，否则 `mall-search` 启动即失败（`analyzer [ik_max_word] has not been configured`）。

## 常用命令

```bash
# 中间件（必须在仓库根目录执行）
docker compose -f docker-compose-middleware.yml up -d
# portal 另需：--profile portal --profile mq
# ES 在：--profile search

# 构建（PowerShell 里 -D 必须加引号）
mvn clean install '-DskipTests=true' '-Ddocker.skip=true'

# 单模块编译（避免 jar 被运行中的进程占用）
mvn compile -pl mall-portal '-DskipTests=true'
```

登录参数格式**两个模块不同**（容易踩）：

- `mall-admin` `POST /admin/login`：**JSON body** `{"username":"test","password":"123456"}`
- `mall-portal` `POST /sso/login`：**表单参数** `-d "username=test&password=123456"`

## 代码约定（改代码前必读）

1. **`mall-mbg` 是生成物**：Model 用显式 getter/setter（不是 Lombok）；加字段必须同时加访问器；
   重跑 MBG 会覆盖手改。自定义 SQL 放独立手写 DAO（`mall-portal/.../dao/`），不要塞进生成的 Mapper XML。
2. **`<foreach>` 遇空集合会拼出非法 SQL**（`values` 后悬空 / `id IN ()`）→ 业务边界必须先判空。
3. **并发正确性靠条件更新 + 判 affected 行数**，不要"先查再改"：
   `UPDATE ... SET x = ? WHERE id = ? AND status = 1`，`affected == 0` 即"别人已经做过"。
4. **金额比较一律 `compareTo`**，禁止 `BigDecimal.intValue()`（向零截断，(−1,0) 会变 0）。
5. 批量接口必须有**单批上限**，长任务必须**分批 + 每批独立事务**。

## AI 模块（`mall-portal/.../portal/ai/`）

**动这块代码前必读 `docs/ai/README.md`**。16 个文件 / 3211 行 + 40 项单测，几条硬约束：

1. **工具签名里绝不放身份参数**。身份一律 `memberService.getCurrentMember()` 从 SecurityContext 取。
   「模型能传的身份 = 用户能伪造的身份」，放 `memberId` 就是水平越权（Controller 鉴权挡不住）。
   有单测钉住：`McpProductToolsTest.noIdentityParameters`。
2. **MCP 只暴露公开数据**（`McpProductTools` 的 2 个商品工具）。
   订单工具在 `MallTools`，**不通过 MCP 暴露**——外部调用没有 SecurityContext，
   要查订单只能让调用方传 `memberId`，等于把口子重新打开。有单测钉住工具清单。
3. **Agent 循环用 `internalToolExecutionEnabled(false)`**。
   不关掉的话 Spring AI 会自己执行工具，`getToolCalls()` 永远空，手写循环等于没跑
   （表面 Answer 正常，实际框架代劳了）。
4. **拿 SecurityContext 的异步任务必须用 `DelegatingSecurityContextExecutorService`**。
   ThreadLocal 不跨线程，否则工具全集 NPE，且表现为「模型说系统有点小故障」——看似正常降级。
5. **RAG 检索前先 `isDataQuery()` 意图路由**。查个人数据的不走知识库：
   实测「我有几个订单」与知识块「订单取消」相似度 0.61 会误命中，而调阈值会顾此失彼。

启动 AI 能力需要三个环境变量：`AI_CHAT_PROVIDER=deepseek` + `RAG_ENABLED=true` + `MCP_AUTH_TOKEN`。
**不配也能正常启动**（`AiAgentFallbackController` 接管 `/ai/**` 返回友好提示），
RAG/Ollama/Milvus 全挂时也只降级不影响主业务。

## 已知坑

1. **`mall-admin` 新增后台接口必须做三件事**（缺一永远 403）：
   ① 注册进 `ums_resource` 并授权角色 ② 重启 admin ③ 清 Redis 的 `mall:ums:resourceList:*` 再重新登录。
   根因：对未注册 URL 是 fail-closed（默认拒绝），且管理员资源列表有缓存。
2. **后台跑的应用会被回收** → 判断存活看**端口监听 + 日志是否推进**，不要只看进程。
3. **代理端口会变**：先 `netstat -ano | grep LISTENING | grep "127.0.0.1:789"` 看实际端口，
   用 `git -c http.proxy=http://127.0.0.1:<port> -c https.proxy=... push` 临时覆盖；**不要改全局配置**。
   代理全关时直连可能反而可用：`git -c http.proxy= -c https.proxy= push`。
4. **HTML 文档有意不入库**（见 `.gitignore`），仓库只跟踪代码、`docs/` 的 lab 脚本与 .md。
5. **Spring AI 版本锁 1.1.0，不要升 2.0.0**：2.0 依赖 Spring Boot 4.1，与本项目 3.5.14 冲突。
6. **Ollama 要用 PowerShell 启动**：`bash` 里 `ollama serve` 会 `panic: %userprofile% is not defined`。
7. **后台进程会随 shell 会话结束被带走**：用 `cmd /c start` 或 `Start-Process` 脱离，否则服务莫名消失（日志无异常）。

## 详细资料

- 仓库内：`docs/`（技术文档 .md + `lab/` 复现与压测脚本）
- **AI 能力详设：`docs/ai/README.md`**（架构 / 设计取舍 / 真机验证数据 / 40 项测试 / 启动步骤）
- 个人长期记忆与资料库：见 `AGENTS.local.md`（本机路径，不提交）
