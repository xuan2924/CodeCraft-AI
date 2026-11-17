# 部分退货逆向分摊挑战

保留基础阶段全部行为与公开 API，在 `settlement.OrderSettlementEngine` 上新增公开实例方法：

```java
public long refundOrderLine(String idempotencyKey, String skuId, int refundQuantity)
```

## 逆向分摊与并发规则

1. **只退实付金额，不退优惠部分**：针对已成功结算订单（`idempotencyKey`）中的指定 `skuId`，以该行结算得到的 `LineAllocation.payableCents()` 与 `settledQuantity` 为基准计算部分退货金额，返回本次退还整数分（`long`）。
2. **累计逆向分摊与尾差守恒**：
   - 设该行已累计退货数量为 `r`，本次退货 `q` 件后累计退货数量为 `k = r + q`（`0 < k <= settledQuantity`）。
   - 到 `k` 件为止的累计应退金额为 `cumulative(k) = floor(payableCents * k / settledQuantity)`（必须使用 `BigInteger` 计算，禁止 `double`/`float`，防止中间乘法溢出 `long`）。
   - 本次退货金额为 `cumulative(r + q) - cumulative(r)`。
   - **守恒保证**：无论以何种分批方式（例如分 3 次每次退 1 件，或一次退完）将该行全部 `settledQuantity` 件退完，各次退货金额之和精确等于 `LineAllocation.payableCents()`；整单全部 SKU 退完之和精确等于 `SettlementReceipt.payableTotalCents()`。
3. **参数校验与超退拦截**：
   - `idempotencyKey` 或 `skuId` 为 `null`/空白、`refundQuantity <= 0` 抛出 `IllegalArgumentException`。
   - 指定 `idempotencyKey` 尚未完成有效结算、或订单内不存在对应 `skuId` 抛出 `IllegalArgumentException`。
   - `refundQuantity` 超过该行当前剩余可退数量（`settledQuantity - r`）抛出 `IllegalArgumentException`，且失败尝试不得修改已退数量。
4. **并发退货安全**：多线程并发对同一订单行发起部分退货时，必须保证剩余可退数量扣减与累计退货金额计算的原子性，禁止超退、竞态丢分或跨订单全局锁阻塞。

## 阶段规则与验收

挑战与基础阶段共用剩余时限，不修改已有初始包配置，不重建初始工程；进入后不可退回，但仍可主动结束。验收包含 10 项基础回归测试与 5 项部分退货挑战测试（共 15 项）；破坏基础功能会明确记录为回归。

评分权重仍为正确性与功能完整性 35、代码质量与工程规范 25、测试与验证 20、问题分析与 AI 协作 20，不现场修改权重，不按 AI 用量扣分；结论须关联证据。未参与挑战不扣基础分，平台故障不计为代码失败。
