export const stateLabels: Record<string, string> = { ACCEPTED: '等待执行', STARTING: '正在启动', RUNNING: '运行中', STOPPING: '正在停止',
  EXITED: 'Bash 已退出', STOPPED: '已停止', TIMED_OUT: '执行超时', ENVIRONMENT_ERROR: '执行环境错误', INTERRUPTED: '执行中断' };
export const syncLabels: Record<string, string> = { PENDING: '尚未确认文件同步', SYNCED: '文件已同步', UNCHANGED: '受管理文件未变化',
  CONFLICT: '同步冲突：保留候选修改，未覆盖较新代码', REJECTED: '文件同步被拒绝：未宣称写入成功' };
export const reasonLabels: Record<string, string> = { command_exit: '命令正常返回（退出码不代表题目验收结果）', user_stop: '收到停止请求',
  command_authority_ended: '原执行授权已结束', platform_deadline: '达到固定执行时限', resource_limit_oom: '超过内存限额',
  command_storage_limit: '超过项目副本容量限额', CANCELLED_BEFORE_START: '已封存执行身份，命令未启动' };
