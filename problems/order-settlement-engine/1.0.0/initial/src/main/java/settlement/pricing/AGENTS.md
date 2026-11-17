# Pricing & Discount Allocation Rules (`settlement.pricing`)

These rules refine the workspace root `AGENTS.md` for all code under `src/main/java/settlement/pricing/`.

## 1. Strict Integer Arithmetic (Zero Floating-Point)
- `double` and `float` are strictly prohibited in `DiscountAllocator` and any helper methods under `settlement.pricing`.
- Compute intermediate products (`amount * basisPoints`, `discount * weight`) with `BigInteger` so inputs near `Long.MAX_VALUE` or above `2^53` never overflow or lose precision.

## 2. Discount Ordering (Percentage First, Threshold Second)
1. Sum all line gross amounts (`line.grossAmountCents()`) with `Math.addExact` to obtain `grossTotalCents`.
2. Apply percentage discount first:
   `percentDiscountCents = floor(grossTotalCents * percentOffBasisPoints / 10000)`.
3. Proportionally allocate `percentDiscountCents` across lines weighted by each line's `grossAmountCents()`.
4. Compute post-percentage subtotal: `afterPercentSubtotal = grossTotalCents - percentDiscountCents`.
5. Evaluate threshold coupon against **`afterPercentSubtotal`** (never against `grossTotalCents`):
   if `thresholdCents > 0 && afterPercentSubtotal >= thresholdCents`, `thresholdDiscountCents = Math.min(thresholdDiscountCents, afterPercentSubtotal)`, else `0`.
6. Proportionally allocate `thresholdDiscountCents` across lines weighted by each line's post-percentage amount (`lineGross - linePercentDiscount`).

## 3. Largest-Remainder (Hamilton) Tail-Cent Rule
When allocating discount `D` across non-negative integer weights `w_0 ... w_{n-1}` (`W = sum(w_i)`):
- If `D == 0` or `W == 0`, every line receives `0`.
- Otherwise, each line's base share is `floor(D * w_i / W)` and exact remainder numerator is `(D * w_i) mod W`.
- Distribute the leftover `D - sum(base_i)` cents (`+1` cent each) in descending order of exact remainder numerator.
- **Tie-breaker**: if remainder numerators match, prefer larger weight `w_i` descending, then earlier line index `i` ascending.
- **Tail-cent safety fallback**: never dump all leftover cents onto index `0` or `n - 1`; lines with `w_i == 0` MUST always receive `0` discount so `payableCents >= 0` holds for every line.
- **Conservation invariant**: `sum(lineAllocations.percentDiscountCents) == percentDiscountCents`, `sum(lineAllocations.thresholdDiscountCents) == thresholdDiscountCents`, and `sum(lineAllocations.payableCents) == payableTotalCents`.
