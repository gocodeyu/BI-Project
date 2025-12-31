# 多级缓存、分布式、SSE 实现总结文档

> **文档版本**: v1.0  
> **创建时间**: 2025年12月  
> **适用范围**: BI 图表分析系统

---

## 目录

1. [架构概述](#1-架构概述)
2. [多级缓存实现](#2-多级缓存实现)
3. [分布式架构设计](#3-分布式架构设计)
4. [SSE 实时通知实现](#4-sse-实时通知实现)
5. [接口实现详解](#5-接口实现详解)
6. [关键技术点](#6-关键技术点)
7. [注意事项与最佳实践](#7-注意事项与最佳实践)
8. [设计决策说明](#8-设计决策说明)

---

## 1. 架构概述

### 1.1 整体架构

本项目采用**三级缓存 + 分布式部署 + SSE 实时推送**的架构设计，旨在：

- **降低数据库压力**：通过多级缓存减少 DB 查询
- **提升响应速度**：本地缓存（Caffeine）提供毫秒级响应
- **保证数据一致性**：写库后立即删除缓存，确保读取最新数据
- **实时状态推送**：通过 SSE 替代前端轮询，减少无效请求

### 1.2 技术栈

| 层级 | 技术 | 用途 |
|------|------|------|
| **L1 缓存** | Caffeine | 本地内存缓存，最快响应 |
| **L2 缓存** | Redis | 分布式缓存，跨实例共享 |
| **L3 数据源** | MySQL | 持久化存储，唯一可信源 |
| **消息队列** | RabbitMQ | 异步任务处理 |
| **实时推送** | SSE + Redis Pub/Sub | 跨实例实时通知 |

### 1.3 核心设计原则

1. **写库 + 删缓存**：写接口不读缓存，只负责写库和删缓存
2. **按需缓存**：只对读多写少的接口使用缓存
3. **版本号机制**：列表缓存使用版本号，避免批量删除 Key
4. **SSE 替代轮询**：实时推送任务状态，减少前端请求

---

## 2. 多级缓存实现

### 2.1 三级缓存架构

```
┌─────────────────────────────────────────────────────────┐
│                     前端请求                             │
└──────────────────────┬────────────────────────────────┘
                         │
                         ▼
        ┌────────────────────────────────┐
        │   L1: Caffeine (本地缓存)       │  ← 最快，但仅本机有效
        │   TTL: 20秒 - 2分钟             │
        └──────────────┬─────────────────┘
                       │ 未命中
                       ▼
        ┌────────────────────────────────┐
        │   L2: Redis (分布式缓存)        │  ← 跨实例共享
        │   TTL: 60秒 - 15分钟           │
        └──────────────┬─────────────────┘
                       │ 未命中
                       ▼
        ┌────────────────────────────────┐
        │   L3: MySQL (数据库)            │  ← 唯一可信源
        └────────────────────────────────┘
```

### 2.2 缓存服务类

项目实现了三个独立的缓存服务类，职责清晰：

#### 2.2.1 ChartCacheService（图表详情缓存）

**文件位置**: `com.yupi.springbootinit.service.cache.ChartCacheService`

**特点**:
- **只使用 Redis + DB 二级缓存**，不使用 Caffeine
- 使用 Redis Hash 结构存储，避免整体序列化开销
- TTL: 15分钟 + 随机偏移（±60秒）

**为什么禁用 Caffeine？**

避免与 SSE 通知的时序冲突：
1. 用户连接在 Server A，查看图表详情，状态为 `WAIT`
2. Server A 将 `WAIT` 状态存入本机 Caffeine（TTL 3分钟）
3. RabbitMQ 消费者（Server B）完成任务，更新 DB 为 `SUCCEED`，删除 Redis 缓存
4. SSE 推送"成功"通知给前端
5. 前端收到通知，立即请求详情
6. **问题**：如果请求又打到 Server A，Caffeine 中仍是 `WAIT`，导致"通知成功但页面显示排队中"的矛盾体验

**解决方案**：只使用 Redis 缓存，Redis 是所有节点共享的，删除后所有实例都会回源 DB。

**Key 设计**:
```
bi:chart:{chartId}
```

**存储结构**（Redis Hash）:
```
bi:chart:123
  ├─ id: "123"
  ├─ name: "销售分析"
  ├─ goal: "分析销售趋势"
  ├─ chartType: "line"
  ├─ chartData: "{...}"
  ├─ genChart: "{...}"
  ├─ genResult: "分析结果..."
  ├─ userId: "456"
  ├─ status: "succeed"
  ├─ execMessage: "图表生成成功"
  ├─ createTime: "1704067200000"
  └─ updateTime: "1704067300000"
```

**实现代码**:
```java
public Chart getChartByIdWithCache(Long chartId) {
    String redisKey = REDIS_KEY_PREFIX + chartId;
    
    // 1. 查 Redis Hash
    Map<Object, Object> hashMap = stringRedisTemplate.opsForHash().entries(redisKey);
    if (!hashMap.isEmpty()) {
        return hashToChart(hashMap);  // 命中缓存
    }
    
    // 2. 查 DB
    Chart chart = chartService.getById(chartId);
    if (chart != null) {
        saveChartToRedis(chart);  // 写入缓存
    }
    return chart;
}
```

#### 2.2.2 ChartListCacheService（图表列表缓存）

**文件位置**: `com.yupi.springbootinit.service.cache.ChartListCacheService`

**特点**:
- **使用三级缓存**：Caffeine → Redis → DB
- **版本号机制**：解决列表缓存删除难的问题
- 仅对简单条件缓存

**版本号机制详解**:

**问题背景**：
- 列表缓存 Key 包含分页参数：`bi:chart:list:my:{userId}:p{current}:s{pageSize}`
- 写操作时需要删除所有分页的缓存，但 Redis 的 `KEYS` 命令在生产环境被禁用
- 使用 `SCAN` 命令复杂且性能差

**解决方案**：版本号机制

1. **版本号 Key（元数据）**：
   ```
   bi:chart:list:my:{userId}:v  → 值: "3"
   ```
   - TTL: 7天（永久）
   - 存储当前版本号

2. **数据 Key（带版本号）**：
   ```
   bi:chart:list:my:{userId}:p1:s10:v3
   bi:chart:list:my:{userId}:p2:s10:v3
   bi:chart:list:my:{userId}:p3:s10:v3
   ```
   - TTL: 60秒 + 随机偏移

3. **读流程**：
   ```
   1. 读取版本号: bi:chart:list:my:123:v → "3"
   2. 构造数据 Key: bi:chart:list:my:123:p1:s10:v3
   3. 查缓存: Caffeine → Redis → DB
   ```

4. **写流程（列表变更时）**：
   ```
   1. 写库成功
   2. 版本号自增: INCR bi:chart:list:my:123:v  → "4"
   3. 旧版本的 Key（v3）虽然还在 Redis，但永远不会被访问
   4. 旧数据随 TTL 自动过期
   ```

**优势**:
- ✅ 删除操作从"批量删除通配符 Key"简化为"单个 Key 的 INCR 操作"
- ✅ 不需要使用 `KEYS` 或 `SCAN` 命令
- ✅ 性能高、无阻塞
- ✅ 旧数据自然过期，无需手动清理

**实现代码**:
```java
public Page<ChartListVO> getMyChartListWithCache(ChartQueryRequest request) {
    // 1. 获取版本号
    String versionKey = REDIS_VERSION_KEY_PREFIX + userId + ":v";
    String versionStr = stringRedisTemplate.opsForValue().get(versionKey);
    Long version = versionStr == null ? 1L : Long.parseLong(versionStr);
    
    // 2. 构造缓存 Key（带版本号）
    String cacheKey = REDIS_DATA_KEY_PREFIX + userId + ":p" + current + ":s" + pageSize + ":v" + version;
    
    // 3. 查 Caffeine
    Page<ChartListVO> result = caffeineCache.getIfPresent(caffeineKey);
    if (result != null) return result;
    
    // 4. 查 Redis
    String redisValue = stringRedisTemplate.opsForValue().get(cacheKey);
    if (StringUtils.isNotBlank(redisValue)) {
        result = gson.fromJson(redisValue, Page.class);
        caffeineCache.put(caffeineKey, result);
        return result;
    }
    
    // 5. 查 DB
    result = queryFromDb(request);
    if (result != null) {
        stringRedisTemplate.opsForValue().set(cacheKey, gson.toJson(result), ttl, TimeUnit.SECONDS);
        caffeineCache.put(caffeineKey, result);
    }
    return result;
}

// 使缓存失效（版本号自增）
public void evictMyChartList(Long userId) {
    String versionKey = REDIS_VERSION_KEY_PREFIX + userId + ":v";
    stringRedisTemplate.opsForValue().increment(versionKey);
}
```

#### 2.2.3 ChartDataCacheService（图表数据预览缓存）

**文件位置**: `com.yupi.springbootinit.service.cache.ChartDataCacheService`

**特点**:
- **使用三级缓存**：Caffeine → Redis → DB
- 仅缓存前 3 页和常用 pageSize（10、20）
- TTL: Redis 10分钟，Caffeine 2分钟

**Key 设计**:
```
bi:chart:data:{chartId}:p{current}:s{pageSize}
```

**实现代码**:
```java
public ChartDataPreviewResponse getChartDataPreviewWithCache(Long chartId, long current, long pageSize) {
    // 判断是否应该缓存
    if (!shouldUseCache(current, pageSize)) {
        return queryFromDb(chartId, current, pageSize);
    }
    
    String cacheKey = REDIS_KEY_PREFIX + chartId + ":p" + current + ":s" + pageSize;
    
    // 1. 查 Caffeine
    ChartDataPreviewResponse result = caffeineCache.getIfPresent(caffeineKey);
    if (result != null) return result;
    
    // 2. 查 Redis
    String redisValue = stringRedisTemplate.opsForValue().get(cacheKey);
    if (StringUtils.isNotBlank(redisValue)) {
        result = gson.fromJson(redisValue, ChartDataPreviewResponse.class);
        caffeineCache.put(caffeineKey, result);
        return result;
    }
    
    // 3. 查 DB
    result = queryFromDb(chartId, current, pageSize);
    if (result != null) {
        stringRedisTemplate.opsForValue().set(cacheKey, gson.toJson(result), ttl, TimeUnit.SECONDS);
        caffeineCache.put(caffeineKey, result);
    }
    return result;
}
```

### 2.3 缓存一致性策略

**核心原则**：**写库 + 删缓存**（不做复杂更新）

#### 2.3.1 写操作流程

```
1. 写库（DB 为唯一可信源）
   ↓
2. 写库成功后，删除相关缓存：
   - 详情缓存: DEL bi:chart:{id}
   - 列表缓存: INCR bi:chart:list:my:{userId}:v  (版本号自增)
   - 数据预览: DEL bi:chart:data:{id}:*
   - Caffeine: invalidate(key)  (仅针对使用 Caffeine 的接口)
```

#### 2.3.2 为什么不做"延迟双删"？

1. **业务特点**：用户自用，并发极低
2. **实现简单**：写库后立即删缓存，下一次读取就会回源 DB
3. **避免复杂性**：延迟双删需要引入定时任务，增加系统复杂度

#### 2.3.3 统一缓存删除方法

在 `ChartController` 中实现统一的缓存删除方法：

```java
private void evictChartCache(Long chartId, Long userId) {
    if (chartId != null && chartId > 0) {
        // 删除详情缓存
        chartCacheService.evictChart(chartId);
        // 删除数据预览缓存
        chartDataCacheService.evictChartData(chartId);
    }
    if (userId != null && userId > 0) {
        // 更新列表缓存版本号（我的图表列表）
        chartListCacheService.evictMyChartList(userId);
        // 删除回收站列表缓存
        chartListCacheService.evictMyDeletedChartList(userId);
    }
}
```

**调用时机**：
- 创建图表后
- 编辑图表后
- 删除/恢复图表后
- 图表生成成功/失败后
- 管理员更新图表后

---

## 3. 分布式架构设计

### 3.1 多实例部署场景

```
                    ┌─────────────┐
                    │  负载均衡器   │
                    └──────┬──────┘
                           │
        ┌──────────────────┼──────────────────┐
        │                  │                  │
        ▼                  ▼                  ▼
   ┌─────────┐      ┌─────────┐      ┌─────────┐
   │Server A  │      │Server B │      │Server C │
   │(Web)     │      │(Web)    │      │(Web)    │
   └────┬────┘      └────┬────┘      └────┬────┘
        │                │                │
        └────────────────┼────────────────┘
                         │
        ┌────────────────┼────────────────┐
        │                │                │
        ▼                ▼                ▼
   ┌─────────┐      ┌─────────┐      ┌─────────┐
   │  Redis  │      │ RabbitMQ│      │  MySQL  │
   │(缓存)   │      │(消息队列)│      │(数据库)  │
   └─────────┘      └─────────┘      └─────────┘
```

### 3.2 分布式缓存（Redis）

**作用**：
- 跨实例共享缓存数据
- 避免每个实例都查询数据库
- 提供统一的缓存失效机制

**配置**：
- 使用 `StringRedisTemplate`，配置 UTF-8 编码（避免中文乱码）
- 配置类：`RedisPubSubConfig`

```java
@Bean
public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
    StringRedisTemplate template = new StringRedisTemplate();
    template.setConnectionFactory(connectionFactory);
    
    // 使用 UTF-8 编码的 StringRedisSerializer
    StringRedisSerializer utf8Serializer = new StringRedisSerializer(StandardCharsets.UTF_8);
    template.setKeySerializer(utf8Serializer);
    template.setValueSerializer(utf8Serializer);
    template.setHashKeySerializer(utf8Serializer);
    template.setHashValueSerializer(utf8Serializer);
    
    template.afterPropertiesSet();
    return template;
}
```

### 3.3 分布式任务处理（RabbitMQ）

**场景**：图表生成是耗时操作，需要异步处理

**流程**：
```
1. 用户提交分析请求
   ↓
2. Controller 创建图表记录（status=WAIT）
   ↓
3. 发送消息到 RabbitMQ
   ↓
4. 立即返回给前端（不等待 AI 生成）
   ↓
5. RabbitMQ 消费者（任意节点）处理任务
   ↓
6. 更新数据库（status=SUCCEED/FAILED）
   ↓
7. 删除缓存
   ↓
8. 推送 SSE 通知（跨实例）
```

**关键点**：
- 消费者可能在任意节点运行
- 需要跨实例推送 SSE 通知（见 SSE 章节）

---

## 4. SSE 实时通知实现

### 4.1 为什么使用 SSE？

**问题背景**：
- 前端需要知道图表生成任务的状态（排队中 → 生成中 → 成功/失败）
- 之前使用 3 秒轮询，存在以下问题：
  - 请求频繁，浪费带宽和服务器资源
  - 在多实例部署下，可能出现"某台实例缓存未更新 → 轮询读到旧状态"
  - 状态更新不及时（最多延迟 3 秒）

**解决方案**：使用 SSE（Server-Sent Events）实时推送

**优势**：
- ✅ 实时推送，状态更新立即通知前端
- ✅ 减少无效请求（不再需要轮询）
- ✅ 降低服务器压力
- ✅ 更好的用户体验（右下角通知卡片）

### 4.2 SSE 架构设计

#### 4.2.1 跨实例通信问题

**问题**：
- 用户连接在 Server A 的 SSE
- 任务由 Server B 的 RabbitMQ 消费者处理完成
- Server B 需要将"任务完成"消息推送给 Server A，但 B 手里没有 A 的 `SseEmitter` 对象

**解决方案**：使用 **Redis Pub/Sub（发布订阅）机制**

#### 4.2.2 完整架构流程

```
┌─────────────────────────────────────────────────────────────┐
│  前端                                                         │
│  ┌─────────────────────────────────────────────────────┐   │
│  │ 建立 SSE 连接: GET /api/notify/sse                  │   │
│  │ 监听事件: chart_task_done                            │   │
│  │ 收到通知 → 显示右下角卡片 → 刷新列表                  │   │
│  └─────────────────────────────────────────────────────┘   │
└──────────────────────┬──────────────────────────────────────┘
                       │ SSE 连接
                       ▼
        ┌──────────────────────────────────────┐
        │  Server A (Web 节点)                  │
        │  ┌────────────────────────────────┐   │
        │  │ SseNotifyService               │   │
        │  │ - 维护本机 SSE 连接 Map         │   │
        │  │ - 订阅 Redis Channel            │   │
        │  │ - 收到消息后推送给本机连接      │   │
        │  └────────────────────────────────┘   │
        └──────────────────────────────────────┘
                       │
                       │ 订阅
                       ▼
        ┌──────────────────────────────────────┐
        │  Redis Pub/Sub                        │
        │  Channel: topic:sse-notify            │
        └──────────────────────────────────────┘
                       ▲
                       │ 发布
                       │
        ┌──────────────────────────────────────┐
        │  Server B (RabbitMQ 消费者)           │
        │  ┌────────────────────────────────┐   │
        │  │ BiAsyncServiceImpl              │   │
        │  │ 1. 更新 DB (status=SUCCEED)     │   │
        │  │ 2. 删除缓存                      │   │
        │  │ 3. 发布消息到 Redis Channel      │   │
        │  └────────────────────────────────┘   │
        └──────────────────────────────────────┘
```

### 4.3 实现细节

#### 4.3.1 SSE 连接管理（SseNotifyService）

**文件位置**: `com.yupi.springbootinit.service.SseNotifyService`

**核心功能**：

1. **注册 SSE 连接**：
```java
// 本机维护的 SSE 连接：userId -> SseEmitter
private final Map<Long, SseEmitter> sseEmitterMap = new ConcurrentHashMap<>();

public void registerSseConnection(Long userId, SseEmitter emitter) {
    // 如果已存在连接，先关闭旧的
    SseEmitter oldEmitter = sseEmitterMap.put(userId, emitter);
    if (oldEmitter != null) {
        oldEmitter.complete();
    }
    
    // 设置连接完成、超时、错误回调
    emitter.onCompletion(() -> sseEmitterMap.remove(userId));
    emitter.onTimeout(() -> sseEmitterMap.remove(userId));
    emitter.onError((ex) -> sseEmitterMap.remove(userId));
    
    // 发送初始连接消息
    emitter.send(SseEmitter.event()
        .name("connected")
        .data(jsonMessage, MediaType.APPLICATION_JSON));
}
```

2. **订阅 Redis Channel**：
```java
@PostConstruct
public void init() {
    // 订阅 Redis Channel
    redisMessageListenerContainer.addMessageListener(this, new ChannelTopic(REDIS_CHANNEL));
    log.info("SSE 通知服务已启动，已订阅 Redis Channel: {}", REDIS_CHANNEL);
}
```

3. **接收 Redis 消息并推送**：
```java
@Override
public void onMessage(Message message, byte[] pattern) {
    // 使用 UTF-8 编码解析消息，避免中文乱码
    String messageBody = new String(message.getBody(), StandardCharsets.UTF_8);
    
    Map<String, Object> data = gson.fromJson(messageBody, Map.class);
    Long userId = parseLongFromObject(data.get("userId"));
    Long chartId = parseLongFromObject(data.get("chartId"));
    String status = (String) data.get("status");
    
    // 检查本机是否有该用户的 SSE 连接
    SseEmitter emitter = sseEmitterMap.get(userId);
    if (emitter != null) {
        // 推送消息给前端
        emitter.send(SseEmitter.event()
            .name("chart_task_done")
            .data(jsonMessage, MediaType.APPLICATION_JSON));
    } else {
        // 本机无该用户的连接，忽略（说明连接在其他节点）
        log.info("【SSE】本机无该用户的 SSE 连接，忽略: userId={}", userId);
    }
}
```

#### 4.3.2 SSE 控制器（SseNotifyController）

**文件位置**: `com.yupi.springbootinit.controller.SseNotifyController`

**接口**：
```java
@GetMapping("/sse")
public SseEmitter createSseConnection(HttpServletRequest request) {
    // 1. 获取登录用户
    User loginUser = userService.getLoginUser(request);
    
    // 2. 创建 SSE 发射器（超时时间 30 分钟）
    SseEmitter emitter = new SseEmitter(TimeUnit.MINUTES.toMillis(30));
    
    // 3. 注册 SSE 连接
    sseNotifyService.registerSseConnection(loginUser.getId(), emitter);
    
    return emitter;
}
```

#### 4.3.3 任务完成时推送通知

**文件位置**: `com.yupi.springbootinit.service.impl.BiAsyncServiceImpl`

**流程**：
```java
public void executeGenChart(long chartId) {
    // ... AI 生成逻辑 ...
    
    // 6. 更新数据库为成功
    chartService.updateById(updateChartSuccess);
    
    // 7. 删除缓存（写库成功后）
    evictChartCache(chartId, chart.getUserId());
    
    // 8. 推送 SSE 通知
    sseNotifyService.publishTaskNotification(
        chart.getUserId(), 
        chartId, 
        "succeed", 
        "图表生成成功"
    );
}
```

**发布消息到 Redis**：
```java
public void publishTaskNotification(Long userId, Long chartId, String status, String execMessage) {
    Map<String, Object> message = new HashMap<>();
    message.put("userId", userId);
    message.put("chartId", chartId);
    message.put("status", status);
    message.put("execMessage", execMessage);
    message.put("type", "chart_task_done");
    
    String messageJson = gson.toJson(message);
    stringRedisTemplate.convertAndSend(REDIS_CHANNEL, messageJson);
}
```

### 4.4 前端实现

**文件位置**: `fronted/src/pages/AddChart/index.tsx`

**关键代码**：
```typescript
useEffect(() => {
  const token = localStorage.getItem('token');
  if (!token) return;
  
  // 使用 fetch API（支持自定义 headers）
  const abortController = new AbortController();
  
  fetch('http://localhost:12345/api/notify/sse', {
    method: 'GET',
    headers: {
      Authorization: token,
    },
    signal: abortController.signal,
  })
    .then((response) => {
      const reader = response.body?.getReader();
      const decoder = new TextDecoder();
      
      const readStream = () => {
        reader?.read().then(({ done, value }) => {
          if (done) return;
          const chunk = decoder.decode(value);
          
          // 解析 SSE 消息
          const lines = chunk.split('\n');
          lines.forEach((line) => {
            if (line.startsWith('event: chart_task_done')) {
              // 处理任务完成事件
            }
            if (line.startsWith('data: ')) {
              const data = JSON.parse(line.substring(6));
              if (data.type === 'chart_task_done') {
                // 显示右下角通知
                notification.open({
                  message: data.status === 'succeed' ? '图表生成成功' : '图表生成失败',
                  description: `图表 ID: ${data.chartId}`,
                  type: data.status === 'succeed' ? 'success' : 'error',
                  placement: 'bottomRight',
                });
                // 刷新列表
                loadData();
              }
            }
          });
          readStream();
        });
      };
      
      readStream();
    });
    
  return () => {
    abortController.abort();
  };
}, []);  // 只在组件挂载时建立一次连接
```

### 4.5 中文编码问题修复

**问题**：SSE 推送的中文显示为乱码

**原因**：
- Redis Pub/Sub 消息接收时使用平台默认编码（Windows 可能是 GBK）
- SSE 发送时未指定 MediaType

**解决方案**：

1. **Redis 消息接收时显式指定 UTF-8**：
```java
String messageBody = new String(message.getBody(), StandardCharsets.UTF_8);
```

2. **SSE 发送时指定 MediaType**：
```java
emitter.send(SseEmitter.event()
    .name("chart_task_done")
    .data(jsonMessage, MediaType.APPLICATION_JSON));  // 明确指定 UTF-8
```

3. **配置 StringRedisTemplate 使用 UTF-8**：
```java
StringRedisSerializer utf8Serializer = new StringRedisSerializer(StandardCharsets.UTF_8);
template.setValueSerializer(utf8Serializer);
```

---

## 5. 接口实现详解

### 5.1 读接口（使用缓存）

#### 5.1.1 GET /chart/get（图表详情）

**实现类**: `ChartController.getChartById()`

**缓存策略**: **只使用 Redis + DB 二级缓存**，不使用 Caffeine

**代码**:
```java
@GetMapping("/get")
public BaseResponse<Chart> getChartById(long id, HttpServletRequest request) {
    // 使用缓存服务获取图表详情
    Chart chart = chartCacheService.getChartByIdWithCache(id);
    // ... 权限校验 ...
    return ResultUtils.success(chart);
}
```

**缓存流程**:
```
1. 查 Redis Hash: bi:chart:{id}
2. 命中 → 返回
3. 未命中 → 查 DB → 写入 Redis → 返回
```

#### 5.1.2 POST /chart/my/list/page（我的图表列表）

**实现类**: `ChartController.listMyChartByPage()`

**缓存策略**: **三级缓存**（Caffeine → Redis → DB）

**代码**:
```java
@PostMapping("/my/list/page")
public BaseResponse<Page<ChartListVO>> listMyChartByPage(
    @RequestBody ChartQueryRequest chartQueryRequest, 
    HttpServletRequest request
) {
    // 使用缓存服务获取列表
    Page<ChartListVO> chartListVOPage = chartListCacheService.getMyChartListWithCache(chartQueryRequest);
    return ResultUtils.success(chartListVOPage);
}
```

**缓存流程**:
```
1. 获取版本号: bi:chart:list:my:{userId}:v
2. 构造 Key: bi:chart:list:my:{userId}:p{current}:s{pageSize}:v{version}
3. 查 Caffeine → 命中返回
4. 查 Redis → 命中 → 写入 Caffeine → 返回
5. 查 DB → 写入 Redis → 写入 Caffeine → 返回
```

#### 5.1.3 GET /chart/data/preview（图表数据预览）

**实现类**: `ChartController.getChartDataPreview()`

**缓存策略**: **三级缓存**（Caffeine → Redis → DB），仅缓存前 3 页

**代码**:
```java
@GetMapping("/data/preview")
public BaseResponse<ChartDataPreviewResponse> getChartDataPreview(
    ChartDataPreviewRequest chartDataPreviewRequest
) {
    ChartDataPreviewResponse response = chartDataCacheService.getChartDataPreviewWithCache(
        chartDataPreviewRequest.getChartId(),
        chartDataPreviewRequest.getCurrent(),
        chartDataPreviewRequest.getPageSize()
    );
    return ResultUtils.success(response);
}
```

### 5.2 写接口（只删缓存，不读缓存）

#### 5.2.1 POST /chart/gen/async/rabbitmq（生成图表）

**实现类**: `ChartController.genChartByAiAsyncRabbitmq()`

**策略**: **不读缓存，只写库 + 删缓存**

**代码**:
```java
@PostMapping("/gen/async/rabbitmq")
public BaseResponse<BiResponse> genChartByAiAsyncRabbitmq(
    @RequestPart("file") MultipartFile multipartFile,
    GenChartByAiRequest genChartByAiRequest,
    HttpServletRequest request
) {
    // 1. 创建图表记录（status=WAIT）
    Chart chart = new Chart();
    // ... 设置字段 ...
    boolean saveResult = chartService.save(chart);
    
    // 2. 发送消息到 RabbitMQ
    biMessageProducer.sendMessage(String.valueOf(chartId), isVip);
    
    // 3. 删除列表缓存（确保新创建的图表能立即显示）
    evictChartCache(null, loginUser.getId());
    
    // 4. 返回
    return ResultUtils.success(biResponse);
}
```

**关键点**：
- ✅ 创建图表后立即删除列表缓存，确保新图表能立即显示
- ✅ 不读缓存，直接写库
- ✅ 异步任务完成后会再次删除缓存并推送 SSE 通知

#### 5.2.2 POST /chart/edit（编辑图表）

**实现类**: `ChartController.editChart()`

**策略**: **不读缓存，只写库 + 删缓存**

**代码**:
```java
@PostMapping("/edit")
public BaseResponse<Boolean> editChart(
    @RequestBody ChartEditRequest chartEditRequest,
    HttpServletRequest request
) {
    // 1. 写库
    boolean result = chartService.updateById(chart);
    
    // 2. 删除缓存
    if (result) {
        evictChartCache(id, oldChart.getUserId());
    }
    
    return ResultUtils.success(result);
}
```

#### 5.2.3 POST /chart/delete（删除图表）

**实现类**: `ChartController.deleteChart()`

**策略**: **不读缓存，只写库 + 删缓存**

**代码**:
```java
@PostMapping("/delete")
public BaseResponse<Boolean> deleteChart(
    @RequestBody DeleteRequest deleteRequest,
    HttpServletRequest request
) {
    // 1. 写库（逻辑删除）
    boolean b = chartService.removeById(id);
    
    // 2. 删除缓存
    if (b) {
        evictChartCache(id, oldChart.getUserId());
    }
    
    return ResultUtils.success(b);
}
```

### 5.3 异步任务处理（RabbitMQ 消费者）

**实现类**: `BiAsyncServiceImpl.executeGenChart()`

**完整流程**:
```java
public void executeGenChart(long chartId) {
    // 1. 更新状态为 RUNNING
    chartService.updateById(updateChartRunning);
    
    // 2. 调用 AI 生成
    String result = aiPrompt.func(goal, chartType, csvData);
    
    // 3. 解析结果
    String genChart = splits[1];
    String genResult = splits[2];
    
    // 4. 更新数据库为 SUCCEED
    chartService.updateById(updateChartSuccess);
    
    // 5. 删除缓存（写库成功后）
    evictChartCache(chartId, chart.getUserId());
    
    // 6. 推送 SSE 通知
    sseNotifyService.publishTaskNotification(
        chart.getUserId(), 
        chartId, 
        "succeed", 
        "图表生成成功"
    );
}
```

**关键点**：
- ✅ 写库成功后立即删除缓存
- ✅ 删除缓存后立即推送 SSE 通知
- ✅ 前端收到通知后刷新列表，此时缓存已删除，会从 DB 获取最新数据

---

## 6. 关键技术点

### 6.1 Caffeine 本地缓存配置

**特点**：
- 基于内存的本地缓存
- 高性能（纳秒级访问）
- 仅本机有效，不跨实例

**配置示例**：
```java
private Cache<String, Page<ChartListVO>> caffeineCache;

@PostConstruct
public void init() {
    caffeineCache = Caffeine.newBuilder()
        .maximumSize(1000)  // 最大缓存条目数
        .expireAfterWrite(20, TimeUnit.SECONDS)  // 写入后 20 秒过期
        .build();
}
```

**使用场景**：
- ✅ 图表列表缓存（读多写少）
- ✅ 图表数据预览缓存（读多写少）
- ❌ 图表详情缓存（避免与 SSE 冲突）

### 6.2 Redis Hash 结构

**为什么使用 Hash 而不是 String？**

1. **避免整体序列化开销**：
   - String: 需要将整个 Chart 对象序列化为 JSON，再反序列化
   - Hash: 可以按字段存取，只更新需要的字段

2. **支持字段级更新**（未来扩展）：
   - 可以只更新 `status` 字段，而不需要重新序列化整个对象

**实现**：
```java
// 保存到 Redis Hash
stringRedisTemplate.opsForHash().put(redisKey, "id", String.valueOf(chart.getId()));
stringRedisTemplate.opsForHash().put(redisKey, "name", chart.getName());
stringRedisTemplate.opsForHash().put(redisKey, "status", chart.getStatus());
// ...

// 从 Redis Hash 读取
Map<Object, Object> hashMap = stringRedisTemplate.opsForHash().entries(redisKey);
Chart chart = hashToChart(hashMap);
```

### 6.3 Redis Pub/Sub 配置

**配置类**: `RedisPubSubConfig`

**关键配置**：
```java
@Bean
public RedisMessageListenerContainer redisMessageListenerContainer(
    RedisConnectionFactory connectionFactory
) {
    RedisMessageListenerContainer container = new RedisMessageListenerContainer();
    container.setConnectionFactory(connectionFactory);
    return container;
}
```

**订阅 Channel**：
```java
@PostConstruct
public void init() {
    redisMessageListenerContainer.addMessageListener(
        this, 
        new ChannelTopic("topic:sse-notify")
    );
}
```

### 6.4 Gson 序列化配置

**问题**：
- Long 类型大数字在 JSON 序列化时可能丢失精度
- 中文可能被 HTML 转义

**解决方案**：
```java
private final Gson gson = new GsonBuilder()
    .setLongSerializationPolicy(LongSerializationPolicy.STRING)  // Long 转为字符串
    .disableHtmlEscaping()  // 禁用 HTML 转义
    .create();
```

### 6.5 SSE MediaType 配置

**问题**：SSE 发送中文时可能乱码

**解决方案**：显式指定 MediaType
```java
emitter.send(SseEmitter.event()
    .name("chart_task_done")
    .data(jsonMessage, MediaType.APPLICATION_JSON));  // 明确指定 UTF-8
```

`MediaType.APPLICATION_JSON` 的定义：
```java
public static final MediaType APPLICATION_JSON = 
    new MediaType("application", "json", StandardCharsets.UTF_8);
```

---

## 7. 注意事项与最佳实践

### 7.1 缓存使用注意事项

#### 7.1.1 图表详情接口禁用 Caffeine

**原因**：避免与 SSE 通知的时序冲突

**场景**：
1. 用户查看图表详情，状态为 `WAIT`，存入本机 Caffeine
2. 任务完成，SSE 推送"成功"通知
3. 前端收到通知，立即请求详情
4. 如果请求打到同一台服务器，Caffeine 中仍是 `WAIT`，导致矛盾体验

**解决方案**：只使用 Redis + DB 二级缓存

#### 7.1.2 列表缓存使用版本号机制

**原因**：避免批量删除通配符 Key 的性能问题

**优势**：
- ✅ 删除操作简化为单个 Key 的 INCR
- ✅ 不需要使用 `KEYS` 或 `SCAN` 命令
- ✅ 旧数据自然过期

#### 7.1.3 写接口不读缓存

**原则**：写接口只负责写库和删缓存，不读缓存

**原因**：
- 保持实现简单
- 避免复杂的一致性逻辑
- DB 是唯一可信源

### 7.2 分布式部署注意事项

#### 7.2.1 Redis 连接配置

**必须配置 UTF-8 编码**：
```java
StringRedisSerializer utf8Serializer = new StringRedisSerializer(StandardCharsets.UTF_8);
template.setValueSerializer(utf8Serializer);
```

**原因**：避免中文乱码

#### 7.2.2 SSE 连接管理

**每个节点维护本机连接**：
- 使用 `ConcurrentHashMap<Long, SseEmitter>` 存储
- 节点间通过 Redis Pub/Sub 通信
- 只有有对应用户连接的节点才会推送

#### 7.2.3 缓存失效时机

**写库成功后立即删除缓存**：
```java
// 1. 写库
boolean result = chartService.updateById(chart);

// 2. 写库成功后立即删除缓存
if (result) {
    evictChartCache(chartId, userId);
}
```

### 7.3 SSE 实现注意事项

#### 7.3.1 连接生命周期管理

**必须设置超时和错误回调**：
```java
emitter.onCompletion(() -> sseEmitterMap.remove(userId));
emitter.onTimeout(() -> sseEmitterMap.remove(userId));
emitter.onError((ex) -> sseEmitterMap.remove(userId));
```

**原因**：避免连接泄漏

#### 7.3.2 前端连接建立

**只在组件挂载时建立一次连接**：
```typescript
useEffect(() => {
  // 建立 SSE 连接
  return () => {
    // 清理连接
  };
}, []);  // 空依赖数组，只建立一次
```

**原因**：避免频繁重建连接

#### 7.3.3 消息编码

**必须使用 UTF-8**：
```java
String messageBody = new String(message.getBody(), StandardCharsets.UTF_8);
```

**原因**：避免中文乱码

### 7.4 性能优化建议

#### 7.4.1 缓存 TTL 设置

**建议**：
- Caffeine: 20秒 - 2分钟（本地缓存，TTL 短）
- Redis: 60秒 - 15分钟（分布式缓存，TTL 长）
- 添加随机偏移，避免缓存雪崩

#### 7.4.2 缓存命中率监控

**建议**：
- 添加缓存命中率日志
- 监控缓存大小和内存使用
- 根据实际情况调整 TTL

#### 7.4.3 缓存预热

**可选**：
- 系统启动时预热常用数据
- 用户登录时预热用户相关数据

---

## 8. 设计决策说明

### 8.1 为什么使用三级缓存而不是两级？

**原因**：
1. **性能优化**：Caffeine 本地缓存提供纳秒级访问，比 Redis 快 100 倍
2. **降低 Redis 压力**：本地缓存命中后不需要访问 Redis
3. **成本考虑**：减少 Redis 网络请求和带宽消耗

**权衡**：
- ✅ 性能提升明显
- ⚠️ 需要处理本地缓存一致性问题（通过禁用某些接口的 Caffeine 解决）

### 8.2 为什么图表详情接口禁用 Caffeine？

**原因**：避免与 SSE 通知的时序冲突

**详细分析**：
1. 如果使用 Caffeine，可能出现"SSE 通知成功但页面显示排队中"的矛盾体验
2. Redis 是跨实例共享的，删除后所有节点都能感知
3. 图表详情接口访问频率不高，Redis 性能足够

**权衡**：
- ✅ 保证数据一致性
- ⚠️ 牺牲少量性能（Redis 访问延迟约 1ms，可接受）

### 8.3 为什么使用版本号机制而不是批量删除 Key？

**原因**：
1. **性能问题**：`KEYS` 命令在生产环境被禁用，`SCAN` 命令复杂且性能差
2. **阻塞问题**：批量删除可能阻塞 Redis
3. **实现简单**：版本号机制只需一个 `INCR` 操作

**权衡**：
- ✅ 性能高、无阻塞
- ✅ 实现简单
- ⚠️ 旧数据会暂时占用 Redis 内存（但会随 TTL 自动过期）

### 8.4 为什么写接口不读缓存？

**原因**：
1. **实现简单**：避免复杂的一致性逻辑
2. **业务特点**：用户自用，并发极低
3. **可靠性**：DB 是唯一可信源，写库后立即删缓存即可

**权衡**：
- ✅ 实现简单、可靠
- ⚠️ 可能短暂的数据不一致（但业务场景可接受）

### 8.5 为什么使用 SSE 而不是 WebSocket？

**原因**：
1. **实现简单**：SSE 是 HTTP 协议，不需要额外的握手和协议升级
2. **单向通信足够**：只需要服务器推送给前端，不需要双向通信
3. **自动重连**：浏览器自动处理 SSE 连接断开和重连

**权衡**：
- ✅ 实现简单
- ✅ 适合单向推送场景
- ⚠️ 不支持双向通信（但本项目不需要）

### 8.6 为什么使用 Redis Pub/Sub 而不是直接推送？

**原因**：
1. **跨实例问题**：用户连接在 Server A，任务在 Server B 完成
2. **解耦**：通过 Redis Pub/Sub 解耦任务处理和 SSE 推送
3. **扩展性**：可以轻松添加更多节点

**权衡**：
- ✅ 支持多实例部署
- ✅ 解耦和扩展性好
- ⚠️ 需要额外的 Redis 配置

---

## 9. 总结

### 9.1 核心设计

1. **三级缓存**：Caffeine（本地） → Redis（分布式） → MySQL（数据库）
2. **版本号机制**：解决列表缓存删除难的问题
3. **SSE + Redis Pub/Sub**：实现跨实例实时推送
4. **写库 + 删缓存**：简单可靠的一致性策略

### 9.2 关键接口

| 接口 | 缓存策略 | 说明 |
|------|---------|------|
| `GET /chart/get` | Redis + DB | 禁用 Caffeine，避免与 SSE 冲突 |
| `POST /chart/my/list/page` | 三级缓存 | 使用版本号机制 |
| `GET /chart/data/preview` | 三级缓存 | 仅缓存前 3 页 |
| `POST /chart/gen/async/rabbitmq` | 不读缓存 | 只写库 + 删缓存 |
| `GET /api/notify/sse` | 无缓存 | SSE 连接端点 |

### 9.3 技术要点

1. **缓存一致性**：写库后立即删除缓存
2. **跨实例通信**：Redis Pub/Sub
3. **实时推送**：SSE + Redis Pub/Sub
4. **编码处理**：UTF-8 编码，避免中文乱码
5. **性能优化**：本地缓存 + 分布式缓存 + 版本号机制

### 9.4 注意事项

1. ✅ 图表详情接口禁用 Caffeine
2. ✅ 列表缓存使用版本号机制
3. ✅ 写接口不读缓存
4. ✅ SSE 连接生命周期管理
5. ✅ Redis 消息编码使用 UTF-8

---

**文档维护**：本文档应随代码变更及时更新  
**最后更新**：2024年12月

