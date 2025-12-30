## 分布式 + 三级缓存 + SSE 优化设计说明

> 版本说明：  
> - 读接口按需使用「Caffeine（本地） + Redis + DB」多级缓存（图表详情接口禁用 Caffeine，避免与 SSE 冲突）。  
> - 写接口一律只做「写库 + 删缓存」，**不读缓存、不复杂更新缓存**。  
> - 异步任务相关接口通过 **SSE + Redis Pub/Sub** 推送状态，替代前端 3 秒轮询。

---

### 1. 设计目标与边界

- **目标**
  - 在多实例部署下，利用三级缓存降低 DB 压力和响应时间。
  - 避免因前端频繁轮询导致的「某些实例缓存未更新 → 返回旧结果」问题。
  - 保持实现简单：写入路径不做复杂缓存更新逻辑，只负责删缓存。
  - 通过 SSE 主动推送任务完成状态，减少无效轮询与不一致窗口。

- **边界 / 不做的事**
  - 写接口（生成、编辑、重试、删除等）**不参与缓存读取**，只写 DB + 删缓存。
  - 不引入「延迟双删」等复杂一致性策略，本项目业务是「用户自用、并发极低」。

---

### 2. 需要三级缓存的核心读接口

结合当前业务（BI 图表），筛选出真正值得做三级缓存的读接口：

#### 2.1 图表详情：`GET /chart/get`

- 用途：前端点击「我的分析」某条记录后，获取完整 `Chart`（含大字段 `genChart`、`genResult`、`chartData`）。
- 特点：读多写少，同一图表被多次查看，但状态（`status`）与 SSE 通知强相关。
- 策略：**只使用 Redis + DB 二级缓存，不使用 Caffeine 本地缓存**。  
  - 原因：SSE 推送“任务完成”后，前端会立刻请求详情，如果仍命中本机 Caffeine 的旧状态（WAIT / RUNNING），会出现「弹窗提示成功但页面仍显示排队中」的矛盾体验。  
  - Redis 是所有节点共享的，RabbitMQ 消费者删除 Redis Key 后，所有实例都会回源 DB，不会被「本机旧缓存」影响。

#### 2.2 图表数据预览：`GET /chart/data/preview`

- 用途：分页查询 `chart_{id}` 动态分表的数据（表头 + 数据 + 总数）。
- 特点：SQL 开销大；同一图表的前几页经常被反复查看。
- 策略：**启用三级缓存**，但只缓存「前几页 + 常用 pageSize」。

#### 2.3 我的图表列表（前几页）：`POST /chart/my/list/page`

- 用途：左侧「我的分析」列表。
- 特点：读多写少；筛选条件多。
- 策略：
  - 仅对 **简单条件** 缓存：
    - `name` 为空
    - `chartType` 为空
    - `current` 在前 3 页内
  - 启用三级缓存；复杂搜索条件直接查 DB，不走缓存。

#### 2.4 回收站列表：`POST /chart/my/delete/list/page`

- 用途：回收站分页。
- 特点：访问频率一般。
- 策略：主要用 Redis 缓存（可选加 Caffeine），TTL 较短即可。

---

### 3. 明确不做缓存或仅删缓存的接口

#### 3.1 生成图表相关接口

- `POST /chart/gen/async/rabbitmq`
- `POST /chart/gen/async`
- `POST /chart/gen`

特点：写多读少，且是任务入口。  
策略：
- **不做结果缓存，不使用 Caffeine**。
- 仅使用 Redis 做限流（已由 `RedisLimiterManager` 负责）。
- 写入成功后只需删相关缓存（详情 / 列表 / 数据预览）。

#### 3.2 编辑 / 重试相关接口

- `POST /chart/edit`
- `POST /chart/edit/rabbitmq`
- `POST /chart/gen/retry`
- `POST /chart/gen/retry/rabbitmq`

特点：触发重新生成、修改元数据或重试任务，属于写接口。  
策略：
- **不读缓存，不使用本地 Caffeine 缓存做任何判断。**
- 写库成功后，只负责删缓存：
  - `Chart` 详情：`bi:chart:{id}`
  - 我的图表列表：`bi:chart:list:my:{userId}:*`
  - 回收站列表：`bi:chart:list:mydel:{userId}:*`
  - 数据预览：`bi:chart:data:{id}:*`

#### 3.3 删除 / 恢复 / 管理员更新

- `POST /chart/delete`
- `POST /chart/delete/forever`
- `POST /chart/recover`
- `POST /chart/update`

策略：
- 只写 DB。
- 写库成功后按上面的规则删缓存即可。

---

### 4. Key 设计与 TTL 策略（精简 + 易维护）

#### 4.1 通用前缀

- Redis 全局前缀：`bi:`
- 资源前缀：`chart:`、`user:`、`config:` 等
- 目标：Key 简短可读，不拼接全部查询条件。

#### 4.2 图表详情（`GET /chart/get`）

- Redis（Hash）：
  - Key：`bi:chart:{id}`
  - 字段：
    - `name`、`goal`、`chartType`
    - `genChart`、`genResult`、`chartData`
    - `userId`、`status`、`execMessage`
    - `createTime`、`updateTime`（建议时间戳字符串）
  - TTL：**15 分钟**（可加随机偏移，例如 ±60 秒，避免雪崩）。

> **注意**：本接口**严禁使用 Caffeine 本地缓存**，原因见 2.1 节说明。只使用 Redis + DB 二级缓存。

#### 4.3 图表数据预览（`GET /chart/data/preview`）

- Redis：
  - Key：`bi:chart:data:{chartId}:p{current}:s{pageSize}`
  - Value：序列化 JSON（`{headers, data, total}`）。
  - 仅缓存：
    - `current` ∈ [1,3]
    - 常用 `pageSize`（如 10、20）
  - TTL：**10 分钟 + 随机偏移**。

- Caffeine（可选）：
  - Key：`chart:data:{chartId}:p{current}:s{pageSize}`
  - TTL：**2 分钟**。

#### 4.4 我的图表列表（`POST /chart/my/list/page`）

采用**版本号机制**解决列表缓存删除难的问题：

- **版本号 Key（元数据）**：
  - Key：`bi:chart:list:my:{userId}:v`
  - Value：当前版本号（整数，如 `1`、`2`、`3`...）
  - TTL：**永久**（或设置很长，如 7 天）

- **实际数据 Key（带版本号）**：
  - Key：`bi:chart:list:my:{userId}:p{current}:s{pageSize}:v{version}`
  - Value：序列化后的 `Page<ChartListVO>`。
  - TTL：**60 秒 + 随机偏移**。

- **Caffeine**：
  - Key：`chart:list:my:{userId}:p{current}:s{pageSize}:v{version}`
  - TTL：**20 秒**。

**工作原理：**
1. **读流程**：
   - 先读取版本号 Key：`bi:chart:list:my:{userId}:v`，得到当前版本（如 `v2`）。
   - 用该版本号构造数据 Key：`bi:chart:list:my:{userId}:p{current}:s{pageSize}:v2`。
   - 查缓存（Caffeine → Redis → DB），命中则返回。

2. **写流程（列表变更时）**：
   - 写库成功后，**不删除旧 Key**。
   - 将版本号 Key 的值**自增 1**（`INCR bi:chart:list:my:{userId}:v`）。
   - 旧版本的 Key（如 `v1`、`v2`）虽然还在 Redis 里，但永远不会再被访问到，它们会随着设置的 TTL（60 秒）自动过期消亡。

**优势：**
- 删除操作从「批量删除通配符 Key」简化为「单个 Key 的 INCR 操作」，性能高、无阻塞。
- 不需要使用 `KEYS` 或 `SCAN` 命令，避免生产环境风险。
- 旧数据自然过期，无需手动清理。

#### 4.5 回收站列表

- Redis：
  - Key：`bi:chart:list:mydel:{userId}:p{current}:s{pageSize}`
  - TTL：**60 秒**。
- Caffeine：按需开启。

---

### 5. 一致性策略：写库 + 删缓存（不做花样）

#### 5.1 基本规则

任何会改变 `Chart` 内容或状态的写操作，都遵循：

1. **先写库**（DB 为唯一可信源）。
2. 写库成功后：
   - 删除详情缓存：`DEL bi:chart:{id}`。
   - 更新列表缓存版本号（版本号机制，见 4.4 节）：
     - `INCR bi:chart:list:my:{userId}:v`（我的图表列表）
     - `INCR bi:chart:list:mydel:{userId}:v`（回收站列表）
   - 删除数据预览缓存：`DEL bi:chart:data:{id}:*`。
   - Caffeine：调用 `invalidate` 删除本机对应 Key（仅针对使用 Caffeine 的接口）。

**不做的事：**
- 不尝试「部分字段更新缓存」。
- 不使用写接口读取缓存做逻辑判断。
- 不引入「延迟双删」之类复杂方案。

> 结合本项目实际：
> - 图表由用户自己触发生成、自己查看，并发度极低。
> - 前端轮询周期较长（后续改为 SSE），只要写库后立即删缓存，下一次读取就会回源 DB 或最新 Redis。

#### 5.2 极端实时场景

若未来增加极端实时的全局配置，可以选择：
- 直接不缓存 / 只用 Redis 短 TTL；
- 或仅使用 Caffeine，本机短 TTL 控制不一致时间。

---

### 6. Chart 使用 Redis Hash 的结构

`Chart` 实体（`com.yupi.springbootinit.model.entity.Chart`）在 Redis 中的结构：

- Key：`bi:chart:{id}`
- 字段映射建议：
  - `id`（可选）
  - `name`, `goal`, `chartType`
  - `chartData`
  - `genChart`, `genResult`
  - `userId`
  - `status`
  - `execMessage`
  - `createTime`, `updateTime`（时间戳字符串）

好处：
- 避免整体 JSON 序列化 / 反序列化开销。
- 后续若只更新 `status`、`execMessage` 等，可升级为字段级 `HSET`（当前阶段仍使用「写库 + 删缓存」简化逻辑）。

---

### 7. 读写流程示意（含三级缓存）

#### 7.1 读：以 `GET /chart/get` 为例

1. 构造 Key：`kRedis = "bi:chart:" + id`。
2. 查 Redis Hash（`kRedis`）：
   - `HGETALL` 非空 → 映射为 `Chart` 对象，返回。
3. 查 DB：
   - `chartService.getById(id)`；
   - 存在 → 写入 Redis Hash（`HSET` + `EXPIRE`），返回。

> **注意**：本接口不使用 Caffeine 本地缓存，原因见 2.1 节说明。

#### 7.2 写：以 `/edit/rabbitmq`、`/gen/async/rabbitmq`、`/gen/retry/rabbitmq` 为例

1. 按现有业务逻辑：
   - 校验权限、限流。
   - 写入 / 更新 `chart` 记录（状态、目标、类型等）。
   - 提交异步任务到 RabbitMQ。
2. DB 事务成功后：
   - **只做删除缓存操作**（不读、不更新）：
     - `DEL bi:chart:{id}`（详情缓存）
     - `INCR bi:chart:list:my:{userId}:v`（列表缓存版本号自增，见 4.4 节）
     - `INCR bi:chart:list:mydel:{userId}:v`（回收站列表版本号自增）
     - `DEL bi:chart:data:{id}:*`（数据预览缓存）
   - Caffeine：调用 `invalidate` 删除本机对应 Key（仅针对使用 Caffeine 的接口）。

当异步任务最终更新 DB（生成成功或失败）时，同样按上面的机制删缓存。

---

### 8. SSE 替代轮询的设计

#### 8.1 问题背景

当前前端通过轮询（每 3 秒请求一次列表 / 详情）获取生成任务状态，存在问题：
- 请求频繁，浪费带宽与服务器资源。
- 在多实例部署 + 本地缓存的情况下，可能出现「某台实例缓存未更新 → 轮询读到旧状态」。

#### 8.2 SSE 设计目标

- 对以下接口触发的异步任务，使用 **SSE（Server-Sent Events）** 推送任务完成/失败事件：
  - `POST /chart/edit/rabbitmq`
  - `POST /chart/gen/async/rabbitmq`
  - `POST /chart/gen/retry/rabbitmq`
- 在任务完成或失败时，后端通过 SSE 推送一条事件：
  - 在页面右下角弹出小卡片提示「某个具体分析任务完成或失败」。
  - 前端收到事件后，再按需调用 `GET /chart/get` 或刷新列表，这时缓存已被删，能读到最新数据。

#### 8.3 SSE 通信模型（含 Redis Pub/Sub 跨实例通信）

**问题背景：**
- 用户连接在 Server A 的 SSE。
- 任务由 Server B 的 RabbitMQ 消费者处理完成。
- Server B 需要将「任务完成」消息推送给 Server A，但 B 手里没有 A 的 `SseEmitter` 对象。

**解决方案：使用 Redis Pub/Sub（发布订阅）机制**

1. **前端：**
   - 登录后或进入「我的分析」页面时，建立 SSE 连接，例如：
     - `GET /api/notify/sse`（单独新建通知控制器）。
   - SSE 连接持续存在，接收 JSON 格式消息：
     - `{ "type": "chart_task_done", "chartId": 123, "status": "succeed" | "failed", "message": "xxx" }`
   - 收到后：
     - 在右下角展示小卡片（Ant Design `notification` / `message`）。
     - 如当前正在查看对应图表，可自动刷新详情 / 列表。

2. **后端架构：**

   **2.1 SSE 连接管理（每个 Web 节点）：**
   - 新建一个 `SseController` / `NotifyController` 管理本机的 SSE 连接。
   - 维护本机内存映射：`Map<Long userId, SseEmitter>`。
   - 当用户建立 SSE 连接时：
     - 将 `userId -> SseEmitter` 存入本机 Map。
     - 订阅 Redis Channel：`topic:sse-notify`（应用启动时统一订阅一次即可）。

   **2.2 Redis Pub/Sub 消息流转：**
   - 当异步任务（RabbitMQ 消费者，可能在任意节点）处理完成：
     1. 更新 DB（写库）。
     2. 按上文规则 **删缓存**（详情 / 列表 / 数据预览）。
     3. **发布消息到 Redis Channel**：
        - `PUBLISH topic:sse-notify { "userId": 123, "chartId": 456, "status": "succeed", "execMessage": "xxx" }`

   **2.3 所有 Web 节点接收并处理：**
   - 所有 Web 节点（A、B、C...）都订阅了 `topic:sse-notify`。
   - 当收到 Redis 消息时：
     - 解析消息，提取 `userId`。
     - 检查**本机内存**是否有该 `userId` 的 SSE 连接：
       - **如果有**：通过本机的 `SseEmitter` 推送事件给前端。
       - **如果没有**：忽略（说明该用户的 SSE 连接在其他节点，由其他节点处理）。

**流程图示意：**
```
RabbitMQ 消费者（Server B）
  ↓
更新 DB + 删缓存
  ↓
PUBLISH topic:sse-notify {userId, chartId, status, ...}
  ↓
Redis Channel 广播
  ↓
所有 Web 节点（Server A、B、C...）收到消息
  ↓
Server A：检查本地 Map → 有 userId 的 SSE 连接 → 推送 ✅
Server B：检查本地 Map → 无 userId 的 SSE 连接 → 忽略
Server C：检查本地 Map → 无 userId 的 SSE 连接 → 忽略
```

**实现要点：**
- Redis Pub/Sub 是**无状态**的，所有节点都能收到消息。
- 每个节点只负责推送给自己本机连接的 SSE，避免跨节点操作。
- 即使某个节点宕机，其他节点的 SSE 连接不受影响。

#### 8.4 与缓存一致性的关系

- 写库成功 → 删缓存（或更新版本号） → 通过 Redis Pub/Sub 推送 SSE 事件；
- 前端根据 SSE 事件发起新的 GET 请求：
  - Redis 中已无旧值（或版本号已更新），会及时回源 DB，**保证读取到的是最新状态**。
  - 图表详情接口不使用 Caffeine，避免了「SSE 通知成功但本机缓存仍是旧状态」的问题。
- 相比 3 秒轮询：
  - 请求次数显著减少；
  - 状态更新更及时（实时推送而非定时轮询）；
  - 不依赖「轮询正好落在缓存失效之后」这种概率事件。

---

### 9. 落地实施建议

1. **实现统一的缓存服务类**
   - `ChartCacheService`：封装 `getChartByIdWithCache`、`evictChart` 等。
   - `ChartListCacheService`、`ChartDataCacheService`：封装列表和数据预览缓存的读/删逻辑。

2. **重构 `ChartController` 读接口**
   - `/chart/get`、`/chart/data/preview`、`/chart/my/list/page` 替换为走缓存服务。

3. **在写接口中调用统一的删除逻辑**
   - 删除、恢复、编辑、生成成功/失败回调处，统一用缓存服务删对应 Key。

4. **引入 SSE 通知模块**
   - 新建 SSE 控制器，管理用户连接。
   - 在 RabbitMQ 消费端的业务逻辑中，更新 DB + 删缓存后，通过 SSE 推送状态。

5. **前端调整**
   - 移除频繁轮询（或降级为备份兜底方案）。
   - 增加 SSE 客户端逻辑和右下角通知 UI。

---

### 10. 总结

- 本设计在保留「写库 + 删缓存」这个简单可靠原则的基础上，对读接口按需使用多级缓存提升性能。
- **图表详情接口（`GET /chart/get`）禁用 Caffeine 本地缓存**，只使用 Redis + DB，避免与 SSE 通知的时序冲突。
- 列表缓存采用**版本号机制**，将「批量删除通配符 Key」简化为「单个 Key 的 INCR 操作」，性能高、无阻塞。
- 对于异步生成 / 编辑 / 重试相关接口，明确 **不读缓存、不用 Caffeine**，仅承担写库和删缓存职责。
- 通过引入 **SSE + Redis Pub/Sub** 代替 3 秒轮询，实现跨实例的实时推送，在分布式场景下减少状态不一致的窗口，同时优化用户体验（任务完成/失败即时提醒）。


