import Footer from '@/components/Footer';
import {
  getCaptchaUsingGet,
  userRegisterUsingPost as register,
} from '@/services/bi/userController';
import { LockOutlined, MobileOutlined, UserOutlined } from '@ant-design/icons';
import {
  LoginForm,
  ProFormCaptcha,
  ProFormText,
} from '@ant-design/pro-components';
import { useEmotionCss } from '@ant-design/use-emotion-css';
import { Helmet, history } from '@umijs/max';
import { message, Tabs } from 'antd';
import React, { useState } from 'react';
import Settings from '../../../../config/defaultSettings';

const Register: React.FC = () => {
  const [type] = useState<string>('account');
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

  const handleSubmit = async (
    values: API.UserRegisterRequest & { phone: string; code: string },
  ) => {
    try {
      // 注册
      const res = await register({
        ...values,
      });

      if (res.code === 0) {
        message.success('注册成功！');
        // 注册成功后跳转到登录页面
        history.push('/user/login');
        return;
      } else {
        message.error(res.message);
      }
    } catch (error) {
      const defaultRegisterFailureMessage = '注册失败，请重试！';
      console.log(error);
      message.error(defaultRegisterFailureMessage);
    }
  };

  return (
    <div className={containerClassName}>
      <Helmet>
        <title>
          {'注册'} - {Settings.title}
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
          submitter={{
            searchConfig: {
              submitText: '注册',
            },
          }}
          onFinish={async (values) => {
            await handleSubmit(
              values as API.UserRegisterRequest & { phone: string; code: string },
            );
          }}
        >
          <Tabs
            activeKey={type}
            centered
            items={[
              {
                key: 'account',
                label: '账号注册',
              },
            ]}
          />

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
              {
                min: 8,
                message: '密码长度不能少于 8 位！',
              },
            ]}
          />
          <ProFormText.Password
            name="checkPassword"
            fieldProps={{
              size: 'large',
              prefix: <LockOutlined />,
            }}
            placeholder={'请确认密码'}
            rules={[
              {
                required: true,
                message: '确认密码是必填项！',
              },
              ({ getFieldValue }) => ({
                validator(_, value) {
                  if (!value || getFieldValue('userPassword') === value) {
                    return Promise.resolve();
                  }
                  return Promise.reject(new Error('两次输入的密码不一致！'));
                },
              }),
            ]}
          />

          {/* 新增：手机号输入框 */}
          <ProFormText
            fieldProps={{
              size: 'large',
              prefix: <MobileOutlined />,
            }}
            name="phone"
            placeholder={'请输入手机号'}
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

          {/* 新增：验证码输入框 */}
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
              if (timing) {
                return `${count} ${'秒后重新获取'}`;
              }
              return '获取验证码';
            }}
            name="code"
            phoneName="phone" // 关联 phone 字段，确保手机号校验通过后才发送请求
            rules={[
              {
                required: true,
                message: '验证码是必填项！',
              },
            ]}
            onGetCaptcha={async (phone) => {
              try {
                // 调用后端接口获取验证码
                const res = await getCaptchaUsingGet({
                  phone,
                });
                if (res.code === 0 && res.data) {
                  // 这里为了方便测试直接显示了验证码，生产环境建议只提示“已发送”
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
        </LoginForm>
      </div>
      <Footer />
    </div>
  );
};

export default Register;