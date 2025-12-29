package com.yupi.springbootinit.model.dto.user;

import lombok.Data;

import java.io.Serializable;

@Data
public class UserLoginByPhoneRequest implements Serializable {
    private static final long serialVersionUID = 3191241716373120793L;
    private String phone;
    private String code;
}
