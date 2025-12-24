import {
  deleteChartUsingPost,
  genChartByAiUsingPost,
  genChartByAiAsyncUsingPost,
  listMyChartByPageUsingPost,
  editChartUsingPost,
  getChartByIdUsingGet,
  retryChartUsingPost
} from '@/services/bi/chartController';
import { 
  userLogoutUsingPost, 
  updateMyUserUsingPost, 
  getLoginUserUsingGet,
  getUserByIdUsingGet,
  exchangeVipUsingPost 
} from '@/services/bi/userController';
import { useModel } from '@@/exports';
import {
  BarChartOutlined,
  CheckOutlined,
  CopyOutlined,
  DeleteOutlined,
  DownloadOutlined,
  EditOutlined,
  FileTextOutlined,
  LeftOutlined,
  LineChartOutlined,
  LogoutOutlined,
  PieChartOutlined,
  PlusOutlined,
  RadarChartOutlined,
  RightOutlined,
  SettingOutlined,
  UserOutlined,
  IdcardOutlined,
  LoadingOutlined,
  DotChartOutlined,
  HeatMapOutlined,
  FundOutlined,
  SlidersOutlined,
  BoxPlotOutlined,
  ApartmentOutlined,
  FunnelPlotOutlined,
  MenuFoldOutlined,
  MenuUnfoldOutlined,
  HistoryOutlined,
  CrownOutlined,
  PayCircleOutlined,
  ClockCircleOutlined,
  CloseCircleOutlined,
  ReloadOutlined,
  QuestionCircleOutlined 
} from '@ant-design/icons';
import {
  Avatar,
  Button,
  Card,
  Col,
  Collapse,
  Descriptions,
  Dropdown,
  Empty,
  Form,
  Input,
  Layout,
  List,
  MenuProps,
  message,
  Modal,
  Popconfirm,
  Row,
  Select,
  Skeleton,
  Space,
  Spin,
  Tabs,
  Tag,
  theme,
  Tooltip,
  Typography,
  Upload,
  Result
} from 'antd';
import Search from 'antd/es/input/Search';
import ReactECharts from 'echarts-for-react';
import React, { useEffect, useRef, useState } from 'react';
import ReactMarkdown from 'react-markdown';
import copy from 'copy-to-clipboard';
import { history } from '@umijs/max';
import { RcFile } from 'antd/es/upload';

const { Sider, Content, Header } = Layout;
const { Text, Title, Paragraph } = Typography;

// --- 1. 扩展的图表类型配置 ---
const CHART_TYPE_MAP: Record<string, { color: string; icon: React.ReactNode }> = {
  '不指定': { color: 'default', icon: <QuestionCircleOutlined /> },
  '折线图': { color: 'blue', icon: <LineChartOutlined /> },
  '柱状图': { color: 'cyan', icon: <BarChartOutlined /> },
  '饼图': { color: 'orange', icon: <PieChartOutlined /> },
  '雷达图': { color: 'purple', icon: <RadarChartOutlined /> },
  '散点图': { color: 'magenta', icon: <DotChartOutlined /> },
  '热力图': { color: 'volcano', icon: <HeatMapOutlined /> },
  '漏斗图': { color: 'gold', icon: <FunnelPlotOutlined /> },
  '仪表盘': { color: 'geekblue', icon: <SlidersOutlined /> },
  'K线图':  { color: 'red', icon: <FundOutlined /> },
  '箱线图': { color: 'lime', icon: <BoxPlotOutlined /> },
  '树图':   { color: 'green', icon: <ApartmentOutlined /> },
  '默认':   { color: 'default', icon: <FileTextOutlined /> },
};

// --- 2. 动态生成下拉选项 ---
const chartTypeOptions = Object.keys(CHART_TYPE_MAP)
  .filter(key => key !== '默认')
  .map(key => ({
    value: key,
    label: (
      <Space>
        {CHART_TYPE_MAP[key].icon}
        <span>{key}</span>
      </Space>
    )
  }));

const beforeUpload = (file: RcFile) => {
  const isJpgOrPng = file.type === 'image/jpeg' || file.type === 'image/png';
  if (!isJpgOrPng) {
    message.error('请上传 JPG/PNG 格式的图片!');
  }
  const isLt1M = file.size / 1024 / 1024 < 1;
  if (!isLt1M) {
    message.error('图片大小不能超过 1MB!');
  }
  return isJpgOrPng && isLt1M;
};

// --- 核心修复函数：增强和修正 ECharts 配置 ---
const fixChartOption = (optionStr: string) => {
  let option: any = {};
  try {
    option = JSON.parse(optionStr);
  } catch (e) {
    return {};
  }

  if (!option.grid) {
    option.grid = { containLabel: true, bottom: '12%', left: '5%', right: '5%' };
  } else {
    option.grid.containLabel = true;
    if (!option.grid.bottom) option.grid.bottom = '12%'; 
  }

  if (option.legend) {
    option.legend.type = 'scroll';    
    option.legend.bottom = '0';       
    option.legend.left = 'center';    
    option.legend.top = undefined;    
    option.legend.orient = 'horizontal'; 
  }

  if (option.xAxis) {
     const axes = Array.isArray(option.xAxis) ? option.xAxis : [option.xAxis];
     axes.forEach((axis: any) => {
        if (!axis.axisLabel) axis.axisLabel = {};
        axis.axisLabel.formatter = function (value: string) {
          if (value && value.length > 8) {
             return value.substring(0, 8) + "...";
          }
          return value;
        };
     });
  }

  if (!option.dataZoom) {
    option.dataZoom = [
      {
        type: 'slider',
        show: true,
        xAxisIndex: [0],
        start: 0,
        end: 100,
        height: 20,
        bottom: 30 
      },
      {
        type: 'inside', 
        xAxisIndex: [0],
        start: 0,
        end: 100
      }
    ];
    option.grid.bottom = '15%'; 
  }

  if (!option.tooltip) {
      option.tooltip = { trigger: 'axis' };
  }

  return option;
};

const AddChart: React.FC = () => {
  const { initialState, setInitialState } = useModel('@@initialState');
  const { currentUser } = initialState ?? {};
  const { token } = theme.useToken();
  const [form] = Form.useForm();
  const [userForm] = Form.useForm();
  const chartRef = useRef<any>(null);

  // --- 状态定义 ---
  const [chartList, setChartList] = useState<API.Chart[]>([]);
  const [listLoading, setListLoading] = useState<boolean>(true);
  
  // [修改] 默认按 updateTime 倒序排序，保证新建/修改的在最前
  const [searchParams, setSearchParams] = useState<API.ChartQueryRequest>({
    current: 1,
    pageSize: 10,
    sortField: 'updateTime', // <--- 改为 updateTime
    sortOrder: 'desc',       // <--- 保持 desc
    name: '',
    chartType: ''
  });

  const [selectedChart, setSelectedChart] = useState<API.Chart | undefined>(undefined);
  const [option, setOption] = useState<any>();
  const [submitting, setSubmitting] = useState<boolean>(false);
  const [collapsed, setCollapsed] = useState(false);

  // 用户弹窗相关状态
  const [userModalOpen, setUserModalOpen] = useState(false);
  const [activeUserTab, setActiveUserTab] = useState<string>('info');
  const [userUpdating, setUserUpdating] = useState(false);
  
  const [avatarLoading, setAvatarLoading] = useState(false);
  const [avatarUrl, setAvatarUrl] = useState<string>();

  // 编辑模态框状态
  const [editModalOpen, setEditModalOpen] = useState(false);
  const [editingChart, setEditingChart] = useState<API.Chart>();
  const [editForm] = Form.useForm();
  const [editLoading, setEditLoading] = useState(false);

  // VIP 弹窗相关状态
  const [vipModalOpen, setVipModalOpen] = useState(false);
  const [vipCode, setVipCode] = useState('');
  const [vipLoading, setVipLoading] = useState(false);

  const normFile = (e: any) => {
    if (Array.isArray(e)) {
      return e;
    }
    return e?.fileList;
  };

  // 获取用户信息
  const fetchUserInfo = async () => {
    try {
      const loginRes = await getLoginUserUsingGet();
      if (loginRes.data) {
        const loginUser = loginRes.data;
        setInitialState((s) => ({ ...s, currentUser: loginUser }));
      }
    } catch (e) {
      console.error("获取用户信息失败", e);
    }
  };

  useEffect(() => {
    // @ts-ignore
    if (!currentUser || !currentUser.userAccount) {
      fetchUserInfo();
    }
  }, []);

  // --- 加载数据函数 (支持静默刷新) ---
  const loadData = async (isSilent = false) => {
    // 如果不是静默刷新，则显示 Loading 圈
    if (!isSilent) {
      setListLoading(true);
    }
    try {
      const res = await listMyChartByPageUsingPost(searchParams);
      if (res.data) {
        setChartList(res.data.records ?? []);
        
        // 如果当前选中的图表正在生成中，且列表刷新后状态变为了成功，自动更新右侧展示
        if (selectedChart && res.data.records) {
           const currentItem = res.data.records.find(item => item.id === selectedChart.id);
           if (currentItem && currentItem.status === 'succeed' && selectedChart.status !== 'succeed') {
              // 自动刷新右侧
              try {
                const opt = fixChartOption(currentItem.genChart ?? '{}');
                if (!opt.title) opt.title = { text: currentItem.name };
                setOption(opt);
                setSelectedChart(currentItem);
                message.success('图表生成完毕');
              } catch (e) { }
           }
        }
      }
    } catch (e: any) {
      message.error('获取列表失败：' + e.message);
    }
    // 只有非静默加载才关闭 Loading，避免轮询时闪烁
    if (!isSilent) {
      setListLoading(false);
    }
  };

  // --- 初始加载 ---
  useEffect(() => {
    loadData();
  }, [searchParams]);

  // --- 自动轮询机制 ---
  // 每 3 秒检查一次列表，如果有 'wait' 或 'running' 的任务，就静默刷新
  useEffect(() => {
    const timer = setInterval(() => {
      // 检查列表中是否有正在进行的任务
      const hasPendingTask = chartList.some(
        item => item.status === 'wait' || item.status === 'running'
      );
      if (hasPendingTask) {
        loadData(true); // 开启静默刷新
      }
    }, 3000); // 3秒轮询一次

    return () => clearInterval(timer);
  }, [chartList, searchParams]); // 依赖 chartList 变化来决定是否继续轮询

  useEffect(() => {
    if (userModalOpen && currentUser) {
      userForm.setFieldsValue({
        userAccount: currentUser.userAccount,
        userAvatar: currentUser.userAvatar,
        userProfile: currentUser.userProfile,
      });
      setAvatarUrl(currentUser.userAvatar); 
    }
  }, [userModalOpen, currentUser]);

  const handleExchangeVip = async () => {
    if (!vipCode) {
        message.error('请输入兑换码');
        return;
    }
    setVipLoading(true);
    try {
        const res = await exchangeVipUsingPost({ vipCode: vipCode });
        if (res.data) {
            message.success('恭喜您成功升级为 VIP 会员！');
            setVipModalOpen(false);
            setVipCode('');
            setInitialState((s) => ({
                ...s,
                currentUser: {
                    ...s?.currentUser,
                    userRole: 'vip',
                    leftNum: 50 
                }
            }));
            fetchUserInfo();
        } else {
            message.error('兑换失败');
        }
    } catch (e: any) {
        message.error('兑换失败：' + e.message);
    } finally {
        setVipLoading(false);
    }
  };

  const openEditModal = (chart: API.Chart, e: React.MouseEvent) => {
    e.stopPropagation();
    setEditingChart(chart);
    editForm.setFieldsValue({
      name: chart.name,
      goal: chart.goal,
      chartType: chart.chartType
    });
    setEditModalOpen(true);
  };

  const handleEditSubmit = async (values: any) => {
    if (!editingChart?.id) return;
    setEditLoading(true);
    try {
      const res = await editChartUsingPost({
        id: editingChart.id,
        ...values
      });
      
      if (res.data) {
        const successMsg = res.data.genResult || '更新已提交，系统处理中...';
        message.success(successMsg);
        
        setEditModalOpen(false);
        
        // [修改] 更新成功后，重置回第一页，确保用户能看到置顶的修改项
        setSearchParams({ ...searchParams, current: 1 });
        
        fetchUserInfo();
        
        if (selectedChart?.id === editingChart.id) {
           setSelectedChart(undefined);
           setOption(undefined);
        }
      } else {
        message.error('更新失败');
      }
    } catch (e: any) {
      message.error('更新失败：' + e.message);
    } finally {
      setEditLoading(false);
    }
  };

  const handleRetry = async (chartId: number, e: React.MouseEvent) => {
    e.stopPropagation(); 
    try {
      const res = await retryChartUsingPost({ id: chartId });
      if (res.data) {
        message.success('已重新加入生成队列');
        loadData();      
        fetchUserInfo(); 
      } else {
        message.error('重试提交失败');
      }
    } catch (e: any) {
      message.error('重试失败：' + e.message);
    }
  };

  const handleLogout = async () => {
    try {
      await userLogoutUsingPost();
      await setInitialState((s) => ({ ...s, currentUser: undefined }));
      message.success('已退出登录');
      history.replace('/user/login');
    } catch (error) {
      message.error('退出失败');
    }
  };

  const handleUpdateUser = async (values: any) => {
    setUserUpdating(true);
    try {
      const res = await updateMyUserUsingPost(values);
      if (res.data) {
        message.success('用户信息更新成功');
        setInitialState((s) => ({
          ...s,
          currentUser: {
            ...s?.currentUser,
            ...values,
          },
        }));
        setUserModalOpen(false);
      } else {
        message.error('更新失败');
      }
    } catch (e: any) {
      message.error('更新失败：' + e.message);
    } finally {
      setUserUpdating(false);
    }
  };

  const handleUploadAvatar = async (options: any) => {
    const { file, onSuccess, onError } = options;
    setAvatarLoading(true);

    try {
      const mockUrl = URL.createObjectURL(file);
      setTimeout(() => {
        setAvatarUrl(mockUrl);
        userForm.setFieldValue('userAvatar', mockUrl); 
        message.success('头像上传成功 (本地模拟)');
        setAvatarLoading(false);
        onSuccess?.(mockUrl);
      }, 1000);
    } catch (e: any) {
      onError?.(e);
      message.error('上传失败');
      setAvatarLoading(false);
    }
  };

  const userMenuItems: MenuProps['items'] = [
    {
      key: 'center',
      icon: <IdcardOutlined />,
      label: '个人中心',
      onClick: () => {
        setActiveUserTab('info');
        setUserModalOpen(true);
      },
    },
    {
      key: 'settings',
      icon: <SettingOutlined />,
      label: '个人设置',
      onClick: () => {
        setActiveUserTab('settings');
        setUserModalOpen(true);
      },
    },
    {
      type: 'divider',
    },
    {
      key: 'logout',
      icon: <LogoutOutlined />,
      label: '退出登录',
      onClick: handleLogout,
      danger: true,
    },
  ];

  const handleDelete = async (chartId: number, e: React.MouseEvent) => {
    e.stopPropagation();
    try {
      const res = await deleteChartUsingPost({ id: chartId });
      if (res.data) {
        message.success('删除成功');
        if (selectedChart?.id === chartId) {
          setSelectedChart(undefined);
          setOption(undefined);
        }
        loadData();
      } else {
        message.error('删除失败');
      }
    } catch (e: any) {
      message.error('删除失败：' + e.message);
    }
  };

  const onFinish = async (values: any) => {
    if (submitting) return;
    setSubmitting(true);
    setOption(undefined);
    setSelectedChart(undefined);

    const params = {
      name: values.name,
      goal: values.goal,
      chartType: values.chartType,
    };
    const fileObj = values.file?.[0]?.originFileObj;

    try {
      const res = await genChartByAiAsyncUsingPost(params, {}, fileObj);
      if (!res?.data) {
        message.error('分析失败');
      } else {
        message.success('分析任务已提交，系统正在处理中...');
        form.resetFields();
        // [修改] 提交成功后，重置回第一页，显示最新创建的图表
        setSearchParams({ ...searchParams, current: 1 });
        fetchUserInfo();
      }
    } catch (e: any) {
      message.error('分析失败：' + e.message);
    } finally {
      setSubmitting(false);
    }
  };

  const handleDownloadChart = () => {
    if (!chartRef.current) return;
    const echartsInstance = chartRef.current.getEchartsInstance();
    const base64 = echartsInstance.getDataURL({
      type: 'png',
      pixelRatio: 2,
      backgroundColor: '#fff',
    });
    const link = document.createElement('a');
    link.href = base64;
    link.download = `${selectedChart?.name || 'chart'}.png`;
    link.click();
  };

  const handleRegenerate = () => {
    if (!selectedChart) return;
    setSelectedChart(undefined);
    setOption(undefined);
    form.setFieldsValue({
      name: selectedChart.name,
      goal: selectedChart.goal,
      chartType: selectedChart.chartType,
    });
    message.info('已将历史信息回填，请重新上传文件进行调整');
  };

  const renderHighlightedText = (text: string, highlight: string) => {
    if (!highlight) return text;
    const parts = text.split(new RegExp(`(${highlight})`, 'gi'));
    return (
      <span>
        {parts.map((part, i) => 
          part.toLowerCase() === highlight.toLowerCase() ? (
            <span key={i} style={{ color: token.colorPrimary, fontWeight: 'bold' }}>{part}</span>
          ) : (
            part
          )
        )}
      </span>
    );
  };

  const uploadButton = (
    <div>
      {avatarLoading ? <LoadingOutlined /> : <PlusOutlined />}
      <div style={{ marginTop: 8 }}>上传</div>
    </div>
  );

  return (
    <Layout style={{ height: '100vh' }}>
      <Header style={{
        display: 'flex', alignItems: 'center', justifyContent: 'space-between',
        background: '#fff', padding: '0 24px', borderBottom: '1px solid #f0f0f0',
        height: 60, zIndex: 10
      }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
          <div style={{
            background: `linear-gradient(135deg, ${token.colorPrimary} 0%, ${token.colorInfo} 100%)`,
            width: 32, height: 32, borderRadius: 6, display: 'flex', alignItems: 'center', justifyContent: 'center'
          }}>
            <BarChartOutlined style={{ color: '#fff', fontSize: 18 }} />
          </div>
          <span style={{ fontSize: 18, fontWeight: 'bold', color: '#262626' }}>数据分析平台</span>
        </div>

        <div style={{ display: 'flex', alignItems: 'center', gap: 16 }}>
            {currentUser && (
                <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                    {currentUser.userRole === 'admin' ? (
                        <Tag color="red">管理员</Tag>
                    ) : currentUser.userRole === 'vip' ? (
                        <Tag color="gold" icon={<CrownOutlined />}>VIP会员</Tag>
                    ) : (
                        <Tag color="default">普通用户</Tag>
                    )}
                    
                    <Tooltip title={`今日剩余智能分析次数：${
                        // @ts-ignore
                        currentUser.leftNum ?? 0
                    }`}>
                        <Tag color="blue" style={{ cursor: 'help' }}>
                           {/* @ts-ignore */}
                           剩余次数: {currentUser.leftNum ?? 0}
                        </Tag>
                    </Tooltip>

                    {currentUser.userRole !== 'vip' && (
                        <Button 
                            type="primary" 
                            size="small" 
                            ghost 
                            icon={<PayCircleOutlined />}
                            onClick={() => setVipModalOpen(true)}
                        >
                            升级会员
                        </Button>
                    )}
                </div>
            )}

            <Dropdown menu={{ items: userMenuItems }} placement="bottomRight" arrow>
              <div style={{ 
                display: 'flex', alignItems: 'center', cursor: 'pointer', padding: '4px 12px', 
                borderRadius: 20, transition: 'all 0.3s', background: 'rgba(0,0,0,0.02)'
              }}
              onMouseEnter={(e) => e.currentTarget.style.background = 'rgba(0,0,0,0.06)'}
              onMouseLeave={(e) => e.currentTarget.style.background = 'rgba(0,0,0,0.02)'}
              >
                <Avatar size="small" src={currentUser?.userAvatar} icon={<UserOutlined />} />
                <span style={{ 
                  marginLeft: 8, color: 'rgba(0,0,0,0.85)', fontWeight: 500, fontSize: 14,
                  maxWidth: 150, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap'
                }}>
                  {currentUser?.userAccount || '匿名用户'}
                </span>
              </div>
            </Dropdown>
        </div>
      </Header>

      <Layout>
        <Sider
          width={300} theme="light" collapsible collapsed={collapsed} onCollapse={setCollapsed}
          trigger={null} style={{ borderRight: '1px solid #f0f0f0' }}
        >
          <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
            <div style={{ 
               padding: '12px 16px', 
               display: 'flex', 
               alignItems: 'center', 
               justifyContent: collapsed ? 'center' : 'space-between',
               borderBottom: '1px solid #f0f0f0',
               minHeight: 56
            }}>
               {!collapsed && (
                 <span style={{ fontWeight: 600, color: '#434343', fontSize: 16 }}>
                    <HistoryOutlined style={{ marginRight: 8 }} />
                    我的分析
                 </span>
               )}
               <Tooltip title={collapsed ? "展开" : "收起"} placement="right">
                 <Button 
                   type="text" 
                   icon={collapsed ? <MenuUnfoldOutlined /> : <MenuFoldOutlined />} 
                   onClick={() => setCollapsed(!collapsed)} 
                   style={{ color: '#666' }}
                 />
               </Tooltip>
            </div>

            <div style={{ padding: 16 }}>
              {!collapsed ? (
                <Button type="primary" block icon={<PlusOutlined />} onClick={() => { setSelectedChart(undefined); setOption(undefined); form.resetFields(); }}>
                  新建分析
                </Button>
              ) : (
                <Button type="primary" icon={<PlusOutlined />} shape="circle" onClick={() => { setSelectedChart(undefined); setOption(undefined); form.resetFields(); }} />
              )}
            </div>

            {!collapsed && (
              <div style={{ padding: '0 16px 16px', display: 'flex', gap: 8 }}>
                <Search 
                   placeholder="搜图表名称" 
                   allowClear 
                   onSearch={(val) => setSearchParams({ ...searchParams, name: val, current: 1 })} 
                   onChange={(e) => {
                     if(!e.target.value) setSearchParams({ ...searchParams, name: '', current: 1 });
                   }}
                   style={{ flex: 1 }}
                />
                <Select
                  placeholder="筛选类型"
                  allowClear
                  style={{ width: 120 }}
                  options={chartTypeOptions}
                  onChange={(value) => {
                    setSearchParams({ ...searchParams, chartType: value || '', current: 1 });
                  }}
                  dropdownMatchSelectWidth={false}
                />
              </div>
            )}

            <div style={{ flex: 1, overflowY: 'auto', padding: '0 8px' }}>
              <List
                dataSource={chartList}
                loading={listLoading}
                locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无历史" /> }}
                renderItem={(item) => {
                  const isSelected = selectedChart?.id === item.id;
                  const typeConfig = CHART_TYPE_MAP[item.chartType || ''] || CHART_TYPE_MAP['默认'];
                  
                  return (
                    <List.Item
                      onClick={() => {
                        // 状态判断逻辑
                        if (item.status === 'wait') {
                            message.warning('当前图表正在排队中，请稍候...');
                            return;
                        }
                        if (item.status === 'running') {
                            message.loading('图表正在生成中，请耐心等待...');
                            return;
                        }
                        if (item.status === 'failed') {
                            // 失败时也允许查看详情，方便重试
                            setSelectedChart(item);
                            setOption(undefined);
                            return;
                        }

                        // 成功状态，解析并展示
                        try {
                          const opt = fixChartOption(item.genChart ?? '{}');
                          if (!opt.title) opt.title = { text: item.name };
                          setOption(opt);
                          setSelectedChart(item);
                        } catch (e) { message.error("图表解析错误"); }
                      }}
                      style={{
                        padding: '12px', cursor: 'pointer', borderRadius: 8, marginBottom: 8,
                        transition: 'all 0.2s',
                        background: isSelected ? '#e6f7ff' : 'transparent',
                        borderLeft: isSelected ? `4px solid ${token.colorPrimary}` : '4px solid transparent',
                        display: 'block'
                      }}
                    >
                      <div style={{ width: '100%' }}>
                        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: 4 }}>
                          <Text strong ellipsis style={{ maxWidth: collapsed ? 40 : 120, fontSize: 14 }}>
                             {renderHighlightedText(item.name || '未命名', searchParams.name || '')}
                          </Text>
                          
                          {!collapsed && (
                            <Space size={2}>
                              {/* 只有成功或失败的才能编辑/删除，运行中的保护起来 */}
                              {(item.status === 'succeed' || item.status === 'failed') ? (
                                <>
                                  <Button 
                                    type="text" 
                                    size="small" 
                                    icon={<EditOutlined style={{ fontSize: 12 }} />} 
                                    onClick={(e) => openEditModal(item, e)} 
                                  />
                                  <Popconfirm title="确认删除？" onConfirm={(e) => handleDelete(item.id as number, e as any)} onCancel={(e) => e?.stopPropagation()}>
                                    <Button type="text" size="small" danger icon={<DeleteOutlined style={{ fontSize: 12 }} />} onClick={(e) => e.stopPropagation()} className="delete-btn" />
                                  </Popconfirm>
                                </>
                              ) : (
                                <LoadingOutlined style={{ fontSize: 14, color: token.colorPrimary, marginRight: 8 }} />
                              )}
                            </Space>
                          )}
                        </div>
                        
                        {!collapsed && (
                           <>
                             {/* 状态展示标签 */}
                             <div style={{ marginBottom: 4 }}>
                                {item.status === 'wait' && <Tag icon={<ClockCircleOutlined />} color="default">排队中</Tag>}
                                {item.status === 'running' && <Tag icon={<LoadingOutlined />} color="processing">生成中</Tag>}
                                {item.status === 'succeed' && <Tag color="success">成功</Tag>}
                                
                                {/* 【确保】失败状态：展示详细错误信息 + 重试按钮 */}
                                {item.status === 'failed' && (
                                   <div style={{ display: 'flex', alignItems: 'center', gap: 8, color: '#ff4d4f', fontSize: 12, marginTop: 4 }}>
                                      <CloseCircleOutlined />
                                      <span style={{ flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }} title={item.execMessage}>
                                         {item.execMessage || '生成失败'}
                                      </span>
                                      <Button 
                                        type="primary" 
                                        danger 
                                        size="small" 
                                        ghost
                                        icon={<ReloadOutlined style={{ fontSize: 12 }} />}
                                        onClick={(e) => handleRetry(item.id, e)}
                                        style={{ fontSize: 12, height: 22, padding: '0 8px' }}
                                      >
                                        重试
                                      </Button>
                                   </div>
                                )}
                             </div>

                             {item.goal && (
                               <Paragraph 
                                 type="secondary" 
                                 ellipsis={{ rows: 2 }} 
                                 style={{ fontSize: 12, marginBottom: 6, color: '#666' }}
                               >
                                 {item.goal}
                               </Paragraph>
                             )}

                             <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                                <Tag color={typeConfig.color} icon={typeConfig.icon} style={{ margin: 0, fontSize: 10, lineHeight: '18px' }}>
                                    {item.chartType}
                                </Tag>
                                <Text type="secondary" style={{ fontSize: 12, transform: 'scale(0.9)', transformOrigin: 'right' }}>
                                  {item.createTime?.substring(5, 10)}
                                </Text>
                             </div>
                           </>
                        )}
                      </div>
                    </List.Item>
                  );
                }}
              />
            </div>
          </div>
        </Sider>

        <Content style={{ padding: 24, background: '#f5f7fa', overflowY: 'auto' }}>
          {!selectedChart ? (
            <div style={{ maxWidth: 1000, margin: '0 auto' }}>
               <Card bordered={false} style={{ marginBottom: 24, background: `linear-gradient(to right, #e6f7ff, #ffffff)` }}>
                  <Row align="middle">
                    <Col span={18}>
                      <Title level={3} style={{ marginBottom: 8 }}>🚀 智能数据分析助手</Title>
                      <Paragraph type="secondary" style={{ marginBottom: 0 }}>
                        请上传 Excel 数据文件，AI 将为您生成可视化图表。支持自动识别数据特征，提供业务增长建议。
                      </Paragraph>
                    </Col>
                    <Col span={6} style={{ textAlign: 'right' }}>
                       <RadarChartOutlined style={{ fontSize: 60, color: token.colorPrimary, opacity: 0.15 }} />
                    </Col>
                  </Row>
               </Card>

               <Card bordered={false}>
                  <Spin spinning={submitting} tip="正在提交任务...">
                    <Form form={form} name="addChart" layout="vertical" onFinish={onFinish} initialValues={{ chartType: '不指定' }}>
                      <Form.Item name="goal" label="分析目标" rules={[{ required: true, message: '请输入分析目标' }]}>
                        <Input.TextArea placeholder="例如：分析网站用户增长趋势..." autoSize={{ minRows: 3, maxRows: 6 }} showCount maxLength={200} />
                      </Form.Item>

                      <Row gutter={24}>
                        <Col span={12}>
                          <Form.Item name="name" label="图表名称">
                            <Input placeholder="生成的图表标题" />
                          </Form.Item>
                        </Col>
                        <Col span={12}>
                          <Form.Item name="chartType" label="图表类型">
                            <Select options={chartTypeOptions} />
                          </Form.Item>
                        </Col>
                      </Row>
                      
                      <Form.Item name="file" label="原始数据" rules={[{ required: true, message: '请上传数据' }]} getValueFromEvent={normFile}>
                        
                        <Upload name="file" maxCount={1} accept=".xlsx,.xls" listType="picture-card" 
                           customRequest={({ onSuccess }) => setTimeout(() => onSuccess?.("ok"), 0)}
                        >
                          <div><PlusOutlined /><div style={{ marginTop: 8 }}>上传 Excel</div></div>
                        </Upload>
                      </Form.Item>

                      <Form.Item>
                        <Button type="primary" htmlType="submit" loading={submitting} block size="large" icon={<CheckOutlined />}>
                           {submitting ? '提交分析' : '开始生成'}
                        </Button>
                      </Form.Item>
                    </Form>
                  </Spin>
               </Card>
            </div>
          ) : (
            <div style={{ maxWidth: 1200, margin: '0 auto' }}>
              <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between' }}>
                <Button icon={<LeftOutlined />} onClick={() => { setSelectedChart(undefined); setOption(undefined); }}>返回</Button>
                <Button icon={<EditOutlined />} onClick={handleRegenerate} disabled={selectedChart.status !== 'succeed'}>基于此调整</Button>
              </div>

              {/* 失败状态展示 */}
              {selectedChart.status === 'failed' ? (
                 <Result
                    status="error"
                    title="图表生成失败"
                    subTitle={selectedChart.execMessage}
                    extra={[
                        // [新增] 详情页也可以加个重试按钮
                        <Button 
                           type="primary" 
                           key="retry" 
                           danger
                           onClick={(e) => handleRetry(selectedChart.id, e)}
                        >
                            尝试重新生成
                        </Button>,
                        <Button key="close" onClick={() => setSelectedChart(undefined)}>
                            关闭
                        </Button>,
                    ]}
                 />
              ) : (
                 <>
                    <Card style={{ marginBottom: 24, borderRadius: 8 }} bordered={false}>
                        <Descriptions title="分析目标">
                            <Descriptions.Item labelStyle={{fontWeight: 'bold'}}>
                                {selectedChart.goal}
                            </Descriptions.Item>
                        </Descriptions>
                    </Card>

                    <Row gutter={[24, 24]}>
                        <Col xs={24} lg={14}>
                        <Card 
                            title="可视化图表" 
                            extra={
                            <Tooltip title="下载为图片">
                                <Button icon={<DownloadOutlined />} onClick={handleDownloadChart} type="text" />
                            </Tooltip>
                            }
                            bordered={false} style={{ height: '100%', minHeight: 450 }}
                        >
                            {option ? (
                            <ReactECharts ref={chartRef} option={option} style={{ height: 400 }} notMerge={true} />
                            ) : (
                            <Skeleton active paragraph={{ rows: 10 }} />
                            )}
                        </Card>
                        </Col>

                        <Col xs={24} lg={10}>
                        <Card 
                            title="AI 分析结论" 
                            extra={
                            <Tooltip title="复制结论">
                                <Button type="text" icon={<CopyOutlined />} onClick={() => { copy(selectedChart.genResult || ''); message.success('已复制'); }} />
                            </Tooltip>
                            }
                            bordered={false} style={{ height: '100%', minHeight: 450 }}
                        >
                            <div style={{ maxHeight: 400, overflowY: 'auto' }}>
                            {selectedChart.genResult ? (
                                <div className="markdown-body">
                                    <ReactMarkdown>
                                    {selectedChart.genResult}
                                    </ReactMarkdown>
                                </div>
                            ) : <Empty description="暂无结论" />}
                            </div>
                        </Card>
                        </Col>

                        <Col span={24}>
                        <Collapse ghost items={[{
                            key: '1',
                            label: '查看原始数据',
                            children: (
                            <div style={{ background: '#fafafa', padding: 12, borderRadius: 6, maxHeight: 300, overflow: 'auto' }}>
                                <pre style={{ margin: 0 }}>{selectedChart.chartData || '无数据'}</pre>
                            </div>
                            )
                        }]} />
                        </Col>
                    </Row>
                 </>
              )}
            </div>
          )}
        </Content>
      </Layout>

      <Modal
        title="编辑图表信息"
        open={editModalOpen}
        onCancel={() => setEditModalOpen(false)}
        footer={null}
        destroyOnClose
      >
        <Form
          form={editForm}
          layout="vertical"
          onFinish={handleEditSubmit}
        >
          <Form.Item 
            name="name" 
            label="图表名称" 
            rules={[{ required: true, message: '请输入名称' }]}
          >
            <Input placeholder="请输入图表名称" />
          </Form.Item>
          
          <Form.Item 
            name="chartType" 
            label="图表类型"
          >
             <Select options={chartTypeOptions} />
          </Form.Item>

          <Form.Item 
            name="goal" 
            label="分析目标" 
            rules={[{ required: true, message: '请输入分析目标' }]}
            help="⚠️ 如果修改了目标或图表类型，系统将尝试为您重新生成图表，这可能需要几秒钟。"
          >
            <Input.TextArea rows={4} placeholder="请输入新的分析目标" />
          </Form.Item>

          <Form.Item>
            <Space style={{ width: '100%', justifyContent: 'flex-end' }}>
              <Button onClick={() => setEditModalOpen(false)}>取消</Button>
              <Button type="primary" htmlType="submit" loading={editLoading}>
                保存更改
              </Button>
            </Space>
          </Form.Item>
        </Form>
      </Modal>

      {/* 用户个人中心 Modal */}
      <Modal
        open={userModalOpen}
        onCancel={() => setUserModalOpen(false)}
        footer={null}
        width={600}
        destroyOnClose
      >
        <Tabs
          activeKey={activeUserTab}
          onChange={setActiveUserTab}
          items={[
            {
              key: 'info',
              label: '个人信息',
              children: (
                <div style={{ padding: '20px 0' }}>
                  <div style={{ textAlign: 'center', marginBottom: 24 }}>
                    <Avatar size={80} src={currentUser?.userAvatar} icon={<UserOutlined />} />
                    <Title level={4} style={{ marginTop: 12 }}>{currentUser?.userAccount}</Title>
                    {/* 根据真实角色显示对应 Tag */}
                    <div style={{ marginTop: 8 }}>
                        {currentUser?.userRole === 'admin' ? (
                            <Tag color="red">管理员</Tag>
                        ) : currentUser?.userRole === 'vip' ? (
                            <Tag color="gold" icon={<CrownOutlined />}>VIP会员</Tag>
                        ) : (
                            <Tag color="blue">普通用户</Tag>
                        )}
                    </div>
                  </div>
                  <Descriptions column={1} bordered>
                    <Descriptions.Item label="用户ID">{currentUser?.id}</Descriptions.Item>
                    <Descriptions.Item label="用户账号">{currentUser?.userAccount}</Descriptions.Item>
                    <Descriptions.Item label="注册时间">{currentUser?.createTime}</Descriptions.Item>
                    <Descriptions.Item label="个人简介">
                      {currentUser?.userProfile || '暂无简介'}
                    </Descriptions.Item>
                  </Descriptions>
                </div>
              ),
            },
            {
              key: 'settings',
              label: '资料修改',
              children: (
                <div style={{ padding: '20px 0' }}>
                  <Form
                    form={userForm}
                    layout="vertical"
                    onFinish={handleUpdateUser}
                  >
                    <Form.Item label="用户账号" name="userAccount" tooltip="这是您的登录凭证">
                      <Input placeholder="请输入新的账号" />
                    </Form.Item>
                    
                    <Form.Item name="userAvatar" hidden>
                      <Input />
                    </Form.Item>

                    <Form.Item label="用户头像" help="点击上传头像，支持 JPG/PNG 格式，小于 1MB">
                       <Upload
                          name="file"
                          listType="picture-circle"
                          className="avatar-uploader"
                          showUploadList={false}
                          customRequest={handleUploadAvatar}
                          beforeUpload={beforeUpload}
                        >
                          {avatarUrl ? <img src={avatarUrl} alt="avatar" style={{ width: '100%', height: '100%', borderRadius: '50%', objectFit: 'cover' }} /> : uploadButton}
                        </Upload>
                    </Form.Item>
                    
                    <Form.Item label="个人简介" name="userProfile">
                      <Input.TextArea placeholder="介绍一下自己吧" rows={4} />
                    </Form.Item>

                    <Form.Item>
                      <Button type="primary" htmlType="submit" loading={userUpdating} block>
                        保存修改
                      </Button>
                    </Form.Item>
                  </Form>
                </div>
              ),
            },
          ]}
        />
      </Modal>

      {/* VIP 充值弹窗 */}
      <Modal
        title="升级为 VIP 会员"
        open={vipModalOpen}
        onCancel={() => setVipModalOpen(false)}
        footer={null}
        destroyOnClose
      >
        <div style={{ textAlign: 'center', padding: 20 }}>
            <CrownOutlined style={{ fontSize: 48, color: '#faad14', marginBottom: 16 }} />
            <Paragraph>
                VIP 用户享有每日 <b>50</b> 次智能分析额度，尊享极速通道。
            </Paragraph>
            <Input 
                placeholder="请输入兑换码 (测试码: vip)" 
                value={vipCode} 
                onChange={e => setVipCode(e.target.value)} 
                style={{ marginBottom: 16 }}
            />
            <Button 
                type="primary" 
                block 
                size="large" 
                loading={vipLoading} 
                onClick={handleExchangeVip}
                style={{ background: '#faad14', borderColor: '#faad14' }}
            >
                立即开通
            </Button>
            
        </div>
      </Modal>
    </Layout>
  );
};

export default AddChart;