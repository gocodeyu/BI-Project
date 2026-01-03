# 事务回滚时MQ消息处理机制说明

## 问题

如果 `biMessageProducer.sendMessage()` 放在和数据库操作同一个事务中，当数据库操作失败时，MQ消息还会发送吗？

## 答案

**不会发送！** 这正是我们想要的行为。

## 执行流程分析

### 场景1：数据库操作成功

```
1. createChartWithTransaction() 方法开始（@Transactional 开启事务）
   ↓
2. chartService.createChart() - 成功
   ↓
3. chartMapper.createChartTable() - 成功
   ↓
4. chartMapper.insertChartData() - 成功
   ↓
5. biMessageProducer.sendMessage() 被调用
   ↓
6. 检测到事务环境，注册 TransactionSynchronization 钩子
   ↓
7. 方法正常返回（事务未提交）
   ↓
8. Spring 事务管理器提交事务
   ↓
9. TransactionSynchronization.afterCommit() 被调用 ✅
   ↓
10. doSendMessage() 执行，MQ消息发送 ✅
```

**结果**：数据库操作成功，MQ消息发送成功 ✅

### 场景2：数据库操作失败（以创建数据表失败为例）

```
1. createChartWithTransaction() 方法开始（@Transactional 开启事务）
   ↓
2. chartService.createChart() - 成功
   ↓
3. chartMapper.createChartTable() - 失败！抛出异常
   ↓
4. 异常向上传播，Spring 事务管理器检测到异常
   ↓
5. biMessageProducer.sendMessage() **还未被调用**（因为第3步就失败了）
   ↓
6. Spring 事务管理器回滚事务
   ↓
7. TransactionSynchronization.afterCompletion(STATUS_ROLLED_BACK) 被调用
   ↓
8. afterCompletion() 中判断 status == STATUS_ROLLED_BACK，只记录日志，不发送消息 ❌
```

**结果**：数据库操作失败并回滚，MQ消息未发送 ✅

### 场景3：数据库操作成功，但 sendMessage() 在最后才调用（当前代码的情况）

当前代码中，`sendMessage()` 在所有数据库操作之后调用：

```java
@Transactional(rollbackFor = Exception.class)
public Long createChartWithTransaction(...) {
    // 1. 数据库操作1
    chartService.createChart(chart);
    
    // 2. 数据库操作2
    chartMapper.createChartTable(tableName, headers);
    
    // 3. 数据库操作3
    chartMapper.insertChartData(tableName, headers, dataRows);
    
    // 4. 发送MQ消息（注册钩子，但不立即发送）
    biMessageProducer.sendMessage(String.valueOf(chartId), isVip);
    
    return chartId; // 方法返回，事务提交
}
```

**执行流程**：

```
1. 所有数据库操作成功
   ↓
2. biMessageProducer.sendMessage() 被调用
   ↓
3. 检测到事务环境，注册 TransactionSynchronization 钩子
   ↓
4. 方法返回（事务还未提交）
   ↓
5. Spring 事务管理器提交事务
   ↓
6. afterCommit() 被调用，发送MQ消息 ✅
```

**如果中间某步失败**：

```
1. chartService.createChart() - 成功
   ↓
2. chartMapper.createChartTable() - 失败！抛出异常
   ↓
3. 异常向上传播
   ↓
4. biMessageProducer.sendMessage() **还未执行到**
   ↓
5. Spring 事务管理器回滚事务
   ↓
6. 没有注册的钩子，MQ消息不会发送 ✅
```

## 代码实现验证

查看 `BiMessageProducer.sendMessage()` 的实现：

```java
public void sendMessage(String chartId, boolean isVip) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
        // 注册事务后钩子
        TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    // ✅ 只有事务提交成功时才会调用
                    doSendMessage(chartId, isVip);
                }
                
                @Override
                public void afterCompletion(int status) {
                    if (status == STATUS_ROLLED_BACK) {
                        // ✅ 事务回滚时只记录日志，不发送消息
                        log.warn("事务回滚，取消发送MQ消息 - chartId={}", chartId);
                    }
                }
            }
        );
    }
}
```

## 关键点总结

1. **事务提交成功** → `afterCommit()` 被调用 → MQ消息发送 ✅
2. **事务回滚** → `afterCompletion(STATUS_ROLLED_BACK)` 被调用 → MQ消息不发送 ✅
3. **异常发生在 sendMessage() 之前** → sendMessage() 根本不会执行 → MQ消息不发送 ✅

## 优势

这种设计确保了：

1. **数据一致性**：只有数据库操作成功，MQ消息才会发送
2. **消息防丢失**：避免数据库回滚但消息已发送的情况
3. **事务原子性**：数据库操作和MQ消息发送作为一个整体，要么都成功，要么都失败

## 注意事项

如果 `sendMessage()` 本身抛出异常（在注册钩子时），这个异常会导致整个事务回滚，这是正确的行为。但如果在 `afterCommit()` 中的 `doSendMessage()` 抛出异常，由于事务已经提交，无法回滚数据库操作。不过这种情况很少发生，因为：

1. 数据库操作已经成功
2. MQ发送失败只影响消息传递，不影响数据完整性
3. 可以通过生产者确认机制和重试机制来处理MQ发送失败的情况

