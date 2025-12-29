/**
 * @name 代理的配置
 * @see 在生产环境 代理是无法生效的，所以这里没有生产环境的配置
 * -------------------------------
 * The agent cannot take effect in the production environment
 * so there is no configuration of the production environment
 * For details, please see
 * https://pro.ant.design/docs/deploy
 *
 * @doc https://umijs.org/docs/guides/proxy
 */
export default {
  // 🟢 重点：这里我帮你把 dev 的注释取消了，并指向了你的本地后端
  dev: {
    // 意思是：所有以 /api 开头的请求，都会被代理转发
    '/api/': {
      // 1. 指向你本地启动的 Spring Boot 后端地址
      target: 'http://localhost:12345',

      // 2. 配置了这个可以从 http 代理到 https (虽然你本地是 http，但保留 true 没坏处)
      changeOrigin: true,

      // 3. 路径重写 (⚠️关键点，请仔细看下面的说明)
      // 如果你的后端接口本身就是 /api/user/login，请注释掉下面这行 pathRewrite
      // 如果你的后端接口是 /user/login (没有 api 前缀)，请保留下面这行 pathRewrite
      // pathRewrite: { '^/api': '' },
    },
  },

  /**
   * @name 详细的代理配置
   * @doc https://github.com/chimurai/http-proxy-middleware
   */
  test: {
    // ... test 环境配置不用动
    '/api/': {
      target: 'https://proapi.azurewebsites.net',
      changeOrigin: true,
      pathRewrite: { '^': '' },
    },
  },
  pre: {
    '/api/': {
      target: 'your pre url',
      changeOrigin: true,
      pathRewrite: { '^': '' },
    },
  },
};
