/** Single color source for application controls, Monaco and the terminal. */
export const colors = {
  background: '#111612', surface: '#191e19', selected: '#303730',
  border: '#3c443c', controlBorder: '#788574', foreground: '#eeeee5', muted: '#aab2a7',
  accent: '#a0c4a5', primary: '#eeeee5', primaryForeground: '#111612',
  failure: '#ffb4a9', warning: '#e5c27e',
} as const;

export function installTokens(root: HTMLElement) {
  for (const [name, value] of Object.entries(colors)) {
    root.style.setProperty(`--${name.replace(/[A-Z]/g, (letter) => `-${letter.toLowerCase()}`)}`, value);
  }
}
