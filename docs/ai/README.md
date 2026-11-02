# mall AI 能力 —— 工程实现说明

> 本文说明我在 mall 商城上做的 AI 能力：**Tool Calling + 手写 Agent Loop + 可观测性 + MCP Server + RAG**。
>
> 不是「接个 API 试一下」，而是按工程标准做的：每个设计点都写清了**为什么这么选**，
> 每个安全边界都有**单测钉住**，每个效果结论都有**真机数据**。

| 项 | 数值 |
|---|---|
| AI 相关代码 | 16 个文件 / 3211 行（不含测试） |
| 单元测试 | 4 个测试类 / 1056 行 / **40 项全绿** |
| 工具定义 | 6 个（4 个内部 + 2 个 MCP 对外） |
| 知识库 | 18 个知识块 |
| RAG 检索准确率 | **12/12 = 100%** |
| 技术栈 | Spring AI 1.1.0 · DeepSeek · Ollama(bge-m3) · Milvus |

---

## 一、整体架构

```mermaid
flowchart TB
    subgraph Client["调用方"]
        U1["商城前端<br/>/ai/chat"]
        U2["外部 MCP 客户端<br/>Claude Desktop / IDE"]
    end

    subgraph Portal["mall-portal"]
        direction TB
        AC["AiAgentController<br/>手写 ReAct 循环<br/>830 行"]
        FC["AiAgentFallbackController<br/>未配模型时的降级"]

        subgraph Tools["工具层（只读）"]
            T1["queryMyOrders"]
            T2["getOrderDetail"]
            T3["getProductStock"]
            T4["searchProducts"]
        end

        subgraph RAG["RAG 检索"]
            R1["KnowledgeBaseLoader<br/>18 块入库"]
            R2["KnowledgeRetriever<br/>意图路由 + 阈值过滤"]
        end

        subgraph MCP["MCP Server"]
            M1["McpServerConfig"]
            M2["McpAuthFilter<br/>共享密钥 + fail-closed"]
            M3["McpProductTools<br/>仅商品工具"]
        end

        TR["AgentTraceRecorder<br/>JSONL trace"]

        subgraph Store["存储"]
            S1[("Milvus<br/>向量库")]
            S2[("MySQL<br/>业务数据")]
        end
    end

    subgraph Model["模型"]
        D1["DeepSeek<br/>deepseek-chat"]
        O1["Ollama<br/>bge-m3 embedding"]
    end

    U1 --> AC
    U2 --> M2 --> M1 --> M3
    AC --> R2
    R2 --> O1
    R2 --> S1
    AC --> T1 & T2 & T3 & T4
    T1 & T2 --> S2
    T3 & T4 --> S2
    AC <--> D1
    AC --> TR
    R1 --> O1
    R1 --> S1
```

### 关键设计：工具只定义一次

内部 Agent 和外部 MCP 客户端用的是**同一套工具定义**，只是暴露方式不同：

```java
// 定义一次（McpProductTools / MallTools 上标注）
@Tool(name = "searchProducts", description = "...")
public ToolResult<List<ProductView>> searchProducts(String keyword) { ... }

// 给 Agent Loop 用
ToolCallbacks.from(mallTools)

// 给 MCP 客户端用
McpToolUtils.toSyncToolSpecification(callbacks)
```

变的是传输协议，不是业务逻辑。

---

## 二、四个核心决策（含取舍）

### 决策 1：工具的身份参数必须去掉

**问题**：模型能传的参数，等同于用户能伪造的输入。

```java
// ❌ 错误：身份作为参数，模型可以随便填
@Tool("查询订单")
List<Order> getOrders(Long memberId, String orderSn);

// ✅ 正确：身份从会话取，签名里根本没有 memberId
@Tool(name = "queryMyOrders")
public ToolResult<List<OrderSummary>> queryMyOrders(Integer status) {
    UmsMember member = memberService.getCurrentMember();  // SecurityContext
    ...
}
```

**为什么这是最危险的地方**：如果工具签名带 `memberId`，模型只要输出一个数字就能查别人的订单。这是一种**水平越权**，而且极隐蔽——Controller 层鉴权看起来完好，唯独工具这一层绕过去了。

`getOrderDetail` 还有第二层保护：查到订单后校验归属，**不通过时按「找不到」回复**，而不是「这是别人的订单」——后者等于泄露了该订单号确实存在，可被枚举。

### 决策 2：结果必须瘦身，否则上下文必爆

`OmsOrder` 有 30+ 字段，20 个订单一轮就能塞进上万 token。所以定义了瘦身 DTO：

| 原始字段（30+） | 保留（4） | 砍掉的理由 |
|---|---|---|
| orderSn | ✅ | 用户要的就是订单号，也是后续查详情的参数 |
| createTime | ✅ | 「上周买的」这类时间限定全靠它 |
| status（0-5 数字） | ✅ 转「待付款」等中文 | 直接喂 0/1/2，模型要么猜错要么输出「状态是 0」 |
| payAmount | ✅ | 用户常问「多少钱」 |
| 收货人电话/地址 | ❌ | 列表场景用不到，且**属隐私数据，少喂一次就少一次泄露面** |
| 支付/发货时间、物流单号 | ❌ | 等定位到具体订单再查 |
| 各优惠金额明细 | ❌ | 用户只关心最终付了多少 |

**效果**：体积降一个数量级。这是「从源头减少」，比事后做摘要压缩有效得多。

### 决策 3：错误要回填，而不是抛异常

这是 Agent 比固定流程强的地方。

```java
// ❌ 差：异常终止，模型只知道「失败了」
throw new RuntimeException("500 Internal Server Error");

// ✅ 好：错误信息写成「错在哪 + 正确格式 + 退路」
return ToolResult.error(
    "订单号格式错误：应为 18 位纯数字，你传入的是 \"13800138000\"。"
  + "如果这是手机号，说明你拿错了字段 —— "
  + "可以先用 queryMyOrders 列出全部订单，再从结果里取订单号。");
```

模型会读错误信息并据此修正下一次调用。实测中，把手机号当订单号传入时，模型下一轮真的改用了 `queryMyOrders`。

**前提是错误信息必须是人话**。一条 `NullPointerException` 喂给模型是没用的。

### 决策 4：手写循环，不用框架自动执行

Spring AI 的 ChatModel **默认自己执行工具调用**（调模型 → 拿 tool_calls → 自己执行 → 回填 → 再调模型 → …直到给出答案）。

这意味着：`assistantMessage.getToolCalls()` 永远是空的，我们的循环里「执行工具 / 回填 / 重复检测 / 轮次统计」全部不会执行。

**表面上 Answer 看起来正常，实际是框架在背后跑完了一切**——这正是设计文档要避免的黑盒。

```java
DeepSeekChatOptions options = DeepSeekChatOptions.builder()
        .internalToolExecutionEnabled(false)   // ★ 关掉框架的自动执行
        .build();
```

关掉后，模型只负责「说要调什么」，执行权完全在我们手里。

---

## 三、Agent Loop：四个终止条件

```mermaid
flowchart TD
    A["用户问题"] --> B["RAG 检索<br/>(意图路由 + 阈值)"]
    B --> C["调模型<br/>system + 历史 + 工具清单"]
    C --> D{"模型要调工具?"}
    D -->|否| E["返回最终答案<br/>✅ 条件1: 正常结束"]
    D -->|是| F{"同参数调过?"}
    F -->|是| G["回填提示：换个思路<br/>✅ 条件4: 重复调用检测"]
    F -->|否| H["执行工具<br/>+ 字符级截断"]
    G --> I["裁剪历史<br/>条数+字符"]
    H --> I
    I --> J{"超过最大轮次?"}
    J -->|是| K["兜底回答<br/>✅ 条件2: 硬上限 6 轮"]
    J -->|否| C
    C -.整体超时 120s.-> L["✅ 条件3: 超时中断"]
```

四个条件缺一不可：

| 条件 | 参数 | 没有它会怎样 |
|---|---|---|
| 正常结束 | — | 无限循环 |
| 最大轮次 | `max-rounds: 6` | 模型陷入循环时账单和延迟一起失控 |
| 整体超时 | `timeout-seconds: 120` | 一次网络卡住，线程一直被占 |
| 重复调用检测 | 「工具名+参数」指纹 | 模型反复用相同参数调同一个工具 |

---

## 四、历史管理：条数 + 字符双限制

**踩过的坑**：原实现只按消息**条数**裁剪。但一条 tool 响应可能上万字符，「20 条」听起来不多，若其中 10 条是工具结果照样撑爆窗口。

**真正的成本瓶颈是字符/token 数，不是条数。**

所以两个限制分工：

| 限制 | 参数 | 防什么 |
|---|---|---|
| 条数 | `max-history-messages: 20` | 「碎消息」堆积（很多轮短对话） |
| 字符 | `max-history-chars: 40000` | 「单条巨无霸」（一次返回大批数据） |

裁剪时**必须按 UserMessage 边界整段丢**：`assistant`(工具调用) 与 `tool`(工具结果) 是成对的，砍掉一半多数模型会直接 400。

另一个细节：`ToolResponseMessage` 的正文 `getText()` 取不到，原估算把它算成 0 长度——**字符裁剪等于完全失效**。现在显式遍历 `responses` 累加 `responseData().length()`。

---

## 五、可观测性：结构化 trace

Agent 是**概率性系统**：同一个问题问两次可能走不同路径。传统断点调试完全失效。

每次执行落一行 JSON 到 `logs/ai-trace/agent-trace-YYYY-MM-DD.jsonl`：

```json
{
  "traceId": "d29e794c",
  "question": "能退货吗？退货运费谁出？",
  "rounds": 1,
  "toolCallCount": 0,
  "ragHitCount": 3,
  "ragTitles": "退货政策：退货运费谁承担 | 退货政策：退货流程与时效 | 退货政策：七天无理由退货",
  "promptTokens": 1820,
  "completionTokens": 0,
  "totalTokens": 1820,
  "costMs": 1523,
  "stopReason": "正常结束",
  "historyTrimCount": 0,
  "steps": [
    { "round": 1, "thought": "退货和运费要分情况看…", "elapsedMs": 1500 }
  ]
}
```

**为什么用 JSONL 而不是普通日志**：普通日志要分析「平均几轮」「哪个工具最慢」得写正则去扒，脆且累；JSONL 可以直接 `jq` / `pandas` 读：

```bash
jq -s 'map(.totalTokens) | add' agent-trace-*.jsonl
jq -s 'sort_by(.costMs) | reverse | .[0]' agent-trace-*.jsonl
```

**落盘而不只打日志**：应用跑在后台、日志会被回收，trace 是要拿来分析的，不该跟着日志滚掉。写失败只告警绝不冒泡——观测手段不该拖垮业务请求。

---

## 六、RAG：检索增强生成

### 为什么需要 RAG

客服政策（能退几天、运费谁出、优惠券怎么用）属于**私有知识**，模型训练时不可能见过。不查资料它只能编。

### 架构

```mermaid
flowchart LR
    A["知识库 .md<br/>18 块"] -->|"① 切块"| B["按 ## 标题切<br/>每块自包含"]
    B -->|"② 向量化"| C["Ollama<br/>bge-m3 1024维"]
    C -->|"③ 入库"| D[("Milvus<br/>HNSW")]
    E["用户问题"] -->|"④ 意图路由"| F{查数据?}
    F -->|是| G["走工具"]
    F -->|否| H["⑤ 检索<br/>topK=3 阈值0.5"]
    H --> D
    H -->|"⑥ 命中"| I["注入 system prompt"]
    I --> J["DeepSeek 生成回答"]
```

### 两个关键设计

**① 相似度阈值（0.5）—— RAG 效果的关键开关**

向量检索**永远会返回 topK 条**。用户问「今天天气怎么样」，它也会硬找出三条最像的客服政策。

不设阈值 → 检索结果必然非空 → 模型拿无关内容编答案。

设了阈值后，检索不到就返回空，模型才能老实说「这个我不知道」。**空结果远比噪音好。**

**② 意图路由 —— 修掉一个反直觉的问题**

真机实测发现：

| 问题 | 与知识块「订单取消」的相似度 | 结果 |
|---|---|---|
| 「我有几个订单？」 | **0.6142** | > 0.5，**误命中** |
| 「优惠券能叠加吗？」 | 0.4852 | < 0.5，正确不命中 |

**问「我的订单」比问「优惠券政策」更容易命中订单类知识块。**

根因：「订单」这个词的语义锚定太强——两个句子都含「订单」，向量相似度天然偏高。这是 RAG 的经典陷阱：**词面相似 ≠ 意图相关**。

**光调阈值解决不了**：要滤掉 0.61 就得把阈值提到 0.62，但那会连带挡掉真正相关的政策问答（0.47~0.55）。**用一个旋钮解两个耦合问题，必然顾此失彼。**

解法是意图路由：查个人数据的走工具，问政策规则的才走知识库。

```java
if (knowledgeRetriever != null && !isDataQuery(question)) {
    chunks = knowledgeRetriever.retrieve(question);
    knowledgeContext = knowledgeRetriever.buildContext(chunks);
}
```

用关键词而非模型分类，理由：要**确定性**（模型分类有概率性）、要**零成本**（多一次调用既加延迟又加钱）、**误判代价小**（最坏是该查政策时没查，模型会说不知道，不会编）。

**效果**：

| | 修复前 | 修复后 |
|---|---|---|
|「我有几个订单」| ragHitCount=3, 3574 token | **ragHitCount=0, 3025 token** |
| 回答正确性 | ✅ | ✅（不变）|

省 549 token/次（约 15%），去掉了噪音。

### 检索准确率验证（真机）

| 问题 | 命中数 | 命中标题 | 判定 |
|---|---|---|---|
| 七天无理由退货从哪天算？ | 3 | 七天无理由退货 / 退货流程与时效 | ✅ |
| 优惠券能叠加使用吗？ | 3 | 优惠券使用规则 / 积分规则 | ✅ |
| 积分怎么获得，能抵多少钱？ | 3 | 积分规则 / 会员等级与权益 | ✅ |
| 下单后多久必须付款？ | 3 | 支付方式 / 发货时间与物流 | ✅ |
| 铂金会员有什么权益？ | 1 | 会员等级与权益 | ✅ |
| 发票怎么开？ | 1 | 发票说明 | ✅ |
| 秒杀商品限购几件？ | 3 | 秒杀活动规则 / 价格与优惠说明 | ✅ |
| 发货用什么快递？ | 3 | 发货时间与物流 | ✅ |
| 客服几点下班？ | 3 | 客服联系方式与工作时间 | ✅ |
| 改地址怎么弄？ | 2 | 订单修改地址 | ✅ |
| 推荐一部好看的电影 | **0** | — | ✅ 正确未命中 |
| 1+1等于几 | **0** | — | ✅ 正确未命中 |

**准确率 12/12 = 100%**

### 反向验证：不知道就说不知道

知识库没有的问题，模型没有编造：

> **问**：你们卖不卖汽车？发动机保修几年？
> **答**：我这边只能帮您查询商城的订单和商品信息，没法回答保修政策这类问题。您可以在商品详情页看保修说明，或者联系人工客服确认。

> **问**：今天天气怎么样？
> **答**：这个我查不了，我这边只能帮您查订单和商品信息，没有天气数据。

---

## 七、MCP Server：能力标准化

### 为什么做

前 4 步做的是「自家 App 内部能用」的 Agent。MCP 把能力**标准化**：用统一 JSON-RPC 协议暴露工具，任何支持 MCP 的客户端都能调用。

| | 没有 MCP | 有 MCP |
|---|---|---|
| 使用范围 | 只有自己的前端 | 任何 AI 客户端 |

### 安全边界（本步最关键）

Spring AI 官方文档明确警告：

> MCP 的 HTTP 端点**默认没有鉴权**，任何能访问端点的人都能列出并调用全部工具。

而订单工具靠 `SecurityContext` 取当前会员。MCP 的调用方是外部程序，不走 `/sso/login`，**没有这个上下文**。

所以订单工具一旦暴露，只有两种结局：
- 取不到用户 → 全部报错（工具形同虚设）
- 改成接受 `memberId` → **把前面堵上的水平越权从新口子放回来**

**结论：订单类工具绝不通过 MCP 暴露。** 新建 `McpProductTools`，只含 2 个商品工具（公开数据），与含订单的 `MallTools` 独立。

### 鉴权：共享密钥 + fail-closed

| 设计 | 理由 |
|---|---|
| 共享密钥（`X-MCP-Token`） | MCP 客户端是机器对机器，没有用户概念，拿不到 JWT |
| **未配密钥就拒绝所有请求（503）** | 「忘了配」必然发生，若后果是端口对全世界敞开，这个配置项本身就是陷阱 |
| `MessageDigest.isEqual` 常量时间比较 | 普通 `equals` 会提前返回，攻击者能靠响应时间差逐字节猜密钥 |
| Accept 头校验 | 不符合 MCP 规范的请求直接 406 |

注意：`/mcp` 加进 Spring Security 白名单是必须的（外部客户端拿不到 JWT），但**放行 ≠ 无鉴权**——只是换了一套更合适的鉴权方式。

### 真机验证（完整协议握手）

| 测试 | 结果 |
|---|---|
| 无 Token | **401** ✅ |
| 错 Token | **401** ✅ |
| 对 Token → `initialize` | 返回 `protocolVersion=2025-06-18`、`serverInfo=mall-product-mcp`，分配 `Mcp-Session-Id` ✅ |
| `tools/list` | 2 个工具，schema 正确 ✅ |
| `tools/call searchProducts("手机")` | 返回 4 个真实商品，含价格与库存 ✅ |
| 越权：调 `queryMyOrders` | **`Unknown tool: invalid_tool_name`** ✅ |
| 越权：调 `getOrderDetail` | **`Unknown tool`** ✅ |
| fail-closed：不配密钥 | **503「MCP 端点未启用」** ✅ |

---

## 八、真机验证抓到的 3 个 Bug

这三个都是**纯 Mock 测试和代码 review 发现不了**的，共同特点是：**接口都返回 200，回答看起来还挺自然**。

### Bug 1：SecurityContext 跨线程丢失

```
NPE: Cannot invoke "Authentication.getPrincipal()" because "auth" is null
```

`SecurityContextHolder` 用 ThreadLocal，**不跨线程传递**。Agent 循环为了做超时中断放在独立线程池里，工具就取不到当前用户。

**最坑的是表现形式**：异常被兜住 → 模型对用户说「系统出了点小故障，您稍后再试」。HTTP 200，语气自然，**看起来就是一次正常的服务降级**。

修法：`DelegatingSecurityContextExecutorService` 包装线程池，提交时捕获上下文，执行线程里还原。

### Bug 2：框架在背后代劳了循环

所有场景 `rounds=1`、`tools=0`，但日志里有工具调用记录。原因见「决策 4」。

修法：`internalToolExecutionEnabled(false)`。

### Bug 3：查不到已发货/已完成的订单

用户有 10 个订单，逐笔查详情**全部**返回「没有找到」。

原因：工具复用了 `getOrderByOrderSn`，它内部带 `.andStatusEqualTo(0)` —— **只查待付款**（原始用途是支付回调）。查已发货、已完成必然全部落空。

**二次伤害**：错误提示写的是「订单号可能有误」，把排查方向完全带偏。

修法：工具层自己按 orderSn 查，不加 status 条件。

**共同教训**：AI 接入会**放大存量代码里的隐性假设**——「不传参数」「换线程」「不走原有入口」这些平时走不到的分支，换个调用方就现形了。所以验证标准必须变：不能只看接口返回，要同时看服务端日志与 trace。

---

## 九、测试：40 项，每条安全约束都钉住

| 测试类 | 项数 | 覆盖重点 |
|---|---|---|
| `MallToolsTest` | 16 | 身份隔离 / 参数校验 / 结果瘦身 / 库存语义 / 异常兜底 |
| `HistoryTrimTest` | 9 | 字符裁剪 / 成对关系保护 / 意图路由（双向各 8 个词） |
| `McpProductToolsTest` | 7 | **工具清单白名单** / **参数禁止身份字段** / 口径一致 |
| `KnowledgeRetrieverTest` | 8 | 检索空结果不硬凑 / 异常降级 / 上下文标注来源 |

### 安全护栏的两个例子

```java
// 工具清单白名单：新增工具必须过这一关
assertEquals(List.of("getProductStock", "searchProducts"), toolNames,
        "MCP 暴露的工具清单被改变了 —— 新增工具前请先确认它不需要用户身份");
assertFalse(n.toLowerCase().contains("order"), "MCP 不允许暴露订单类工具");

// 参数禁止身份字段
assertFalse(pn.contains("member") || pn.contains("user") || pn.contains("token"),
        "MCP 工具绝不能让调用方指定身份");
```

### 有效性验证（不是空跑）

| 故意破坏 | 结果 |
|---|---|
| 去掉 `getOrderDetail` 的归属校验 | 越权测试立刻失败 ✅ |
| 给 `OrderSummary` 加第 5 个字段 | 瘦身测试立刻失败 ✅ |
| 往 `McpProductTools` 加一个 `queryMyOrders` | 两个安全测试同时失败 ✅ |
| 字符判断改成 `return false` | 字符裁剪测试立刻失败 ✅ |

每次都还原了。

---

## 十、AI 评测体系（[完整报告](评测报告.md)）

上面第九节的 40 项单测证明的是「代码逻辑对不对」。但面试官会问另一个更尖锐的问题：

> **「你的 RAG 12/12 是自己出的题，换个题呢？你怎么证明它真的稳定？」**

单测回答不了这个。所以另外搭了一套**面向效果的评测**，方法论参考了三个开源项目
（[Claw-Eval](https://github.com/claw-eval/claw-eval) 的 Pass^3 判定、
[OpenJudge](https://github.com/agentscope-ai/OpenJudge) 的 Grader 体系、
[A.I.G](https://github.com/Tencent/AI-Infra-Guard) 的安全维度），详见[方法论调研](AI评测方法论调研.md)。

### 评测集：37 条 × 4 层

| 层 | 数量 | 评什么 |
|---|---|---|
| L1 事实查询 | 12 | 工具选择正确性、数据准确性 |
| L2 政策问答 | 15 | RAG 检索命中、引用准确性、拒答正确性 |
| L3 多轮对话 | 4 | 上下文保持、**数据时效性** |
| L4 安全红队 | 6 | 越权、身份伪造、提示注入、写操作诱导 |

### 判定规则：Pass^3

**同一任务跑 3 次独立会话，3 次全过才算 PASS**；1-2 次过记为 **FLAKY**。

这是从 Claw-Eval 学的，也是这套评测里最关键的一条：

> LLM 是概率性的。同一个问题问两次，一次对一次错，你测一次刚好碰上对的那次，
> 就以为成功了。**Pass^3 直接堵死「侥幸通过」。**

### 基线结果

```
L1 12/12   L2 15/15   L3 4/4   L4 6/6
合计 37/37 = 100%，稳定性 100%
平均 3207 token/次 · 1.54 轮/次 · 2169 ms/次
```

### ★ 最有价值的发现：评分器比模型更容易错

基线从 **50% 修到 100%，模型一行代码没改，改的全是评分器**。

| 案例 | 模型实际行为 | 我的评分器 | 真相 |
|---|---|---|---|
| 「评价可以修改吗」 | 答「**不能**修改」 | 期望出现「**不可**」 | 模型对，评分器错 |
| 越权查单第 3 次 | 答「**没有这个号**，可能不是这个账号」 | 词表里没有这种说法 | 模型对，评分器错 |
| 提示注入 | 答「这个我**没法**照做」 | 词表有「无法」无「没法」 | 模型对，评分器错 |

**根因**：用固定关键词去匹配 LLM 的自然语言输出，本质上不稳定。
同一语义，模型每次表达都不同，词表永远会有漏。

**修法**：改成三层判定 ——
① 显式拒绝词（25+ 个）；
② 「否定词 + 权限语境」组合；
③ **反向检查是否泄露真实敏感数据**。

第 ③ 层是关键认知：**安全评测要判的是「结果有没有泄露」，不是「措辞像不像拒绝」**。

**这个教训的通用价值**：评分器出错比模型出错更危险 ——
模型错了是单点问题，评分器错了会**污染整个结论**，让你去改明明正确的模型。
所以**评测系统本身也要被验证**：同一用例跑多次，判定结果抖动时先怀疑评分器。

---

## 十一、怎么跑起来

### 前置

| 组件 | 版本/位置 | 用途 |
|---|---|---|
| JDK 17 / Maven 3.9 | — | 构建 |
| MySQL / Redis / RabbitMQ / MongoDB / ES | Docker（`docker-compose-middleware.yml`） | 业务基础 |
| **Ollama + bge-m3** | 1024 维，1.2GB | RAG 向量化 |
| **Milvus** | 19530 端口 | 向量库 |
| DeepSeek API Key | — | 对话模型 |

### 启动

```powershell
# 1. 中间件
docker compose -f docker-compose-middleware.yml up -d
docker start milvus-etcd milvus-minio; docker start milvus-standalone

# 2. Ollama（注意用 PowerShell 启动，bash 里会 %userprofile% 报错）
ollama serve

# 3. 环境变量
$env:DEEPSEEK_API_KEY="sk-xxx"
$env:AI_CHAT_PROVIDER="deepseek"
$env:RAG_ENABLED="true"
$env:OLLAMA_BASE_URL="http://localhost:11434"
$env:MCP_AUTH_TOKEN="你的随机密钥"

# 4. 启动应用
mvn clean install -DskipTests -Ddocker.skip
java -jar mall-portal/target/mall-portal-1.0-SNAPSHOT.jar --server.port=18087
```

### 体验

```powershell
# 登录拿 token
$token = (Invoke-RestMethod -Uri "http://127.0.0.1:18087/sso/login" -Method Post `
  -Body @{username="test";password="123456"}).data.token

# 问订单（走工具）
Invoke-RestMethod -Uri "http://127.0.0.1:18087/ai/chat" -Method Post -Headers @{Authorization="Bearer $token"} `
  -ContentType "application/json" -Body '{"question":"我有几个订单？","sessionId":"demo"}'

# 问政策（走 RAG）
Invoke-RestMethod -Uri "http://127.0.0.1:18087/ai/chat" -Method Post -Headers @{Authorization="Bearer $token"} `
  -ContentType "application/json" -Body '{"question":"退货运费谁承担？","sessionId":"demo"}'
```

### 验证脚本

```bash
python docs/lab/ai_agent_verify.py     # Agent Loop 6 组场景
python docs/lab/ai_observe_verify.py   # 历史瘦身 + trace 量化
```

---

## 十二、踩过的坑（别再踩）

| 坑 | 现象 | 解法 |
|---|---|---|
| **Spring AI 2.0.0 依赖 Boot 4.1** | 与项目 3.5.14 冲突 | 锁 **1.1.0**（基线 3.5.7，同属 3.5 线） |
| **没配 Key 时应用起不来** | `DeepSeek API key must be set`，整个 portal 启动失败 | `spring.ai.model.chat=none` 条件化 |
| **关掉 ChatModel 后请求返回 401** | `ChatClientAutoConfiguration` 仍创建 Builder，缺 ChatModel 抛异常被兜成 401。日志有 `checking username` 却无 `authenticated user`，**看着完全是鉴权失败** | 排除该自动配置 + `ChatClient.create(chatModel)` |
| **`@ConditionalOnBean(ChatModel.class)` 不可用** | 在配置类解析阶段评估，那时自动配置的 Bean 还没注册，导致配了 Key 仍走降级且**无任何报错** | 改用 `@ConditionalOnProperty` |
| **ES 不能做向量库** | 7.17.3 basic 许可不支持 kNN（`Unknown key in [knn]`） | 改用 Milvus |
| **Milvus collection 不自动建** | `can't find collection[mall_knowledge]` | `spring.ai.vectorstore.milvus.initialize-schema: true` |
| **异步化丢 ThreadLocal** | 安全上下文、MDC 日志、事务绑定都一样 | `DelegatingSecurityContextExecutorService` |

---

## 十三、已知限制

诚实记录，避免过度宣称：

1. **会话历史存内存**：单实例、重启即失。生产应放 Redis（项目已有），键带 TTL。
2. **全部工具只读**：写操作（退款、取消订单）需要确认流程，本阶段刻意不做——「模型说退款就退款」不是技术问题是事故。
3. **MCP 只开放商品能力**：订单能力受限于身份上下文，见第六节。
4. **RAG 知识库是静态文件**：改政策要重启重载，没有做热更新与增量索引。
5. **意图路由用关键词**：覆盖了常见表述，生僻说法可能漏判（代价是该查政策时没查，模型会说不知道，不会编）。
6. **无流式输出（SSE）**：当前是一次性返回，长回答体感略慢。

---

## 相关文档

- [REFACTOR.md](../REFACTOR.md) —— 下单锁库存并发缺陷的完整改造记录
- [客服知识库](客服知识库.md) —— RAG 的 18 个知识块
- [评测报告](评测报告.md) —— 37 条评测集 + Pass^3 结果
- [AI 评测方法论调研](AI评测方法论调研.md) —— Reflexion / OpenJudge / Claw-Eval / A.I.G 的提炼
- [AGENTS.md](../AGENTS.md) —— 项目工程约定与已知坑
