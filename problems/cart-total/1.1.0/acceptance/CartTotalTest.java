package interview;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CartTotalTest {
    @Test
    void emptyCartIsFree() {
        assertEquals(0L, CartTotal.total(List.of()));
    }

    @Test
    void sumsPriceTimesQuantityInCents() {
        assertEquals(2597L, CartTotal.total(List.of(
                new CartTotal.Item(799L, 3), new CartTotal.Item(100L, 2))));
    }

    @Test
    void allowsZeroPriceAndZeroQuantity() {
        assertEquals(0L, CartTotal.total(List.of(
                new CartTotal.Item(0L, 9), new CartTotal.Item(999L, 0))));
    }

    @Test
    void rejectsNegativePrice() {
        assertThrows(IllegalArgumentException.class,
                () -> CartTotal.total(List.of(new CartTotal.Item(-1L, 1))));
    }

    @Test
    void rejectsNegativeQuantity() {
        assertThrows(IllegalArgumentException.class,
                () -> CartTotal.total(List.of(new CartTotal.Item(1L, -1))));
    }

    @Test
    void rejectsNullCartAndItem() {
        assertThrows(NullPointerException.class, () -> CartTotal.total(null));
        assertThrows(NullPointerException.class,
                () -> CartTotal.total(java.util.Arrays.asList((CartTotal.Item) null)));
    }

    @Test
    void detectsMultiplicationOverflow() {
        assertThrows(ArithmeticException.class,
                () -> CartTotal.total(List.of(new CartTotal.Item(Long.MAX_VALUE, 2))));
    }

    @Test
    void detectsAdditionOverflow() {
        assertThrows(ArithmeticException.class, () -> CartTotal.total(List.of(
                new CartTotal.Item(Long.MAX_VALUE, 1), new CartTotal.Item(1L, 1))));
    }
}
