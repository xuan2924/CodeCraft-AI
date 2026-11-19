import type { ReferencePage } from './referenceApi';

export interface ComposerDraft {
  text: string;
  selectedSkills: string[];
  references: ReferencePage[];
}

const drafts = new Map<string, ComposerDraft>();

export function draftKey(owner: string, exerciseId: string, conversationId?: string): string {
  return `${owner}/${exerciseId}/${conversationId ?? exerciseId}`;
}

export function loadComposerDraft(key: string): ComposerDraft {
  const existing = drafts.get(key);
  if (!existing) return { text: '', selectedSkills: [], references: [] };
  return {
    text: existing.text,
    selectedSkills: [...existing.selectedSkills],
    references: existing.references.map(item => ({ ...item })),
  };
}

export function saveComposerDraft(key: string, draft: ComposerDraft): void {
  if (!draft.text && !draft.selectedSkills.length && !draft.references.length) {
    drafts.delete(key);
    return;
  }
  drafts.set(key, {
    text: draft.text,
    selectedSkills: [...draft.selectedSkills],
    references: draft.references.map(item => ({ ...item })),
  });
}

export function clearOwnerComposerDrafts(owner: string): void {
  const prefix = `${owner}/`;
  for (const key of drafts.keys()) {
    if (key.startsWith(prefix)) drafts.delete(key);
  }
}

export function clearAllComposerDrafts(): void {
  drafts.clear();
}
