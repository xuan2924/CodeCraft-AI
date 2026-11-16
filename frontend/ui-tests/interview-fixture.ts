import { createApp } from 'vue';
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query';
import { installTokens } from '../src/components/ui/tokens';
import InterviewFixture from './InterviewFixture.vue';
import '../src/style.css';
import '../src/components/ui/ui.css';
installTokens(document.documentElement);
createApp(InterviewFixture).use(VueQueryPlugin, { queryClient: new QueryClient() }).mount('#app');
