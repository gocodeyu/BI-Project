package com.yupi.springbootinit.manager;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;

@SpringBootTest
class AiManagerTest {

    @Resource
    private AiManager aiManager;

    /**
     * 测试 1: 简单的连通性测试
     * 目的: 验证 API Key 是否正确，网络是否通畅
     */
    @Test
    void testSimpleChat() {
        String systemPrompt = "你是一个幽默的助手";
        String userMessage = "你好，给我讲个短笑话";

        System.out.println("----- 开始简单测试 -----");
        try {
            String answer = aiManager.doChat(systemPrompt, userMessage);
            System.out.println("AI 回答: " + answer);
        } catch (Exception e) {
            System.err.println("测试失败，请检查 API Key 或网络配置");
            e.printStackTrace();
        }
        System.out.println("----- 结束简单测试 -----");
    }

    /**
     * 测试 2: 模拟复杂的图表生成业务
     * 目的: 验证 AI 是否能听懂指令返回 【【【【【 格式，以及 JSON 能否解析
     */
    @Test
    void testGenChartLogic() {
        System.out.println("----- 开始业务逻辑测试 -----");

        // 1. 构造系统预设 (与 Controller 保持一致)
        StringBuilder systemPrompt = new StringBuilder();
        systemPrompt.append("你是一个高级数据分析师和前端开发专家。接下来我会给你提供分析目标和原始数据。\n");
        systemPrompt.append("请严格按照以下格式输出内容，不要包含任何多余的开场白或结束语：\n");
        systemPrompt.append("【【【【【\n");
        systemPrompt.append("{这里填写前端 Echarts V5 的 option 配置对象 JSON 代码，不要加 ```json 代码块包裹，直接返回 JSON 内容，确保属性名使用双引号}\n");
        systemPrompt.append("【【【【【\n");
        systemPrompt.append("{这里填写明确的数据分析结论，越详细越好}\n");

        // 2. 构造用户输入 (模拟 CSV 数据)
        StringBuilder userMessage = new StringBuilder();
        userMessage.append("分析目标：分析网站用户的增长趋势\n");
        userMessage.append("原始数据：\n");
        userMessage.append("日期,用户数\n");
        userMessage.append("1号,10\n");
        userMessage.append("2号,20\n");
        userMessage.append("3号,30\n");

        try {
            // 3. 调用 AI
            String result = aiManager.doChat(systemPrompt.toString(), userMessage.toString());
            System.out.println("AI 原始返回结果:\n" + result);

            // 4. 尝试手动解析 (模拟 Controller 的逻辑)
            String[] splits = result.split("【【【【【");
            if (splits.length < 3) {
                System.err.println("❌ 格式解析失败！AI 没有返回足够的 【【【【【 分隔符");
            } else {
                String genChart = splits[1].trim();
                String genResult = splits[2].trim();

                // 去除可能存在的 markdown 符号
                genChart = genChart.replace("```json", "").replace("```", "").trim();

                System.out.println("✅ 格式解析成功！");
                System.out.println(">>> 生成的图表代码:\n" + genChart);
                System.out.println(">>> 生成的分析结论:\n" + genResult);
            }

        } catch (Exception e) {
            System.err.println("❌ 测试过程中发生异常");
            e.printStackTrace();
        }
        System.out.println("----- 结束业务逻辑测试 -----");
    }
}