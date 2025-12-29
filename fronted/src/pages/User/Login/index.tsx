import Footer from '@/components/Footer';
import {
  getCaptchaUsingGet,
  getLoginUserUsingGet,
  userLoginUsingPost,   // 对应 /api/user/login/phone
  userLoginUsingPost1,  // 对应 /api/user/login
} from '@/services/bi/userController';
import {
  ModalForm, // 新增
} from '@ant-design/pro-components';
import { userPasswordResetUsingPost } from '@/services/bi/userController'; // 新增引入
import {
  AlipayCircleOutlined,
  LockOutlined,
  MobileOutlined,
  TaobaoCircleOutlined,
  UserOutlined,
  WeiboCircleOutlined,
} from '@ant-design/icons';
import {
  LoginForm,
  ProFormCaptcha,
  ProFormCheckbox,
  ProFormText,
} from '@ant-design/pro-components';
import { useEmotionCss } from '@ant-design/use-emotion-css';
import { Helmet, history, useModel } from '@umijs/max';
import { Alert, message, Tabs } from 'antd';
import React, { useState } from 'react';
import { flushSync } from 'react-dom';
import Settings from '../../../../config/defaultSettings';

const ActionIcons = () => {
  const langClassName = useEmotionCss(({ token }) => {
    return {
      marginLeft: '8px',
      color: 'rgba(0, 0, 0, 0.2)',
      fontSize: '24px',
      verticalAlign: 'middle',
      cursor: 'pointer',
      transition: 'color 0.3s',
      '&:hover': {
        color: token.colorPrimaryActive,
      },
    };
  });
  return (
    <>
      <AlipayCircleOutlined key="AlipayCircleOutlined" className={langClassName} />
      <TaobaoCircleOutlined key="TaobaoCircleOutlined" className={langClassName} />
      <WeiboCircleOutlined key="WeiboCircleOutlined" className={langClassName} />
    </>
  );
};

const LoginMessage: React.FC<{
  content: string;
}> = ({ content }) => {
  return (
    <Alert
      style={{
        marginBottom: 24,
      }}
      message={content}
      type="error"
      showIcon
    />
  );
};

const Login: React.FC = () => {
  const [userLoginState, setUserLoginState] = useState<API.LoginResult>({});
  const [type, setType] = useState<string>('account'); // 登录类型：account 或 mobile
  const { setInitialState } = useModel('@@initialState');
  const containerClassName = useEmotionCss(() => {
    return {
      display: 'flex',
      flexDirection: 'column',
      height: '100vh',
      overflow: 'auto',
      backgroundImage:
        "url('https://mdn.alipayobjects.com/yuyan_qk0oxh/afts/img/V-_oS6r-i7wAAAAAAAAAAAAAFl94AQBr')",
      backgroundSize: '100% 100%',
    };
  });

  const fetchUserInfo = async () => {
    const userInfo = await getLoginUserUsingGet();
    if (userInfo) {
      flushSync(() => {
        setInitialState((s) => ({
          ...s,
          currentUser: userInfo?.data,
        }));
      });
    }
  };

  const handleSubmit = async (values: API.UserLoginRequest & API.UserLoginByPhoneRequest) => {
    try {
      let res;
      // 【修改点】根据 tab 类型调用不同的登录接口
      if (type === 'account') {
        // 账号密码登录
        res = await userLoginUsingPost1({
          userAccount: values.userAccount,
          userPassword: values.userPassword,
        });
      } else {
        // 手机号登录
        res = await userLoginUsingPost({
          phone: values.phone,
          code: values.code,
        });
      }

      if (res.code === 0) {
        const defaultLoginSuccessMessage = '登录成功！';
        message.success(defaultLoginSuccessMessage);

        // 保存 Token 到 localStorage
        if (res.data?.token) {
          localStorage.setItem('token', res.data.token);
        }

        // 获取用户信息
        await fetchUserInfo();

        const urlParams = new URL(window.location.href).searchParams;
        history.push(urlParams.get('redirect') || '/');
        return;
      } else {
        message.error(res.message);
      }
    } catch (error) {
      const defaultLoginFailureMessage = '登录失败，请重试！';
      console.log(error);
      message.error(defaultLoginFailureMessage);
    }
  };

  const { status, type: loginType } = userLoginState;

  return (
    <div className={containerClassName}>
      <Helmet>
        <title>
          {'登录'}- {Settings.title}
        </title>
      </Helmet>

      <div
        style={{
          flex: '1',
          padding: '32px 0',
        }}
      >
        <LoginForm
          contentStyle={{
            minWidth: 280,
            maxWidth: '75vw',
          }}
          logo={<img alt="logo" src="/logo.svg" />}
          title="智能 BI"
          subTitle={'智能 BI'}
          initialValues={{
            autoLogin: true,
          }}
          actions={['其他登录方式 :', <ActionIcons key="icons" />]}
          onFinish={async (values) => {
            // 这里 values 会包含当前 tab 下的所有字段
            await handleSubmit(values as any);
          }}
        >
          <Tabs
            activeKey={type}
            onChange={setType}
            centered
            items={[
              {
                key: 'account',
                label: '账户密码登录',
              },
              {
                key: 'mobile',
                label: '手机号登录',
              },
            ]}
          />

          {status === 'error' && loginType === 'account' && (
            <LoginMessage content={'错误的用户名和密码'} />
          )}
          {type === 'account' && (
            <>
              <ProFormText
                name="userAccount"
                fieldProps={{
                  size: 'large',
                  prefix: <UserOutlined />,
                }}
                placeholder={'请输入用户名'}
                rules={[
                  {
                    required: true,
                    message: '用户名是必填项！',
                  },
                ]}
              />
              <ProFormText.Password
                name="userPassword"
                fieldProps={{
                  size: 'large',
                  prefix: <LockOutlined />,
                }}
                placeholder={'请输入密码'}
                rules={[
                  {
                    required: true,
                    message: '密码是必填项！',
                  },
                ]}
              />
            </>
          )}

          {status === 'error' && loginType === 'mobile' && <LoginMessage content="验证码错误" />}
          {type === 'mobile' && (
            <>
              {/* 【修改点】name改为 phone，与后端 UserLoginByPhoneRequest 对应 */}
              <ProFormText
                fieldProps={{
                  size: 'large',
                  prefix: <MobileOutlined />,
                }}
                name="phone"
                placeholder={'请输入手机号！'}
                rules={[
                  {
                    required: true,
                    message: '手机号是必填项！',
                  },
                  {
                    pattern: /^1\d{10}$/,
                    message: '不合法的手机号！',
                  },
                ]}
              />
              {/* 【修改点】name改为 code，与后端对应 */}
              <ProFormCaptcha
                fieldProps={{
                  size: 'large',
                  prefix: <LockOutlined />,
                }}
                captchaProps={{
                  size: 'large',
                }}
                placeholder={'请输入验证码！'}
                captchaTextRender={(timing, count) => {
                  if (timing) {
                    return `${count} ${'秒后重新获取'}`;
                  }
                  return '获取验证码';
                }}
                name="code"
                phoneName="phone" // 关联手机号字段，用于校验手机号是否已填
                rules={[
                  {
                    required: true,
                    message: '验证码是必填项！',
                  },
                ]}
                onGetCaptcha={async (phone) => {
                  // 【修改点】调用真实后端接口
                  try {
                    const res = await getCaptchaUsingGet({ phone });
                    if (res.code === 0 && res.data) {
                      message.success(`验证码获取成功：${res.data}`);
                    } else {
                      throw new Error(res.message || '获取验证码失败');
                    }
                  } catch (error: any) {
                    message.error(error.message);
                    throw error; // 抛出错误以停止倒计时
                  }
                }}
              />
            </>
          )}
          <div
            style={{
              marginBottom: 24,
            }}
          >
            <ProFormCheckbox noStyle name="autoLogin">
              自动登录
            </ProFormCheckbox>
           <div
            style={{
              marginBottom: 24,
            }}
          >
            
            {/* --- 忘记密码弹窗开始 --- */}
            <ModalForm
              title="重置密码"
              trigger={
                <a
                  style={{
                    float: 'right',
                  }}
                >
                  忘记密码 ?
                </a>
              }
              width={500}
              autoFocusFirstInput
              onFinish={async (values) => {
                try {
                  // 调用重置密码接口
                  const res = await userPasswordResetUsingPost({
                    phone: values.phone,
                    code: values.code,
                    newPassword: values.newPassword,
                  });
                  if (res.code === 0 && res.data) {
                    message.success('密码重置成功，请重新登录');
                    return true; // 关闭弹窗
                  } else {
                    throw new Error(res.message);
                  }
                } catch (error: any) {
                  message.error(error.message || '重置失败，请重试');
                  return false; // 阻止关闭
                }
              }}
            >
              <ProFormText
                name="phone"
                fieldProps={{
                  size: 'large',
                  prefix: <MobileOutlined />,
                }}
                placeholder={'请输入手机号'}
                rules={[
                  {
                    required: true,
                    message: '请输入手机号！',
                  },
                  {
                    pattern: /^1\d{10}$/,
                    message: '手机号格式错误！',
                  },
                ]}
              />
              <ProFormCaptcha
                fieldProps={{
                  size: 'large',
                  prefix: <LockOutlined />,
                }}
                captchaProps={{
                  size: 'large',
                }}
                placeholder={'请输入验证码'}
                captchaTextRender={(timing, count) => {
                  return timing ? `${count} ${'秒后重新获取'}` : '获取验证码';
                }}
                name="code"
                phoneName="phone" // 关联上面的 phone 字段
                rules={[
                  {
                    required: true,
                    message: '请输入验证码！',
                  },
                ]}
                onGetCaptcha={async (phone) => {
                  // 复用之前的获取验证码接口
                  const res = await getCaptchaUsingGet({ phone });
                  if (res.code === 0 && res.data) {
                    message.success(`验证码已发送: ${res.data}`); // 生产环境请去掉验证码显示
                  } else {
                    throw new Error(res.message);
                  }
                }}
              />
              <ProFormText.Password
                name="newPassword"
                fieldProps={{
                  size: 'large',
                  prefix: <LockOutlined />,
                }}
                placeholder={'请输入新密码'}
                rules={[
                  {
                    required: true,
                    message: '请输入新密码！',
                  },
                  {
                    min: 8,
                    message: '密码长度不能少于 8 位！',
                  },
                ]}
              />
            </ModalForm>
            {/* --- 忘记密码弹窗结束 --- */}
            
          </div>
          </div>
        </LoginForm>
        <div style={{ textAlign: 'center', marginTop: 16 }}>
          <a href="/user/register">没有账号？立即注册</a>
        </div>
      </div>
      <Footer />
    </div>
  );
};
export default Login;