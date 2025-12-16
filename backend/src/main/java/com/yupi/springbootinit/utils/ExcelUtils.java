package com.yupi.springbootinit.utils;

import cn.hutool.core.collection.CollUtil;
import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.support.ExcelTypeEnum;
import org.springframework.util.ResourceUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;
import java.util.Map;

public class ExcelUtils {
    public static String excelTocsv(MultipartFile multipartFile) throws FileNotFoundException {
        //读取文件
        //File file = ResourceUtils.getFile("classpath:test_excel.xlsx");

        List<Map<Integer, String>> list = null;
        try {
            list = EasyExcel.read(multipartFile.getInputStream())
                    .excelType(ExcelTypeEnum.XLSX)
                    .sheet()
                    .headRowNumber(0)
                    .doReadSync();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        //读取表头
        if(CollUtil.isEmpty( list))
            return "";
        //读取表头
        String head = list.get(0).values().stream().reduce((x,y)->x+","+y).get();
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append(head).append("\n");
        //System.out.println(head);
        for (int i = 1; i < list.size(); i++) {
            String body = list.get(i).values().stream().reduce((x,y)->x+","+y).get();
            stringBuilder.append(body).append("\n");
          //  System.out.println(body);
        }
        return stringBuilder.toString();
    }
    public static void main(String[] args) throws FileNotFoundException {
        try {
            excelTocsv(null);
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

}
