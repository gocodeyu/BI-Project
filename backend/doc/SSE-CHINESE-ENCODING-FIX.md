# SSE 中文乱码问题修复

## 问题描述

SSE 推送的消息中，中文显示为乱码：
- 原始文本：`"图表生成成功"`
- 乱码显示：`"鍥捐〃鐢熸垚鎴愬姛"`

这是典型的 **UTF-8 文本被错误地解析为 ISO-8859-1** 导致的编码问题。

## 问题分析

### 根本原因（已定位）

**真正的问题在 Redis Pub/Sub 消息传输过程中！**

经过深入调查，发现有两个编码问题：

#### 1. Redis 消息接收时未指定编码

在 `SseNotifyService.java` 的 `onMessage` 方法中：

```java
// ❌ 错误的方式 - 使用平台默认编码
String messageBody = new String(message.getBody());
```

在 Windows 系统上，平台默认编码可能是 GBK，导致 UTF-8 的字节被错误解析。

#### 2. SSE 发送时未指定 MediaType

```java
// ❌ 错误的方式
emitter.send(SseEmitter.event()
        .name("chart_task_done")
        .data(gson.toJson(pushMessage)));  // 没有指定 MediaType
```

### 编码流程分析

1. **Java String** → UTF-8 编码 → `"图表生成成功"` (正确)
2. **Gson 序列化** → JSON 字符串 → 仍然是 UTF-8
3. **Redis 发布** → StringRedisTemplate 使用 UTF-8（默认）→ 正确
4. **Redis 接收** → ❌ `new String(bytes)` 使用平台默认编码（GBK）→ **乱码产生**
5. **SSE 发送** → 已经是乱码了
6. **前端接收** → 接收到的就是乱码

## 解决方案

### ✅ 关键修复 1：Redis 消息接收时显式指定 UTF-8 编码

**文件：** `SseNotifyService.java`

**修改前：**
```java
@Override
public void onMessage(Message message, byte[] pattern) {
    try {
        String messageBody = new String(message.getBody()); // ❌ 使用平台默认编码
        log.info("【SSE】收到 Redis Pub/Sub 消息: {}", messageBody);
```

**修改后：**
```java
@Override
public void onMessage(Message message, byte[] pattern) {
    try {
        // ✅ 使用 UTF-8 编码解析消息，避免中文乱码
        String messageBody = new String(message.getBody(), java.nio.charset.StandardCharsets.UTF_8);
        log.info("【SSE】收到 Redis Pub/Sub 消息: {}", messageBody);
```

这是**最关键的修复**！必须显式指定 UTF-8 编码。

### ✅ 关键修复 2：配置 StringRedisTemplate 使用 UTF-8

**文件：** `RedisPubSubConfig.java`

添加显式的 UTF-8 配置：

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

### 3. 修改 Gson 配置

```java
private final Gson gson = new GsonBuilder()
        .setLongSerializationPolicy(LongSerializationPolicy.STRING)
        .disableHtmlEscaping() // 禁用 HTML 转义
        .create();
```

### 4. SSE 发送时显式指定 MediaType

**修改前：**
```java
emitter.send(SseEmitter.event()
        .name("chart_task_done")
        .data(gson.toJson(pushMessage)));
```

**修改后：**
```java
String jsonMessage = gson.toJson(pushMessage);
emitter.send(SseEmitter.event()
        .name("chart_task_done")
        .data(jsonMessage, org.springframework.http.MediaType.APPLICATION_JSON));
```

### 5. 同样修复初始连接消息

```java
Map<String, String> connectedMessage = new java.util.HashMap<>();
connectedMessage.put("type", "connected");
connectedMessage.put("message", "SSE连接已建立");
String jsonMessage = gson.toJson(connectedMessage);
emitter.send(SseEmitter.event()
        .name("connected")
        .data(jsonMessage, org.springframework.http.MediaType.APPLICATION_JSON));
```

## 测试验证

### 修复前
```
message: "鍥捐〃鐢熸垚鎴愬姛"
```

### 修复后（期望）
```
message: "图表生成成功"
```

## 技术细节

### MediaType.APPLICATION_JSON 的作用

`MediaType.APPLICATION_JSON` 的定义：
```java
public static final MediaType APPLICATION_JSON = new MediaType("application", "json", StandardCharsets.UTF_8);
```

它明确指定了：
- **Content-Type**: `application/json`
- **Charset**: `UTF-8`

这确保了数据在传输过程中使用正确的编码。

### 为什么之前没问题？

如果之前在其他地方没有遇到中文乱码，可能是因为：
1. 其他接口使用了 `@RestController` 和 `@RequestMapping`，Spring 自动处理了编码
2. SSE 是长连接流式传输，需要显式指定编码
3. `application.yml` 中可能有全局编码配置，但 SSE 不遵守

### 相关配置

如果问题仍然存在，检查 `application.yml`:

```yaml
spring:
  http:
    encoding:
      charset: UTF-8
      enabled: true
      force: true
```

## 相关文件

- `backend/src/main/java/com/yupi/springbootinit/service/SseNotifyService.java`

## 修复时间

2025-12-30

## 参考资料

- [Spring SseEmitter Documentation](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/web/servlet/mvc/method/annotation/SseEmitter.html)
- [MediaType.APPLICATION_JSON](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/http/MediaType.html#APPLICATION_JSON)
- [UTF-8 vs ISO-8859-1 编码问题](https://stackoverflow.com/questions/643694/what-is-the-difference-between-utf-8-and-iso-8859-1)

