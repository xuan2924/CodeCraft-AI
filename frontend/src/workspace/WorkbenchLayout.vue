<script setup lang="ts">
import { ref } from 'vue';
import { useMediaQuery } from '@vueuse/core';
import { ChevronDown, ChevronUp } from '@lucide/vue';
import Button from '../components/ui/Button.vue';
import ResizablePanels from '../components/ui/ResizablePanels.vue';

defineProps<{ leftHidden?: boolean }>();

const compact = useMediaQuery('(max-width: 1100px)');
type ToolTab = 'commands' | 'tests' | 'history' | 'storage';
const tool = ref<ToolTab>('commands');
const toolsOpen = ref(true);
defineExpose({
  openTool(tab: ToolTab) {
    tool.value = tab;
    toolsOpen.value = true;
  },
});
const toolTabs = [
  { id: 'commands', label: '终端' },
  { id: 'tests', label: '验证输出' },
  { id: 'history', label: '变更历史' },
  { id: 'storage', label: '数据容量' },
] as const;
</script>

<template>
  <div
    class="workbench"
    :class="{ compact }"
  >
    <div class="workbench-viewport">
      <ResizablePanels
        first-label="题目与文件面板"
        second-label="实现与协作面板"
        resize-label="调整题目面板宽度"
        :direction="compact ? 'vertical' : 'horizontal'"
        :first-size="20"
        :min-size="14"
        :first-hidden="!!leftHidden"
      >
        <template #first>
          <div class="pane files">
            <slot name="files" />
          </div>
        </template>
        <template #second>
          <ResizablePanels
            first-label="代码与验证面板"
            second-label="AI 协作面板"
            resize-label="调整编辑器与协作面板"
            :direction="compact ? 'vertical' : 'horizontal'"
            :first-size="63"
            :min-size="25"
          >
            <template #first>
              <div class="editor-stack">
                <ResizablePanels
                  first-label="源码编辑器"
                  second-label="终端与输出面板"
                  resize-label="调整终端面板高度"
                  direction="vertical"
                  :first-size="68"
                  :min-size="18"
                  :second-hidden="!toolsOpen"
                >
                  <template #first>
                    <div class="pane editor">
                      <slot name="editor" />
                    </div>
                  </template>
                  <template #second>
                    <div class="tools">
                      <div class="tools-header">
                        <div
                          class="tool-tabs"
                          role="tablist"
                          aria-label="终端面板"
                        >
                          <button
                            v-for="tab in toolTabs"
                            :id="`tool-tab-${tab.id}`"
                            :key="tab.id"
                            type="button"
                            role="tab"
                            :aria-selected="tool === tab.id"
                            :aria-controls="`tool-panel-${tab.id}`"
                            @click="tool = tab.id"
                          >
                            {{ tab.label }}
                          </button>
                        </div>
                        <Button
                          class="terminal-toggle"
                          variant="ghost"
                          size="small"
                          :aria-expanded="toolsOpen"
                          aria-label="收起终端面板"
                          @click="toolsOpen = false"
                        >
                          <ChevronDown
                            :size="14"
                            aria-hidden="true"
                          />
                          <span>收起</span>
                        </Button>
                      </div>
                      <div
                        v-for="tab in toolTabs"
                        v-show="tool === tab.id"
                        :id="`tool-panel-${tab.id}`"
                        :key="tab.id"
                        class="tool-content"
                        role="tabpanel"
                        :aria-labelledby="`tool-tab-${tab.id}`"
                        tabindex="0"
                      >
                        <slot :name="tab.id" />
                      </div>
                    </div>
                  </template>
                </ResizablePanels>
                <div
                  v-if="!toolsOpen"
                  class="terminal-collapsed-bar"
                >
                  <div class="collapsed-tabs">
                    <button
                      v-for="tab in toolTabs"
                      :key="tab.id"
                      type="button"
                      class="collapsed-tab-btn"
                      @click="tool = tab.id; toolsOpen = true"
                    >
                      {{ tab.label }}
                    </button>
                  </div>
                  <Button
                    class="terminal-toggle"
                    variant="ghost"
                    size="small"
                    :aria-expanded="toolsOpen"
                    aria-label="展开终端面板"
                    @click="toolsOpen = true"
                  >
                    <ChevronUp
                      :size="14"
                      aria-hidden="true"
                    />
                    <span>展开终端</span>
                  </Button>
                </div>
              </div>
            </template>
            <template #second>
              <div class="pane assistant">
                <slot name="assistant" />
              </div>
            </template>
          </ResizablePanels>
        </template>
      </ResizablePanels>
    </div>
  </div>
</template>

<style scoped>
.workbench {
  flex: 1;
  min-height: 0;
  min-width: 0;
  display: flex;
  flex-direction: column;
  background: var(--oc-bg);
}

.workbench-viewport {
  flex: 1;
  min-height: 0;
  min-width: 0;
  display: flex;
  overflow: hidden;
}

.pane {
  height: 100%;
  min-height: 0;
  min-width: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: var(--oc-bg);
}

.pane.files {
  background: var(--oc-bg-subtle);
}

.pane.assistant {
  background: var(--oc-bg-subtle);
}

.editor-stack {
  height: 100%;
  min-height: 0;
  min-width: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.tools {
  height: 100%;
  min-height: 0;
  min-width: 0;
  display: flex;
  flex-direction: column;
  background: var(--oc-bg-subtle);
}

.tools-header {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  height: 34px;
  padding: 0 10px;
  border-bottom: 1px solid var(--oc-border);
  background: var(--oc-bg-subtle);
}

.tool-tabs {
  display: flex;
  align-items: center;
  gap: 14px;
  height: 100%;
  min-width: 0;
  overflow-x: auto;
}

.tool-tabs button {
  position: relative;
  height: 100%;
  padding: 0 2px;
  border: 0;
  background: transparent;
  color: var(--oc-fg-muted);
  font-size: 12px;
  font-weight: 500;
  cursor: pointer;
  white-space: nowrap;
  border-bottom: 2px solid transparent;
  transition: color 120ms var(--oc-ease), border-color 120ms var(--oc-ease);
}

.tool-tabs button:hover {
  color: var(--oc-fg);
}

.tool-tabs button[aria-selected="true"] {
  color: var(--oc-fg);
  border-bottom-color: var(--oc-accent);
}

.tool-content {
  flex: 1;
  min-height: 0;
  min-width: 0;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
}

.terminal-toggle {
  flex-shrink: 0;
  font-size: 11.5px;
  min-height: 24px;
  padding: 2px 6px;
}

.terminal-collapsed-bar {
  flex-shrink: 0;
  height: 32px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 0 10px;
  border-top: 1px solid var(--oc-border);
  background: var(--oc-bg-subtle);
}

.collapsed-tabs {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
  overflow-x: auto;
}

.collapsed-tab-btn {
  border: 0;
  background: transparent;
  color: var(--oc-fg-muted);
  font-size: 12px;
  padding: 2px 4px;
  cursor: pointer;
  white-space: nowrap;
}

.collapsed-tab-btn:hover {
  color: var(--oc-fg);
}

.workbench.compact {
  height: auto;
}

.workbench.compact .workbench-viewport {
  display: block;
  overflow: visible;
  height: auto;
}

.workbench.compact :deep(.ui-splitter) {
  display: flex;
  flex-direction: column !important;
  height: auto !important;
}

.workbench.compact :deep(.ui-splitter-panel) {
  flex: none !important;
  width: 100% !important;
  height: auto !important;
  overflow: visible !important;
}

.workbench.compact :deep(.ui-splitter-handle) {
  display: none;
}

.workbench.compact :deep(.pane.files) {
  max-height: 360px;
  overflow: auto;
  border-bottom: 1px solid var(--oc-border);
}

.workbench.compact :deep(.editor-stack) {
  min-height: 460px;
  border-bottom: 1px solid var(--oc-border);
}

.workbench.compact :deep(.pane.assistant) {
  height: 560px;
  min-height: 460px;
}
</style>
