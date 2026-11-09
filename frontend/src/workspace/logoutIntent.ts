import { readonly, ref } from 'vue';

const owner = ref<string | null>(null);
// Navigation carries intent only; credentials and draft contents stay out of it.
export const saveLogoutOwner = readonly(owner);
export function requestSaveLogout(account: string) { owner.value = account; }
export function cancelSaveLogout() { owner.value = null; }
