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
const toolTabs = [{ id: 'commands', label: '命令与历史' }, { id: 'tests', label: '验证输出' }, { id: 'storage', label: '数据容量' }];
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
          variant="secondary"
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
    <p class="workbench-hint">
      题目与文件、编辑与验证、AI 协作相互独立。专注模式不停止 AI 任务，不暂停演练；暂停状态始终显示，AI 停止与审批操作不会被专注模式隐藏。
    </p>
    <div class="workbench-viewport">
      <ResizablePanels
        first-label="题目与文件面板"
        second-label="实现与协作面板"
        resize-label="调整题目面板宽度"
        :direction="compact ? 'vertical' : 'horizontal'"
        :first-size="24"
        :min-size="15"
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
            :first-size="64"
            :min-size="25"
          >
            <template #first>
              <div class="editor-stack">
                <ResizablePanels
                  first-label="源码编辑器"
                  second-label="命令与验证终端"
                  resize-label="调整终端面板高度"
                  direction="vertical"
                  :first-size="64"
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
                <Button
                  class="terminal-toggle"
                  variant="ghost"
                  size="small"
                  :aria-expanded="toolsOpen"
                  @click="toolsOpen = !toolsOpen"
                >
                  <TerminalSquare
                    :size="14"
                    aria-hidden="true"
                  />
                  <span>{{ toolsOpen ? '收起终端面板' : '展开终端面板' }}</span>
                </Button>
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
}

.workbench-toolbar {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  padding: 10px 14px;
  background: var(--oc-surface);
  border: 1px solid var(--oc-border);
  border-bottom: 0;
  border-radius: 12px 12px 0 0;
  box-shadow: var(--oc-hairline-top);
}

.toolbar-title {
  display: flex;
  align-items: center;
  gap: 8px;
}

.toolbar-dot {
  width: 7px;
  height: 7px;
  border-radius: 999px;
  background: var(--oc-accent);
  box-shadow: 0 0 8px var(--oc-accent);
}

.workbench-toolbar h2 {
  margin: 0;
  font-size: 13px;
  color: var(--oc-fg);
  font-weight: 600;
}

.workbench-hint {
  margin: 0;
  padding: 0 14px 10px;
  background: var(--oc-surface);
  border-left: 1px solid var(--oc-border);
  border-right: 1px solid var(--oc-border);
  color: var(--oc-fg-muted);
  font-size: 12px;
  line-height: 1.5;
}

.workbench-viewport {
  flex: 1;
  min-height: 0;
  overflow: hidden;
  border: 1px solid var(--oc-border);
  border-radius: 0 0 12px 12px;
  background: var(--oc-surface);
  box-shadow: var(--oc-shadow-sm);
}

.workbench-viewport > :deep(.ui-splitter) {
  height: 100%;
}

.pane {
  min-width: 0;
  padding: 16px;
  height: 100%;
  overflow: auto;
  overflow-wrap: anywhere;
}

.files,
.assistant {
  background: var(--oc-bg-subtle);
}

.editor {
  background: var(--oc-surface);
}

.editor-stack {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
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

.tool-tabs {
  display: flex;
  gap: 4px;
  padding: 0 12px;
  flex-shrink: 0;
  border-bottom: 1px solid var(--oc-border);
  background: var(--oc-surface);
}

.tool-tabs button {
  position: relative;
  padding: 10px 12px;
  background: none;
  border: 0;
  border-bottom: 2px solid transparent;
  color: var(--oc-fg-muted);
  font-size: 12.5px;
  font-weight: 550;
  cursor: pointer;
  transition: color 160ms var(--oc-ease), border-color 160ms var(--oc-ease);
}

.tool-tabs button:hover {
  color: var(--oc-fg);
}

.tool-tabs button[aria-selected="true"] {
  color: var(--oc-fg);
  border-bottom-color: var(--oc-accent);
}

.tool-content {
  padding: 16px;
  overflow: auto;
  min-height: 0;
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 16px;
}

.terminal-toggle {
  flex-shrink: 0;
  align-self: stretch;
  border-radius: 0;
  border-top: 1px solid var(--oc-border);
  background: var(--oc-surface);
  font-size: 12px;
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
