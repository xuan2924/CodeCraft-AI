import { defineStore } from 'pinia';
import { ref } from 'vue';

/** Ephemeral UI only. No credentials, identity or server entities enter this store. */
export const useLayoutStore = defineStore('layout', () => {
  const navigationExpanded = ref(false);
  function toggleNavigation() { navigationExpanded.value = !navigationExpanded.value; }
  function closeNavigation() { navigationExpanded.value = false; }
  return { navigationExpanded, toggleNavigation, closeNavigation };
});
