import {
  deleteChartUsingPost,
  genChartByAiUsingPost,
  listMyChartByPageUsingPost
} from '@/services/bi/chartController';
// 引入 getUserByIdUsingGet 以便在 LoginUserVO 缺失字段时补全数据
import { 
  userLogoutUsingPost, 
  updateMyUserUsingPost, 
  getLoginUserUsingGet,
  getUserByIdUsingGet 
} from '@/services/bi/userController';
// 引入文件上传接口
import { uploadFileUsingPost } from '@/services/bi/fileController';
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
  LoadingOutlined
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
  Upload
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

// 图表类型映射配置
const CHART_TYPE_MAP: Record<string, { color: string; icon: React.ReactNode }> = {
  '折线图': { color: 'blue', icon: <LineChartOutlined /> },
  '柱状图': { color: 'cyan', icon: <BarChartOutlined /> },
  '饼图': { color: 'orange', icon: <PieChartOutlined /> },
  '雷达图': { color: 'purple', icon: <RadarChartOutlined /> },
  '散点图': { color: 'magenta', icon: <BarChartOutlined /> },
  '默认': { color: 'default', icon: <FileTextOutlined /> },
};

// 文件上传校验
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
  const [searchParams, setSearchParams] = useState<API.ChartQueryRequest>({
    current: 1,
    pageSize: 10,
    sortField: 'createTime',
    sortOrder: 'desc',
    name: '',
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

  // 1. 获取完整用户信息
  useEffect(() => {
    const fetchUserInfo = async () => {
      try {
        const loginRes = await getLoginUserUsingGet();
        if (loginRes.data) {
          const loginUser = loginRes.data;
          // 如果缺少账号信息，尝试通过 ID 获取
          // @ts-ignore
          if (!loginUser.userAccount && loginUser.id) {
             const fullUserRes = await getUserByIdUsingGet({ id: loginUser.id });
             if (fullUserRes.data) {
                const fullUser = { ...loginUser, ...fullUserRes.data };
                setInitialState((s) => ({ ...s, currentUser: fullUser }));
                return;
             }
          }
          setInitialState((s) => ({ ...s, currentUser: loginUser }));
        }
      } catch (e) {
        console.error("获取用户信息失败", e);
      }
    };

    // @ts-ignore
    if (!currentUser || !currentUser.userAccount) {
      fetchUserInfo();
    }
  }, []);

  // --- 数据加载 ---
  const loadData = async () => {
    setListLoading(true);
    try {
      const res = await listMyChartByPageUsingPost(searchParams);
      if (res.data) {
        setChartList(res.data.records ?? []);
      }
    } catch (e: any) {
      message.error('获取列表失败：' + e.message);
    }
    setListLoading(false);
  };

  useEffect(() => {
    loadData();
  }, [searchParams]);

  // 2. 回填表单：这次我们回填 userAccount
  useEffect(() => {
    if (userModalOpen && currentUser) {
      userForm.setFieldsValue({
        // 🟢 修改点：绑定 userAccount，而不是 userName
        userAccount: currentUser.userAccount,
        userAvatar: currentUser.userAvatar,
        userProfile: currentUser.userProfile,
      });
      setAvatarUrl(currentUser.userAvatar); 
    }
  }, [userModalOpen, currentUser]);

  // --- 业务操作 ---

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
      // 🟢 修改点：前端发送的是 { userAccount: "xxx", ... }
      // 请确保后端的 UserUpdateMyRequest 类中有 userAccount 字段！
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
      
      /* // 真实后端上传
      const res = await uploadFileUsingPost({}, { biz: 'user_avatar' }, file);
      if (res.data) { ... }
      */
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

    const params = {
      name: values.name,
      goal: values.goal,
      chartType: values.chartType,
    };
    const fileObj = values.file?.[0]?.originFileObj;

    try {
      const res = await genChartByAiUsingPost(params, {}, fileObj);
      if (!res?.data) {
        message.error('分析失败');
      } else {
        message.success('分析成功');
        const chartOption = JSON.parse(res.data.genChart ?? '{}');
        if (!chartOption.title) chartOption.title = { text: values.name };

        const newChart: API.Chart = {
          id: res.data.chartId,
          name: values.name,
          goal: values.goal,
          chartType: values.chartType,
          genChart: res.data.genChart,
          genResult: res.data.genResult,
          createTime: new Date().toISOString(),
          chartData: res.data.chartData,
        };

        setOption(chartOption);
        setSelectedChart(newChart);
        setSearchParams({ ...searchParams, current: 1 });
        form.resetFields();
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
          <span style={{ fontSize: 18, fontWeight: 'bold', color: '#262626' }}>智能 BI 平台</span>
        </div>

        <Dropdown menu={{ items: userMenuItems }} placement="bottomRight" arrow>
          <div style={{ 
            display: 'flex', alignItems: 'center', cursor: 'pointer', padding: '4px 12px', 
            borderRadius: 20, transition: 'all 0.3s', background: 'rgba(0,0,0,0.02)'
          }}
          onMouseEnter={(e) => e.currentTarget.style.background = 'rgba(0,0,0,0.06)'}
          onMouseLeave={(e) => e.currentTarget.style.background = 'rgba(0,0,0,0.02)'}
          >
            <Avatar size="small" src={currentUser?.userAvatar} icon={<UserOutlined />} />
            {/* 🟢 修改点：Header 直接显示 userAccount */}
            <span style={{ 
              marginLeft: 8, color: 'rgba(0,0,0,0.85)', fontWeight: 500, fontSize: 14,
              maxWidth: 150, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap'
            }}>
              {currentUser?.userAccount || '匿名用户'}
            </span>
          </div>
        </Dropdown>
      </Header>

      <Layout>
        <Sider
          width={300} theme="light" collapsible collapsed={collapsed} onCollapse={setCollapsed}
          trigger={null} style={{ borderRight: '1px solid #f0f0f0' }}
        >
          <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
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
              <div style={{ padding: '0 16px 16px' }}>
                <Search 
                   placeholder="搜索图表名称..." allowClear 
                   onSearch={(val) => setSearchParams({ ...searchParams, name: val, current: 1 })} 
                   onChange={(e) => {
                     if(e.target.value === '') setSearchParams({ ...searchParams, name: '', current: 1 });
                   }}
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
                        try {
                          const opt = JSON.parse(item.genChart ?? '{}');
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
                      }}
                    >
                      <div style={{ width: '100%' }}>
                        <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 6 }}>
                          <Text strong ellipsis style={{ maxWidth: collapsed ? 40 : 160 }}>
                             {renderHighlightedText(item.name || '未命名', searchParams.name || '')}
                          </Text>
                          {!collapsed && (
                            <Popconfirm title="确认删除？" onConfirm={(e) => handleDelete(item.id as number, e as any)} onCancel={(e) => e?.stopPropagation()}>
                              <Button type="text" size="small" danger icon={<DeleteOutlined />} onClick={(e) => e.stopPropagation()} className="delete-btn" />
                            </Popconfirm>
                          )}
                        </div>
                        {!collapsed && (
                          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                            <Tag color={typeConfig.color} style={{ margin: 0, fontSize: 10, lineHeight: '18px' }}>{item.chartType}</Tag>
                            <Text type="secondary" style={{ fontSize: 12 }}>
                              {item.createTime?.substring(5, 10)}
                            </Text>
                          </div>
                        )}
                      </div>
                    </List.Item>
                  );
                }}
              />
            </div>
            
            <div style={{ borderTop: '1px solid #f0f0f0', padding: 8, textAlign: 'center' }}>
              <Button type="text" icon={collapsed ? <RightOutlined /> : <LeftOutlined />} onClick={() => setCollapsed(!collapsed)} block />
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
                  <Spin spinning={submitting} tip="正在进行 AI 深度分析...">
                    <Form form={form} name="addChart" layout="vertical" onFinish={onFinish} initialValues={{ chartType: '折线图' }}>
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
                            <Select options={[
                              { value: '折线图', label: '折线图' }, { value: '柱状图', label: '柱状图' },
                              { value: '饼图', label: '饼图' }, { value: '雷达图', label: '雷达图' },
                            ]} />
                          </Form.Item>
                        </Col>
                      </Row>

                      <Form.Item name="file" label="原始数据" rules={[{ required: true, message: '请上传数据' }]}>
                        <Upload name="file" maxCount={1} accept=".xlsx,.xls" listType="picture-card" 
                           customRequest={({ onSuccess }) => setTimeout(() => onSuccess?.("ok"), 0)}
                        >
                          <div><PlusOutlined /><div style={{ marginTop: 8 }}>上传 Excel</div></div>
                        </Upload>
                      </Form.Item>

                      <Form.Item>
                        <Button type="primary" htmlType="submit" loading={submitting} block size="large" icon={<CheckOutlined />}>
                           {submitting ? '分析中...' : '开始生成'}
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
                <Button icon={<EditOutlined />} onClick={handleRegenerate}>基于此调整</Button>
              </div>

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
                      <ReactECharts ref={chartRef} option={option} style={{ height: 400 }} />
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
                          <ReactMarkdown className="markdown-body">
                             {selectedChart.genResult}
                          </ReactMarkdown>
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
            </div>
          )}
        </Content>
      </Layout>

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
                    {/* 🟢 修改点：只显示 userAccount */}
                    <Title level={4} style={{ marginTop: 12 }}>{currentUser?.userAccount}</Title>
                    <Tag color="blue">{currentUser?.userRole === 'admin' ? '管理员' : '普通用户'}</Tag>
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
                    {/* 🟢 修改点：这里绑定的是 userAccount */}
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
    </Layout>
  );
};

export default AddChart;