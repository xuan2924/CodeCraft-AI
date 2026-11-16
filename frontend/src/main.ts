import { createApp } from 'vue';
import { VueQueryPlugin } from '@tanstack/vue-query';
import { createPinia } from 'pinia';
import { installTokens } from './components/ui/tokens';
import './components/ui/ui.css';
import App from './App.vue';
import { router } from './router';
import './style.css';

installTokens(document.documentElement);
createApp(App).use(createPinia()).use(VueQueryPlugin).use(router).mount('#app');
