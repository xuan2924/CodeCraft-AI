# Order Settlement Engine Workspace Rules

## Numerical & Monetary Integrity
- All monetary values (prices, discounts, line allocations, payable amounts, refunds) MUST be computed strictly in integer cents (`long` or `BigInteger`).
- **Forbidden**: Do NOT use `double`, `float`, or floating-point `Math.floor` / `Math.round` anywhere in `src/main/java/**`.
- Always protect against `long` overflow with `Math.addExact`, `Math.multiplyExact`, or `BigInteger` intermediate products followed by `longValueExact()`.

## Module Boundaries & Public Contract
- Keep all 6 core classes and their public constructors, records, and method signatures backward-compatible:
  - `settlement.OrderSettlementEngine`
  - `settlement.model.OrderLine`
  - `settlement.model.CouponSpec`
  - `settlement.model.SettlementReceipt`
  - `settlement.pricing.DiscountAllocator` (see `src/main/java/settlement/pricing/AGENTS.md`)
  - `settlement.idempotency.SettlementLedger`
- Pricing math belongs in `settlement.pricing`; idempotency & concurrency coordination belongs in `settlement.idempotency`.
- Do NOT edit `pom.xml`, add external Maven dependencies, or introduce symbolic links or binary files.

## Idempotency & Concurrency Discipline
- Same `idempotencyKey` + identical request payload MUST execute the settlement allocation at most once even under concurrent calls.
- Same `idempotencyKey` + different request payload MUST throw `IllegalArgumentException` and never return a cached receipt from different inputs.
- Failed settlement attempts MUST NOT permanently poison the `idempotencyKey`; subsequent retries must be able to execute cleanly.

## Verification
- Place candidate practice unit tests under `src/test/java/**` covering tail-cent conservation, discount ordering, `2^53+` / `Long.MAX_VALUE` precision, failure retries, and multi-threaded concurrency.
