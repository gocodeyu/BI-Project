package com.yupi.springbootinit.controller;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.io.FileUtil;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.google.gson.Gson;
import com.yupi.springbootinit.annotation.AuthCheck;
import com.yupi.springbootinit.bizmq.BiMessageProducer;
import com.yupi.springbootinit.common.BaseResponse;
import com.yupi.springbootinit.common.DeleteRequest;
import com.yupi.springbootinit.common.ErrorCode;
import com.yupi.springbootinit.common.ResultUtils;
import com.yupi.springbootinit.constant.CommonConstant;
import com.yupi.springbootinit.constant.FileConstant;
import com.yupi.springbootinit.constant.UserConstant;
import com.yupi.springbootinit.exception.BusinessException;
import com.yupi.springbootinit.exception.ThrowUtils;
import com.yupi.springbootinit.manager.AiManager;
import com.yupi.springbootinit.manager.AiPrompt;
import com.yupi.springbootinit.manager.CosManager;
import com.yupi.springbootinit.manager.RedisLimiterManager;
import com.yupi.springbootinit.mapper.ChartMapper;
import com.yupi.springbootinit.model.dto.chart.*;
import com.yupi.springbootinit.model.dto.chart.ChartDataPreviewRequest;
import com.yupi.springbootinit.model.dto.file.UploadFileRequest;
import com.yupi.springbootinit.model.entity.Chart;
import com.yupi.springbootinit.model.entity.User;
import com.yupi.springbootinit.model.enums.FileUploadBizEnum;
import com.yupi.springbootinit.model.enums.GenChartStatusEnum;
import com.yupi.springbootinit.model.vo.BiResponse;
import com.yupi.springbootinit.model.vo.ChartDataPreviewResponse;
import com.yupi.springbootinit.model.vo.ChartListVO;
import com.yupi.springbootinit.service.BiAsyncService;
import com.yupi.springbootinit.service.ChartService;
import com.yupi.springbootinit.service.ChartTransactionService;
import com.yupi.springbootinit.service.DistributedLockService;
import com.yupi.springbootinit.service.UserService;
import com.yupi.springbootinit.utils.ExcelUtils;
import com.yupi.springbootinit.utils.HashUtils;
import com.yupi.springbootinit.utils.SqlUtils;
import io.reactivex.rxjava3.core.Completable;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.StringUtils;

import org.springframework.beans.BeanUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.concurrent.ThreadPoolExecutor;
import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 帖子接口
 *
 * @author <a href="https://github.com/liyupi">程序员鱼皮</a>
 * @from <a href="https://yupi.icu">编程导航知识星球</a>
 */
@RestController
@RequestMapping("/chart")
@Slf4j
public class ChartController {
    @Resource
    private BiAsyncService biAsyncService;
    @Resource
    private ThreadPoolExecutor threadPoolExecutor;

    @Resource
    private ChartService chartService;

    @Resource
    private CosManager cosManager;

    @Resource
    private UserService userService;

    @Resource
    private AiManager aiManager;

    @Resource
    private ChartMapper chartMapper;
    @Resource
    private AiPrompt aiPrompt;
    @Resource
    private RedisLimiterManager redisLimiterManager;
    @Resource
    private BiMessageProducer biMessageProducer;

    @Resource
    private ChartTransactionService chartTransactionService;

    @Resource
    private com.yupi.springbootinit.service.cache.ChartCacheService chartCacheService;

    @Resource
    private com.yupi.springbootinit.service.cache.ChartListCacheService chartListCacheService;

    @Resource
    private com.yupi.springbootinit.service.cache.ChartDataCacheService chartDataCacheService;

    @Resource
    private DistributedLockService distributedLockService;

    private final static Gson GSON = new Gson();

    /**
     * 统一的缓存删除方法
     * 删除图表相关的所有缓存：详情、列表、数据预览
     *
     * @param chartId 图表ID
     * @param userId 用户ID
     */
    private void evictChartCache(Long chartId, Long userId) {
        if (chartId != null && chartId > 0) {
            // 删除详情缓存
            chartCacheService.evictChart(chartId);
            // 删除数据预览缓存
            chartDataCacheService.evictChartData(chartId);
        }
        if (userId != null && userId > 0) {
            // 更新列表缓存版本号（我的图表列表）
            chartListCacheService.evictMyChartList(userId);
            // 删除回收站列表缓存
            chartListCacheService.evictMyDeletedChartList(userId);
        }
    }

    /**
     * 删除
     *
     * @param deleteRequest
     * @param request
     * @return
     */
    @PostMapping("/delete")
    public BaseResponse<Boolean> deleteChart(@RequestBody DeleteRequest deleteRequest, HttpServletRequest request) {
        if (deleteRequest == null || deleteRequest.getId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        User user = userService.getLoginUser(request);
        long id = deleteRequest.getId();
        // 判断是否存在
        Chart oldChart = chartService.getById(id);
        ThrowUtils.throwIf(oldChart == null, ErrorCode.NOT_FOUND_ERROR);
        // 仅本人或管理员可删除
        if (!oldChart.getUserId().equals(user.getId()) && !userService.isAdmin(request)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        }
        boolean b = chartService.removeById(id);//使用deleteById(id)返回的是删除的行数，其中id是主键字段
        /*
        -- 你调用的是 remove/delete，但实际执行的是：
UPDATE chart SET is_delete = 1 WHERE id = 10086
         */
        // 删除缓存
        if (b) {
            evictChartCache(id, oldChart.getUserId());
        }
        return ResultUtils.success(b);
    }

    /**
     * 从数据库中彻底删除数据
     *
     * @param deleteRequest
     * @param request
     * @return
     */
    @PostMapping("/delete/forever")
    public BaseResponse<Boolean> deleteChartforever(@RequestBody DeleteRequest deleteRequest, HttpServletRequest request) {
        if (deleteRequest == null || deleteRequest.getId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        User user = userService.getLoginUser(request);
        long id = deleteRequest.getId();
        // 判断是否存在
        Chart oldChart = chartMapper.getById(id);
        ThrowUtils.throwIf(oldChart == null, ErrorCode.NOT_FOUND_ERROR);
        // 仅本人或管理员可删除
        if (!oldChart.getUserId().equals(user.getId()) && !userService.isAdmin(request)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        }
        int b = chartMapper.deleteByIdforever(id);
        ThrowUtils.throwIf(b <= 0, ErrorCode.OPERATION_ERROR);
        String tablename="chart_"+id;
        chartMapper.chartTable(tablename);
        // 删除缓存
        if (b > 0) {
            evictChartCache(id, oldChart.getUserId());
        }
        return ResultUtils.success(true);
    }

    /**
     * 更新（仅管理员）
     *
     * @param chartUpdateRequest
     * @return
     */
    @PostMapping("/update")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> updateChart(@RequestBody ChartUpdateRequest chartUpdateRequest) {
        if (chartUpdateRequest == null || chartUpdateRequest.getId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        Chart chart = new Chart();

        BeanUtils.copyProperties(chartUpdateRequest, chart);
        long id = chartUpdateRequest.getId();
        // 判断是否存在
        Chart oldChart = chartService.getById(id);
        ThrowUtils.throwIf(oldChart == null, ErrorCode.NOT_FOUND_ERROR);
        boolean result = chartService.updateById(chart);
        // 删除缓存
        if (result) {
            evictChartCache(id, oldChart.getUserId());
        }
        return ResultUtils.success(result);
    }

    @PostMapping("/recover")
    public BaseResponse<Boolean> recoverChart(@RequestBody ChartUpdateRequest chartUpdateRequest, HttpServletRequest request) {
        if (chartUpdateRequest == null || chartUpdateRequest.getId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        User loginUser = userService.getLoginUser(request);
        long id = chartUpdateRequest.getId();
        
        // 使用 chartMapper.getById 查询（自定义 XML SQL 不受逻辑删除拦截，可以查询到 isDelete=1 的数据）
        Chart oldChart = chartMapper.getById(id);
        ThrowUtils.throwIf(oldChart == null, ErrorCode.NOT_FOUND_ERROR);
        
        // 仅本人或管理员可恢复
        if (!oldChart.getUserId().equals(loginUser.getId()) && !userService.isAdmin(request)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        }
        
        // 执行恢复操作
        boolean result = chartMapper.recoverChart(id);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR, "恢复失败");

        // 删除缓存
        evictChartCache(id, oldChart.getUserId());
        
        return ResultUtils.success(true);
    }

    /**
     * 根据 id 获取（包含完整信息）
     * 使用缓存：Redis + DB（不使用 Caffeine，避免与 SSE 冲突）
     *
     * @param id
     * @param request
     * @return
     */
    @GetMapping("/get")
    public BaseResponse<Chart> getChartById(long id, HttpServletRequest request) {
        if (id <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        User loginUser = userService.getLoginUser(request);
        
        // 使用缓存服务获取（Redis + DB）
        Chart chart = chartCacheService.getChartByIdWithCache(id);
        if (chart == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR);
        }
        // 仅本人或管理员可查看
        if (!chart.getUserId().equals(loginUser.getId()) && !userService.isAdmin(loginUser)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        }
        return ResultUtils.success(chart);
    }

    /**
     * 分页获取当前用户创建的资源列表
     * 使用缓存：Caffeine → Redis → DB（仅对简单条件缓存）
     *
     * @param chartQueryRequest
     * @param request
     * @return
     */
    @PostMapping("/my/list/page")
    public BaseResponse<Page<ChartListVO>> listMyChartByPage(@RequestBody ChartQueryRequest chartQueryRequest,
                                                       HttpServletRequest request) {
        if (chartQueryRequest == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        User loginUser = userService.getLoginUser(request);
        chartQueryRequest.setUserId(loginUser.getId());
        long size = chartQueryRequest.getPageSize();
        // 限制爬虫
        ThrowUtils.throwIf(size > 20, ErrorCode.PARAMS_ERROR);
        
        // 使用缓存服务获取（Caffeine → Redis → DB）
        Page<ChartListVO> chartListVOPage = chartListCacheService.getMyChartListWithCache(chartQueryRequest);
        
        return ResultUtils.success(chartListVOPage);
    }
    /**
     * 分页获取当前用户创建的资源列表（回收站）
     * 使用缓存：Redis → DB
     *
     * @param chartQueryRequest
     * @param request
     * @return
     */
    @PostMapping("/my/delete/list/page")
    public BaseResponse<Page<Chart>> listMyChartByPagedelete(@RequestBody ChartQueryRequest chartQueryRequest,
                                                       HttpServletRequest request) {
        if (chartQueryRequest == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        User loginUser = userService.getLoginUser(request);
        chartQueryRequest.setUserId(loginUser.getId());
        long size = chartQueryRequest.getPageSize();
        // 限制爬虫
        ThrowUtils.throwIf(size > 20, ErrorCode.PARAMS_ERROR);
        
        // 使用缓存服务获取（Redis → DB）
        Page<Chart> chartPage = chartListCacheService.getMyDeletedChartListWithCache(chartQueryRequest);
        return ResultUtils.success(chartPage);
    }
    /**
     * 对于fail的状态重试
     * 使用分布式锁防止重复提交
     */
    @PostMapping("/gen/retry/rabbitmq")
    public BaseResponse<Boolean> retryChartRabbitmq(@RequestBody ChartReloadRequest reloadRequest, HttpServletRequest request) {
        if (reloadRequest == null || reloadRequest.getId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        User loginUser = userService.getLoginUser(request);
        long chartId = reloadRequest.getId();

        // 1. 校验图表是否存在
        Chart chart = chartService.getById(chartId);
        ThrowUtils.throwIf(chart == null, ErrorCode.NOT_FOUND_ERROR);
        // 2. 校验权限（仅本人或管理员可重试）
        if (!chart.getUserId().equals(loginUser.getId()) && !userService.isAdmin(loginUser)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        }
        // 3. 限流校验
        redisLimiterManager.doRateLimit("gen_chart_freq_" + loginUser.getId());
        redisLimiterManager.doDailyLimit(loginUser.getId(), loginUser.getUserRole());

        // 4. 构造分布式锁的 Key
        // 格式: chart:retry:userId:chartId
        String lockKey = "chart:retry:" + loginUser.getId() + ":" + chartId;
        log.info("尝试获取分布式锁: lockKey={}, userId={}, chartId={}", lockKey, loginUser.getId(), chartId);

        // 5. 使用分布式锁包装业务逻辑
        try {
            Boolean result = distributedLockService.executeWithLock(
                lockKey,
                0,  // waitTime: 0 秒，不等待，立即失败
                10, // leaseTime: 10 秒后自动释放锁（防止死锁）
                () -> {
                    // 在事务中更新图表状态并发送MQ消息
                    boolean isVip = "vip".equals(loginUser.getUserRole());
                    chartTransactionService.updateChartStatusAndSendMessage(chartId, isVip);
                    
                    // 删除缓存（在事务提交后执行，不影响事务）
                    evictChartCache(chartId, chart.getUserId());
                    
                    return true;
                }
            );

            return ResultUtils.success(result);

        } catch (BusinessException e) {
            // 如果是分布式锁获取失败（重复提交），需要回退限流次数
            if (e.getMessage() != null && e.getMessage().contains("重复提交")) {
                log.warn("检测到重复提交，回退限流次数: userId={}, lockKey={}", loginUser.getId(), lockKey);
                redisLimiterManager.rollbackDailyLimit(loginUser.getId(), loginUser.getUserRole());
                log.info("已成功回退限流次数: userId={}, 当前剩余次数={}",
                    loginUser.getId(),
                    redisLimiterManager.getRemainingPermits(loginUser.getId(), loginUser.getUserRole()));
            }
            throw e;
        } catch (Exception e) {
            log.error("重试任务失败", e);
            // 其他异常也回退限流次数
            redisLimiterManager.rollbackDailyLimit(loginUser.getId(), loginUser.getUserRole());
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "重试任务失败：" + e.getMessage());
        }
    }
    /**
     * 编辑（用户）
     * 使用分布式锁防止重复提交
     *
     * @param chartEditRequest
     * @param request
     * @return
     */
    @PostMapping("/edit/rabbitmq")
    public BaseResponse<BiResponse> editChartRabbitmq(@RequestBody ChartEditRequest chartEditRequest, HttpServletRequest request) {
        if (chartEditRequest == null || chartEditRequest.getId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }

        // 1. 实体转换
        Chart chart = new Chart();
        BeanUtils.copyProperties(chartEditRequest, chart);
        User loginUser = userService.getLoginUser(request);
        long id = chartEditRequest.getId();

        // 2. 校验权限
        Chart oldChart = chartService.getById(id);
        ThrowUtils.throwIf(oldChart == null, ErrorCode.NOT_FOUND_ERROR);
        if (!oldChart.getUserId().equals(loginUser.getId()) && !userService.isAdmin(loginUser)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        }

        // 3. 判断是否需要 AI 重新生成
        String newGoal = chart.getGoal();
        String oldGoal = oldChart.getGoal();
        String newChartType = chart.getChartType();
        String oldChartType = oldChart.getChartType();

        boolean needRegen = (StringUtils.isNotBlank(newGoal) && !newGoal.equals(oldGoal)) ||
                (StringUtils.isNotBlank(newChartType) && !newChartType.equals(oldChartType));

        // A. 场景：不需要 AI 重新生成，直接更新元数据
        if (!needRegen) {
            boolean result = chartService.updateById(chart);
            ThrowUtils.throwIf(!result, ErrorCode.SYSTEM_ERROR, "更新图表失败");
            // 删除缓存
            if (result) {
                evictChartCache(id, oldChart.getUserId());
            }

            BiResponse biResponse = new BiResponse();
            biResponse.setChartId(id);
            biResponse.setGenChart(oldChart.getGenChart());
            biResponse.setGenResult(oldChart.getGenResult());
            return ResultUtils.success(biResponse);
        }

        // B. 场景：需要 AI 重新生成
        String tableName = "chart_" + id;
        List<Map<String, Object>> chartDataList = chartMapper.queryChartData(tableName);
        if (CollUtil.isEmpty(chartDataList)) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "图表数据不存在");
        }

        String csvData = ExcelUtils.mapToString(chartDataList);
        if (StringUtils.isBlank(csvData)) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "原图表数据丢失，无法重新生成");
        }

        // 限流校验
        redisLimiterManager.doRateLimit("gen_chart_freq_" + loginUser.getId());
        redisLimiterManager.doDailyLimit(loginUser.getId(), loginUser.getUserRole());

        // 4. 构造分布式锁的 Key
        // 格式: chart:edit:userId:chartId:md5(goal + chartType)
        String requestId = HashUtils.generateRequestId(String.valueOf(id), newGoal, newChartType);
        String lockKey = "chart:edit:" + loginUser.getId() + ":" + requestId;
        log.info("尝试获取分布式锁: lockKey={}, userId={}, chartId={}", lockKey, loginUser.getId(), id);

        // 5. 使用分布式锁包装业务逻辑
        try {
            BiResponse biResponse = distributedLockService.executeWithLock(
                lockKey,
                0,  // waitTime: 0 秒，不等待，立即失败
                30, // leaseTime: 30 秒后自动释放锁（防止死锁，覆盖完整业务流程）
                () -> {
                    // 在事务中更新图表并发送MQ消息
                    boolean isVip = "vip".equals(loginUser.getUserRole());
                    chartTransactionService.updateChartOnlyAndSendMessage(chart, isVip);
                    
                    // 删除缓存（在事务提交后执行，不影响事务）
                    evictChartCache(id, oldChart.getUserId());

                    // 立即返回前端
                    BiResponse response = new BiResponse();
                    response.setChartId(id);
                    response.setGenResult("分析任务已提交，请稍后在\"我的图表\"查看结果");
                    return response;
                }
            );

            return ResultUtils.success(biResponse);

        } catch (BusinessException e) {
            // 如果是分布式锁获取失败（重复提交），需要回退限流次数
            if (e.getMessage() != null && e.getMessage().contains("重复提交")) {
                log.warn("检测到重复提交，回退限流次数: userId={}, lockKey={}", loginUser.getId(), lockKey);
                redisLimiterManager.rollbackDailyLimit(loginUser.getId(), loginUser.getUserRole());
                log.info("已成功回退限流次数: userId={}, 当前剩余次数={}",
                    loginUser.getId(),
                    redisLimiterManager.getRemainingPermits(loginUser.getId(), loginUser.getUserRole()));
            }
            throw e;
        } catch (Exception e) {
            log.error("编辑任务失败", e);
            // 其他异常也回退限流次数
            redisLimiterManager.rollbackDailyLimit(loginUser.getId(), loginUser.getUserRole());
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "编辑任务失败：" + e.getMessage());
        }
    }

    /**
     * 智能分析（异步+rabbitmq模式）
     * 使用分布式锁防止重复提交
     *
     */
    @PostMapping("/gen/async/rabbitmq")
    public BaseResponse<BiResponse> genChartByAiAsyncRabbitmq(@RequestPart("file") MultipartFile multipartFile,
                                                      GenChartByAiRequest genChartByAiRequest, HttpServletRequest request) throws FileNotFoundException {
        String name = genChartByAiRequest.getName();
        String goal = genChartByAiRequest.getGoal();
        String chartType = genChartByAiRequest.getChartType();
        User loginUser = userService.getLoginUser(request);

        // 1. 校验
        ThrowUtils.throwIf(StringUtils.isBlank(goal), ErrorCode.PARAMS_ERROR, "分析目标为空");
        ThrowUtils.throwIf(StringUtils.isNotBlank(name) && name.length() > 100, ErrorCode.PARAMS_ERROR, "名称过长");
        // 校验文件
        long size = multipartFile.getSize();
        String originalFilename = multipartFile.getOriginalFilename();
        // 校验文件大小 (例如 1MB)
        final long ONE_MB = 1024 * 1024L;
        ThrowUtils.throwIf(size > ONE_MB, ErrorCode.PARAMS_ERROR, "文件超过 1MB");
        // 校验文件后缀
        String suffix = FileUtil.getSuffix(originalFilename);
        final List<String> validFileSuffixList = Arrays.asList("xlsx", "xls");
        ThrowUtils.throwIf(!validFileSuffixList.contains(suffix), ErrorCode.PARAMS_ERROR, "文件后缀非法");

        //2. 先读取 Excel 为原始 List 结构
        List<Map<Integer, String>> rawDataList = ExcelUtils.readExcel(multipartFile);
        if(CollUtil.isEmpty(rawDataList)){
            // 数据为空，回退限流次数
            //redisLimiterManager.rollbackDailyLimit(loginUser.getId(), loginUser.getUserRole());
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "数据为空");
        }

        // 3.限流校验
        //全局频率限流：每个用户每秒只能请求 2 次
        redisLimiterManager.doRateLimit("gen_chart_freq_" + loginUser.getId());
        //每日额度限流：会员 50 次，非会员 3 次
        redisLimiterManager.doDailyLimit(loginUser.getId(), loginUser.getUserRole());

        
        // 4. 生成文件内容的唯一标识（用于防重复提交）
        // 读取文件字节内容
        String fileContentHash;
        try {
            byte[] fileBytes = multipartFile.getBytes();
            fileContentHash = HashUtils.md5(new String(fileBytes));
        } catch (Exception e) {
            log.error("读取文件内容失败", e);
            redisLimiterManager.rollbackDailyLimit(loginUser.getId(), loginUser.getUserRole());
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "文件读取失败");
        }
        
        // 5. 构造分布式锁的 Key
        // 格式: chart:gen:userId:md5(fileContent + goal + chartType)
        String requestId = HashUtils.generateRequestId(fileContentHash, goal, chartType);
        String lockKey = "chart:gen:" + loginUser.getId() + ":" + requestId;
        
        log.info("尝试获取分布式锁: lockKey={}, userId={}", lockKey, loginUser.getId());
        
        // 6. 使用分布式锁包装业务逻辑
        try {
            BiResponse biResponse = distributedLockService.executeWithLock(
                lockKey, 
                0,  // waitTime: 0 秒，不等待，立即失败
                30, // leaseTime: 30 秒后自动释放锁（防止死锁，覆盖完整业务流程）
                () -> {
                    // 业务逻辑开始
                    List<String> headers = ExcelUtils.getHeaders(rawDataList);
                    //数据清洗：处理表头
                    // 防止表头为空或包含特殊字符导致建表失败
                    // 如果表头为空，给一个默认名字，例如 "col_0", "col_1"
                    for (int i = 0; i < headers.size(); i++) {
                        if (StringUtils.isBlank(headers.get(i))) {
                            headers.set(i, "col_" + i);
                        } else {
                            // 简单的防注入过滤，只保留中文、字母、数字、下划线
                            // headers.set(i, headers.get(i).replaceAll("[^a-zA-Z0-9_\\u4e00-\\u9fa5]", ""));
                            // MyBatis XML 中使用了反引号包裹列名，所以空格等特殊字符其实是可以支持的，这里去重空格即可
                            headers.set(i, headers.get(i).trim());
                        }
                    }
                    List<List<Object>> dataRows = ExcelUtils.getDataList(rawDataList);
                    
                    // 在事务中创建图表、创建数据表、插入数据、发送MQ消息
                    Chart chart = new Chart();
                    chart.setName(name);
                    chart.setGoal(goal);
                    chart.setChartType(chartType);
                    chart.setUserId(loginUser.getId());
                    chart.setStatus(GenChartStatusEnum.WAIT.getValue());
                    
                    boolean isVip = "vip".equals(loginUser.getUserRole());
                    // 在事务中执行所有数据库操作和MQ消息发送
                    long chartId = chartTransactionService.createChartWithTransaction(chart, headers, dataRows, isVip);

                    // 删除列表缓存，确保新创建的图表能立即显示在列表中（在事务提交后执行，不影响事务）
                    evictChartCache(null, loginUser.getId());

                    // 立即返回给前端信息，不等AI分析结束
                    BiResponse response = new BiResponse();
                    response.setChartId(chartId);
                    response.setGenResult("分析任务已提交，请稍后在\"我的图表\"查看结果");
                    return response;
                }
            );
            
            return ResultUtils.success(biResponse);
            
        } catch (BusinessException e) {
            // 如果是分布式锁获取失败（重复提交），需要回退限流次数
            if (e.getMessage() != null && e.getMessage().contains("重复提交")) {
                log.warn("检测到重复提交，回退限流次数: userId={}, lockKey={}", loginUser.getId(), lockKey);
                // 回退每日限流次数（因为这次请求没有真正执行）
                redisLimiterManager.rollbackDailyLimit(loginUser.getId(), loginUser.getUserRole());
                log.info("已成功回退限流次数: userId={}, 当前剩余次数={}", 
                    loginUser.getId(), 
                    redisLimiterManager.getRemainingPermits(loginUser.getId(), loginUser.getUserRole()));
            }
            throw e;
        } catch (Exception e) {
            log.error("图表生成失败", e);
            // 其他异常也回退限流次数
            redisLimiterManager.rollbackDailyLimit(loginUser.getId(), loginUser.getUserRole());
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "图表生成失败：" + e.getMessage());
        }
    }

    /**
     * 获取查询包装类
     *
     * @param chartQueryRequest
     * @return
     */
    private QueryWrapper<Chart> getQueryWrapper(ChartQueryRequest chartQueryRequest) {
        QueryWrapper<Chart> queryWrapper = new QueryWrapper<>();
        if (chartQueryRequest == null) {
            return queryWrapper;
        }
        Long id = chartQueryRequest.getId();
        String name = chartQueryRequest.getName();
        String goal = chartQueryRequest.getGoal();
        String chartType = chartQueryRequest.getChartType();
        Long userId = chartQueryRequest.getUserId();
        String sortField = chartQueryRequest.getSortField();
        String sortOrder = chartQueryRequest.getSortOrder();

        queryWrapper.eq(id != null && id > 0, "id", id);
        queryWrapper.like(StringUtils.isNotBlank(name), "name", name);
        queryWrapper.eq(StringUtils.isNotBlank(goal), "goal", goal);
        queryWrapper.eq(StringUtils.isNotBlank(chartType), "chartType", chartType);
        queryWrapper.eq(ObjectUtils.isNotEmpty(userId), "userId", userId);
        queryWrapper.eq("isDelete", false);
        // [修改] 设置默认排序：如果没有指定排序字段，则默认按 updateTime 倒序
        // 这样可以保证最新修改或创建的图表排在最前面
        if (StringUtils.isBlank(sortField)) {
            sortField = "updateTime";
            sortOrder = CommonConstant.SORT_ORDER_DESC;
        }
        queryWrapper.orderBy(SqlUtils.validSortField(sortField), sortOrder.equals(CommonConstant.SORT_ORDER_ASC),
                sortField);
        return queryWrapper;
    }

    /**
     * 辅助方法：处理失败状态
     */
    private void handleChartUpdateError(long chartId, String execMessage) {
        Chart updateChart = new Chart();
        updateChart.setId(chartId);
        updateChart.setStatus(GenChartStatusEnum.FAILED.getValue());
        updateChart.setExecMessage(execMessage);
        chartService.updateById(updateChart);
    }


    /**
     * 预览图表数据
     * 使用缓存：Caffeine → Redis → DB（仅缓存前 3 页和常用 pageSize）
     *
     * @param chartDataPreviewRequest
     * @param request
     * @return
     */
    @GetMapping("/data/preview")
    public BaseResponse<ChartDataPreviewResponse> previewChartData(
            ChartDataPreviewRequest chartDataPreviewRequest, HttpServletRequest request) {
        // 1. 参数校验
        if (chartDataPreviewRequest == null || chartDataPreviewRequest.getChartId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        
        // 2. 权限校验
        User loginUser = userService.getLoginUser(request);
        long chartId = chartDataPreviewRequest.getChartId();
        Chart chart = chartMapper.getById(chartId);
        ThrowUtils.throwIf(chart == null, ErrorCode.NOT_FOUND_ERROR);
        
        // 仅本人或管理员可预览
        if (!chart.getUserId().equals(loginUser.getId()) && !userService.isAdmin(loginUser)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        }
        
        // 3. 使用缓存服务获取数据（Caffeine → Redis → DB）
        long current = chartDataPreviewRequest.getCurrent();
        long pageSize = chartDataPreviewRequest.getPageSize();
        ChartDataPreviewResponse response = chartDataCacheService.getChartDataPreviewWithCache(chartId, current, pageSize);
        
        return ResultUtils.success(response);
    }
}


