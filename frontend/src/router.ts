import { createRouter, createWebHistory } from 'vue-router';
import StatusPage from './status/StatusPage.vue';
import AccountPage from './auth/AccountPage.vue';
import WorkspacePage from './workspace/WorkspacePage.vue';

export const router = createRouter({ history: createWebHistory(), routes: [
  { path: '/', name: 'status', component: StatusPage },
  { path: '/account', name: 'account', component: AccountPage },
  { path: '/workspace', name: 'workspace', component: WorkspacePage },
  { path: '/models', name: 'models', component: () => import('./modelaccess/ModelSettingsPage.vue') },
] });
