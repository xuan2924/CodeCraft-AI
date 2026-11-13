import { onScopeDispose, ref, watch, type Ref } from 'vue';
import { useQueryClient, type QueryKey } from '@tanstack/vue-query';
import { ApiError } from '../api/request';
import { latestTaskSnapshot, testTask, watchTask, type TaskEvent, type TestTask } from './taskApi';

/** The query cache owns server state. The only local buffer is a bounded render batch. */
export function useTaskStream(task: Ref<TestTask | undefined>, key: (id: string) => QueryKey) {
  const client = useQueryClient();
  const status = ref('等待任务快照');
  let cleanup = () => {};
  watch(() => `${task.value?.taskId ?? ''}:${!!task.value?.cursor}`, () => {
    cleanup();
    const initial = task.value;
    if (!initial?.cursor || !initial.sequence) return;
    const controller = new AbortController();
    let close = () => {};
    let pending: TaskEvent[] = [];
    let bytes = 0;
    let last = BigInt(initial.sequence);
    let flushTimer: ReturnType<typeof setTimeout> | undefined;
    let retryTimer: ReturnType<typeof setTimeout> | undefined;
    let recovering = false;
    let failures = 0;
    const active = () => !controller.signal.aborted;
    function clearBatch() {
      clearTimeout(flushTimer); flushTimer = undefined; pending = []; bytes = 0;
    }
    async function recover() {
      if (!active() || recovering) return;
      recovering = true; close(); clearBatch();
      status.value = '连接中断或日志断档，正在恢复快照；不会重新启动任务。';
      try {
        const snapshot = await testTask(initial!.exerciseId, initial!.workspaceInstanceId, initial!.taskId, controller.signal);
        if (!active()) return;
        await client.cancelQueries({ queryKey: key(snapshot.taskId), exact: true });
        if (!active()) return;
        const current = client.setQueryData<TestTask>(key(snapshot.taskId), (previous) => latestTaskSnapshot(previous, snapshot));
        recovering = false;
        connect(current ?? snapshot);
      } catch (error) {
        if (!active()) return;
        recovering = false;
        if (error instanceof ApiError && ([401, 403, 404].includes(error.status) || error.code === 'TEST_HISTORY_PRUNED')) {
          status.value = error.code === 'TEST_HISTORY_PRUNED' ? '较早测试记录已精简，订阅已关闭。' : '订阅已关闭，请重新登录或确认资源访问权限。';
          client.removeQueries({ queryKey: key(initial!.taskId), exact: true });
          return;
        }
        status.value = '日志连接暂不可用，保留的结果可能不是最新状态。';
        retryTimer = setTimeout(() => { retryTimer = undefined; void recover(); }, Math.min(1000 * 2 ** Math.min(failures++, 4), 15000));
      }
    }
    function flush() {
      flushTimer = undefined;
      if (!active() || recovering) return;
      const batch = pending; pending = []; bytes = 0;
      let terminal = false;
      client.setQueryData<TestTask>(key(initial!.taskId), (current) => {
        if (!current || current.taskId !== initial!.taskId) return current;
        let next = current;
        for (const event of batch) {
          if (next.sequence && BigInt(event.sequence) <= BigInt(next.sequence)) continue;
          if (event.type === 'progress') {
            next = { ...next, ...(event.payload.replaceLogs ? { logs: [] } : {}),
              ...(['ACCEPTED', 'STARTING', 'RUNNING', 'STOPPING'].includes(event.payload.state ?? '') ? { state: event.payload.state! } : {}) };
          } else if (event.type === 'log') {
            const logs = [...next.logs, { channel: event.payload.channel!, text: event.payload.data! }];
            let retained = logs.reduce((sum, log) => sum + log.text.length, 0);
            let clipped = false;
            while (logs.length > 4096 || retained > 1048576) { retained -= logs.shift()!.text.length; clipped = true; }
            next = { ...next, logs, logsIncomplete: next.logsIncomplete || clipped };
          } else if (event.type === 'log_truncated') {
            next = { ...next, droppedBytes: event.payload.droppedBytes!, logsIncomplete: true };
          } else terminal = true;
          next = { ...next, sequence: event.sequence, cursor: event.cursor };
        }
        return next;
      });
      if (terminal) void recover(); // Fetch evidence/exit status; never grade console text.
    }
    function connect(snapshot: TestTask) {
      if (!active() || !snapshot.cursor || snapshot.sequence === null) return;
      last = BigInt(snapshot.sequence);
      status.value = '实时日志已连接；断开不会停止任务。';
      close = watchTask(snapshot, (event) => {
        if (!active() || recovering) return;
        const sequence = BigInt(event.sequence);
        if (sequence <= last) return;
        if (sequence !== last + 1n) { void recover(); return; }
        bytes += (event.payload.data?.length ?? 0) * 3 + 512;
        if (bytes > 262144) { void recover(); return; }
        last = sequence; pending.push(event);
        if (!flushTimer) flushTimer = setTimeout(flush, 100);
      }, () => {
        if (!active() || recovering || retryTimer) return;
        close();
        retryTimer = setTimeout(() => { retryTimer = undefined; void recover(); }, 250);
      }, controller.signal);
    }
    connect(initial);
    cleanup = () => { controller.abort(); close(); clearBatch(); clearTimeout(retryTimer); };
  }, { immediate: true });
  onScopeDispose(() => cleanup());
  return { status };
}
