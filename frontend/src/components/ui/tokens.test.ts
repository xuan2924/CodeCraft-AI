import { describe, expect, it } from 'vitest';
import { colors, installTokens } from './tokens';

function luminance(hex: string) {
  const channels = [1, 3, 5].map((start) => Number.parseInt(hex.slice(start, start + 2), 16) / 255)
    .map((channel) => channel <= .04045 ? channel / 12.92 : ((channel + .055) / 1.055) ** 2.4);
  return channels[0]! * .2126 + channels[1]! * .7152 + channels[2]! * .0722;
}
function contrast(first: string, second: string) {
  const a = luminance(first), b = luminance(second);
  return (Math.max(a, b) + .05) / (Math.min(a, b) + .05);
}

describe('theme accessibility contract', () => {
  it('keeps body, secondary and semantic text above WCAG AA and controls distinguishable', () => {
    for (const background of [colors.background, colors.surface, colors.selected]) {
      for (const foreground of [colors.foreground, colors.muted, colors.accent, colors.failure, colors.warning]) {
        expect(contrast(foreground, background), `${foreground} on ${background}`).toBeGreaterThanOrEqual(4.5);
      }
    }
    expect(contrast(colors.primary, colors.primaryForeground)).toBeGreaterThanOrEqual(4.5);
    expect(contrast(colors.controlBorder, colors.surface)).toBeGreaterThanOrEqual(3);
    expect(contrast(colors.accent, colors.surface)).toBeGreaterThanOrEqual(3);
  });
  it('installs the same palette as semantic CSS properties, without persisting client data', () => {
    const root = document.createElement('div');
    installTokens(root);
    expect(root.style.getPropertyValue('--background')).toBe('#0b0d10');
    expect(root.style.getPropertyValue('--primary-foreground')).toBe('#0b0d10');
    expect(root.style.getPropertyValue('--control-border')).toBe('#758296');
  });
});
