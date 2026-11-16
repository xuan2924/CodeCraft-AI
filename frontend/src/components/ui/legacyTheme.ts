import type { GlobalThemeOverrides } from 'naive-ui';
import { colors } from './tokens';

/** Migration-only bridge through Naive's public theme API; remove with the last legacy page. */
export const legacyTheme: GlobalThemeOverrides = {
  common: {
    primaryColor: colors.accent, primaryColorHover: colors.foreground, primaryColorPressed: colors.accent,
    bodyColor: colors.background, cardColor: colors.surface, modalColor: colors.surface,
    popoverColor: colors.surface, inputColor: colors.background,
    textColorBase: colors.foreground, textColor1: colors.foreground, textColor2: colors.foreground,
    textColor3: colors.muted, borderColor: colors.controlBorder,
    errorColor: colors.failure, warningColor: colors.warning,
    fontFamily: '"Microsoft YaHei", "PingFang SC", system-ui, sans-serif', borderRadius: '6px',
  },
};
