package com.yupi.springbootinit.service.impl;

import static com.yupi.springbootinit.constant.UserConstant.USER_LOGIN_STATE;

import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.CollectionUtils;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.google.gson.Gson;
import com.yupi.springbootinit.common.ErrorCode;
import com.yupi.springbootinit.constant.CommonConstant;
import com.yupi.springbootinit.exception.BusinessException;
import com.yupi.springbootinit.mapper.UserMapper;
import com.yupi.springbootinit.model.dto.user.UserQueryRequest;
import com.yupi.springbootinit.model.entity.User;
import com.yupi.springbootinit.model.enums.UserRoleEnum;
import com.yupi.springbootinit.model.vo.LoginUserVO;
import com.yupi.springbootinit.model.vo.UserVO;
import com.yupi.springbootinit.service.UserService;
import com.yupi.springbootinit.utils.SqlUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import me.chanjar.weixin.common.bean.WxOAuth2UserInfo;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;
import cn.hutool.core.util.IdUtil;
/**
 * 用户服务实现
 *
 * @author <a href="https://github.com/liyupi">程序员鱼皮</a>
 * @from <a href="https://yupi.icu">编程导航知识星球</a>
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {

    /**
     * 盐值，混淆密码
     */
    // 定义 Redis Key 前缀
    private static final String CAPTCHA_KEY_PREFIX = "user:captcha:";
    private static final String SALT = "yupi";
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private static final Gson GSON = new Gson();

    // 定义 Redis Key 前缀
    private static final String LOGIN_USER_KEY_PREFIX = "login:token:";
    // Token 过期时间（例如 30 分钟）
    private static final long LOGIN_USER_TTL = 30L;

    @Override
    public long userRegister(String userAccount, String userPassword, String checkPassword,String phone,String code) {
        // 1. 校验
        if (StringUtils.isAnyBlank(userAccount, userPassword, checkPassword)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "参数为空");
        }
        if (userAccount.length() < 4) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户账号过短");
        }
        if (userPassword.length() < 8 || checkPassword.length() < 8) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户密码过短");
        }
        // 密码和校验密码相同
        if (!userPassword.equals(checkPassword)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "两次输入的密码不一致");
        }
        // 2. 校验验证码 (从 Redis 获取)
        String key = CAPTCHA_KEY_PREFIX + phone;
        String cacheCode = stringRedisTemplate.opsForValue().get(key);

        if (StringUtils.isBlank(cacheCode)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "验证码已过期");
        }
        if (!cacheCode.equals(code)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "验证码错误");
        }

        // 3. 验证通过，删除 Redis 中的验证码 (防止重复使用)
        stringRedisTemplate.delete(key);
        // 4. 查询用户是否存在
        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("phone", phone);
        User user = this.baseMapper.selectOne(queryWrapper);
        if (user != null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "该用户已存在");
        }
            // 2. 加密
            String encryptPassword = DigestUtils.md5DigestAsHex((SALT + userPassword).getBytes());
            // 3. 插入数据
            User user1 = new User();
            user1.setUserAccount(userAccount);
            user1.setUserPassword(encryptPassword);
            user1.setPhone(phone);
            boolean saveResult = this.save(user1);
            if (!saveResult) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "注册失败，数据库错误");
            }
            return user1.getId();

    }
    public LoginUserVO userLogin(String userAccount, String userPassword, HttpServletRequest request) {
        // 1. 校验
        if (StringUtils.isAnyBlank(userAccount, userPassword)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "参数为空");
        }
        if (userAccount.length() < 4) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "账号错误");
        }
        if (userPassword.length() < 8) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "密码错误");
        }
        // 2. 加密
        String encryptPassword = DigestUtils.md5DigestAsHex((SALT + userPassword).getBytes());
        // 查询用户是否存在
        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("userAccount", userAccount);
        queryWrapper.eq("userPassword", encryptPassword);
        User user = this.baseMapper.selectOne(queryWrapper);
        // 用户不存在
        if (user == null) {
            log.info("user login failed, userAccount cannot match userPassword");
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户不存在或密码错误");

        }

        // 3. 【修改点】生成 Token
        String token = UUID.randomUUID().toString(true);

        // 4. 【修改点】将 User 对象存入 Redis
        // 为了安全，建议把密码置空再存
        User safetyUser = new User();
        BeanUtils.copyProperties(user, safetyUser);
        safetyUser.setUserPassword(null);

        String userJson = GSON.toJson(safetyUser);
        String key = LOGIN_USER_KEY_PREFIX + token;
        //存入redis, 设置过期时间
        stringRedisTemplate.opsForValue().set(key, userJson, LOGIN_USER_TTL, TimeUnit.MINUTES);
        // 3. 记录用户的登录态
        //request.getSession().setAttribute(USER_LOGIN_STATE, user);
        LoginUserVO loginUserVO = this.getLoginUserVO(user);
        loginUserVO.setToken(token); // 设置 Token 返回
        return loginUserVO;
    }

    /**
     * 获取当前登录用户+分布式
     *
     * @param request
     * @return
     */
    @Override
    public User getLoginUser(HttpServletRequest request) {
        // 1. 【修改点】从 Header 中获取 Token
        // 前端需约定 header key，例如 "Authorization" 或 "Token"
        String token=request.getHeader("Authorization");
        if (StringUtils.isBlank(token)) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }
        // 2. 查询 Redis
        String key = LOGIN_USER_KEY_PREFIX + token;
        String userJson = stringRedisTemplate.opsForValue().get(key);

        if (StringUtils.isBlank(userJson)) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }

        User user = GSON.fromJson(userJson, User.class);
        // 3. 【关键】续期：用户在活跃，刷新过期时间
        stringRedisTemplate.expire(key, LOGIN_USER_TTL, TimeUnit.MINUTES);
        return user;
    }

    /**
     * 获取当前登录用户（允许未登录）
     *
     * @param request
     * @return
     */
    @Override
    public User getLoginUserPermitNull(HttpServletRequest request) {
        // 先判断是否已登录
        Object userObj = request.getSession().getAttribute(USER_LOGIN_STATE);
        User currentUser = (User) userObj;
        if (currentUser == null || currentUser.getId() == null) {
            return null;
        }
        // 从数据库查询（追求性能的话可以注释，直接走缓存）
        long userId = currentUser.getId();
        return this.getById(userId);
    }

    /**
     * 是否为管理员
     *
     * @param request
     * @return
     */
    @Override
    public boolean isAdmin(HttpServletRequest request) {
        // 仅管理员可查询
        Object userObj = request.getSession().getAttribute(USER_LOGIN_STATE);
        User user = (User) userObj;
        return isAdmin(user);
    }

    @Override
    public boolean isAdmin(User user) {
        return user != null && UserRoleEnum.ADMIN.getValue().equals(user.getUserRole());
    }



    /**
     * 用户退出+分布式
     *
     * @param request
     */
    @Override
    public boolean userLogout(HttpServletRequest request) {
        // 1. 获取 Token
        String token = request.getHeader("Authorization");
        if (StringUtils.isBlank(token)) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "未登录");
        }
        // 2. 删除 Redis Key
        String key = LOGIN_USER_KEY_PREFIX + token;
        Boolean delete = stringRedisTemplate.delete(key);
        return Boolean.TRUE.equals(delete);
    }


    @Override
    public LoginUserVO getLoginUserVO(User user) {
        if (user == null) {
            return null;
        }
        LoginUserVO loginUserVO = new LoginUserVO();
        BeanUtils.copyProperties(user, loginUserVO);
        return loginUserVO;
    }

    @Override
    public UserVO getUserVO(User user) {
        if (user == null) {
            return null;
        }
        UserVO userVO = new UserVO();
        BeanUtils.copyProperties(user, userVO);
        return userVO;
    }

    @Override
    public List<UserVO> getUserVO(List<User> userList) {
        if (CollectionUtils.isEmpty(userList)) {
            return new ArrayList<>();
        }
        return userList.stream().map(this::getUserVO).collect(Collectors.toList());
    }

    @Override
    public QueryWrapper<User> getQueryWrapper(UserQueryRequest userQueryRequest) {
        if (userQueryRequest == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "请求参数为空");
        }
        Long id = userQueryRequest.getId();
        String unionId = userQueryRequest.getUnionId();
        String mpOpenId = userQueryRequest.getMpOpenId();
        String userName = userQueryRequest.getUserName();
        String userProfile = userQueryRequest.getUserProfile();
        String userRole = userQueryRequest.getUserRole();
        String sortField = userQueryRequest.getSortField();
        String sortOrder = userQueryRequest.getSortOrder();
        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq(id != null, "id", id);
        queryWrapper.eq(StringUtils.isNotBlank(unionId), "unionId", unionId);
        queryWrapper.eq(StringUtils.isNotBlank(mpOpenId), "mpOpenId", mpOpenId);
        queryWrapper.eq(StringUtils.isNotBlank(userRole), "userRole", userRole);
        queryWrapper.like(StringUtils.isNotBlank(userProfile), "userProfile", userProfile);
        queryWrapper.like(StringUtils.isNotBlank(userName), "userName", userName);
        queryWrapper.orderBy(SqlUtils.validSortField(sortField), sortOrder.equals(CommonConstant.SORT_ORDER_ASC),
                sortField);
        return queryWrapper;
    }
    @Override
    public String getCaptcha(String phone){
        // 1. 校验手机号
        if (StringUtils.isBlank(phone)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "手机号不能为空");
        }
        // 简单的手机号格式校验 (或者使用 PhoneUtil.isMobile(phone))
        if (phone.length() != 11) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "手机号格式错误");
        }
        String code = RandomUtil.randomNumbers(6);
        log.info("手机号 {} 的验证码是：{}", phone, code);
        stringRedisTemplate.opsForValue().set(CAPTCHA_KEY_PREFIX + phone, code, 2, TimeUnit.MINUTES);
        return code;
    }
    @Override
    public LoginUserVO userLoginByPhone(String phone, String code, HttpServletRequest request) {
        // 1. 基本校验
        if (StringUtils.isAnyBlank(phone, code)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "参数为空");
        }
        if (phone.length() != 11) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "手机号错误");
        }
        // 2. 校验验证码 (从 Redis 获取)
        String key = CAPTCHA_KEY_PREFIX + phone;
        String cacheCode = stringRedisTemplate.opsForValue().get(key);

        if (StringUtils.isBlank(cacheCode)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "验证码已过期");
        }
        if (!cacheCode.equals(code)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "验证码错误");
        }

        // 3. 验证通过，删除 Redis 中的验证码 (防止重复使用)
        stringRedisTemplate.delete(key);
        // 4. 查询用户是否存在
        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("phone", phone);
        User user = this.baseMapper.selectOne(queryWrapper);
        // 5. 如果用户不存在，则自动注册
        if (user == null) {
            user = new User();
            user.setPhone(phone);
            user.setUserAccount("用户" + phone.substring(7));
            user.setUserName("用户" + phone.substring(7)); // 默认昵称
            user.setUserRole("user");
            // 设置一个随机密码 (因为是验证码登录，密码可以随机生成，防止被暴力破解)
            user.setUserPassword(DigestUtils.md5DigestAsHex((SALT + RandomUtil.randomString(8)).getBytes()));
            boolean saveResult = this.save(user);
            if (!saveResult) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "注册失败");
            }
        }

        // 6. 登录成功，生成 Token (复用之前的 userLoginRedis 逻辑)
        // 生成 Token
        String token = UUID.randomUUID().toString(true);

        // 构造返回对象
        User safetyUser = new User();
        BeanUtils.copyProperties(user, safetyUser);
        safetyUser.setUserPassword(null);

        // 存入 Redis (登录态)
        String loginKey = LOGIN_USER_KEY_PREFIX + token;
        stringRedisTemplate.opsForValue().set(loginKey, GSON.toJson(safetyUser), LOGIN_USER_TTL, TimeUnit.MINUTES);

        // 返回 VO
        LoginUserVO loginUserVO = this.getLoginUserVO(user);
        loginUserVO.setToken(token);
        return loginUserVO;
    }
    @Override
    public boolean userPasswordReset(String phone, String code, String newPassword) {
        // 1. 基本校验
        if (StringUtils.isAnyBlank(phone, code, newPassword)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "参数为空");
        }
        if (phone.length() != 11) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "手机号格式错误");
        }
        if (newPassword.length() < 8) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "新密码过短");
        }

        // 2. 校验验证码 (复用 Redis 逻辑)
        String captchaKey = CAPTCHA_KEY_PREFIX + phone;
        String cacheCode = stringRedisTemplate.opsForValue().get(captchaKey);
        if (StringUtils.isBlank(cacheCode)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "验证码已过期");
        }
        if (!cacheCode.equals(code)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "验证码错误");
        }

        // 3. 验证通过，删除验证码
        stringRedisTemplate.delete(captchaKey);

        // 4. 查询用户是否存在
        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("phone", phone);
        User user = this.baseMapper.selectOne(queryWrapper);
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "该手机号未注册");
        }

        // 5. 加密并更新密码
        String encryptPassword = DigestUtils.md5DigestAsHex((SALT + newPassword).getBytes());
        user.setUserPassword(encryptPassword);

        boolean result = this.updateById(user);
        if (!result) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "重置密码失败");
        }
        return true;
    }


}
