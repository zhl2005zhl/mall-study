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

## 已知坑

1. **`mall-admin` 新增后台接口必须做三件事**（缺一永远 403）：
   ① 注册进 `ums_resource` 并授权角色 ② 重启 admin ③ 清 Redis 的 `mall:ums:resourceList:*` 再重新登录。
   根因：对未注册 URL 是 fail-closed（默认拒绝），且管理员资源列表有缓存。
2. **后台跑的应用会被回收** → 判断存活看**端口监听 + 日志是否推进**，不要只看进程。
3. **代理端口会变**：先 `netstat -ano | grep LISTENING | grep "127.0.0.1:789"` 看实际端口，
   用 `git -c http.proxy=http://127.0.0.1:<port> -c https.proxy=... push` 临时覆盖；**不要改全局配置**。
4. **HTML 文档有意不入库**（见 `.gitignore`），仓库只跟踪代码、`docs/` 的 lab 脚本与 .md。

## 详细资料

- 仓库内：`docs/`（技术文档 .md + `lab/` 复现与压测脚本）
- 个人长期记忆与资料库：见 `AGENTS.local.md`（本机路径，不提交）
