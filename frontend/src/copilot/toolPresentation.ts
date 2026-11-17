import { object } from '../api/request';

export interface ToolMeta {
  name: string;
  label: string;
  category: 'read' | 'search' | 'edit' | 'exec' | 'context' | 'delegate' | 'web' | 'other';
}

const TOOL_CATALOG: Record<string, Omit<ToolMeta, 'name'>> = {
  read: { label: '读取文件', category: 'read' },
  view_file: { label: '读取文件', category: 'read' },
  grep: { label: '代码检索', category: 'search' },
  grep_code: { label: '代码检索', category: 'search' },
  find: { label: '查找文件', category: 'search' },
  glob_files: { label: '查找文件', category: 'search' },
  edit: { label: '局部编辑', category: 'edit' },
  write: { label: '写入文件', category: 'edit' },
  propose_changes: { label: '整批提案', category: 'edit' },
  run_tests: { label: '运行测试', category: 'exec' },
  bash: { label: '执行命令', category: 'exec' },
  load_skill: { label: '加载 Skill', category: 'context' },
  read_source: { label: '读取引用', category: 'context' },
  read_evidence: { label: '读取证据', category: 'context' },
  delegate_task: { label: '委派子任务', category: 'delegate' },
  web_search: { label: '联网搜索', category: 'web' },
  web_read: { label: '读取网页', category: 'web' },
};

export function toolMeta(name: string): ToolMeta {
  const entry = TOOL_CATALOG[name];
  return entry ? { name, ...entry } : { name, label: '工具调用', category: 'other' };
}

export interface ParsedToolReceipt {
  toolName: string;
  callId: string;
  meta: ToolMeta;
  rawPayload: string;
  statusCode: string | null;
  statusTone: 'accent' | 'warning' | 'danger' | undefined;
  chips: string[];
  truncated: boolean;
}

const RECEIPT_RE = /^([a-z][a-z0-9_]{0,63}) \[([^\]]+)\]\r?\n([\s\S]*)$/;

export function isStructuredToolReceipt(text: string): boolean {
  return RECEIPT_RE.test(text);
}

function resolveStatusTone(code: string): 'accent' | 'warning' | 'danger' | undefined {
  if (['OK', 'SUCCEEDED', 'APPLIED', 'PASSED'].includes(code)) return 'accent';
  if (['WAITING', 'PENDING', 'INTERRUPTED', 'NOT_EXECUTED', 'APPLYING'].includes(code)) return 'warning';
  if (code) return 'danger';
  return undefined;
}

export function parseToolReceipt(text: string): ParsedToolReceipt | null {
  const match = RECEIPT_RE.exec(text);
  if (!match) return null;
  const toolName = match[1]!;
  const callId = match[2]!;
  const rawPayload = match[3] ?? '';
  const meta = toolMeta(toolName);
  const chips: string[] = [];
  let statusCode: string | null = null;
  let truncated = false;

  try {
    const parsed: unknown = JSON.parse(rawPayload);
    if (object(parsed)) {
      const codeVal = parsed.code ?? parsed.outcomeCode ?? parsed.state ?? parsed.status;
      if (typeof codeVal === 'string' && codeVal) statusCode = codeVal;
      if (typeof parsed.path === 'string' && parsed.path) chips.push(parsed.path);
      if (typeof parsed.pattern === 'string' && parsed.pattern) chips.push(`模式: ${parsed.pattern}`);
      if (typeof parsed.revision === 'string' || typeof parsed.revision === 'number') chips.push(`r${parsed.revision}`);
      if (typeof parsed.baseRevision === 'string' || typeof parsed.baseRevision === 'number') chips.push(`基线 r${parsed.baseRevision}`);
      if (typeof parsed.startLine === 'number' && typeof parsed.endLine === 'number') {
        const total = typeof parsed.totalLines === 'number' ? ` / ${parsed.totalLines}` : '';
        chips.push(`L${parsed.startLine}-${parsed.endLine}${total}`);
      }
      if (typeof parsed.matchCount === 'number') chips.push(`${parsed.matchCount} 处匹配`);
      else if (Array.isArray(parsed.matches)) chips.push(`${parsed.matches.length} 处匹配`);
      if (Array.isArray(parsed.paths)) chips.push(`${parsed.paths.length} 个文件`);
      if (typeof parsed.fileCount === 'number') chips.push(`${parsed.fileCount} 个文件`);
      else if (Array.isArray(parsed.files)) chips.push(`${parsed.files.length} 个文件`);
      if (typeof parsed.mode === 'string' && parsed.mode) chips.push(parsed.mode);
      if (parsed.truncated === true) truncated = true;
    }
  } catch {
    // Plain text payloads are rendered directly without JSON summary chips.
  }

  return {
    toolName,
    callId,
    meta,
    rawPayload,
    statusCode,
    statusTone: statusCode ? resolveStatusTone(statusCode) : undefined,
    chips,
    truncated,
  };
}

export interface AssistantToolCall {
  name: string;
  callId: string;
  label: string;
}

export function splitAssistantToolCalls(text: string): { body: string; calls: AssistantToolCall[] } {
  const marker = '\n工具调用：[';
  const idx = text.lastIndexOf(marker);
  const startsWithMarker = idx === -1 && text.startsWith('工具调用：[');
  if (idx === -1 && !startsWithMarker) return { body: text, calls: [] };

  const start = startsWithMarker ? 0 : idx;
  const listStart = startsWithMarker ? '工具调用：['.length : idx + marker.length;
  if (!text.endsWith(']')) return { body: text, calls: [] };

  const body = text.slice(0, start);
  const inner = text.slice(listStart, -1).trim();
  if (!inner) return { body: text, calls: [] };

  const calls: AssistantToolCall[] = [];
  const itemRe = /([a-z][a-z0-9_]{0,63}) \[([^\]]+)\]/g;
  let found: RegExpExecArray | null;
  while ((found = itemRe.exec(inner)) !== null) {
    const name = found[1]!;
    calls.push({ name, callId: found[2]!, label: toolMeta(name).label });
  }
  if (!calls.length) return { body: text, calls: [] };
  return { body, calls };
}
