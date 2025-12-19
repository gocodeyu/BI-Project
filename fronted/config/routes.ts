export default [
  { path: '/user', layout: false, routes: [{ path: '/user/login', component: './User/Login' }, { path: '/user/register', component: './User/Register' }] },
  {path:'/',redirect:'/add_chart'},
  {name:'添加图表',icon:'smile',path:'/add_chart',component:'./AddChart',layout: false},
  { path: '/welcome', icon: 'smile', component: './Welcome' },
  {
    path: '/admin',
    icon: 'crown',
    access: 'canAdmin',
    routes: [
      { path: '/admin', name: '管理页面', redirect: '/admin/sub-page' },
      { path: '/admin/sub-page', name: '管理页面2', component: './Admin' },
    ],
  },
  { icon: 'table', path: '/list', component: './TableList' },
  { path: '/', redirect: '/welcome' },
  { path: '*', layout: false, component: './404' },
];
