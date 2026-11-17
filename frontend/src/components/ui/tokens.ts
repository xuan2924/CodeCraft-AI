/** Single color source for application controls, Monaco and the terminal. */
export const colors = {
  background: '#0b0d10',
  surface: '#13171e',
  selected: '#1e2632',
  border: '#232a35',
  controlBorder: '#758296',
  foreground: '#f1f5f9',
  muted: '#94a3b8',
  accent: '#6ee7b7',
  primary: '#f1f5f9',
  primaryForeground: '#0b0d10',
  failure: '#fda4af',
  warning: '#fcd34d',
} as const;

export function installTokens(root: HTMLElement) {
  for (const [name, value] of Object.entries(colors)) {
    root.style.setProperty(`--${name.replace(/[A-Z]/g, (letter) => `-${letter.toLowerCase()}`)}`, value);
  }
}
