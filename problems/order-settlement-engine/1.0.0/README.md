# 订单促销分摊与幂等结算引擎 1.0.0

60 分钟；基础阶段通过后可主动进入一轮预设挑战（部分退货逆向分摊），共用剩余时限，未参与不扣基础分。

重构并修复包 `settlement` 下的多文件结算引擎，保留全部公开类、记录与方法签名：

- `settlement.OrderSettlementEngine`：`settle(String idempotencyKey, List<OrderLine> lines, CouponSpec coupon)` 及两个公开构造器。
- `settlement.model.OrderLine(String skuId, long unitPriceCents, int quantity)`（含 `grossAmountCents()`）
- `settlement.model.CouponSpec(int percentOffBasisPoints, long thresholdCents, long thresholdDiscountCents)`（含 `CouponSpec.none()`）
- `settlement.model.SettlementReceipt`（含嵌套 `LineAllocation`）
- `settlement.pricing.DiscountAllocator`：`allocate(String idempotencyKey, List<OrderLine> lines, CouponSpec coupon)`
- `settlement.idempotency.SettlementLedger`：`executeIdempotent(String idempotencyKey, String requestFingerprint, Supplier<SettlementReceipt> action)` 与 `find(String idempotencyKey)`

## 业务与精度规则

1. **全整型分核算**：所有金额（单价、优惠、分摊、实付）均使用整数“分”（`long` / `BigInteger`），禁止使用 `double` / `float` 进行金额计算或比例分摊，并防止 `long` 乘法/加法溢出（溢出抛出 `ArithmeticException`）。
2. **输入与重复校验**：
   - `lines` 为 `null`、元素为 `null` 或 `coupon` 为 `null` 抛出 `NullPointerException`。
   - `idempotencyKey` 为 `null` 或空白、`lines` 为空、同一订单内存在重复 `skuId` 抛出 `IllegalArgumentException`。
   - 单价 `< 0`、数量 `<= 0`、折扣基点不在 `[0, 10000]`、满减参数非法均抛出 `IllegalArgumentException`。
3. **折扣顺序（先比例折，后满减）**：
   - 先计算订单毛总额 `grossTotalCents = sum(line.grossAmountCents())`。
   - 订单级比例折扣：`percentDiscountCents = floor(grossTotalCents * percentOffBasisPoints / 10000)`（使用 `BigInteger` 避免中间乘法溢出）。
   - 按各行毛额比例将 `percentDiscountCents` 分摊至各行。
   - 用**折后小计** `afterPercentSubtotal = grossTotalCents - percentDiscountCents` 判断是否满足 `thresholdCents`（`thresholdCents > 0 && afterPercentSubtotal >= thresholdCents`）；满足则满减金额为 `Math.min(thresholdDiscountCents, afterPercentSubtotal)`，否则为 `0`。
   - 按各行**比例折后金额**（`lineGross - linePercentDiscount`）比例将 `thresholdDiscountCents` 分摊至各行。
4. **最大余数法（Hamilton）尾差守恒分摊**：
   - 将总折扣 `D` 按各行权重 `w_i`（`W = sum(w_i)`）分摊时，若 `D == 0` 或 `W == 0`，所有行分摊 `0`。
   - 否则每行基础分摊为 `floor(D * w_i / W)`，精确余数分子为 `(D * w_i) mod W`。
   - 将剩余尾差 `D - sum(base_i)` 逐分分配给精确余数分子最大的前若干行；若余数相同，依次按**权重 `w_i` 降序**、**订单行下标 `i` 升序**打破平局。
   - 守恒不变量：`sum(line.percentDiscountCents) == percentDiscountCents`、`sum(line.thresholdDiscountCents) == thresholdDiscountCents`、`sum(line.payableCents) == payableTotalCents`，且权重为 0 的免费行永远不分摊任何折扣（`payableCents >= 0`）。
5. **并发安全与幂等账本**：
   - 同一 `idempotencyKey` 携带完全一致的请求参数（含行序列与优惠券）时，即使高并发同时请求，底层结算逻辑也只执行一次，所有并发与重放调用返回相同的 `SettlementReceipt`。
   - 同一 `idempotencyKey` 携带不同请求参数（订单行或优惠券任一字段不同）必须拒绝并抛出 `IllegalArgumentException`，不得返回旧凭据。
   - 若某次结算执行抛出异常失败，不得将该 `idempotencyKey` 永久卡死在失败或进行中状态；后续使用该幂等键重试必须能够正常执行并成功。

## 工程边界与验证

可编辑 `src/main/java/**`、`src/test/java/**` 中的文本文件；工作区根目录 `AGENTS.md` 与子目录 `src/main/java/settlement/pricing/AGENTS.md` 提供了工程约束与分摊规范；不允许链接、上传二进制或修改 `pom.xml`。可自行在 `src/test/java` 编写练习测试，但平台 `base` 仅使用独立只读 `acceptance/` 权威测试判定。

`initial/` 是准备工作区的唯一来源；`reference/` 只用于维护者发布验证，**不分发给作答者，不打入执行镜像**。初始代码可编译，10 项基础权威测试中预期 7 项失败；参考解应全部通过。评价按正确性与功能完整性 35、代码质量与工程规范 25、测试与验证 20、问题分析与 AI 协作 20，总计 100 分；每项结论须引用真实证据，不按 AI 用量扣分。
