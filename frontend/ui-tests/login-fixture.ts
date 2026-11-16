// Presentation-only entry; no production routes or fixed captcha answers are added.
import { createApp, h } from 'vue';
import { createRouter, createMemoryHistory, RouterView } from 'vue-router';
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query';
import { darkTheme, NConfigProvider } from 'naive-ui';
import { legacyTheme } from '../src/components/ui/legacyTheme';
import { installTokens } from '../src/components/ui/tokens';
import AccountPage from '../src/auth/AccountPage.vue';
import CatalogPage from '../src/catalog/CatalogPage.vue';
import HistoryPage from '../src/catalog/HistoryPage.vue';
import '../src/style.css';
import '../src/components/ui/ui.css';
installTokens(document.documentElement);
const selectedPage = new URLSearchParams(location.search).get('page');
const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', name: 'account', component: AccountPage },
  { path: '/practice', name: 'catalog', component: CatalogPage }, { path: '/history', name: 'history', component: HistoryPage },
  { path: '/workspace', name: 'workspace', component: { template: '<p>工作区展示边界</p>' } }] });
await router.push(selectedPage === 'catalog' ? '/practice' : selectedPage === 'history' ? '/history' : '/');
createApp({ render: () => h(NConfigProvider, { theme: darkTheme, themeOverrides: legacyTheme, class: selectedPage ? '' : 'legacy-content' }, { default: () => h(RouterView) }) })
  .use(router).use(VueQueryPlugin, { queryClient: new QueryClient() }).mount('#app');
