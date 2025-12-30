package com.yupi.springbootinit.controller;

import com.yupi.springbootinit.common.ErrorCode;
import com.yupi.springbootinit.exception.BusinessException;
import com.yupi.springbootinit.model.entity.User;
import com.yupi.springbootinit.service.SseNotifyService;
import com.yupi.springbootinit.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.util.concurrent.TimeUnit;

/**
 * SSE 通知控制器
 * 用于前端建立 SSE 连接，接收任务完成通知
 *
 * @author yupi
 */
@RestController
@RequestMapping("/notify")
@Slf4j
public class SseNotifyController {

    @Resource
    private SseNotifyService sseNotifyService;

    @Resource
    private UserService userService;

    /**
     * 建立 SSE 连接
     * 前端调用：GET /api/notify/sse
     *
     * @param request HTTP 请求
     * @return SseEmitter
     */
    @GetMapping("/sse")
    public SseEmitter createSseConnection(HttpServletRequest request) {
        // 1. 获取登录用户
        User loginUser = userService.getLoginUser(request);
        if (loginUser == null) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }

        // 2. 创建 SSE 发射器（超时时间 30 分钟）
        SseEmitter emitter = new SseEmitter(TimeUnit.MINUTES.toMillis(30));

        // 3. 注册 SSE 连接
        sseNotifyService.registerSseConnection(loginUser.getId(), emitter);

        log.info("SSE 连接已建立: userId={}", loginUser.getId());
        return emitter;
    }
}

