// Presentation-only entry; no production routes or fixed captcha answers are added.
import { createApp, h } from 'vue';
import { createRouter, createMemoryHistory, RouterView } from 'vue-router';
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query';
import { installTokens } from '../src/components/ui/tokens';
import AccountPage from '../src/auth/AccountPage.vue';
import CatalogPage from '../src/catalog/CatalogPage.vue';
import HistoryPage from '../src/catalog/HistoryPage.vue';
import PlatformAgentsPage from '../src/platformagent/PlatformAgentsPage.vue';
import WorkspacePage from '../src/workspace/WorkspacePage.vue';
import FeedbackPage from '../src/workspace/FeedbackPage.vue';
import ModelSettingsPage from '../src/modelaccess/ModelSettingsPage.vue';
import SkillPage from '../src/skills/SkillPage.vue';
import '../src/style.css';
import '../src/components/ui/ui.css';
installTokens(document.documentElement);
const selectedPage = new URLSearchParams(location.search).get('page');
const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', name: 'account', component: AccountPage },
  { path: '/practice', name: 'catalog', component: CatalogPage }, { path: '/history', name: 'history', component: HistoryPage },
  { path: '/admin/platform-agents', name: 'platform-agents', component: PlatformAgentsPage },
  { path: '/workspace', name: 'workspace', component: WorkspacePage },
  { path: '/models', name: 'models', component: ModelSettingsPage },
  { path: '/skills', name: 'skills', component: SkillPage },
  { path: '/feedback', name: 'feedback', component: FeedbackPage },
  { path: '/report', name: 'report', component: { template: '<p>报告请使用独立报告夹具验证</p>' } }] });
const selectedRoutes: Record<string, string> = { catalog: '/practice', history: '/history', platform: '/admin/platform-agents', workspace: '/workspace', feedback: '/feedback', models: '/models', skills: '/skills' };
await router.push(selectedRoutes[selectedPage ?? ''] ?? '/');
createApp({ render: () => h(RouterView) })
  .use(router).use(VueQueryPlugin, { queryClient: new QueryClient() }).mount('#app');
