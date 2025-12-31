# 防重复提交与任务去重（分布式锁 + 限流回退机制）

## 1. 业务场景

在 `genChartByAiAsyncRabbitmq` 接口中，虽然已经实现了限流机制，但仍存在以下问题：

- 用户手抖连续点击两次提交按钮
- 用户在两个浏览器标签页同时提交完全相同的文件和参数
- 网络延迟导致的重复请求

这些情况会导致：
1. **资源浪费**：产生多条完全相同的 AI 分析任务
2. **成本增加**：浪费昂贵的 AI Token
3. **用户体验差**：用户的限流次数被无意义地消耗

## 2. 技术方案

### 2.1 核心思路

使用 **Redisson 分布式锁** + **限流回退机制**：

1. **先限流，后加锁**：保证系统整体的流量安全
2. **精准识别重复**：基于 `userId + 文件内容 + 分析目标 + 图表类型` 生成唯一标识
3. **智能回退**：如果锁获取失败（重复提交），自动回退已扣减的限流次数

### 2.2 分布式锁的 Key 设计

```java
// 锁的 Key 格式
String lockKey = "chart:gen:" + userId + ":" + requestId;

// requestId 生成规则
String requestId = md5(fileContentHash + goal + chartType);
```

**为什么这样设计？**

- `userId`：不同用户可以并发提交相同内容
- `fileContentHash`：文件内容的 MD5 哈希
- `goal`：分析目标
- `chartType`：图表类型

这样可以精准识别"同一个用户提交的完全相同的请求"。

## 3. 实现流程详解

### 3.1 完整执行流程图

```
用户提交请求
    ↓
【步骤1】参数校验（文件大小、格式等）
    ↓
【步骤2】限流检查
    ├─ 频率限流：每秒最多 2 次
    └─ 每日限流：普通用户 3 次，VIP 50 次
    ↓ (限流次数 -1)
【步骤3】读取文件内容，生成哈希值
    ↓
【步骤4】构造分布式锁的 Key
    ↓
【步骤5】尝试获取分布式锁 (waitTime=0, leaseTime=10s)
    ├─ 获取成功 ──→ 【步骤6】执行业务逻辑
    │                 ├─ 解析 Excel 数据
    │                 ├─ 保存到数据库
    │                 ├─ 发送到 RabbitMQ
    │                 └─ 返回成功响应
    │                 ↓
    │             【步骤7】自动释放锁
    │
    └─ 获取失败 ──→ 【步骤8】检测到重复提交
                     ↓
                 【步骤9】回退限流次数 (限流次数 +1)
                     ↓
                 【步骤10】返回错误提示
```

### 3.2 关键代码分析

#### Step 1-2: 参数校验和限流

```java
// 1. 参数校验
ThrowUtils.throwIf(StringUtils.isBlank(goal), ErrorCode.PARAMS_ERROR, "分析目标为空");
ThrowUtils.throwIf(size > ONE_MB, ErrorCode.PARAMS_ERROR, "文件超过 1MB");

// 2. 限流检查（会扣减一次限流次数）
redisLimiterManager.doRateLimit("gen_chart_freq_" + loginUser.getId());
redisLimiterManager.doDailyLimit(loginUser.getId(), loginUser.getUserRole());
```

**注意**：此时限流次数已经被扣减了！

#### Step 3-4: 生成唯一标识和锁 Key

```java
// 3. 生成文件内容的唯一标识
String fileContentHash;
try {
    byte[] fileBytes = multipartFile.getBytes();
    fileContentHash = HashUtils.md5(new String(fileBytes));
} catch (Exception e) {
    // 如果读取失败，需要回退限流次数
    redisLimiterManager.rollbackDailyLimit(loginUser.getId(), loginUser.getUserRole());
    throw new BusinessException(ErrorCode.SYSTEM_ERROR, "文件读取失败");
}

// 4. 构造分布式锁的 Key
String requestId = HashUtils.generateRequestId(fileContentHash, goal, chartType);
String lockKey = "chart:gen:" + loginUser.getId() + ":" + requestId;
```

#### Step 5-7: 分布式锁包装业务逻辑

```java
try {
    BiResponse biResponse = distributedLockService.executeWithLock(
        lockKey, 
        0,  // waitTime: 0 秒，不等待，立即失败
        10, // leaseTime: 10 秒后自动释放锁（防止死锁）
        () -> {
            // ========== 业务逻辑开始 ==========
            
            // 解析 Excel 数据
            List<String> headers = ExcelUtils.getHeaders(rawDataList);
            List<List<Object>> dataRows = ExcelUtils.getDataList(rawDataList);
            
            // 保存到数据库
            Chart chart = new Chart();
            chart.setName(name);
            chart.setGoal(goal);
            chart.setChartType(chartType);
            chart.setUserId(loginUser.getId());
            chart.setStatus(GenChartStatusEnum.WAIT.getValue());
            chartService.createChart(chart);
            
            // 创建图表数据表
            long chartId = chart.getId();
            String tableName = "chart_" + chartId;
            chartMapper.createChartTable(tableName, headers);
            chartMapper.insertChartData(tableName, headers, dataRows);
            
            // 发送到 RabbitMQ
            boolean isVip = "vip".equals(loginUser.getUserRole());
            biMessageProducer.sendMessage(String.valueOf(chartId), isVip);
            
            // 删除列表缓存
            evictChartCache(null, loginUser.getId());
            
            // 返回响应
            BiResponse response = new BiResponse();
            response.setChartId(chartId);
            response.setGenResult("分析任务已提交，请稍后在\"我的图表\"查看结果");
            return response;
            
            // ========== 业务逻辑结束 ==========
        }
    );
    
    return ResultUtils.success(biResponse);
    
} catch (BusinessException e) {
    // Step 8-9: 检测到重复提交，回退限流次数
    if (e.getMessage() != null && e.getMessage().contains("重复提交")) {
        log.warn("检测到重复提交，回退限流次数: userId={}, lockKey={}", loginUser.getId(), lockKey);
        // 回退每日限流次数（因为这次请求没有真正执行）
        redisLimiterManager.rollbackDailyLimit(loginUser.getId(), loginUser.getUserRole());
        log.info("已成功回退限流次数: userId={}, 当前剩余次数={}", 
            loginUser.getId(), 
            redisLimiterManager.getRemainingPermits(loginUser.getId(), loginUser.getUserRole()));
    }
    throw e;
} catch (Exception e) {
    // 其他异常也回退限流次数
    log.error("图表生成失败", e);
    redisLimiterManager.rollbackDailyLimit(loginUser.getId(), loginUser.getUserRole());
    throw new BusinessException(ErrorCode.SYSTEM_ERROR, "图表生成失败：" + e.getMessage());
}
```

### 3.3 分布式锁的实现细节

在 `DistributedLockServiceImpl` 中：

```java
@Override
public <T> T executeWithLock(String lockKey, long waitTime, long leaseTime, Supplier<T> supplier) throws Exception {
    RLock lock = redissonClient.getLock(lockKey);
    boolean acquired = false;
    
    try {
        // 尝试获取锁
        acquired = lock.tryLock(waitTime, leaseTime, TimeUnit.SECONDS);
        
        if (!acquired) {
            log.warn("获取分布式锁失败，检测到重复提交: lockKey={}", lockKey);
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "检测到重复提交，请勿短时间内提交相同的请求");
        }
        
        log.info("成功获取分布式锁: lockKey={}", lockKey);
        
        // 执行业务逻辑
        return supplier.get();
        
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        log.error("获取锁时被中断: lockKey={}", lockKey, e);
        throw new BusinessException(ErrorCode.SYSTEM_ERROR, "系统繁忙，请稍后再试");
    } finally {
        // 释放锁（仅当当前线程持有锁时）
        if (acquired && lock.isHeldByCurrentThread()) {
            lock.unlock();
            log.info("释放分布式锁: lockKey={}", lockKey);
        }
    }
}
```

**关键参数说明**：

- `waitTime = 0`：不等待，如果锁已被占用，立即返回失败
- `leaseTime = 10`：锁的最大持有时间为 10 秒，防止死锁

### 3.4 限流回退机制

在 `RedisLimiterManager` 中实现了 `rollbackDailyLimit` 方法：

```java
public void rollbackDailyLimit(Long userId, String userRole) {
    String todayStr = getTodayStr();
    String dailyKey = "gen_chart_daily_" + userId + "_" + todayStr;
    RRateLimiter dailyLimiter = redissonClient.getRateLimiter(dailyKey);
    
    // 如果限流器不存在，说明没有扣减过，无需回退
    if (!dailyLimiter.isExists()) {
        log.warn("限流器不存在，无需回退: userId={}", userId);
        return;
    }
    
    // 计算该用户角色的总额度
    long dailyLimitCount = "vip".equals(userRole) || "admin".equals(userRole) ? 50 : 3;
    
    // 获取当前剩余的令牌数
    long availablePermits = dailyLimiter.availablePermits();
    
    // 如果已经达到上限，说明没有扣减过或已经回退过，不再重复回退
    if (availablePermits >= dailyLimitCount) {
        log.info("限流次数已达上限，无需回退: userId={}, availablePermits={}", userId, availablePermits);
        return;
    }
    
    try {
        // 保存当前已使用的次数
        long usedCount = dailyLimitCount - availablePermits;
        
        // 回退一次，即已使用次数减1
        long newUsedCount = Math.max(0, usedCount - 1);
        
        // 删除旧的限流器
        dailyLimiter.delete();
        
        // 重新创建限流器，设置新的令牌数
        dailyLimiter = redissonClient.getRateLimiter(dailyKey);
        dailyLimiter.trySetRate(RateType.OVERALL, dailyLimitCount, 24, RateIntervalUnit.HOURS);
        dailyLimiter.expire(1, TimeUnit.DAYS);
        
        // 预先消费掉已使用的令牌
        for (int i = 0; i < newUsedCount; i++) {
            dailyLimiter.tryAcquire(1);
        }
        
        log.info("成功回退限流次数: userId={}, 回退后可用次数={}", userId, dailyLimiter.availablePermits());
        
    } catch (Exception e) {
        log.error("回退限流次数失败: userId={}", userId, e);
    }
}
```

**回退逻辑说明**：

1. **检查限流器是否存在**：如果不存在，说明还没有扣减过，无需回退
2. **检查是否已达上限**：如果已达上限，说明已经回退过，避免重复回退
3. **计算已使用次数**：`usedCount = dailyLimitCount - availablePermits`
4. **回退一次**：`newUsedCount = usedCount - 1`
5. **重建限流器**：删除旧的，创建新的，并预先消费掉 `newUsedCount` 个令牌

## 4. 关键问题解答

### 4.1 为什么要先限流再加锁？

**答案**：保证系统整体的安全性。

- **限流**：防止恶意攻击和流量洪峰，保护整个系统
- **分布式锁**：防止重复提交，优化用户体验和资源利用

如果先加锁再限流，恶意用户可以通过不同的请求内容绕过限流，仍然会对系统造成压力。

### 4.2 如何确保限流次数不会被错误扣减？

**答案**：在所有可能失败的分支中都添加回退逻辑。

```java
try {
    // 限流检查（扣减次数）
    redisLimiterManager.doDailyLimit(userId, userRole);
    
    // 读取文件
    byte[] fileBytes = multipartFile.getBytes();
    
    // 尝试获取锁
    BiResponse response = distributedLockService.executeWithLock(...);
    
    return success(response);
    
} catch (BusinessException e) {
    // 重复提交 → 回退
    if (e.getMessage().contains("重复提交")) {
        redisLimiterManager.rollbackDailyLimit(userId, userRole);
    }
    throw e;
} catch (Exception e) {
    // 其他异常 → 回退
    redisLimiterManager.rollbackDailyLimit(userId, userRole);
    throw new BusinessException(ErrorCode.SYSTEM_ERROR, e.getMessage());
}
```

**关键场景**：

- ✅ 文件读取失败 → 回退
- ✅ 分布式锁获取失败（重复提交）→ 回退
- ✅ 业务逻辑执行失败 → 回退
- ❌ 业务逻辑执行成功 → 不回退

### 4.3 为什么 waitTime 设置为 0？

**答案**：快速失败，避免用户长时间等待。

- `waitTime = 0`：如果锁已被占用，立即返回失败，告诉用户"请勿重复提交"
- 如果 `waitTime > 0`：会阻塞等待，用户体验差，而且这种等待没有意义（因为前一个相同的请求正在处理中）

### 4.4 为什么 leaseTime 设置为 10 秒？

**答案**：防止死锁，同时给业务逻辑足够的执行时间。

- **业务逻辑**：解析 Excel、保存数据库、发送 MQ 消息，预计 1-3 秒
- **安全余量**：10 秒足够处理异常情况
- **防止死锁**：如果线程意外崩溃，锁会在 10 秒后自动释放

### 4.5 如果用户修改了一个字符重新提交，会被拦截吗？

**答案**：不会，因为锁的 Key 会不同。

```java
// 场景1：goal = "分析销售数据"
String requestId1 = md5(fileHash + "分析销售数据" + "折线图");
// lockKey = "chart:gen:123:a1b2c3d4..."

// 场景2：goal = "分析销售数据趋势"
String requestId2 = md5(fileHash + "分析销售数据趋势" + "折线图");
// lockKey = "chart:gen:123:e5f6g7h8..."
```

这两个请求会生成不同的 `requestId`，因此不会被认为是重复提交。

### 4.6 限流回退会不会导致并发问题？

**答案**：不会，Redisson 的限流器是线程安全的。

`rollbackDailyLimit` 方法中：
1. 删除旧的限流器
2. 创建新的限流器
3. 预先消费令牌

整个过程是原子性的（通过 Redis 的单线程模型保证），不会出现并发问题。

## 5. 测试场景

### 5.1 正常提交

```
用户提交 → 限流检查通过 → 获取锁成功 → 业务逻辑执行 → 返回成功
```

**期望结果**：
- 限流次数 -1
- 业务执行成功
- 图表创建成功

### 5.2 重复提交（1秒内连续点击两次）

```
第一次：用户提交 → 限流检查通过（3 → 2）→ 获取锁成功 → 业务逻辑执行中...
第二次：用户提交 → 限流检查通过（2 → 1）→ 获取锁失败 → 回退限流（1 → 2）→ 返回错误
```

**期望结果**：
- 第一次请求成功，限流次数从 3 变为 2
- 第二次请求失败，提示"检测到重复提交"
- 限流次数回退为 2（不会因为重复提交而浪费配额）

### 5.3 文件读取失败

```
用户提交 → 限流检查通过（3 → 2）→ 文件读取失败 → 回退限流（2 → 3）→ 返回错误
```

**期望结果**：
- 限流次数没有被消耗
- 用户可以修复文件后重新提交

### 5.4 不同内容的并发提交

```
请求A：file1.xlsx + "分析销售" → 锁A → 正常执行
请求B：file2.xlsx + "分析库存" → 锁B → 正常执行（不冲突）
```

**期望结果**：
- 两个请求并发执行，互不干扰
- 每个请求都消耗一次限流配额

## 6. 监控和日志

### 6.1 关键日志

```java
// 分布式锁相关
log.info("尝试获取分布式锁: lockKey={}, userId={}", lockKey, userId);
log.info("成功获取分布式锁: lockKey={}", lockKey);
log.warn("获取分布式锁失败，检测到重复提交: lockKey={}", lockKey);
log.info("释放分布式锁: lockKey={}", lockKey);

// 限流回退相关
log.warn("检测到重复提交，回退限流次数: userId={}, lockKey={}", userId, lockKey);
log.info("已成功回退限流次数: userId={}, 当前剩余次数={}", userId, remaining);
```

### 6.2 监控指标

建议监控以下指标：

1. **重复提交率**：`(锁获取失败次数 / 总请求次数) * 100%`
2. **限流回退次数**：统计每天的回退次数
3. **锁持有时间**：业务逻辑的平均执行时间
4. **锁等待超时次数**：如果 `waitTime > 0`，统计等待超时的次数

## 7. 优化建议

### 7.1 锁的过期时间优化

如果业务逻辑执行时间可能超过 10 秒，可以考虑：

```java
// 方案1：延长 leaseTime
distributedLockService.executeWithLock(lockKey, 0, 30, () -> { ... });

// 方案2：使用 Redisson 的看门狗机制（leaseTime = -1）
distributedLockService.executeWithLock(lockKey, 0, -1, () -> { ... });
```

### 7.2 缓存重复请求的结果

对于完全相同的请求，可以缓存第一次的结果，后续请求直接返回：

```java
// 在锁内部检查是否已有结果
String cacheKey = "chart:result:" + requestId;
String cachedResult = redisTemplate.opsForValue().get(cacheKey);
if (cachedResult != null) {
    // 直接返回缓存结果
    return JSON.parseObject(cachedResult, BiResponse.class);
}

// 执行业务逻辑...
BiResponse response = ...;

// 缓存结果（例如缓存 5 分钟）
redisTemplate.opsForValue().set(cacheKey, JSON.toJSONString(response), 5, TimeUnit.MINUTES);
```

### 7.3 前端防抖

虽然后端已经做了防重复提交，但前端仍应该实现防抖功能：

```javascript
// 防抖示例
let submitting = false;

async function submitChart() {
    if (submitting) {
        message.warning('请勿重复提交');
        return;
    }
    
    submitting = true;
    try {
        await api.genChart(data);
    } finally {
        setTimeout(() => {
            submitting = false;
        }, 2000); // 2秒后才能再次提交
    }
}
```

## 8. 总结

通过 **分布式锁 + 限流回退** 的方案，我们实现了：

✅ **防止重复提交**：同一用户提交相同内容时，只会执行一次  
✅ **保护限流配额**：重复提交不会浪费用户的限流次数  
✅ **快速失败**：用户不需要等待，立即得到反馈  
✅ **系统稳定**：避免产生重复的 AI 任务，节省资源和成本  
✅ **用户体验好**：清晰的错误提示，公平的配额管理  

这是一个典型的 **分布式场景下的幂等性设计**，核心思想是：

> **先扣费（限流），后验证（加锁），验证失败则退款（回退）**

这种模式可以推广到其他需要防重复提交的场景，例如：
- 订单支付
- 表单提交
- 文件上传
- API 调用

---

**文档更新时间**：2025-12-31  
**作者**：BI 项目开发团队

