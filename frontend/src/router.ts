import { createRouter, createWebHistory } from 'vue-router';
import StatusPage from './status/StatusPage.vue';
import AccountPage from './auth/AccountPage.vue';
import WorkspacePage from './workspace/WorkspacePage.vue';
import { routes } from './routes';

export const router = createRouter({ history: createWebHistory(), routes: [
  { ...routes.status, component: StatusPage },
  { ...routes.account, component: AccountPage },
  { ...routes.workspace, component: WorkspacePage },
  { ...routes.report, component: () => import('./workspace/ReportPage.vue'), meta: { modern: true } },
  { ...routes.feedback, component: () => import('./workspace/FeedbackPage.vue'), meta: { modern: true } },
  { ...routes.catalog, component: () => import('./catalog/CatalogPage.vue'), meta: { modern: true } },
  { ...routes.history, component: () => import('./catalog/HistoryPage.vue'), meta: { modern: true } },
  { ...routes.platformAgents, component: () => import('./platformagent/PlatformAgentsPage.vue'), meta: { modern: true } },
  { ...routes.models, component: () => import('./modelaccess/ModelSettingsPage.vue') },
  { ...routes.skills, component: () => import('./skills/SkillPage.vue') },
] });
