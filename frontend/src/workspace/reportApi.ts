import { ApiError, object, ownerHeaders, post, request, download } from '../api/request';
export interface Evidence { id: string; kind: string; source: string; summary: string; truncated: boolean }
export interface Dimension { key: string; score: number | null; feedback: string; evidenceIds: string[] }
export interface TimelineEntry { timestamp: string; kind: string; title: string; detail?: string | null; revision?: string | null; sourceId?: string | null }
export interface HintUsage { topic: string; maxLevel: number }
export interface ContextTrace {
  referenceCount: number;
  skillLoadCount: number;
  steerCount: number;
  btwTaskCount: number;
  interviewerClarifyCount: number;
  interviewerDefenseCount: number;
  hintsUsed?: HintUsage[];
}
export interface Report {
  exerciseId: string; reportId: string | null; generation: number; state: string; finalRevision: string | null; verification: string | null;
  finalTestId: string | null; reviewSkipped: boolean | null; platformVersionId: string | null; attempts: number; reason: string | null;
  evaluation: { verification: string; total: number | null; conclusion: string; dimensions: Dimension[] } | null; evidence: Evidence[];
  timeline?: TimelineEntry[]; contextTrace?: ContextTrace;
}
const weights: Record<string, number> = { CORRECTNESS: 35, QUALITY: 25, VALIDATION: 20, COLLABORATION: 20 };
const invalid = (): never => { throw new ApiError(502, 'INVALID_RESPONSE'); };
const nullableText = (value: unknown): value is string | null => value === null || typeof value === 'string';
const nonNegativeInt = (value: unknown): value is number => typeof value === 'number' && Number.isSafeInteger(value) && value >= 0;
const score = (value: unknown): value is number | null => value === null || typeof value === 'number' && Number.isInteger(value) && value >= 0 && value <= 100;
function evidence(value: unknown): Evidence {
  if (!object(value) || typeof value.id !== 'string' || typeof value.kind !== 'string' || typeof value.source !== 'string' || typeof value.summary !== 'string' || typeof value.truncated !== 'boolean') return invalid();
  return { id: value.id, kind: value.kind, source: value.source, summary: value.summary, truncated: value.truncated };
}
function parseTimeline(raw: unknown): TimelineEntry[] | undefined {
  if (raw === undefined || raw === null) return undefined;
  if (!Array.isArray(raw)) return invalid();
  return raw.map((item: unknown): TimelineEntry => {
    if (!object(item) || typeof item.timestamp !== 'string' || !Number.isFinite(Date.parse(item.timestamp))
      || typeof item.kind !== 'string' || !item.kind || typeof item.title !== 'string'
      || (item.detail !== undefined && !nullableText(item.detail))
      || (item.revision !== undefined && item.revision !== null && (typeof item.revision !== 'string' || !/^\d+$/.test(item.revision)))
      || (item.sourceId !== undefined && !nullableText(item.sourceId))) return invalid();
    return { timestamp: item.timestamp, kind: item.kind, title: item.title, detail: item.detail ?? null, revision: item.revision ?? null, sourceId: item.sourceId ?? null };
  });
}
function parseContextTrace(raw: unknown): ContextTrace | undefined {
  if (raw === undefined || raw === null) return undefined;
  if (!object(raw) || !nonNegativeInt(raw.referenceCount) || !nonNegativeInt(raw.skillLoadCount)
    || !nonNegativeInt(raw.steerCount) || !nonNegativeInt(raw.btwTaskCount)
    || !nonNegativeInt(raw.interviewerClarifyCount) || !nonNegativeInt(raw.interviewerDefenseCount)) return invalid();
  let hintsUsed: HintUsage[] = [];
  if (raw.hintsUsed !== undefined && raw.hintsUsed !== null) {
    if (!Array.isArray(raw.hintsUsed)) return invalid();
    hintsUsed = raw.hintsUsed.map((item: unknown): HintUsage => {
      if (!object(item) || typeof item.topic !== 'string' || !item.topic
        || typeof item.maxLevel !== 'number' || !Number.isInteger(item.maxLevel) || item.maxLevel < 1 || item.maxLevel > 3) return invalid();
      return { topic: item.topic, maxLevel: item.maxLevel };
    });
  }
  return {
    referenceCount: raw.referenceCount,
    skillLoadCount: raw.skillLoadCount,
    steerCount: raw.steerCount,
    btwTaskCount: raw.btwTaskCount,
    interviewerClarifyCount: raw.interviewerClarifyCount,
    interviewerDefenseCount: raw.interviewerDefenseCount,
    hintsUsed,
  };
}
export function parseReport(value: unknown): Report {
  if (!object(value) || typeof value.exerciseId !== 'string' || !nullableText(value.reportId) || typeof value.generation !== 'number' || !Number.isSafeInteger(value.generation) || value.generation < 0
    || typeof value.state !== 'string' || !['WAITING_REVIEW', 'NOT_READY', 'QUEUED', 'PREPARING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'UNKNOWN'].includes(value.state)
    || !nullableText(value.finalRevision) || value.finalRevision !== null && !/^\d+$/.test(value.finalRevision) || !nullableText(value.verification) || !nullableText(value.finalTestId) || !nullableText(value.platformVersionId) || !nullableText(value.reason)
    || !(value.reviewSkipped === null || typeof value.reviewSkipped === 'boolean') || typeof value.attempts !== 'number' || !Number.isInteger(value.attempts) || value.attempts < 0 || value.attempts > 3 || !Array.isArray(value.evidence)) return invalid();
  let evaluation: Report['evaluation'] = null;
  if (value.evaluation !== null) {
    const item = value.evaluation;
    if (!object(item) || typeof item.verification !== 'string' || typeof item.conclusion !== 'string' || !score(item.total) || !Array.isArray(item.dimensions)) return invalid();
    const dimensions = item.dimensions.map((dimension: unknown): Dimension => {
      if (!object(dimension) || typeof dimension.key !== 'string' || !Object.hasOwn(weights, dimension.key) || !score(dimension.score) || dimension.score !== null && dimension.score > weights[dimension.key]! || typeof dimension.feedback !== 'string' || !Array.isArray(dimension.evidenceIds) || !dimension.evidenceIds.every((id: unknown) => typeof id === 'string')) return invalid();
      return { key: dimension.key, score: dimension.score, feedback: dimension.feedback, evidenceIds: dimension.evidenceIds };
    });
    if (dimensions.length !== 4 || new Set(dimensions.map(dimension => dimension.key)).size !== 4 || item.verification !== value.verification) return invalid();
    if (item.total !== null && (dimensions.some(dimension => dimension.score === null) || ['UNAVAILABLE', 'PENDING'].includes(item.verification)
      || dimensions.reduce((sum, dimension) => sum + (dimension.score ?? 0), 0) !== item.total)) return invalid();
    evaluation = { verification: item.verification, total: item.total, conclusion: item.conclusion, dimensions };
  }
  const timeline = parseTimeline(value.timeline);
  const contextTrace = parseContextTrace(value.contextTrace);
  return {
    exerciseId: value.exerciseId, reportId: value.reportId, generation: value.generation, state: value.state, finalRevision: value.finalRevision, verification: value.verification, finalTestId: value.finalTestId,
    reviewSkipped: value.reviewSkipped, platformVersionId: value.platformVersionId, attempts: value.attempts, reason: value.reason, evaluation, evidence: value.evidence.map(evidence),
    ...(timeline !== undefined ? { timeline } : {}),
    ...(contextTrace !== undefined ? { contextTrace } : {}),
  };
}
const path = (exercise: string) => `/api/exercises/${encodeURIComponent(exercise)}/report`;
export async function read(owner: string, exercise: string, signal: AbortSignal): Promise<Report> { return parseReport(await request(path(exercise), signal, { headers: ownerHeaders(owner) })); }
export async function review(owner: string, exercise: string, skip: boolean, text: string, key: string, signal: AbortSignal): Promise<Report> {
  return parseReport(await post(`${path(exercise)}/review`, { skip, text }, signal, key, undefined, owner));
}
export async function retry(owner: string, exercise: string, generation: number, key: string, signal: AbortSignal): Promise<Report> {
  return parseReport(await post(`${path(exercise)}/retry`, { expectedGeneration: generation }, signal, key, undefined, owner));
}
export async function detail(owner: string, exercise: string, generation: number, id: string, signal: AbortSignal): Promise<Evidence & { content: string }> {
  const value = await request(`${path(exercise)}/evidence?generation=${generation}&id=${encodeURIComponent(id)}`, signal, { headers: ownerHeaders(owner) });
  if (!object(value) || typeof value.content !== 'string') return invalid();
  return { ...evidence(value), content: value.content };
}
export function pdf(owner: string, exercise: string, generation: number, signal: AbortSignal): Promise<Blob> {
  return download(`${path(exercise)}/pdf?generation=${generation}`, signal, owner, 'application/pdf');
}
