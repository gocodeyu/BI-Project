package com.yupi.springbootinit.model.dto.user;

import lombok.Data;

import java.io.Serializable;
@Data
public class UserPasswordResetRequest implements Serializable {
    private static final long serialVersionUID = 3191241716373120793L;
    private String phone;
    /**
     * 验证码
     */
    private String code;

    /**
     * 新密码
     */
    private String newPassword;
}
