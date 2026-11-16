package interview;

import java.util.List;

public final class CartTotal {
    private CartTotal() {
    }

    public record Item(long unitPriceCents, int quantity) {
    }

    public static long total(List<Item> items) {
        // TODO: 按题目要求实现，金额单位为分。
        return 0L;
    }
}
