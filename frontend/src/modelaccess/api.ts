import { ApiError, object, ownerHeaders, post, put, remove, request } from '../api/request';

export const protocols = ['OPENAI_CHAT', 'OPENAI_RESPONSES', 'ANTHROPIC'] as const;
export const presets = ['DEEPSEEK', 'GLM', 'KIMI', 'OPENAI', 'CUSTOM'] as const;
export type Protocol = typeof protocols[number];
export type Preset = typeof presets[number];
export interface Connection {
  id: string; name: string; baseUrl: string; protocol: Protocol; preset: Preset;
  version: string; configurationVersion: string; keyConfigured: boolean; deleted: boolean;
}
export interface Model {
  id: string; connectionId: string; name: string; modelId: string; contextWindow: number; maxOutputTokens: number;
  thinkingMode: 'OFF' | 'ON'; reasoningEffort: string | null; thinkingBudgetTokens: number | null; temperature: number | null;
  version: string; configurationVersion: string; windowSource: 'USER_PROVIDED'; deleted: boolean;
}
export interface ConnectionInput {
  name: string; baseUrl: string; protocol: Protocol; preset: Preset; apiKey: string | null; expectedVersion: string | null;
}
export interface ModelInput {
  name: string; modelId: string; contextWindow: number; maxOutputTokens: number; thinkingMode: 'OFF' | 'ON';
  reasoningEffort: string | null; thinkingBudgetTokens: number | null; temperature: number | null; expectedVersion: string | null;
}
export interface Page<T> { items: T[]; nextCursor: string | null }
const groupStates = ['QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'INTERRUPTED', 'SUPERSEDED'] as const;
const itemStates = ['PENDING', 'NOT_RUN', 'RUNNING', 'SUCCEEDED', 'FAILED', 'INTERRUPTED', 'INCOMPLETE'] as const;
export interface Check {
  id: string; modelConfigurationId: string; connectionConfigurationVersion: string; modelConfigurationVersion: string;
  status: typeof groupStates[number]; startedAt: string;
}
export interface CheckItem {
  status: typeof itemStates[number]; completedAt: string | null; validUntil: string | null;
  reused: boolean; valid: boolean; errorCode: string | null;
}
export interface CheckStatus { check: Check; reason: string | null; currentConfiguration: boolean; basic: CheckItem; tool: CheckItem }

function invalid(): never { throw new ApiError(502, 'INVALID_RESPONSE'); }
function record(value: unknown): Record<string, unknown> { if (!object(value)) return invalid(); return value; }
function text(value: unknown, max = 2048): string { if (typeof value !== 'string' || value.length > max) return invalid(); return value; }
function bool(value: unknown): boolean { if (typeof value !== 'boolean') return invalid(); return value; }
function uuid(value: unknown): string {
  const result = text(value, 36);
  if (!/^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(result)) return invalid();
  return result;
}
function version(value: unknown): string { const result = text(value, 19); if (!/^[1-9]\d{0,18}$/.test(result)) return invalid(); return result; }
function integer(value: unknown, max = 2147483647): number {
  if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < 1 || value > max) return invalid(); return value;
}
function choice<T extends string>(value: unknown, options: readonly T[]): T {
  const result = options.find((option) => option === value); if (result === undefined) return invalid(); return result;
}
function nullable<T>(value: unknown, parse: (value: unknown) => T): T | null { return value === null ? null : parse(value); }
function date(value: unknown): string { const result = text(value, 64); if (!Number.isFinite(Date.parse(result))) return invalid(); return result; }
function finite(value: unknown): number { if (typeof value !== 'number' || !Number.isFinite(value)) return invalid(); return value; }
function connection(value: unknown): Connection {
  const item = record(value);
  return { id: uuid(item.id), name: text(item.name, 128), baseUrl: text(item.baseUrl), protocol: choice(item.protocol, protocols),
    preset: choice(item.preset, presets), version: version(item.version), configurationVersion: version(item.configurationVersion),
    keyConfigured: bool(item.keyConfigured), deleted: bool(item.deleted) };
}
function model(value: unknown): Model {
  const item = record(value);
  const result: Model = { id: uuid(item.id), connectionId: uuid(item.connectionId), name: text(item.name, 128), modelId: text(item.modelId, 256),
    contextWindow: integer(item.contextWindow), maxOutputTokens: integer(item.maxOutputTokens, 16384), thinkingMode: choice(item.thinkingMode, ['OFF', 'ON']),
    reasoningEffort: nullable(item.reasoningEffort, (value) => text(value, 32)), thinkingBudgetTokens: nullable(item.thinkingBudgetTokens, integer),
    temperature: nullable(item.temperature, finite), version: version(item.version), configurationVersion: version(item.configurationVersion),
    windowSource: choice(item.windowSource, ['USER_PROVIDED']), deleted: bool(item.deleted) };
  if (result.maxOutputTokens >= result.contextWindow || result.temperature !== null && (result.temperature < 0 || result.temperature > 2)) return invalid();
  return result;
}
function check(value: unknown): Check {
  const item = record(value);
  return { id: uuid(item.id), modelConfigurationId: uuid(item.modelConfigurationId), connectionConfigurationVersion: version(item.connectionConfigurationVersion),
    modelConfigurationVersion: version(item.modelConfigurationVersion), status: choice(item.status, groupStates), startedAt: date(item.startedAt) };
}
function checkItem(value: unknown): CheckItem {
  const item = record(value);
  const result: CheckItem = { status: choice(item.status, itemStates), completedAt: nullable(item.completedAt, date), validUntil: nullable(item.validUntil, date),
    reused: bool(item.reused), valid: bool(item.valid), errorCode: nullable(item.errorCode, (value) => text(value, 128)) };
  if (result.valid && (result.status !== 'SUCCEEDED' || !result.completedAt || !result.validUntil || result.errorCode !== null)) return invalid();
  return result;
}
function checkStatus(value: unknown): CheckStatus {
  const item = record(value);
  const result: CheckStatus = { check: check(item.check), reason: nullable(item.reason, (value) => text(value, 128)), currentConfiguration: bool(item.currentConfiguration),
    basic: checkItem(item.basic), tool: checkItem(item.tool) };
  if (!result.currentConfiguration && (result.basic.valid || result.tool.valid)) return invalid();
  return result;
}
function page<T>(value: unknown, key: string, parse: (value: unknown) => T): Page<T> {
  const item = record(value); const rows = item[key];
  if (!Array.isArray(rows) || rows.length > 20) return invalid();
  return { items: rows.map(parse), nextCursor: nullable(item.nextCursor, uuid) };
}
function path(id: string): string { return encodeURIComponent(uuid(id)); }
function after(cursor: string | null): string { return cursor ? `?after=${path(cursor)}` : ''; }

export async function connections(cursor: string | null, signal: AbortSignal, owner?: string): Promise<Page<Connection>> {
  return page(await request(`/api/model-connections${after(cursor)}`, signal, { headers: ownerHeaders(owner) }), 'connections', connection);
}
export async function getConnection(id: string, signal: AbortSignal, owner?: string): Promise<Connection> {
  const result = connection(await request(`/api/model-connections/${path(id)}`, signal, { headers: ownerHeaders(owner) })); if (result.id !== id) return invalid(); return result;
}
export async function saveConnection(id: string | null, input: ConnectionInput, key: string, signal: AbortSignal, owner?: string): Promise<Connection> {
  const result = connection(await (id ? put(`/api/model-connections/${path(id)}`, input, signal, key, owner) : post('/api/model-connections', input, signal, key, undefined, owner)));
  if (id && result.id !== id || result.deleted) return invalid(); return result;
}
export async function deleteConnection(id: string, expectedVersion: string, key: string, signal: AbortSignal, owner?: string): Promise<Connection> {
  const result = connection(await remove(`/api/model-connections/${path(id)}`, signal, key, { expectedVersion }, owner));
  if (result.id !== id || !result.deleted) return invalid(); return result;
}
export async function models(connectionId: string, cursor: string | null, signal: AbortSignal, owner?: string): Promise<Page<Model>> {
  const result = page(await request(`/api/model-connections/${path(connectionId)}/models${after(cursor)}`, signal, { headers: ownerHeaders(owner) }), 'models', model);
  if (result.items.some((item) => item.connectionId !== connectionId)) return invalid(); return result;
}
export async function getModel(connectionId: string, id: string, signal: AbortSignal, owner?: string): Promise<Model> {
  const result = model(await request(`/api/model-configurations/${path(id)}`, signal, { headers: ownerHeaders(owner) }));
  if (result.id !== id || result.connectionId !== connectionId) return invalid(); return result;
}
export async function saveModel(connectionId: string, id: string | null, input: ModelInput, key: string, signal: AbortSignal, owner?: string): Promise<Model> {
  const result = model(await (id ? put(`/api/model-configurations/${path(id)}`, input, signal, key, owner)
    : post(`/api/model-connections/${path(connectionId)}/models`, input, signal, key, undefined, owner)));
  if (result.connectionId !== connectionId || id && result.id !== id || result.deleted) return invalid(); return result;
}
export async function deleteModel(id: string, expectedVersion: string, key: string, signal: AbortSignal, owner?: string): Promise<Model> {
  const result = model(await remove(`/api/model-configurations/${path(id)}`, signal, key, { expectedVersion }, owner));
  if (result.id !== id || !result.deleted) return invalid(); return result;
}
export async function startCheck(modelId: string, key: string, signal: AbortSignal, owner?: string): Promise<Check> {
  const result = check(await post(`/api/model-configurations/${path(modelId)}/checks`, { feeAcknowledged: true }, signal, key, undefined, owner));
  if (result.modelConfigurationId !== modelId) return invalid(); return result;
}
export async function getCheck(id: string, signal: AbortSignal, owner?: string): Promise<CheckStatus> {
  const result = checkStatus(await request(`/api/model-checks/${path(id)}`, signal, { headers: ownerHeaders(owner) })); if (result.check.id !== id) return invalid(); return result;
}
export async function latestCheck(modelId: string, signal: AbortSignal, owner?: string): Promise<CheckStatus | null> {
  try {
    const result = checkStatus(await request(`/api/model-configurations/${path(modelId)}/checks/latest`, signal, { headers: ownerHeaders(owner) }));
    if (result.check.modelConfigurationId !== modelId) return invalid(); return result;
  } catch (failure) {
    if (failure instanceof ApiError && failure.status === 404 && failure.code === 'MODEL_CHECK_NOT_FOUND') return null;
    throw failure;
  }
}
