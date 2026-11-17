<script setup lang="ts">
import { ref } from 'vue';
import { useMediaQuery } from '@vueuse/core';
import { PanelLeftClose, PanelLeftOpen, TerminalSquare } from '@lucide/vue';
import Button from '../components/ui/Button.vue';
import Tooltip from '../components/ui/Tooltip.vue';
import ResizablePanels from '../components/ui/ResizablePanels.vue';

const compact = useMediaQuery('(max-width: 1100px)');
const focused = ref(false);
const tool = ref('commands');
const toolsOpen = ref(true);
const toolTabs = [
  { id: 'commands', label: '命令与历史' },
  { id: 'tests', label: '验证输出' },
  { id: 'storage', label: '数据容量' },
];
</script>

<template>
  <div
    class="workbench"
    :class="{ compact }"
  >
    <div class="workbench-toolbar">
      <div class="toolbar-title">
        <span class="toolbar-dot" />
        <h2>实现工作台</h2>
      </div>
      <Tooltip text="专注模式只收起题目，不停止 AI 任务，不暂停演练。">
        <Button
          variant="ghost"
          size="small"
          :aria-pressed="focused"
          @click="focused = !focused"
        >
          <component
            :is="focused ? PanelLeftOpen : PanelLeftClose"
            :size="14"
            aria-hidden="true"
          />
          <span>{{ focused ? '退出专注，显示全部面板' : '专注实现，收起题目' }}</span>
        </Button>
      </Tooltip>
    </div>
    <div class="workbench-viewport">
      <ResizablePanels
        first-label="题目与文件面板"
        second-label="实现与协作面板"
        resize-label="调整题目面板宽度"
        :direction="compact ? 'vertical' : 'horizontal'"
        :first-size="22"
        :min-size="14"
        :first-hidden="focused"
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
                  second-label="命令与验证终端"
                  resize-label="调整终端面板高度"
                  direction="vertical"
                  :first-size="66"
                  :min-size="15"
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
                          @click="toolsOpen = false"
                        >
                          <TerminalSquare
                            :size="13"
                            aria-hidden="true"
                          />
                          <span>收起终端面板</span>
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
                        <slot
                          v-if="tab.id === 'commands'"
                          name="tools"
                        />
                      </div>
                    </div>
                  </template>
                </ResizablePanels>
                <div
                  v-if="!toolsOpen"
                  class="terminal-collapsed-bar"
                >
                  <Button
                    class="terminal-toggle"
                    variant="ghost"
                    size="small"
                    :aria-expanded="toolsOpen"
                    @click="toolsOpen = true"
                  >
                    <TerminalSquare
                      :size="13"
                      aria-hidden="true"
                    />
                    <span>展开终端面板</span>
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
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
  flex: 1;
  background: var(--oc-bg);
}

.workbench-toolbar {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  min-height: 36px;
  padding: 4px 12px;
  background: var(--oc-bg-subtle);
  border-bottom: 1px solid var(--oc-border);
}

.toolbar-title {
  display: flex;
  align-items: center;
  gap: 8px;
}

.toolbar-dot {
  width: 6px;
  height: 6px;
  border-radius: 999px;
  background: var(--oc-accent);
}

.workbench-toolbar h2 {
  margin: 0;
  font-size: 12.5px;
  color: var(--oc-fg-muted);
  font-weight: 600;
}

.workbench-viewport {
  flex: 1;
  min-height: 0;
  overflow: hidden;
  background: var(--oc-bg);
}

.workbench-viewport > :deep(.ui-splitter) {
  height: 100%;
}

.workbench-viewport :deep(.ui-splitter-handle) {
  position: relative;
  flex: 0 0 5px;
  background: transparent;
}

.workbench-viewport :deep(.ui-splitter-handle::after) {
  content: '';
  display: block;
  width: 1px;
  height: 100%;
  border-radius: 0;
  background: var(--oc-border);
}

.workbench-viewport :deep(.ui-splitter-handle[data-orientation="vertical"]::after) {
  width: 100%;
  height: 1px;
}

.workbench-viewport :deep(.ui-splitter-handle:hover),
.workbench-viewport :deep(.ui-splitter-handle:focus-visible) {
  background: var(--oc-accent-soft);
}

.workbench-viewport :deep(.ui-splitter-handle:hover::after),
.workbench-viewport :deep(.ui-splitter-handle:focus-visible::after) {
  background: var(--oc-accent);
  transform: none;
}

.pane {
  min-width: 0;
  height: 100%;
  overflow: auto;
  overflow-wrap: anywhere;
}

.files,
.assistant {
  background: var(--oc-bg-subtle);
  padding: 0;
}

.editor {
  background: var(--oc-bg);
  padding: 0;
  display: flex;
  flex-direction: column;
}

.editor-stack {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  background: var(--oc-bg);
}

.editor-stack > :deep(.ui-splitter) {
  flex: 1;
  min-height: 0;
}

.tools {
  height: 100%;
  display: flex;
  flex-direction: column;
  background: var(--oc-bg-subtle);
  overflow: hidden;
}

.tools-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 0 10px 0 6px;
  flex-shrink: 0;
  border-bottom: 1px solid var(--oc-border);
  background: var(--oc-surface);
}

.tool-tabs {
  display: flex;
  gap: 2px;
  min-width: 0;
}

.tool-tabs button {
  position: relative;
  padding: 8px 10px;
  background: none;
  border: 0;
  border-bottom: 2px solid transparent;
  color: var(--oc-fg-muted);
  font-size: 12px;
  font-weight: 550;
  cursor: pointer;
  white-space: nowrap;
  transition: color 140ms var(--oc-ease), border-color 140ms var(--oc-ease);
}

.tool-tabs button:hover {
  color: var(--oc-fg);
}

.tool-tabs button[aria-selected="true"] {
  color: var(--oc-fg);
  border-bottom-color: var(--oc-accent);
}

.tool-content {
  padding: 12px;
  overflow: auto;
  min-height: 0;
  flex: 1;
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 12px;
  align-content: start;
}

.terminal-collapsed-bar {
  flex-shrink: 0;
  display: flex;
  justify-content: flex-end;
  padding: 3px 10px;
  border-top: 1px solid var(--oc-border);
  background: var(--oc-surface);
}

.terminal-toggle {
  font-size: 12px;
  min-height: 26px;
  padding: 2px 8px;
}

.compact .workbench-viewport {
  flex: none;
  height: 1650px;
  min-height: 1000px;
}

.compact .pane {
  min-height: 140px;
}
</style>
