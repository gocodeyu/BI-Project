import {
  deleteChartforeverUsingPost,
  listMyChartByPagedeleteUsingPost,
  recoverChartUsingPost,
} from '@/services/bi/chartController';
import { userLogoutUsingPost } from '@/services/bi/userController';
import {
  DeleteOutlined,
  HistoryOutlined,
  ReloadOutlined,
  UserOutlined,
  QuestionCircleOutlined,
  LineChartOutlined,
  BarChartOutlined,
  PieChartOutlined,
  RadarChartOutlined,
  DotChartOutlined,
} from '@ant-design/icons';
import { history, useModel } from '@umijs/max';
import {
  Avatar,
  Button,
  Card,
  Dropdown,
  Empty,
  Input,
  Layout,
  List,
  message,
  Pagination,
  Popconfirm,
  Select,
  Spin,
  Tag,
  Typography,
} from 'antd';
import { useEffect, useState } from 'react';

const { Header, Content } = Layout;
const { Paragraph, Text } = Typography;
const { Search } = Input;

// --- 图表类型配置 ---
const CHART_TYPE_MAP: Record<string, { color: string; icon: React.ReactNode }> = {
  不指定: { color: 'default', icon: <QuestionCircleOutlined /> },
  折线图: { color: 'blue', icon: <LineChartOutlined /> },
  柱状图: { color: 'cyan', icon: <BarChartOutlined /> },
  饼图: { color: 'orange', icon: <PieChartOutlined /> },
  雷达图: { color: 'purple', icon: <RadarChartOutlined /> },
  散点图: { color: 'magenta', icon: <DotChartOutlined /> },
  热力图: { color: 'volcano', icon: <DotChartOutlined /> },
  漏斗图: { color: 'gold', icon: <DotChartOutlined /> },
  仪表盘: { color: 'geekblue', icon: <DotChartOutlined /> },
  K线图: { color: 'red', icon: <LineChartOutlined /> },
  箱线图: { color: 'lime', icon: <DotChartOutlined /> },
  树图: { color: 'green', icon: <DotChartOutlined /> },
  默认: { color: 'default', icon: <QuestionCircleOutlined /> },
};

// --- 动态生成下拉选项 ---
const chartTypeOptions = Object.keys(CHART_TYPE_MAP)
  .filter((key) => key !== '默认')
  .map((key) => ({
    value: key,
    label: (
      <span>
        {CHART_TYPE_MAP[key].icon}
        <span style={{ marginLeft: 8 }}>{key}</span>
      </span>
    ),
  }));

const RecycleBin: React.FC = () => {
  const [chartList, setChartList] = useState<API.Chart[]>([]);
  const [listLoading, setListLoading] = useState(false);
  const [total, setTotal] = useState<number>(0);

  const [searchParams, setSearchParams] = useState<API.ChartQueryRequest>({
    current: 1,
    pageSize: 10,
    sortField: 'updateTime',
    sortOrder: 'desc',
    name: '',
    chartType: '',
  });

  // 增加 setInitialState
const { initialState, setInitialState } = useModel('@@initialState');
  const { currentUser } = initialState || {};

  const loadData = async (isSilent = false) => {
    if (!isSilent) {
      setListLoading(true);
    }
    try {
      const res = await listMyChartByPagedeleteUsingPost(searchParams);
      if (res.data) {
        setChartList(res.data.records || []);
        setTotal(Number(res.data.total) || 0);
      } else {
        message.error('获取列表失败');
      }
    } catch (e: any) {
      message.error('获取列表失败：' + e.message);
    }
    if (!isSilent) {
      setListLoading(false);
    }
  };

  useEffect(() => {
    loadData();
  }, [searchParams]);

  const handleRestore = async (chartId: number) => {
    try {
      const res = await recoverChartUsingPost({ id: chartId, isDelete: 0 });
      if (res.data) {
        message.success('恢复成功');
        loadData();
      } else {
        message.error('恢复失败');
      }
    } catch (e: any) {
      message.error('恢复失败：' + e.message);
    }
  };

  const handleDeleteForever = async (chartId: number) => {
    try {
      const res = await deleteChartforeverUsingPost({ id: chartId });
      if (res.data) {
        message.success('永久删除成功');
        loadData();
      } else {
        message.error('永久删除失败');
      }
    } catch (e: any) {
      message.error('永久删除失败：' + e.message);
    }
  };

  const renderHighlightedText = (text: string, highlight: string) => {
    if (!highlight || !text) return text;
    const regex = new RegExp(`(${highlight})`, 'gi');
    const parts = text.split(regex);
    return parts.map((part, index) =>
      regex.test(part) ? (
        <span key={index} style={{ backgroundColor: '#fff2e8' }}>
          {part}
        </span>
      ) : (
        part
      )
    );
  };

  const handleLogout = async () => {
    try {
      // 1. 调用后端注销接口 (清除 Redis 中的 Token)
      await userLogoutUsingPost();
    } catch (error) {
      // 即使后端报错（比如 Token 已过期），前端也要继续执行清除逻辑
      console.error('Logout failed:', error);
    }
    
    // 2. 【核心】清除本地保存的 Token
    localStorage.removeItem('token');

    // 3. 清空全局状态中的用户信息
    if (setInitialState) {
      setInitialState((s) => ({ ...s, currentUser: undefined }));
    }

    // 4. 重定向到登录页
    history.push('/user/login');
  };

  const userMenuItems = [
    {
      key: 'profile',
      icon: <UserOutlined />,
      label: '个人资料',
    },
    {
      key: 'settings',
      icon: <UserOutlined />,
      label: '个人设置',
    },
    {
      type: 'divider' as const,
    },
    {
      key: 'logout',
      icon: <UserOutlined />,
      label: '退出登录',
      onClick: handleLogout,
      danger: true,
    },
  ];

  return (
    <Layout style={{ height: '100vh' }}>
      <Header
        style={{
          background: '#fff',
          padding: '0 24px',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          boxShadow: '0 1px 4px rgba(0,21,41,.08)',
        }}
      >
        <div style={{ display: 'flex', alignItems: 'center' }}>
          <HistoryOutlined style={{ fontSize: 20, marginRight: 12, color: '#1890ff' }} />
          <span style={{ fontSize: 18, fontWeight: 600, color: '#262626' }}>回收站</span>
        </div>
        <Dropdown menu={{ items: userMenuItems }} trigger={['click']}>
          <div
            style={{
              cursor: 'pointer',
              padding: '4px 12px',
              borderRadius: 20,
              transition: 'all 0.3s',
              background: 'rgba(0,0,0,0.02)',
            }}
            onMouseEnter={(e) => (e.currentTarget.style.background = 'rgba(0,0,0,0.06)')}
            onMouseLeave={(e) => (e.currentTarget.style.background = 'rgba(0,0,0,0.02)')}
          >
            <Avatar size="small" src={currentUser?.userAvatar} icon={<UserOutlined />} />
            <span
              style={{
                marginLeft: 8,
                color: 'rgba(0,0,0,0.85)',
                fontWeight: 500,
                fontSize: 14,
                maxWidth: 150,
                overflow: 'hidden',
                textOverflow: 'ellipsis',
                whiteSpace: 'nowrap',
              }}
            >
              {currentUser?.userAccount || '匿名用户'}
            </span>
          </div>
        </Dropdown>
      </Header>

      <Layout>
        <Content style={{ padding: 24, background: '#f5f5f5' }}>
          <Card
            title={
              <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
                <span>已删除的图表</span>
                <Button onClick={() => history.push('/add_chart')}>返回图表列表</Button>
              </div>
            }
            bordered={false}
          >
            <div style={{ marginBottom: 16, display: 'flex', gap: 8 }}>
              <Search
                placeholder="搜索图表名称"
                allowClear
                onSearch={(val) => setSearchParams({ ...searchParams, name: val, current: 1 })}
                onChange={(e) => {
                  if (!e.target.value) setSearchParams({ ...searchParams, name: '', current: 1 });
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
            <Spin spinning={listLoading}>
              <List
                dataSource={chartList}
                locale={{
                  emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无已删除的图表" />,
                }}
                renderItem={(item) => {
                  const typeConfig = CHART_TYPE_MAP[item.chartType || ''] || CHART_TYPE_MAP['默认'];

                  return (
                    <List.Item
                      actions={[
                        <Button
                          type="primary"
                          icon={<ReloadOutlined />}
                          onClick={() => handleRestore(item.id as number)}
                        >
                          恢复
                        </Button>,
                        <Popconfirm
                          title="确认永久删除？"
                          description="此操作不可逆，图表将被永久删除"
                          onConfirm={() => handleDeleteForever(item.id as number)}
                          okText="确认删除"
                          cancelText="取消"
                          okButtonProps={{ danger: true }}
                        >
                          <Button
                            danger
                            icon={<DeleteOutlined />}
                          >
                            永久删除
                          </Button>
                        </Popconfirm>,
                      ]}
                      style={{
                        padding: '12px',
                        borderRadius: 8,
                        marginBottom: 8,
                        transition: 'all 0.2s',
                        background: 'transparent',
                        border: '1px solid #f0f0f0',
                      }}
                    >
                      <div style={{ width: '100%' }}>
                        <div
                          style={{
                            display: 'flex',
                            justifyContent: 'space-between',
                            alignItems: 'flex-start',
                            marginBottom: 4,
                          }}
                        >
                          <Text
                            strong
                            ellipsis
                            style={{ maxWidth: 200, fontSize: 14 }}
                          >
                            {renderHighlightedText(item.name || '未命名', searchParams.name || '')}
                          </Text>
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

                        <div
                          style={{
                            display: 'flex',
                            justifyContent: 'space-between',
                            alignItems: 'center',
                          }}
                        >
                          <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
                            <Tag
                              color={typeConfig.color}
                              icon={typeConfig.icon}
                              style={{ margin: 0, fontSize: 10, lineHeight: '18px' }}
                            >
                              {item.chartType}
                            </Tag>
                            <Tag color="default">已删除</Tag>
                          </div>
                          <Text
                            type="secondary"
                            style={{
                              fontSize: 12,
                              transform: 'scale(0.9)',
                              transformOrigin: 'right',
                            }}
                          >
                            {item.updateTime?.substring(5, 10)}
                          </Text>
                        </div>
                      </div>
                    </List.Item>
                  );
                }}
              />
            </Spin>

            <div style={{ marginTop: 16, textAlign: 'center' }}>
              <Pagination
                current={searchParams.current}
                pageSize={searchParams.pageSize}
                total={total}
                onChange={(page, pageSize) => {
                  setSearchParams({ ...searchParams, current: page, pageSize });
                }}
                showSizeChanger
                pageSizeOptions={[5, 10, 20, 50]}
                showQuickJumper
                showTotal={(total, range) => `第 ${range[0]}-${range[1]} 条，共 ${total} 条`}
              />
            </div>
          </Card>
        </Content>
      </Layout>
    </Layout>
  );
};

export default RecycleBin;