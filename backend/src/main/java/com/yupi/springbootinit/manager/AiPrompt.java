package com.yupi.springbootinit.manager;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
@Service
public class AiPrompt {
    @Resource
    private AiManager aiManager;
    public String func(String goal, String chartType, String csvData){
        StringBuilder systemPrompt = new StringBuilder();
        systemPrompt.append("你是一个高级数据分析师和前端开发专家。接下来我会给你提供分析目标和原始数据。\n");
        systemPrompt.append("【请严格遵守以下 ECharts 可视化布局规范】：\n");
        systemPrompt.append("1. 图例 (legend) 必须设置在图表最下方 (bottom: '0', left: 'center')，并且设置为可滚动 (type: 'scroll') 以防止溢出。\n");
        systemPrompt.append("1. 在 option 中必须包含 grid 属性，并设置 grid: { containLabel: true }，以防止坐标轴标签被遮挡。\n");
        systemPrompt.append("2. 必须设置 grid 组件的 bottom 属性为 '15%' 或更大值，以确保图表底部有足够的空间容纳图例，避免遮挡坐标轴。\n");
        systemPrompt.append("3. 如果 X 轴数据较多，请设置 xAxis.axisLabel.rotate 为 45 度，并设置 interval: 0 强制显示所有标签，防止标签重叠。\n");
        systemPrompt.append("4. 确保 dataZoom 组件（如果有）不与图例重叠。\n");

        systemPrompt.append("请严格按照以下格式输出内容，不要包含任何多余的开场白或结束语：\n");
        systemPrompt.append("【【【【【\n");
        systemPrompt.append("{这里填写前端 Echarts V5 的 option 配置对象 JSON 代码，不要加 ```json 代码块包裹，直接返回 JSON 内容，确保属性名使用双引号}\n");
        systemPrompt.append("【【【【【\n");
        systemPrompt.append("{这里填写明确的数据分析结论，越详细越好}\n");
        StringBuilder userMessage = new StringBuilder();
        userMessage.append("分析目标：").append(goal);
        if (StringUtils.isNotBlank(chartType)) {
            userMessage.append("，请使用").append(chartType);
        }
        userMessage.append("\n");
        userMessage.append("原始数据：\n").append(csvData);

        // 调用 AI
        String result = aiManager.doChat(systemPrompt.toString(), userMessage.toString());
        return result;
    }


}
