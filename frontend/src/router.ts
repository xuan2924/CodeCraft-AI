import { createRouter, createWebHistory } from 'vue-router';
import StatusPage from './status/StatusPage.vue';
import AccountPage from './auth/AccountPage.vue';

export const router = createRouter({ history: createWebHistory(), routes: [
  { path: '/', component: StatusPage },
  { path: '/account', component: AccountPage },
] });
