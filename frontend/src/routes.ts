/** Application destinations; business components navigate by name, never by duplicated paths. */
export const routes = {
  status: { name: 'status', path: '/' },
  account: { name: 'account', path: '/account' },
  workspace: { name: 'workspace', path: '/workspace' },
  models: { name: 'models', path: '/models' },
  skills: { name: 'skills', path: '/skills' },
} as const;
