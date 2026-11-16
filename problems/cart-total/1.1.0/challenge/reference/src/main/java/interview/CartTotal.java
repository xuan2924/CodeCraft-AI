package interview;

import java.math.BigInteger;
import java.util.List;
import java.util.Objects;

public final class CartTotal {
    private CartTotal() {
    }

    public record Item(long unitPriceCents, int quantity) {
    }

    public static long totalAfterDiscount(List<Item> items, int discountBasisPoints) {
        if (discountBasisPoints < 0 || discountBasisPoints > 10000) {
            throw new IllegalArgumentException("Discount must be between 0 and 10000 basis points");
        }
        return BigInteger.valueOf(total(items)).multiply(BigInteger.valueOf(10000L - discountBasisPoints))
            .divide(BigInteger.valueOf(10000)).longValueExact();
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
