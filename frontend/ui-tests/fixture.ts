// Isolated presentation fixture, not an API mock or product route.
import { createApp } from 'vue';
import { createPinia } from 'pinia';
import { createMemoryHistory, createRouter } from 'vue-router';
import Fixture from './Fixture.vue';
import { routes } from '../src/routes';
import { installTokens } from '../src/components/ui/tokens';
import '../src/components/ui/ui.css';

installTokens(document.documentElement);
const router = createRouter({ history: createMemoryHistory(), routes: Object.values(routes).map((route) => ({ ...route, component: { template: '<p>展示边界</p>' } })) });
await router.push({ name: routes.workspace.name });
createApp(Fixture).use(createPinia()).use(router).mount('#app');
