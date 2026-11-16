// Presentation-only entry; no production routes or fixed captcha answers are added.
import { createApp, h } from 'vue';
import { createRouter, createMemoryHistory, RouterView } from 'vue-router';
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query';
import { darkTheme, NConfigProvider } from 'naive-ui';
import { legacyTheme } from '../src/components/ui/legacyTheme';
import { installTokens } from '../src/components/ui/tokens';
import AccountPage from '../src/auth/AccountPage.vue';
import '../src/style.css';
import '../src/components/ui/ui.css';
installTokens(document.documentElement);
const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', component: AccountPage }] });
await router.push('/');
createApp({ render: () => h(NConfigProvider, { theme: darkTheme, themeOverrides: legacyTheme, class: 'legacy-content' }, { default: () => h(RouterView) }) })
  .use(router).use(VueQueryPlugin, { queryClient: new QueryClient() }).mount('#app');
