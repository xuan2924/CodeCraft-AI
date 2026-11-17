<script setup lang="ts">
import { computed, onMounted, onScopeDispose, ref, watch } from 'vue';
import { History, Play, RefreshCw, Square, Terminal as TerminalIcon } from '@lucide/vue';
import type { Terminal } from '@xterm/xterm';
import '@xterm/xterm/css/xterm.css';
import { ApiError } from '../api/request';
import Button from '../components/ui/Button.vue';
import { colors } from '../components/ui/tokens';
import { message, type Exercise } from './api';
import { detail } from './commandApi';
import { reasonLabels, stateLabels, syncLabels } from './commandPresentation';
import * as terminalApi from './terminalApi';

const props = defineProps<{
  owner: string;
  exercise: Exercise;
  credential: string;
  disabled: boolean;
}>();

const emit = defineEmits<{
  locked: [value: boolean];
  changed: [];
  unauthorized: [];
}>();

type ConnectionPhase = 'IDLE' | 'CONNECTING' | 'CONNECTED' | 'SYNCING' | 'CLOSED' | 'ERROR';

const host = ref<globalThis.HTMLDivElement>();
const ruler = ref<globalThis.HTMLSpanElement>();
const phase = ref<ConnectionPhase>('IDLE');
const activeCommandId = ref<string | null>(null);
const cols = ref(80);
const rows = ref(24);
const banner = ref('');
const notice = ref('');
const audits = ref<terminalApi.TerminalAudit[]>([]);
const showAudits = ref(false);
const auditsLoading = ref(false);
const xtermUnavailable = ref(false);

const encoder = new globalThis.TextEncoder();
let term: Terminal | undefined;
let socket: globalThis.WebSocket | undefined;
let observer: globalThis.ResizeObserver | undefined;
let heartbeatTimer: ReturnType<typeof globalThis.setInterval> | undefined;
let controller = new globalThis.AbortController();
let scopeGeneration = 0;
let disposed = false;
let lastExitSummary: { synchronization: string; reason: string; revision: string } | null = null;

const terminalTheme = computed(() => ({
  background: colors.background,
  foreground: colors.foreground,
  cursor: colors.accent,
  selectionBackground: colors.selected,
}));

const activeBusy = computed(() => phase.value === 'CONNECTING' || phase.value === 'CONNECTED' || phase.value === 'SYNCING');
const canLaunch = computed(() => !props.disabled && !!props.credential && props.exercise.revision !== null && !activeBusy.value);
const canSync = computed(() => phase.value === 'CONNECTED' && !!activeCommandId.value);
const canDisconnect = computed(() => phase.value === 'CONNECTING' || phase.value === 'CONNECTED');

const phaseLabel = computed(() => {
  switch (phase.value) {
    case 'IDLE': return '待命 · 点击启动受控容器终端';
    case 'CONNECTING': return '正在初始化 Dev 容器与 PTY…';
    case 'CONNECTED': return '已连接 · Dev CLI 交互会话';
    case 'SYNCING': return '正在停机校验并提交变更…';
    case 'CLOSED': return '会话已结束';
    case 'ERROR': return '终端连接异常';
    default: return '会话已结束';
  }
});

watch(activeBusy, busy => emit('locked', busy), { immediate: true });
watch(terminalTheme, theme => { if (term) term.options.theme = theme; });

function clearSocketTimers() {
  if (heartbeatTimer !== undefined) {
    globalThis.clearInterval(heartbeatTimer);
    heartbeatTimer = undefined;
  }
}

function detachSocket(code = 1000, reason = 'terminal closed') {
  clearSocketTimers();
  const current = socket;
  socket = undefined;
  if (current) {
    current.onopen = null;
    current.onmessage = null;
    current.onerror = null;
    current.onclose = null;
    if (current.readyState === globalThis.WebSocket.CONNECTING || current.readyState === globalThis.WebSocket.OPEN) {
      current.close(code, reason);
    }
  }
}

function resetScope(nextPhase: ConnectionPhase = 'IDLE') {
  scopeGeneration++;
  controller.abort();
  controller = new globalThis.AbortController();
  detachSocket();
  activeCommandId.value = null;
  phase.value = nextPhase;
  banner.value = '';
  notice.value = '';
  lastExitSummary = null;
}

watch(() => `${props.owner}:${props.exercise.workspaceInstanceId}`, () => {
  resetScope('IDLE');
  term?.reset();
  void loadAudits();
}, { flush: 'sync' });

function computeGeometry(): { cols: number; rows: number } {
  const container = host.value;
  if (!container || container.clientWidth <= 0 || container.clientHeight <= 0) return { cols: 80, rows: 24 };
  const sample = ruler.value?.getBoundingClientRect();
  const charW = sample && sample.width > 3 ? sample.width : 7.6;
  const lineH = sample && sample.height > 8 ? sample.height : 17.5;
  return {
    cols: Math.max(40, Math.min(240, Math.floor((container.clientWidth - 8) / charW))),
    rows: Math.max(10, Math.min(80, Math.floor((container.clientHeight - 8) / lineH))),
  };
}

function sendControl(payload: Record<string, unknown>) {
  if (!socket || socket.readyState !== globalThis.WebSocket.OPEN) return;
  socket.send(JSON.stringify(payload));
}

function sendTerminalBytes(data: string) {
  if (!socket || socket.readyState !== globalThis.WebSocket.OPEN || phase.value !== 'CONNECTED') return;
  const bytes = encoder.encode(data);
  const maxChunk = 16384;
  for (let offset = 0; offset < bytes.length; offset += maxChunk) {
    socket.send(bytes.slice(offset, offset + maxChunk));
  }
}

function applyResize() {
  if (disposed || !term || !host.value) return;
  const next = computeGeometry();
  if (next.cols === cols.value && next.rows === rows.value) return;
  cols.value = next.cols;
  rows.value = next.rows;
  term.resize(next.cols, next.rows);
  if (phase.value === 'CONNECTED') {
    sendControl({ type: 'resize', cols: next.cols, rows: next.rows });
  }
}

async function loadAudits() {
  const token = scopeGeneration;
  auditsLoading.value = true;
  try {
    const items = await terminalApi.sessions(props.owner, props.exercise.exerciseId, controller.signal);
    if (!disposed && token === scopeGeneration) audits.value = items;
  } catch {
    if (!disposed && token === scopeGeneration) audits.value = audits.value.slice();
  } finally {
    if (!disposed && token === scopeGeneration) auditsLoading.value = false;
  }
}

function toggleAudits() {
  showAudits.value = !showAudits.value;
  if (showAudits.value) void loadAudits();
}

function handleServerEvent(frame: terminalApi.TerminalServerEvent, token: number) {
  if (disposed || token !== scopeGeneration) return;
  if (frame.type === 'opened') {
    phase.value = 'CONNECTED';
    banner.value = '';
    notice.value = '';
    const size = computeGeometry();
    cols.value = size.cols;
    rows.value = size.rows;
    term?.resize(size.cols, size.rows);
    sendControl({ type: 'resize', cols: size.cols, rows: size.rows });
    term?.focus();
    return;
  }
  if (frame.type === 'error') {
    banner.value = '后端终端网关暂不可用，请检查执行状态。';
    phase.value = 'ERROR';
    detachSocket(1011, 'gateway error');
    void loadAudits();
    return;
  }
  lastExitSummary = { synchronization: frame.synchronization, reason: frame.reason, revision: frame.revision };
  const exitText = frame.hasExitCode && frame.exitCode !== null ? `退出码 ${frame.exitCode}` : '进程已停止';
  const syncText = syncLabels[frame.synchronization] ?? frame.synchronization;
  const reasonText = frame.reason ? `（${reasonLabels[frame.reason] ?? frame.reason}）` : '';
  notice.value = `${stateLabels[frame.state] ?? frame.state} · ${exitText} · ${syncText}${reasonText}`;
  if (frame.synchronization === 'SYNCED') {
    notice.value = `已将受控容器内修改同步为工作区版本 r${frame.revision}，可点击「启动终端」基于新版本重建会话（保留 Maven/npm 缓存）。`;
    emit('changed');
  } else if (frame.synchronization === 'CONFLICT') {
    banner.value = '容器内存在未同步修改，已按草稿安全保留（可在「单次命令」查看 diffs）。';
  }
  phase.value = 'CLOSED';
  activeCommandId.value = null;
  detachSocket();
  void loadAudits();
}

function handleIdentityOrActionError(error: unknown) {
  if (error instanceof ApiError && (error.status === 401 || error.code === 'STALE_CONTROL' || error.code === 'TERMINAL_AUTHORITY_ENDED')) {
    emit('unauthorized');
  }
  banner.value = message(error);
}

async function startTerminal() {
  if (!canLaunch.value || props.exercise.revision === null) return;
  const token = scopeGeneration;
  phase.value = 'CONNECTING';
  banner.value = '';
  notice.value = '';
  lastExitSummary = null;
  try {
    const issued = await terminalApi.issueSession(
      props.owner,
      props.exercise.exerciseId,
      props.exercise.revision,
      globalThis.crypto.randomUUID(),
      props.credential,
      controller.signal,
    );
    if (disposed || token !== scopeGeneration) return;
    activeCommandId.value = issued.commandId;
    term?.reset();
    term?.writeln('\x1b[90m正在建立受控 Dev Container PTY 通道…\x1b[0m');
    const url = terminalApi.terminalWsUrl(issued.webSocketPath, globalThis.window.location);
    const ws = new globalThis.WebSocket(url, [issued.protocol, `ticket.${issued.ticket}`]);
    ws.binaryType = 'arraybuffer';
    socket = ws;

    ws.onopen = () => {
      if (disposed || token !== scopeGeneration || socket !== ws) return;
      clearSocketTimers();
      heartbeatTimer = globalThis.setInterval(() => {
        if (!disposed && token === scopeGeneration && socket === ws && ws.readyState === globalThis.WebSocket.OPEN) {
          sendControl({ type: 'heartbeat' });
        }
      }, 25000);
    };

    ws.onmessage = event => {
      if (disposed || token !== scopeGeneration || socket !== ws) return;
      if (typeof event.data === 'string') {
        const parsed = terminalApi.parseServerMessage(event.data);
        if (parsed) handleServerEvent(parsed, token);
        return;
      }
      if (event.data instanceof ArrayBuffer || Object.prototype.toString.call(event.data) === '[object ArrayBuffer]') {
        term?.write(new Uint8Array(event.data as ArrayBuffer));
      }
    };

    ws.onerror = () => {
      if (disposed || token !== scopeGeneration || socket !== ws) return;
      banner.value = 'WebSocket 终端通道连接失败或被拦截。';
    };

    ws.onclose = event => {
      if (disposed || token !== scopeGeneration || socket !== ws) return;
      clearSocketTimers();
      socket = undefined;
      if (phase.value !== 'CLOSED' && phase.value !== 'ERROR') {
        const hadCommand = activeCommandId.value;
        phase.value = event.wasClean ? 'CLOSED' : 'ERROR';
        activeCommandId.value = null;
        if (!lastExitSummary && !banner.value) {
          notice.value = event.wasClean
            ? '终端会话已断开，未显式同步的修改会作为草稿安全保留。'
            : `终端异常中断（code ${event.code}），可重新启动会话。`;
        }
        if (hadCommand) void refreshAfterDisconnect(hadCommand, token);
      }
      void loadAudits();
    };
  } catch (error) {
    if (disposed || token !== scopeGeneration) return;
    phase.value = 'ERROR';
    activeCommandId.value = null;
    handleIdentityOrActionError(error);
  }
}

async function refreshAfterDisconnect(commandId: string, token: number) {
  try {
    const task = await detail(props.owner, props.exercise.exerciseId, props.exercise.workspaceInstanceId, commandId, controller.signal);
    if (disposed || token !== scopeGeneration) return;
    if (task.synchronization === 'SYNCED' && task.synchronizedRevision) {
      notice.value = `已将改动同步为版本 r${task.synchronizedRevision}。`;
      emit('changed');
    } else if (task.synchronization === 'CONFLICT') {
      banner.value = '容器内存在未同步修改，已作为草稿安全保留，未自动覆盖工作区。';
    }
  } catch {
    // Ignore transient lookup failures; audit drawer retains authoritative history.
  }
}

async function triggerSync() {
  const commandId = activeCommandId.value;
  if (!canSync.value || !commandId) return;
  const token = scopeGeneration;
  phase.value = 'SYNCING';
  banner.value = '';
  notice.value = '正在停止容器进程并校验受保护文件（AGENTS.md / pom.xml）…';
  if (socket && socket.readyState === globalThis.WebSocket.OPEN) {
    sendControl({ type: 'sync' });
    return;
  }
  try {
    const task = await terminalApi.syncSession(
      props.owner,
      props.exercise.exerciseId,
      props.exercise.workspaceInstanceId,
      commandId,
      globalThis.crypto.randomUUID(),
      controller.signal,
    );
    if (disposed || token !== scopeGeneration) return;
    if (task.synchronization === 'SYNCED' && task.synchronizedRevision) {
      notice.value = `同步完成：工作区版本推进至 r${task.synchronizedRevision}。`;
      emit('changed');
    }
    phase.value = 'CLOSED';
    activeCommandId.value = null;
    void loadAudits();
  } catch (error) {
    if (disposed || token !== scopeGeneration) return;
    phase.value = 'ERROR';
    handleIdentityOrActionError(error);
  }
}

function disconnectTerminal() {
  if (!canDisconnect.value) return;
  detachSocket(1000, 'user requested disconnect');
  phase.value = 'CLOSED';
  activeCommandId.value = null;
  notice.value = '已主动断开交互终端，缓存目录保留，未同步改动作为草稿存储。';
  void loadAudits();
}

onMounted(async () => {
  void loadAudits();
  if (!host.value) return;
  try {
    const { Terminal: Xterm } = await import('@xterm/xterm');
    if (disposed || !host.value) return;
    term = new Xterm({
      convertEol: false,
      cursorBlink: true,
      scrollback: 5000,
      cols: 80,
      rows: 24,
      fontSize: 12.5,
      lineHeight: 1.38,
      fontFamily: "'JetBrains Mono', ui-monospace, 'Cascadia Mono', SFMono-Regular, Menlo, monospace",
      theme: terminalTheme.value,
    });
    term.open(host.value);
    term.onData(chunk => sendTerminalBytes(chunk));
    observer = new globalThis.ResizeObserver(() => applyResize());
    observer.observe(host.value);
    applyResize();
  } catch {
    xtermUnavailable.value = true;
    term?.dispose();
    term = undefined;
  }
});

onScopeDispose(() => {
  disposed = true;
  scopeGeneration++;
  controller.abort();
  detachSocket(1000, 'scope disposed');
  observer?.disconnect();
  term?.dispose();
  term = undefined;
  emit('locked', false);
});
</script>

<template>
  <div class="pty-shell">
    <div class="pty-toolbar">
      <div class="pty-status-group">
        <span
          class="pty-dot"
          :data-phase="phase"
          aria-hidden="true"
        />
        <span class="pty-phase-label">{{ phaseLabel }}</span>
        <span class="pty-chip font-mono-num">{{ cols }}×{{ rows }}</span>
        <span
          v-if="exercise.revision !== null"
          class="pty-chip font-mono-num"
        >基线 r{{ exercise.revision }}</span>
        <span
          v-if="activeCommandId"
          class="pty-chip font-mono-num"
        >#{{ activeCommandId.slice(0, 8) }}</span>
      </div>

      <div class="pty-actions">
        <Button
          v-if="!activeBusy"
          type="button"
          size="small"
          variant="primary"
          :disabled="!canLaunch"
          data-testid="pty-start-btn"
          @click="startTerminal"
        >
          <Play
            :size="13"
            aria-hidden="true"
          />
          <span>启动终端</span>
        </Button>
        <Button
          v-if="canSync || phase === 'SYNCING'"
          type="button"
          size="small"
          variant="secondary"
          :disabled="!canSync"
          data-testid="pty-sync-btn"
          @click="triggerSync"
        >
          <RefreshCw
            :size="13"
            aria-hidden="true"
          />
          <span>{{ phase === 'SYNCING' ? '同步中…' : '同步到工作区' }}</span>
        </Button>
        <Button
          v-if="canDisconnect"
          type="button"
          size="small"
          variant="ghost"
          data-testid="pty-stop-btn"
          @click="disconnectTerminal"
        >
          <Square
            :size="12"
            aria-hidden="true"
          />
          <span>断开</span>
        </Button>
        <Button
          type="button"
          size="small"
          variant="ghost"
          data-testid="pty-audit-btn"
          @click="toggleAudits"
        >
          <History
            :size="13"
            aria-hidden="true"
          />
          <span>{{ showAudits ? '收起审计' : `会话审计 (${audits.length})` }}</span>
        </Button>
      </div>
    </div>

    <p
      v-if="banner"
      role="alert"
      class="pty-banner pty-banner-error"
    >
      {{ banner }}
    </p>
    <p
      v-else-if="notice"
      role="status"
      class="pty-banner pty-banner-note"
    >
      {{ notice }}
    </p>
    <p
      v-else-if="phase === 'IDLE'"
      class="pty-banner pty-banner-idle"
    >
      <TerminalIcon
        :size="13"
        aria-hidden="true"
      />
      <span>按需启动独立受控 Dev Container（挂载持久化缓存与主机临时代理凭证）；修改仅在点击「同步到工作区」后写入演练版本号。</span>
    </p>

    <div
      v-if="showAudits"
      class="pty-audit-panel"
    >
      <p
        v-if="auditsLoading && !audits.length"
        class="pty-audit-empty"
      >
        正在加载终端审计历史…
      </p>
      <p
        v-else-if="!audits.length"
        class="pty-audit-empty"
      >
        尚无交互终端会话记录。
      </p>
      <ul
        v-else
        class="pty-audit-list"
      >
        <li
          v-for="item in audits"
          :key="item.commandId"
          class="pty-audit-item"
        >
          <span class="font-mono-num">#{{ item.commandId.slice(0, 8) }}</span>
          <span>{{ stateLabels[item.state] ?? item.state }}</span>
          <span>{{ syncLabels[item.synchronization] ?? item.synchronization }}</span>
          <span class="font-mono-num">
            r{{ item.baseRevision ?? '-' }} → r{{ item.synchronizedRevision ?? '-' }}
          </span>
        </li>
      </ul>
    </div>

    <div class="pty-canvas-wrap">
      <span
        ref="ruler"
        class="pty-ruler font-mono"
        aria-hidden="true"
      >M</span>
      <p
        v-if="xtermUnavailable"
        class="pty-fallback"
      >
        当前浏览器环境无法初始化交互式终端画布。
      </p>
      <div
        ref="host"
        class="pty-canvas"
      />
    </div>
  </div>
</template>

<style scoped>
.pty-shell {
  flex: 1;
  min-height: 0;
  min-width: 0;
  display: flex;
  flex-direction: column;
  background: var(--oc-bg);
}

.pty-toolbar {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 5px 10px;
  border-bottom: 1px solid var(--oc-border);
  background: var(--oc-bg-subtle);
  flex-wrap: wrap;
}

.pty-status-group {
  display: flex;
  align-items: center;
  gap: 7px;
  min-width: 0;
  flex-wrap: wrap;
}

.pty-dot {
  width: 7px;
  height: 7px;
  border-radius: 999px;
  background: var(--oc-fg-dim);
  flex-shrink: 0;
}

.pty-dot[data-phase="CONNECTED"] {
  background: var(--oc-accent);
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--oc-accent) 20%, transparent);
}

.pty-dot[data-phase="CONNECTING"],
.pty-dot[data-phase="SYNCING"] {
  background: var(--oc-warning);
}

.pty-dot[data-phase="ERROR"] {
  background: var(--oc-danger);
}

.pty-phase-label {
  font-size: 12px;
  font-weight: 500;
  color: var(--oc-fg);
}

.pty-chip {
  padding: 1px 6px;
  border: 1px solid var(--oc-border);
  border-radius: 4px;
  font-size: 11px;
  color: var(--oc-fg-muted);
}

.pty-actions {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
}

.pty-banner {
  flex-shrink: 0;
  margin: 0;
  padding: 6px 10px;
  font-size: 11.5px;
  border-bottom: 1px solid var(--oc-border);
  display: flex;
  align-items: center;
  gap: 6px;
}

.pty-banner-idle {
  color: var(--oc-fg-muted);
  background: var(--oc-bg-subtle);
}

.pty-banner-note {
  color: var(--oc-accent);
  background: color-mix(in srgb, var(--oc-accent) 8%, var(--oc-bg));
}

.pty-banner-error {
  color: var(--oc-danger);
  background: color-mix(in srgb, var(--oc-danger) 10%, var(--oc-bg));
}

.pty-audit-panel {
  flex-shrink: 0;
  max-height: 128px;
  overflow-y: auto;
  padding: 6px 10px;
  border-bottom: 1px solid var(--oc-border);
  background: var(--oc-bg-subtle);
}

.pty-audit-empty {
  margin: 0;
  font-size: 11.5px;
  color: var(--oc-fg-dim);
}

.pty-audit-list {
  margin: 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.pty-audit-item {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 11.5px;
  color: var(--oc-fg-muted);
}

.pty-canvas-wrap {
  position: relative;
  flex: 1;
  min-height: 0;
  min-width: 0;
  padding: 4px 6px;
  overflow: hidden;
}

.pty-ruler {
  position: absolute;
  visibility: hidden;
  pointer-events: none;
  font-size: 12.5px;
  line-height: 1.38;
  font-family: 'JetBrains Mono', ui-monospace, 'Cascadia Mono', SFMono-Regular, Menlo, monospace;
}

.pty-fallback {
  margin: 0;
  padding: 10px;
  font-size: 12px;
  color: var(--oc-fg-muted);
}

.pty-canvas {
  width: 100%;
  height: 100%;
}

.pty-canvas :deep(.xterm) {
  height: 100%;
}
</style>
