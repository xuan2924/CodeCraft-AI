# 03: 多文件工程题目包 order-settlement-engine/1.0.0 与双阶段受控验收

**What to build:** 新增并验证一道贴近真实工程场景的多文件 Java/Maven 题目包 `order-settlement-engine/1.0.0`（订单促销分摊与幂等结算引擎重构），包含 6 个核心业务类、根目录与子目录多层 `AGENTS.md` 项目规范、基础验收测试及独立隔离的部分退货逆向分摊挑战阶段。

**Blocked by:** None (can start immediately).

**Status:** ready-for-agent

- [x] 创建 `problems/order-settlement-engine/1.0.0/`，包含符合 v1 契约的 `manifest.json`、题面 `README.md`、离线 `Dockerfile`、`initial/`、`reference/`、`acceptance/` 与 `challenge/`。
- [x] `initial/` 提供 6 个多文件工程源码（`OrderLine`、`CouponSpec`、`SettlementReceipt`、`DiscountAllocator`、`SettlementLedger`、`OrderSettlementEngine`）及根 `AGENTS.md` 与 `src/main/java/settlement/pricing/AGENTS.md`，初始代码可编译且在 10 项基础权威测试中暴露预设的分摊尾差不守恒与幂等冲突缺陷。
- [x] `reference/` 参考解通过全部 10 项基础验收测试；`challenge/` 提供逐字节一致的 `initial/`、新增部分退货逆向分摊需求 `refundOrderLine`、15 项含基础回归的权威测试及全通过的挑战参考解。
- [x] 通过 `node --test scripts/problem-contract.test.mjs` 契约校验（在测试中纳入新题包验证），确保题包结构、摘要、无符号链接及权限约束全部达标。

## Comments

### 实现与脚本适配说明
- 新增多文件题目包 `problems/order-settlement-engine/1.0.0/`（`schemaVersion: 1`，Java 21 / Maven 3.9.15），包含 6 个可编辑业务类（`settlement.model.{OrderLine,CouponSpec,SettlementReceipt}`、`settlement.pricing.DiscountAllocator`、`settlement.idempotency.SettlementLedger`、`settlement.OrderSettlementEngine`），并在 `initial/` 与 `challenge/initial/` 中放置根目录 `AGENTS.md` 与子目录 `src/main/java/settlement/pricing/AGENTS.md`（全整型分核算、`BigInteger` 防溢出、先比例折后满减、Hamilton 最大余数尾差分配规则）。
- 经协调放开 `scripts/problem-contract.mjs` 与 `scripts/validate-problem.mjs:139` 对单文件 `CartTotal` 的硬编码限制：
  - `scripts/problem-contract.mjs` 泛化为校验 `README.md`、`Dockerfile`、`initial/pom.xml`、`initial/src/main/java/**/*.java`、`reference/src/main/java/**/*.java` 及基于 `manifest.execution.suite` 推导的 `acceptance/<TestClass>.java`，保持对 `cart-total/1.1.0` 的全部原有严格检查。
  - `scripts/validate-problem.mjs` 第 139 行泛化为检查 `/work/target/surefire-reports/TEST-${suiteName}.xml`。
- 在 `scripts/problem-contract.test.mjs` 新增针对 `order-settlement-engine/1.0.0`（含 `challenge` 与多级 `AGENTS.md` 一致性及负向篡改用例）的契约测试。

### 实测验证结果
1. **Node 契约与脚本测试**：
   - `node --test scripts/problem-contract.test.mjs scripts/publication-contract.test.mjs scripts/docker-images.test.mjs`：23/23 通过（0 失败）。
2. **真实 Linux/amd64 Docker 离线受控执行验证（`scripts/validate-problem.mjs`，使用系统临时目录并已清理临时证据，未覆写 `.runtime/problem-publication.json`）**：
   - **新题包 `problems/order-settlement-engine/1.0.0`**（`verdict: local-contract-validation-passed`，`timeoutSeconds: 60`，`cpus: 1`, `memoryMiB: 768`）：
     - 基础阶段（`packageSha256: 676ce97879223ab9297326a8d5b7c4b76268ad387dad35e0b7c345a85833549f`，`imageId: sha256:a715c460c585e6b48c9bda601c6ace93f6bdc2699bc2c837675281ad4a2f689e`）：
       - `isolation`：`exitCode=0`（1066 ms）
       - `initial`：`exitCode=1`，`tests=10, failures=7, errors=0, skipped=0`（22230 ms，peak mem 182,345,728 B）
       - `reference`：`exitCode=0`，`tests=10, failures=0, errors=0, skipped=0`（21955 ms，peak mem 185,634,816 B）
       - `log-overflow` 与 `deadline`（`exitCode=137, timedOut=true, oomKilled=false`）均按契约通过。
     - 挑战阶段（`packageSha256: f5b8552c63e21804b3a5e6abf765d8193d824ef36b9175866abcad87a70ee907`，`imageId: sha256:e8dc1c476ec7c476627235c71fb9e2a3648757804dcedf19e5af45151ff7d3e5`）：
       - `isolation`：`exitCode=0`（1222 ms）
       - `initial`：`exitCode=1`，`tests=15, failures=12, errors=0, skipped=0`（14811 ms，peak mem 191,832,064 B）
       - `reference`：`exitCode=0`，`tests=15, failures=0, errors=0, skipped=0`（11453 ms，peak mem 179,257,344 B）
       - `log-overflow` 与 `deadline`（`exitCode=137, timedOut=true, oomKilled=false`）均按契约通过。
   - **既有题包回归 `problems/cart-total/1.1.0`**（`verdict: local-contract-validation-passed`）：
     - 基础阶段（`packageSha256: 0cb23729a06539cec9841f035afc7cc3d83757291422730614acd0eac4ef2809`，`imageId: sha256:f95008270969f2a58595bffb40af7bd6666a967c72c5b2e2dbb59dff82a5fe0b`）：`initial` `8/6/0/0`，`reference` `8/0/0/0`。
     - 挑战阶段：`initial` `12/10/0/0`，`reference` `12/0/0/0`。

