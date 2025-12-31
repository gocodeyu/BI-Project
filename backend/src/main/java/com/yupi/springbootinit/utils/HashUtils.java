package com.yupi.springbootinit.utils;

import cn.hutool.crypto.digest.DigestUtil;
import org.apache.commons.lang3.StringUtils;

/**
 * 哈希工具类
 * 用于生成数据的唯一标识
 */
public class HashUtils {
    
    /**
     * 生成 MD5 哈希值
     * 
     * @param data 待哈希的数据
     * @return MD5 哈希字符串
     */
    public static String md5(String data) {
        if (StringUtils.isBlank(data)) {
            return "";
        }
        return DigestUtil.md5Hex(data);
    }
    
    /**
     * 生成请求的唯一标识
     * 用于防重复提交
     * 
     * @param parts 组成唯一标识的各个部分
     * @return MD5 哈希字符串
     */
    public static String generateRequestId(String... parts) {
        if (parts == null || parts.length == 0) {
            return "";
        }
        
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part != null) {
                sb.append(part).append("|");
            }
        }
        
        return md5(sb.toString());
    }
}

