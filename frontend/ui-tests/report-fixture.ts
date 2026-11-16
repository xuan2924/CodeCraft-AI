import { createApp } from 'vue';
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query';
import { installTokens } from '../src/components/ui/tokens';
import ReportPanel from '../src/workspace/ReportPanel.vue';
import '../src/style.css';
import '../src/components/ui/ui.css';
installTokens(document.documentElement);
createApp(ReportPanel, { owner: 'fixture-owner', exerciseId: 'fixture-exercise' }).use(VueQueryPlugin, { queryClient: new QueryClient() }).mount('#app');
