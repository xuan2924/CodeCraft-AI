# 工作区一致性决策探针（任务 #4）

独立、可丢弃的 Linux Go 模块，仅验证受控文本夹具，不是正式执行服务、gRPC 实现或生产存储库。无第三方依赖；Go 1.26.5，模块语言版本 1.26.0。

有效方案与限制见 [`docs/design/workspace-execution.md`](../../../docs/design/workspace-execution.md#文件一致性与恢复契约任务-04)，实际证据见 [`docs/validation/workspace-consistency-04.md`](../../../docs/validation/workspace-consistency-04.md)。

## 运行

Linux，Go 1.26.5：

```sh
go test -run TestBatchRevisionAndReceipt -count=1 ./...
go vet ./...
go test -count=1 -timeout=60s ./...
go test -race -count=1 -timeout=60s ./...
```

最后一项需要兼容 C 工具链；本次实际运行的是无 race 的 Linux 测试程序。不要将它标记为 race 通过。

本次 Windows + Git Bash 复现（仓库根目录；使用已经存在的 Maven Linux 镜像作为运行环境，不运行 Maven 或候选人代码）：

```sh
cd backend/prototypes/workspace-consistency
gofmt -l .
GOOS=linux GOARCH=amd64 CGO_ENABLED=0 go vet ./...
GOOS=linux GOARCH=amd64 CGO_ENABLED=0 go test -c -o /tmp/workspace-probe.test
MSYS_NO_PATHCONV=1 docker run --rm --network none \
  --mount "type=bind,source=$(cygpath -w /tmp/workspace-probe.test),target=/probe,readonly" \
  maven:3.9.15-eclipse-temurin-21 \
  /probe -test.v -test.timeout=60s
```

夹具位于容器临时文件系统，测试结束由 Go 清理，容器 `--rm` 回收；测试程序通过只读挂载进入容器。镜像身份记录在验证文档。不挂载仓库、凭证或 Docker socket。

## 接口与布局

- `Open` 只配置服务私有目录、精确可编辑路径及故障 hook，不代表身份校验或业务工作区创建。新目录首次读取为修订 0。
- `Apply` 接受请求 ID、基线和路径→文本/删除的整批变更。
- `Read` 返回当前版本和独立文件集；`Query` 只查询已提交回执。
- `Copy` 将指定当前版本复制到尚不存在、服务控制的目标目录；`Freeze` 在写入锁内持久化只读状态。
- 调用均传入 Context；等锁和发布前检查取消。系统调用本身不能被 Context 抢占，提交之后取消不等于回滚。

```text
工作区私有目录/
├── lock                         # 跨 Store/进程的 flock
├── stage-<随机值>/               # 未发布，读取忽略；崩溃后可能残留
└── commit-00000000000000000002/  # 完整目录原子重命名发布
    ├── manifest.json            # 修订、冻结状态、累计回执和请求指纹
    └── tree/                    # 完整独立文件树，不与旧版本硬链接
```

提交序号不同于内容修订：无变化保存和冻结也要发布回执/状态，但不增加内容修订。读取最大有效命名提交目录；最新目录损坏则报错，不能回退旧版本伪装成功。没有第二个单独更新的 HEAD 文件，避免双提交点。

## 探针限制（禁止直接用于生产）

- Linux 本地文件系统；Windows/junction、NFS、跨卷、分布式锁未实现。官方 `os.Rename` 不保证非 Unix 平台原子性。
- 根目录及父目录、元数据、锁文件、复制目标由服务独占，不能被候选人或容器修改。所有调用必须遵守同一锁。路径防护不是抵抗同权限宿主攻击者的完整存储完整性机制。
- `openat + O_NOFOLLOW` 逐级固定目录描述符，拒绝内部/外部 symlink；`fstat` 拒绝硬链接和特殊文件。FIFO 使用 `O_NONBLOCK` 防止校验前阻塞。链接交换压力测试只证明其执行样本未读取外部内容，不证明任意宿主攻击下的完整性。
- 全量复制和累计回执仅用于小夹具，未限制请求大小、文件总量和历史磁盘占用，没有 GC/配额/准入；不能部署为对外服务。正式实现必须有容量拒绝与历史/回执保留策略。
- 故障 hook 仅测试使用，不是生产配置；`file-written` 返回错误模拟中途 I/O 失败，不代表已实测 ENOSPC。实际 `SIGKILL` 在指定持久化边界执行。
- 未实现存储内容校验和、损坏修复、备份、断电恢复、旧版本读取、授权撤销、gRPC、Windows 运行支持。遗留 stage 不参与读取，但自动清扫尚未实现。
- 回执指纹是规范化 JSON 的 SHA-256，包含基线及完整变更，不包含密码或其他凭证；仅用于请求载荷一致性，不作为授权或内容防篡改机制。
