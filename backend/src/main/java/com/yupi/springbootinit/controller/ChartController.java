package com.yupi.springbootinit.controller;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.io.FileUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.google.gson.Gson;
import com.yupi.springbootinit.annotation.AuthCheck;
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
import com.yupi.springbootinit.model.dto.file.UploadFileRequest;
import com.yupi.springbootinit.model.entity.Chart;
import com.yupi.springbootinit.model.entity.User;
import com.yupi.springbootinit.model.enums.FileUploadBizEnum;
import com.yupi.springbootinit.model.enums.GenChartStatusEnum;
import com.yupi.springbootinit.model.vo.BiResponse;
import com.yupi.springbootinit.service.BiAsyncService;
import com.yupi.springbootinit.service.ChartService;
import com.yupi.springbootinit.service.UserService;
import com.yupi.springbootinit.utils.ExcelUtils;
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



    private final static Gson GSON = new Gson();

    // region 增删改查

    /**
     * 创建
     *
     * @param chartAddRequest
     * @param request
     * @return
     */
    @PostMapping("/add")
    public BaseResponse<Long> addChart(@RequestBody ChartAddRequest chartAddRequest, HttpServletRequest request) {
        if (chartAddRequest == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        Chart chart = new Chart();
        BeanUtils.copyProperties(chartAddRequest, chart);
        User loginUser = userService.getLoginUser(request);
        chart.setUserId(loginUser.getId());
        boolean result = chartService.save(chart);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
        long newChartId = chart.getId();
        return ResultUtils.success(newChartId);
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
        boolean b = chartService.removeById(id);
        return ResultUtils.success(b);
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
        return ResultUtils.success(result);
    }

    /**
     * 根据 id 获取
     *
     * @param id
     * @return
     */
    @GetMapping("/get")
    public BaseResponse<Chart> getChartById(long id, HttpServletRequest request) {
        if (id <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        Chart chart = chartService.getById(id);
        if (chart == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR);
        }
        return ResultUtils.success(chart);
    }

    /**
     * 分页获取列表（封装类）
     *
     * @param chartQueryRequest
     * @param request
     * @return
     */
    @PostMapping("/list/page")
    public BaseResponse<Page<Chart>> listChartByPage(@RequestBody ChartQueryRequest chartQueryRequest,
                                                     HttpServletRequest request) {
        long current = chartQueryRequest.getCurrent();
        long size = chartQueryRequest.getPageSize();
        // 限制爬虫
        ThrowUtils.throwIf(size > 20, ErrorCode.PARAMS_ERROR);
        Page<Chart> chartPage = chartService.page(new Page<>(current, size),
                getQueryWrapper(chartQueryRequest));
        return ResultUtils.success(chartPage);
    }

    /**
     * 分页获取当前用户创建的资源列表
     *
     * @param chartQueryRequest
     * @param request
     * @return
     */
    @PostMapping("/my/list/page")
    public BaseResponse<Page<Chart>> listMyChartByPage(@RequestBody ChartQueryRequest chartQueryRequest,
                                                       HttpServletRequest request) {
        if (chartQueryRequest == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        User loginUser = userService.getLoginUser(request);
        chartQueryRequest.setUserId(loginUser.getId());
        long current = chartQueryRequest.getCurrent();
        long size = chartQueryRequest.getPageSize();
        // 限制爬虫
        ThrowUtils.throwIf(size > 20, ErrorCode.PARAMS_ERROR);
        Page<Chart> chartPage = chartService.page(new Page<>(current, size),
                getQueryWrapper(chartQueryRequest));
        return ResultUtils.success(chartPage);
    }

    /**
     * 对于fail的状态重试
     */
    @PostMapping("/gen/retry")
    public BaseResponse<Boolean> retryChart(@RequestBody ChartReloadRequest reloadRequest, HttpServletRequest request) {
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

        //4. 更新表格状态
        Chart updateChart = new Chart();
        updateChart.setId(chartId);
        updateChart.setStatus(GenChartStatusEnum.WAIT.getValue());
        updateChart.setExecMessage("");
        boolean update = chartService.updateById(updateChart);
        if(!update){
            log.error("更新图表状态失败, chartId: {}", chartId);
            throw new BusinessException(ErrorCode.OPERATION_ERROR);
        }
        //5. 异步提交任务
        try{
            CompletableFuture.runAsync(() -> {
                biAsyncService.executeGenChart(chartId);
            }, threadPoolExecutor);
        }catch (Exception e){
            log.error("提交任务失败, chartId: {}", chartId, e);
            handleChartUpdateError(chartId, "系统繁忙，重试提交失败");
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "系统繁忙，请稍后再试");
        }

        return ResultUtils.success(true);
    }

    /**
     * 编辑（用户）
     *
     * @param chartEditRequest
     * @param request
     * @return
     */
    @PostMapping("/edit")
    public BaseResponse<BiResponse> editChart(@RequestBody ChartEditRequest chartEditRequest, HttpServletRequest request) {
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

        // 4. 更新数据库状态为 WAIT (主线程)
        // 这里的 chart 包含了用户修改的新 name, goal, type
        chart.setStatus(GenChartStatusEnum.WAIT.getValue());
        boolean saveResult = chartService.updateById(chart);
        ThrowUtils.throwIf(!saveResult, ErrorCode.SYSTEM_ERROR, "更新图表状态失败");

        // 5. 开启异步任务
        try {
            CompletableFuture.runAsync(() -> {
                /*
                biAsyncService.executeGenChart(id) 本身只是一个普通的 Java 方法调用。

外层的 CompletableFuture.runAsync 是为了把这个耗时的方法调用从 Tomcat 的 HTTP 响应线程中剥离出来，
扔到后台去跑，从而实现**“接口立即响应，任务后台处理”**的效果。
                 */
                biAsyncService.executeGenChart(id);
            }, threadPoolExecutor);

        } catch (Exception e) {
            log.error("图表生成异步任务提交失败, chartId: {}", id, e);
            handleChartUpdateError(id, "系统繁忙，任务提交失败");
        }

        // 6. 立即返回前端
        BiResponse biResponse = new BiResponse();
        biResponse.setChartId(id);
        biResponse.setGenResult("分析任务已提交，请稍后在“我的图表”查看结果");
        return ResultUtils.success(biResponse);
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
     * 智能分析（异步模式）
     *
     */
    @PostMapping("/gen/async")
    public BaseResponse<BiResponse> genChartByAiAsync(@RequestPart("file") MultipartFile multipartFile,
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

        // 2.限流校验
        //全局频率限流：每个用户每秒只能请求 2 次
        redisLimiterManager.doRateLimit("gen_chart_freq_" + loginUser.getId());
        //每日额度限流：会员 50 次，非会员 3 次
        redisLimiterManager.doDailyLimit(loginUser.getId(), loginUser.getUserRole());

        //3. 先读取 Excel 为原始 List 结构
        List<Map<Integer, String>> rawDataList = ExcelUtils.readExcel(multipartFile);
        if(CollUtil.isEmpty(rawDataList)){
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "数据为空");
        }

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
        //3、保存到数据库
        Chart chart = new Chart();
        chart.setName(name);
        chart.setGoal(goal);
        chart.setChartData("数据存储在分表：chart_" + System.currentTimeMillis());
        chart.setChartType(chartType);
        chart.setUserId(loginUser.getId());
        chart.setStatus(GenChartStatusEnum.WAIT.getValue());
        boolean saveResult = chartService.save(chart);
        if(!saveResult){
            log.error("保存图表失败");
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "保存图表失败");

        }

        long chartId = chart.getId();
        String tableName = "chart_" + chartId;
        chartMapper.createChartTable(tableName, headers);
        if (CollUtil.isNotEmpty(dataRows)) {
            int batchSize = 1000;
            for (int i = 0; i < dataRows.size(); i += batchSize) {
                int end = Math.min(i + batchSize, dataRows.size());
                chartMapper.insertChartData(tableName, headers, dataRows.subList(i, end));
            }
        }

        // 4. 请求任务保存到线程池中
        try {
            CompletableFuture.runAsync(() -> {
               biAsyncService.executeGenChart(chartId);
            }, threadPoolExecutor);
        } catch (Exception e) {
            // 如果提交任务到线程池就失败了（例如队列满了 RejectedExecutionException）
            log.error("提交分析任务失败，队列已满: {}", e.getMessage());
            // 优化点2配套：标记为"系统繁忙"，方便定时任务捞起来重试
            handleChartUpdateError(chartId, "系统繁忙，请稍后再试（任务已加入重试队列）");
        }
        //5、立即返回给前端信息，不等AI分析结束
        BiResponse biResponse = new BiResponse();
        biResponse.setChartId(chartId);
        biResponse.setGenResult("分析任务已提交，请稍后在“我的图表”查看结果");
        return ResultUtils.success(biResponse);

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
         * 智能分析（同步模式）
         *
         * @param multipartFile
         * @param genChartByAiRequest
         * @param request
         * @return
         */
        @PostMapping("/gen")
        public BaseResponse<BiResponse> genChartByAi(@RequestPart("file") MultipartFile multipartFile,
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

            // 2.限流校验
            //全局频率限流：每个用户每秒只能请求 2 次
            redisLimiterManager.doRateLimit("gen_chart_freq_" + loginUser.getId());
            //每日额度限流：会员 50 次，非会员 3 次
            redisLimiterManager.doDailyLimit(loginUser.getId(), loginUser.getUserRole());

            //3. 先读取 Excel 为原始 List 结构
            List<Map<Integer, String>> rawDataList = ExcelUtils.readExcel(multipartFile);
            if(CollUtil.isEmpty(rawDataList)){
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "数据为空");
            }

            // 转换为 CSV 字符串（给 AI 用）
            String csvData = ExcelUtils.convertListToCsv(rawDataList);

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



            // 3. 调用 AI
            // 使用新的 doChat 方法，传入 system prompt 和 user message
            String result = aiPrompt.func(goal,chartType,csvData);

            // 4. 解析结果
            String[] splits = result.split("【【【【【");
            if (splits.length < 3) {
                log.error("AI 生成格式错误，返回内容：{}", result);
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "AI 生成格式异常，请稍后重试");
            }

            String genChart = splits[1].trim();
            String genResult = splits[2].trim();

            // 清理一下可能的 Markdown 代码块标记 (兼容性处理)
            genChart = genChart.replace("```json", "").replace("```", "").trim();

            // 5. 保存到数据库
            Chart chart = new Chart();
            chart.setName(name);
            chart.setGoal(goal);
            chart.setChartData("数据存储在分表：chart_" + System.currentTimeMillis());
            chart.setChartType(chartType);
            chart.setGenChart(genChart);
            chart.setGenResult(genResult);
            chart.setUserId(loginUser.getId());
            boolean saveResult = chartService.save(chart);
            ThrowUtils.throwIf(!saveResult, ErrorCode.SYSTEM_ERROR, "图表保存失败");

            //6. 动态创建chart_{id}表
            Long chartId = chart.getId();
            String tableName = "chart_" + chartId;
            try {
                // 6.1 创建表：create table chart_1 (id..., date..., num...)
                chartMapper.createChartTable(tableName, headers);

                // 6.2 插入数据：分批插入，防止 SQL 过长报错
                if (CollUtil.isNotEmpty(dataRows)) {
                    int batchSize = 1000; // 每批插入 1000 条
                    int totalRows = dataRows.size();
                    for (int i = 0; i < totalRows; i += batchSize) {
                        int end = Math.min(i + batchSize, totalRows);
                        List<List<Object>> subList = dataRows.subList(i, end);
                        // 执行插入
                        chartMapper.insertChartData(tableName, headers, subList);
                    }
                }
            } catch (Exception e) {
                log.error("分表创建或数据插入失败，chartId: {}", chartId, e);
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "数据存储失败，请检查表头格式");
            }

            // 7. 返回结果
            BiResponse biResponse = new BiResponse();
            biResponse.setGenChart(genChart);
            biResponse.setGenResult(genResult);
            biResponse.setChartId(chart.getId());
            return ResultUtils.success(biResponse);
        }
    }


