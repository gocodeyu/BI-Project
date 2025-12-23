package com.yupi.springbootinit.utils;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.support.ExcelTypeEnum;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Excel 工具类
 */
@Slf4j
public class ExcelUtils {

    /**
     * 读取 Excel 文件，返回列表形式（用于数据库操作）
     * @param multipartFile
     * @return List<Map<Integer, String>> 每一行是一个 Map，Key是列索引，Value是值
     * 例子：[{0: "日期", 1: "人数", 2: "男性"}, {1: "4号", 1: "5人", 2: "2人"}]
     */
    public static List<Map<Integer, String>> readExcel(MultipartFile multipartFile) {
        try {
            return EasyExcel.read(multipartFile.getInputStream())
                    .excelType(ExcelTypeEnum.XLSX)
                    .sheet()
                    .headRowNumber(0)
                    .doReadSync();
        } catch (IOException e) {
            log.error("表格处理错误", e);
            throw new RuntimeException("表格处理错误");
        }
    }


    /**
     * 核心转换逻辑：List<Map> -> CSV String
     * @param list
     * @return CSV String
     * 例子："日期,人数,男性\n4号,5人,2人"
     */
    public static String convertListToCsv(List<Map<Integer, String>> list) {
        if (CollUtil.isEmpty(list)) {
            return "";
        }

        StringBuilder stringBuilder = new StringBuilder();

        // 读取每一行
        for (Map<Integer, String> rowMap : list) {
            // 过滤掉全是空值的行（可选）
            if (ObjectUtil.isEmpty(rowMap)) {
                continue;
            }

            // 将 Map 的值转为 List，并处理特殊字符
            List<String> rowValues = rowMap.values().stream()
                    .map(value -> {
                        if (StringUtils.isBlank(value)) {
                            return ""; // 空值处理
                        }
                        // 关键优化：如果内容包含逗号，用引号包裹，避免 CSV 格式错乱
                        // 例如： Hello, World -> "Hello, World"
                        if (value.contains(",")) {
                            return "\"" + value + "\"";
                        }
                        return value;
                    })
                    .collect(Collectors.toList());

            // 使用逗号拼接
            stringBuilder.append(StringUtils.join(rowValues, ",")).append("\n");
        }

        return stringBuilder.toString();
    }

    /**
     * 获取表头（第一行）
     */
    public static List<String> getHeaders(List<Map<Integer, String>> list) {
        if (CollUtil.isEmpty(list)) {
            return new ArrayList<>();
        }
        // 假设第一行是表头
        Map<Integer, String> headerMap = list.get(0);
        return new ArrayList<>(headerMap.values());
    }

    /**
     * 获取数据内容（去除表头后的数据）
     */
    public static List<List<Object>> getDataList(List<Map<Integer, String>> list) {
        if (CollUtil.isEmpty(list) || list.size() < 2) {
            return new ArrayList<>();
        }
        // 跳过第一行表头
        List<List<Object>> dataList = new ArrayList<>();
        for (int i = 1; i < list.size(); i++) {
            Map<Integer, String> rowMap = list.get(i);
            // 将 Map values 转为 List<Object>
            dataList.add(new ArrayList<>(rowMap.values()));
        }
        return dataList;
    }
    /**
     * 将数据库查询结果 List<Map<String, Object>> 转为 CSV 字符串
     *
     * @param dataList 数据库查询出的数据
     * @return CSV 格式字符串
     */
    public static String mapToString(List<Map<String, Object>> dataList) {
        if (CollUtil.isEmpty(dataList)) {
            return "";
        }

        // 1. 获取表头 (取出第一行数据的 Key 集合)
        // 注意：LinkedHashMap 才能保证顺序，通常 MyBatis 返回的结果如果是 LinkedHashMap 则顺序一致
        Map<String, Object> firstRow = dataList.get(0);

        // 过滤掉 'id' 列 (通常分表的主键 id 不需要传给 AI)
        List<String> headerList = firstRow.keySet().stream()
                .filter(key -> !key.equals("id"))
                .collect(Collectors.toList());

        StringBuilder stringBuilder = new StringBuilder();

        // 2. 拼接表头
        stringBuilder.append(StringUtils.join(headerList, ",")).append("\n");

        // 3. 拼接每一行数据
        for (Map<String, Object> row : dataList) {
            List<String> rowData = headerList.stream().map(key -> {
                Object value = row.get(key);
                String valStr = value == null ? "" : String.valueOf(value);
                // 如果包含逗号，加上双引号防止格式错乱
                if (valStr.contains(",")) {
                    valStr = "\"" + valStr + "\"";
                }
                return valStr;
            }).collect(Collectors.toList());

            stringBuilder.append(StringUtils.join(rowData, ",")).append("\n");
        }

        return stringBuilder.toString();
    }
}