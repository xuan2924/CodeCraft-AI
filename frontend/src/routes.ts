/** Application destinations; business components navigate by name, never by duplicated paths. */
export const routes = {
  status: { name: 'status', path: '/' },
  account: { name: 'account', path: '/account' },
  catalog: { name: 'catalog', path: '/practice' },
  history: { name: 'history', path: '/history' },
  workspace: { name: 'workspace', path: '/workspace' },
  report: { name: 'report', path: '/report' },
  feedback: { name: 'feedback', path: '/feedback' },
  models: { name: 'models', path: '/models' },
  platformAgents: { name: 'platform-agents', path: '/admin/platform-agents' },
  adminOperations: { name: 'admin-operations', path: '/admin/operations' },
  skills: { name: 'skills', path: '/skills' },
} as const;
