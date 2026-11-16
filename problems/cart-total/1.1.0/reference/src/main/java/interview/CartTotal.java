package interview;

import java.util.List;
import java.util.Objects;

public final class CartTotal {
    private CartTotal() {
    }

    public record Item(long unitPriceCents, int quantity) {
    }

    public static long total(List<Item> items) {
        Objects.requireNonNull(items, "items");
        long totalCents = 0L;
        for (Item item : items) {
            Objects.requireNonNull(item, "item");
            if (item.unitPriceCents() < 0 || item.quantity() < 0) {
                throw new IllegalArgumentException("Price and quantity must be non-negative");
            }
            long lineCents = Math.multiplyExact(item.unitPriceCents(), (long) item.quantity());
            totalCents = Math.addExact(totalCents, lineCents);
        }
        return totalCents;
    }
}
