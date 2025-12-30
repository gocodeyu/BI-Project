# SSE 通知弹窗优化

## 修改内容

### 优化前
```
标题：✅ 图表生成成功
内容：图表 ID: 2005983321342169090
     图表生成成功
```

### 优化后
```
顶部：[网站图标] 智能 BI 平台
标题：图表生成成功
内容：分析：图表名称，分析成功
```

## 实现细节

### 1. 移除了 emoji 符号
- 去掉了标题中的 ✅ 和 ❌ 符号
- 使通知更加简洁专业

### 2. 隐藏了图表 ID
- 不再显示技术性的 chartId
- 用户不需要关心内部 ID

### 3. 显示图表名称
- 通过 `getChartByIdUsingGet` 获取图表详细信息
- 显示用户创建时输入的图表名称
- 如果获取失败，显示简化版通知

### 4. 改进文案
- **标题**：`图表生成成功` / `图表生成失败`
- **描述**：`分析：{图表名称}，分析成功` / `分析：{图表名称}，分析失败`

## 代码实现

```typescript
// 获取图表信息以显示图表名称
getChartByIdUsingGet({ id: chartId })
  .then((res) => {
    const chartData = res.data;
    const chartName = chartData?.name || '未命名图表';
    
    // 显示通知
    notification.open({
      message: status === 'succeed' ? '图表生成成功' : '图表生成失败',
      description: `分析：${chartName}，${status === 'succeed' ? '分析成功' : '分析失败'}`,
      type: status === 'succeed' ? 'success' : 'error',
      placement: 'bottomRight',
      duration: 8,
      onClick: () => loadData(),
    });
  })
  .catch((e) => {
    // 降级处理：如果获取失败，显示简化版通知
    notification.open({
      message: status === 'succeed' ? '图表生成成功' : '图表生成失败',
      description: status === 'succeed' ? '分析成功' : '分析失败',
      type: status === 'succeed' ? 'success' : 'error',
      placement: 'bottomRight',
      duration: 8,
      onClick: () => loadData(),
    });
  });
```

## 用户体验提升

### 优点
1. **更直观**：直接显示图表名称，用户立即知道是哪个分析完成了
2. **更简洁**：移除了技术细节（ID、emoji）
3. **更专业**：文案更加规范
4. **容错性好**：即使获取图表信息失败，也能显示通知

### 示例

用户创建了一个名为"销售数据分析"的图表，完成后显示：

```
图表生成成功
分析：销售数据分析，分析成功
```

## 相关文件

- `fronted/src/pages/AddChart/index.tsx`

## 修改时间

2025-12-30

