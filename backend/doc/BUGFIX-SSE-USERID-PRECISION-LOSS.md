# BUG 修复：SSE 通知中 userId 精度丢失导致弹窗不显示

## 问题描述

当用户创建新的分析任务时，任务完成后没有弹窗通知出现。

## 问题分析

通过查看终端日志，发现关键问题：

```
2025-12-30 20:25:00.406  INFO 9884 --- [ntContainer#2-1] c.y.s.service.impl.BiAsyncServiceImpl    : 【SSE】准备推送任务完成通知: chartId=2005978365906251778, userId=1999814477493903362, status=succeed
2025-12-30 20:25:00.408  INFO 9884 --- [ntContainer#2-1] c.y.s.service.SseNotifyService           : 【SSE】已发布任务通知到 Redis: userId=1999814477493903362, chartId=2005978365906251778, status=succeed
2025-12-30 20:25:00.411  INFO 9884 --- [enerContainer-2] c.y.s.service.SseNotifyService           : 【SSE】收到 Redis Pub/Sub 消息: {"chartId":2005978365906251778,"execMessage":"鍥捐〃鐢熸垚鎴愬姛","type":"chart_task_done","userId":1999814477493903362,"status":"succeed"}
2025-12-30 20:25:00.416  INFO 9884 --- [enerContainer-2] c.y.s.service.SseNotifyService           : 【SSE】本机无该用户的 SSE 连接，忽略: userId=1999814477493903360
```

**关键发现：**
- 发送时的 userId：`1999814477493903362`（正确）
- 接收时解析的 userId：`1999814477493903360`（错误！最后两位变了）

## 根本原因

在 `SseNotifyService.java` 中：

1. **序列化阶段（发布消息）：**
   - 使用 `Gson` 将 Long 类型的 userId 序列化为 JSON
   - 默认情况下，Gson 会将 Long 序列化为 JSON 数字类型

2. **反序列化阶段（接收消息）：**
   - Gson 将 JSON 数字反序列化为 `Map<String, Object>`
   - 对于大数字（> 2^53），Gson 会将其解析为 `Double` 类型
   - **Double 类型只有 53 位精度，无法精确表示 19 位的 Long 值**

3. **转换阶段（parseLongFromObject）：**
   - 原代码使用 `String.format("%.0f", ((Number) obj).doubleValue())`
   - 但此时 Double 值已经丢失精度，格式化也无法恢复

### 精度丢失示例

```
原始值：1999814477493903362 (19位)
Double表示：1.9998144774939034E18
转为Long：1999814477493903360 (最后两位变成60)
```

## 解决方案

修改 `SseNotifyService.java`：

### 1. 配置 Gson 使用字符串序列化 Long

```java
// 修改前
private final Gson gson = new Gson();

// 修改后
private final Gson gson = new GsonBuilder()
        .setLongSerializationPolicy(LongSerializationPolicy.STRING)
        .create();
```

这样 Long 值会被序列化为 JSON 字符串：
```json
{
  "userId": "1999814477493903362",  // 字符串形式，保持精度
  "chartId": "2005978365906251778"
}
```

### 2. 更新 parseLongFromObject 方法

```java
private Long parseLongFromObject(Object obj) {
    if (obj == null) {
        return null;
    }
    if (obj instanceof String) {
        // Gson 使用 LongSerializationPolicy.STRING 后，Long 会被序列化为字符串
        return Long.parseLong((String) obj);
    }
    if (obj instanceof Number) {
        // 兼容处理：如果仍然是 Number 类型
        return ((Number) obj).longValue();
    }
    // 其他情况，尝试转为字符串再解析
    return Long.parseLong(obj.toString());
}
```

## 技术细节

### JavaScript Number 的限制

这个问题也与 JavaScript 的 `Number.MAX_SAFE_INTEGER` 限制有关：
- JavaScript 的 Number 类型使用 IEEE 754 双精度浮点数
- 安全整数范围：-(2^53 - 1) 到 (2^53 - 1)
- 即：-9007199254740991 到 9007199254740991
- 超出此范围的整数会丢失精度

我们的 userId `1999814477493903362` 远超这个范围，因此必须使用字符串传输。

### 最佳实践

对于分布式系统中的大数字 ID（如雪花算法生成的 ID）：
1. **JSON 序列化**：使用字符串格式
2. **前端处理**：使用字符串而非 Number
3. **Redis 传输**：使用字符串键值

## 测试验证

修复后，请按以下步骤验证：

1. 重启后端服务
2. 前端登录用户（userId: 1999814477493903362）
3. 创建一个新的图表分析
4. 观察日志，确认 userId 前后一致
5. 验证弹窗正常显示

## 相关文件

- `backend/src/main/java/com/yupi/springbootinit/service/SseNotifyService.java`

## 修复时间

2025-12-30

## 影响范围

此问题影响所有使用大数字 ID（超过 2^53）的用户的 SSE 实时通知功能。

## 预防措施

1. 在项目中统一使用 `LongSerializationPolicy.STRING` 配置 Gson
2. 对于雪花算法生成的 ID，在前端使用 string 类型而非 number
3. 添加单元测试验证大数字 ID 的序列化和反序列化

