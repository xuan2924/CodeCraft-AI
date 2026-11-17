# 03: 多文件工程题目包 order-settlement-engine/1.0.0 与双阶段受控验收

**What to build:** 新增并验证一道贴近真实工程场景的多文件 Java/Maven 题目包 `order-settlement-engine/1.0.0`（订单促销分摊与幂等结算引擎重构），包含 6 个核心业务类、根目录与子目录多层 `AGENTS.md` 项目规范、基础验收测试及独立隔离的部分退货逆向分摊挑战阶段。

**Blocked by:** None (can start immediately).

**Status:** ready-for-agent

- [ ] 创建 `problems/order-settlement-engine/1.0.0/`，包含符合 v1 契约的 `manifest.json`、题面 `README.md`、离线 `Dockerfile`、`initial/`、`reference/`、`acceptance/` 与 `challenge/`。
- [ ] `initial/` 提供 6 个多文件工程源码（`OrderLine`、`CouponSpec`、`SettlementReceipt`、`DiscountAllocator`、`SettlementLedger`、`OrderSettlementEngine`）及根 `AGENTS.md` 与 `src/main/java/settlement/pricing/AGENTS.md`，初始代码可编译且在 10 项基础权威测试中暴露预设的分摊尾差不守恒与幂等冲突缺陷。
- [ ] `reference/` 参考解通过全部 10 项基础验收测试；`challenge/` 提供逐字节一致的 `initial/`、新增部分退货逆向分摊需求 `refundOrderLine`、15 项含基础回归的权威测试及全通过的挑战参考解。
- [ ] 通过 `node --test scripts/problem-contract.test.mjs` 契约校验（在测试中纳入新题包验证），确保题包结构、摘要、无符号链接及权限约束全部达标。

## Comments
