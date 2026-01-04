# Retry接口多事务并发问题分析

## 📋 问题描述

**现象**：使用 JMeter 对 `/api/chart/gen/retry/rabbitmq` 接口进行并发测试（5个线程同时请求），发现**有2个线程成功**，但理论上应该只有1个线程成功。

**预期行为**：由于使用了分布式锁，5个并发请求中应该只有1个能获取到锁并成功执行，其他4个应该失败。

**实际行为**：5个并发请求中有2个成功，说明分布式锁没有完全生效。

---

## 📚 基础知识

在深入分析问题之前，我们需要理解几个关键概念：

### 1. 什么是事务（Transaction）？

**简单理解**：事务就像银行转账，要么全部成功，要么全部失败。

**例子**：
- 你从账户A转100元到账户B
- 如果A扣款成功，但B加款失败，那么A的扣款也要撤销（回滚）
- 这就是事务的**原子性**：要么全部成功，要么全部失败

**在代码中的体现**：
```java
@Transactional(rollbackFor = Exception.class)
public void updateChartStatusAndSendMessage(Long chartId, boolean isVip) {
    // 这个方法中的所有数据库操作要么全部成功，要么全部失败
    chartService.updateById(updateChart);  // 操作1：更新数据库
    biMessageProducer.sendMessage(...);    // 操作2：发送MQ消息
    // 如果操作1成功但操作2失败，操作1会被撤销（回滚）
}
```

**关键点**：
- `@Transactional` 注解告诉Spring：这个方法需要在事务中执行
- 事务的提交发生在**方法返回之后**，由Spring的AOP（面向切面编程）代理完成
- 也就是说，方法执行完了，但事务可能还没提交！

### 2. 什么是分布式锁（Distributed Lock）？

**简单理解**：分布式锁就像厕所的门锁，同一时间只能有一个人使用。

**为什么需要分布式锁？**
- 在单机环境下，可以用Java的`synchronized`关键字
- 但在多服务器环境下（比如3台服务器同时运行），`synchronized`只能锁住当前服务器
- 分布式锁使用Redis等共享存储，可以跨服务器锁住资源

**例子**：
```
服务器A：线程1尝试获取锁 → 成功 → 执行操作
服务器B：线程2尝试获取锁 → 失败（因为A已经持有锁） → 等待或失败
服务器C：线程3尝试获取锁 → 失败 → 等待或失败
```

**在代码中的体现**：
```java
// 使用Redisson（Redis的Java客户端）实现分布式锁
RLock lock = redissonClient.getLock("chart:retry:123:456");
if (lock.tryLock(0, 10, TimeUnit.SECONDS)) {
    // 获取锁成功，执行业务逻辑
    try {
        // 业务代码
    } finally {
        lock.unlock();  // 释放锁
    }
} else {
    // 获取锁失败，说明有其他线程正在执行
    throw new BusinessException("检测到重复提交");
}
```

### 3. Spring AOP（面向切面编程）是什么？

**简单理解**：AOP就像给方法套了一个"包装盒"，在方法执行前后可以做一些额外的事情。

**例子**：
```java
// 原始方法
public void doSomething() {
    System.out.println("执行业务逻辑");
}

// Spring AOP会在方法执行前后插入代码
public void doSomething() {
    // AOP插入：开始事务
    try {
        System.out.println("执行业务逻辑");
        // AOP插入：提交事务
    } catch (Exception e) {
        // AOP插入：回滚事务
    }
}
```

**关键点**：
- `@Transactional`注解就是通过AOP实现的
- 方法执行完了，但AOP的事务提交代码还没执行
- 这导致了**方法返回 ≠ 事务提交**

### 4. 什么是Lambda表达式（Supplier）？

**简单理解**：Lambda表达式是一种简化的函数写法。

**例子**：
```java
// 传统写法
Runnable task = new Runnable() {
    @Override
    public void run() {
        System.out.println("执行任务");
    }
};

// Lambda写法（简化版）
Runnable task = () -> {
    System.out.println("执行任务");
};

// Supplier（带返回值的Lambda）
Supplier<String> supplier = () -> {
    return "返回结果";
};
```

**在我们的代码中**：
```java
distributedLockService.executeWithLock(
    lockKey,
    0,
    10,
    () -> {  // 这是一个Supplier，包含要执行的业务逻辑
        chartTransactionService.updateChartStatusAndSendMessage(chartId, isVip);
        return true;
    }
);
```

---

## 🔍 代码流程分析

让我们看看完整的代码执行流程：

### 1. Controller层（入口）

**文件**：`ChartController.java`

```java
@PostMapping("/gen/retry/rabbitmq")
public BaseResponse<Boolean> retryChartRabbitmq(@RequestBody ChartReloadRequest reloadRequest, HttpServletRequest request) {
    // ... 参数校验、权限校验、限流校验 ...
    
    // 构造分布式锁的Key
    String lockKey = "chart:retry:" + loginUser.getId() + ":" + chartId;
    
    // 使用分布式锁包装业务逻辑
    Boolean result = distributedLockService.executeWithLock(
        lockKey,
        0,  // waitTime: 0秒，不等待，立即失败
        10, // leaseTime: 10秒后自动释放锁
        () -> {
            // 在事务中更新图表状态并发送MQ消息
            boolean isVip = "vip".equals(loginUser.getUserRole());
            chartTransactionService.updateChartStatusAndSendMessage(chartId, isVip);
            
            // 删除缓存
            evictChartCache(chartId, chart.getUserId());
            
            return true;
        }
    );
    
    return ResultUtils.success(result);
}
```

**流程说明**：
1. 调用 `distributedLockService.executeWithLock()`，传入一个Lambda表达式（Supplier）
2. Lambda表达式内部调用 `chartTransactionService.updateChartStatusAndSendMessage()`
3. 这个方法有 `@Transactional` 注解，会在事务中执行

### 2. 分布式锁服务（问题所在）

**文件**：`DistributedLockServiceImpl.java`

#### ❌ 修复前的代码（有问题）

```java
@Override
public <T> T executeWithLock(String lockKey, long waitTime, long leaseTime, Supplier<T> supplier) throws Exception {
    RLock lock = redissonClient.getLock(lockKey);
    boolean acquired = false;
    
    try {
        // 1. 尝试获取锁
        acquired = lock.tryLock(waitTime, leaseTime, TimeUnit.SECONDS);
        
        if (!acquired) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "检测到重复提交");
        }
        
        log.info("成功获取分布式锁: lockKey={}", lockKey);
        
        // 2. 执行业务逻辑（调用supplier.get()）
        return supplier.get();  // ⚠️ 这里会调用事务方法
        
    } catch (InterruptedException e) {
        // 异常处理...
    } finally {
        // 3. 释放锁
        if (acquired && lock.isHeldByCurrentThread()) {
            lock.unlock();  // ❌ 问题：锁在这里被释放了！
            log.info("释放分布式锁: lockKey={}", lockKey);
        }
    }
}
```

**问题分析**：
1. `supplier.get()` 调用了 `updateChartStatusAndSendMessage()` 方法
2. 这个方法有 `@Transactional` 注解，会在事务中执行
3. `supplier.get()` **返回了**，但事务**还没提交**（事务提交由Spring AOP在方法返回后完成）
4. `finally` 块立即执行，**锁被释放了**
5. 此时事务还在等待提交，但锁已经释放了！

#### ✅ 修复后的代码

```java
@Override
public <T> T executeWithLock(String lockKey, long waitTime, long leaseTime, Supplier<T> supplier) throws Exception {
    RLock lock = redissonClient.getLock(lockKey);
    boolean acquired = false;
    boolean registeredTransactionSync = false; // 标记是否注册了事务同步回调
    
    try {
        // 1. 尝试获取锁
        acquired = lock.tryLock(waitTime, leaseTime, TimeUnit.SECONDS);
        
        if (!acquired) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "检测到重复提交");
        }
        
        log.info("成功获取分布式锁: lockKey={}", lockKey);
        
        // 2. 检查是否有活跃事务
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            // 3. 注册事务同步回调，在事务提交/回滚后释放锁
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    // 事务完成（提交或回滚）后释放锁
                    if (lock.isHeldByCurrentThread()) {
                        lock.unlock();
                        log.info("事务完成后释放分布式锁: lockKey={}, status={}", lockKey, status);
                    }
                }
            });
            registeredTransactionSync = true;
            log.info("已注册事务同步回调，锁将在事务提交后释放: lockKey={}", lockKey);
        } else {
            log.info("当前无事务，锁将在业务逻辑执行后立即释放: lockKey={}", lockKey);
        }
        
        // 4. 执行业务逻辑
        return supplier.get();
        
    } catch (InterruptedException e) {
        // 异常处理...
    } finally {
        // 5. 如果没有注册事务同步回调，立即释放锁；如果已注册，锁将在事务同步回调中释放
        if (!registeredTransactionSync && acquired && lock.isHeldByCurrentThread()) {
            lock.unlock();
            log.info("释放分布式锁（无事务）: lockKey={}", lockKey);
        }
    }
}
```

**修复说明**：
1. 使用 `TransactionSynchronizationManager.isActualTransactionActive()` 检查是否有活跃事务
2. 如果有事务，注册一个事务同步回调（`TransactionSynchronization`）
3. 在 `afterCompletion()` 方法中释放锁，这个方法会在事务提交或回滚后执行
4. 如果没有事务，在 `finally` 块中立即释放锁

### 3. 事务服务（业务逻辑）

**文件**：`ChartTransactionServiceImpl.java`

#### ❌ 修复前的代码（缺少状态检查）

```java
@Override
@Transactional(rollbackFor = Exception.class)
public void updateChartStatusAndSendMessage(Long chartId, boolean isVip) {
    log.info("[事务服务] 开始更新图表状态 - chartId={}", chartId);
    
    // 1. 更新图表状态为等待
    Chart updateChart = new Chart();
    updateChart.setId(chartId);
    updateChart.setStatus(GenChartStatusEnum.WAIT.getValue());
    updateChart.setExecMessage("");
    
    boolean update = chartService.updateById(updateChart);
    if (!update) {
        throw new BusinessException(ErrorCode.OPERATION_ERROR, "更新图表状态失败");
    }
    
    // 2. 发送MQ消息
    biMessageProducer.sendMessage(String.valueOf(chartId), isVip);
}
```

**问题**：没有检查当前状态，即使状态已经是 `WAIT`，也会继续执行。

#### ✅ 修复后的代码（添加状态检查）

```java
@Override
@Transactional(rollbackFor = Exception.class)
public void updateChartStatusAndSendMessage(Long chartId, boolean isVip) {
    log.info("[事务服务] 开始更新图表状态 - chartId={}", chartId);
    
    // 1. 检查当前状态，只有 FAILED 状态才能重试（幂等性保证）
    Chart currentChart = chartService.getById(chartId);
    if (currentChart == null) {
        throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "图表不存在");
    }
    String currentStatus = currentChart.getStatus();
    if (!GenChartStatusEnum.FAILED.getValue().equals(currentStatus)) {
        log.warn("[事务服务] 图表状态不是 FAILED，不能重试 - chartId={}, currentStatus={}", chartId, currentStatus);
        throw new BusinessException(ErrorCode.OPERATION_ERROR, "只有失败状态的图表才能重试，当前状态：" + currentStatus);
    }
    
    // 2. 更新图表状态为等待
    Chart updateChart = new Chart();
    updateChart.setId(chartId);
    updateChart.setStatus(GenChartStatusEnum.WAIT.getValue());
    updateChart.setExecMessage("");
    
    boolean update = chartService.updateById(updateChart);
    if (!update) {
        throw new BusinessException(ErrorCode.OPERATION_ERROR, "更新图表状态失败");
    }
    
    // 3. 发送MQ消息
    biMessageProducer.sendMessage(String.valueOf(chartId), isVip);
}
```

**修复说明**：
1. 在更新状态之前，先查询当前状态
2. 只有 `FAILED` 状态才能重试，其他状态直接抛出异常
3. 这是一个**防御性编程**措施，即使分布式锁有问题，也能防止重复执行

---

## 🐛 问题根源详解

### 时间线分析（修复前）

假设有2个线程（Thread1 和 Thread2）同时请求：

```
时间点    Thread1                          Thread2
-----------------------------------------------------------
T1        获取分布式锁成功                 等待获取锁
T2        调用 updateChartStatusAndSendMessage()
T3        开始事务（Spring AOP）
T4        更新数据库（状态：FAILED → WAIT）
T5        方法返回（supplier.get() 返回）
T6        finally块执行，释放锁 ❌         获取锁成功 ✅
T7        事务提交（Spring AOP）           调用 updateChartStatusAndSendMessage()
T8                                       开始事务
T9                                       更新数据库（状态：WAIT → WAIT）
T10                                      方法返回
T11                                      释放锁
T12                                      事务提交
```

**问题**：
- T6时刻，Thread1的锁被释放了，但事务还没提交（T7）
- T6时刻，Thread2获取到了锁，开始执行
- 结果：两个线程都成功执行了！

### 为什么会有这个问题？

1. **方法返回 ≠ 事务提交**
   - `supplier.get()` 返回时，事务方法已经执行完
   - 但事务的提交是由Spring AOP在方法返回**之后**完成的
   - 这是一个**异步**的过程

2. **finally块执行时机**
   - `finally` 块在 `try` 块执行完后立即执行
   - 此时事务可能还没提交
   - 锁被提前释放了

3. **并发竞态条件**
   - Thread1释放锁 → Thread2获取锁 → Thread1的事务还没提交
   - 两个线程都能执行更新操作

---

## ✅ 修复方案

### 方案1：事务感知的锁释放（核心修复）

**原理**：使用Spring的 `TransactionSynchronizationManager` 注册事务同步回调，在事务提交后才释放锁。

**关键代码**：
```java
// 检查是否有活跃事务
if (TransactionSynchronizationManager.isActualTransactionActive()) {
    // 注册事务同步回调
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCompletion(int status) {
            // 事务完成（提交或回滚）后释放锁
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    });
}
```

**执行流程**：
```
1. 获取锁
2. 检查是否有事务 → 有
3. 注册事务同步回调
4. 执行业务逻辑（supplier.get()）
5. 方法返回，但锁不释放
6. Spring AOP提交事务
7. 事务同步回调执行 → 释放锁 ✅
```

### 方案2：状态检查（防御性编程）

**原理**：在事务方法中检查当前状态，只有 `FAILED` 状态才能重试。

**关键代码**：
```java
// 检查当前状态
Chart currentChart = chartService.getById(chartId);
String currentStatus = currentChart.getStatus();
if (!GenChartStatusEnum.FAILED.getValue().equals(currentStatus)) {
    throw new BusinessException("只有失败状态的图表才能重试");
}
```

**作用**：
- 即使分布式锁有问题，也能防止重复执行
- 第一个线程将状态从 `FAILED` 改为 `WAIT`
- 第二个线程检查状态时发现是 `WAIT`，直接抛出异常

---

## 📊 修复前后对比

### 修复前的时间线

```
Thread1: 获取锁 → 执行事务 → 方法返回 → 释放锁 ❌ → 事务提交
Thread2: 等待 → 获取锁 ✅ → 执行事务 → 方法返回 → 释放锁 → 事务提交
结果：两个线程都成功 ❌
```

### 修复后的时间线

```
Thread1: 获取锁 → 执行事务 → 方法返回 → 注册事务回调 → 事务提交 → 释放锁 ✅
Thread2: 等待 → 等待 → 等待 → 等待 → 等待 → 获取锁失败 ❌
结果：只有Thread1成功 ✅
```

---

## 🔧 相关类和接口说明

### 1. TransactionSynchronizationManager

**作用**：Spring提供的事务同步管理器，用于在事务的不同阶段执行回调。

**常用方法**：
- `isActualTransactionActive()`：检查当前是否有活跃事务
- `registerSynchronization(TransactionSynchronization)`：注册事务同步回调

**使用场景**：
- 需要在事务提交后执行某些操作（如清理缓存、发送通知等）
- 需要在事务回滚后执行某些操作（如记录日志等）

### 2. TransactionSynchronization

**作用**：事务同步回调接口，定义了事务不同阶段的回调方法。

**常用方法**：
- `afterCommit()`：事务提交后执行
- `afterCompletion(int status)`：事务完成后执行（无论提交还是回滚）
  - `status = 0`：事务已提交
  - `status = 1`：事务已回滚
  - `status = 2`：状态未知

### 3. RLock（Redisson Lock）

**作用**：Redisson提供的分布式锁接口。

**常用方法**：
- `tryLock(waitTime, leaseTime, timeUnit)`：尝试获取锁
  - `waitTime`：等待获取锁的时间（0表示不等待）
  - `leaseTime`：锁的自动释放时间（防止死锁）
- `unlock()`：释放锁
- `isHeldByCurrentThread()`：检查当前线程是否持有锁

### 4. @Transactional 注解

**作用**：声明式事务管理，告诉Spring这个方法需要在事务中执行。

**常用属性**：
- `rollbackFor`：哪些异常需要回滚事务
- `propagation`：事务传播行为（如：REQUIRED、REQUIRES_NEW等）
- `isolation`：事务隔离级别

**工作原理**：
1. Spring AOP拦截方法调用
2. 在方法执行前开始事务
3. 执行方法
4. 在方法返回后提交事务（或异常时回滚）

---

## 🧪 测试验证

### 测试步骤

1. 准备一个状态为 `FAILED` 的图表
2. 使用JMeter创建5个并发线程
3. 同时请求 `/api/chart/gen/retry/rabbitmq` 接口
4. 观察结果

### 预期结果（修复后）

- ✅ 只有1个线程成功（返回200）
- ❌ 其他4个线程失败（返回错误，提示"检测到重复提交"或"只有失败状态的图表才能重试"）

### 验证点

1. **分布式锁生效**：只有1个线程能获取到锁
2. **状态检查生效**：如果第一个线程已经将状态改为 `WAIT`，第二个线程应该失败
3. **事务完整性**：确保事务提交后才释放锁

---

## 📝 总结

### 问题根源

1. **分布式锁在事务提交前被释放**
   - `finally` 块在方法返回后立即执行
   - 但事务提交是在方法返回后由Spring AOP完成的
   - 导致锁释放时机早于事务提交

2. **缺少状态检查**
   - 没有检查当前状态，即使状态已经是 `WAIT`，也会继续执行
   - 缺少防御性编程措施

### 修复方案

1. **事务感知的锁释放**
   - 使用 `TransactionSynchronizationManager` 注册事务同步回调
   - 在事务提交后才释放锁
   - 确保锁的生命周期覆盖整个事务

2. **状态检查**
   - 在事务方法中检查当前状态
   - 只有 `FAILED` 状态才能重试
   - 作为防御性编程措施

### 关键知识点

1. **方法返回 ≠ 事务提交**
   - Spring AOP会在方法返回后提交事务
   - 需要理解这个异步过程

2. **分布式锁的生命周期**
   - 锁的生命周期应该覆盖整个事务
   - 不能只在方法执行期间持有锁

3. **防御性编程**
   - 即使有分布式锁，也要添加业务层面的检查
   - 多重防护，确保系统稳定性

---

## 📚 参考资料

- [Spring事务管理文档](https://docs.spring.io/spring-framework/docs/current/reference/html/data-access.html#transaction)
- [Redisson分布式锁文档](https://github.com/redisson/redisson/wiki/8.-Distributed-locks-and-synchronizers)
- [Spring AOP文档](https://docs.spring.io/spring-framework/docs/current/reference/html/core.html#aop)

---

**文档创建时间**：2026-01-04  
**问题修复时间**：2026-01-04  
**修复人员**：AI Assistant

