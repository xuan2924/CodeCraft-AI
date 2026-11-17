/** Single color source for application controls, Monaco and the terminal. */
export const colors = {
  background: '#18181b',
  surface: '#1f1f23',
  selected: '#27272a',
  border: '#2e2e34',
  controlBorder: '#758296',
  foreground: '#f4f4f5',
  muted: '#a1a1aa',
  accent: '#6ee7b7',
  primary: '#f4f4f5',
  primaryForeground: '#18181b',
  failure: '#fda4af',
  warning: '#fcd34d',
} as const;

export function installTokens(root: HTMLElement) {
  for (const [name, value] of Object.entries(colors)) {
    root.style.setProperty(`--${name.replace(/[A-Z]/g, (letter) => `-${letter.toLowerCase()}`)}`, value);
  }
}
