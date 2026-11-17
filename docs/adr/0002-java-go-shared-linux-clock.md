---
status: accepted
---

# ADR-0002：Java 与 Go 统一部署在同一 Linux 时钟域

## 背景

演练准备、文件写入与测试执行涉及跨 Java 和 Go 传递绝对截止时间戳。早期开发采用“Windows 宿主机运行 Java JVM + Docker Desktop Linux VM 运行 Go 沙箱”，实测出现严重的时钟偏差：Java 派发时请求已过期 `624 ms`，Go 接收时仍显示剩余 `119 ms`，导致本应拒绝的过期任务被放行。

## 备选方案

- **维持 Windows JVM + Linux VM 混合开发**：需自行设计跨宿主时钟同步或相对租约补偿协议，复杂度高且与生产部署脱节。
- **Java 与 Go 统一运行在同一 Linux Docker daemon / VM 内**：两端直接共享同一个 Linux 系统 UTC 时钟；Windows 宿主机继续运行 IDE、Vite 前端和浏览器。

## 决策与代价

选择 **Java 与 Go 运行在同一 Linux Docker 时钟域**。

- **收益**：彻底消除双端 OS 时钟漂移导致的截止判定分歧（迁移后原复现用例 10 次全部通过）。
- **代价**：增加了 Java 容器化构建与启动步骤（通过 `scripts/dev.mjs java` 封装）；Windows 本地仍可做静态检查和纯单测，但跨端截止验收必须在同一 Linux 域进行。

## 关键源码

- `compose.yaml`、`scripts/dev.mjs`、`PreparationReceiptValidator.java`
