package com.yupi.springbootinit.model.enums;

import org.springframework.stereotype.Component;


public enum GenChartStatusEnum {
    WAIT("等待中", "wait"),
    RUNNING("执行中", "running"),
    SUCCEED("成功", "succeed"),
    FAILED("失败", "failed");

    private final String text;
    private final String value;

    GenChartStatusEnum(String text, String value) {
        this.text = text;
        this.value = value;
    }
    public String getText() {
        return text;
    }
    public String getValue() {
        return value;
    }
}
