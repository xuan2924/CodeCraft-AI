package interview;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CartTotalTest {
    private long discounted(List<CartTotal.Item> items, int discount) {
        java.lang.reflect.Method method = org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> CartTotal.class.getMethod("totalAfterDiscount", List.class, int.class),
                "Implement the public static totalAfterDiscount method");
        try {
            return (Long) method.invoke(null, items, discount);
        } catch (java.lang.reflect.InvocationTargetException error) {
            if (error.getCause() instanceof RuntimeException cause) throw cause;
            throw new AssertionError(error.getCause());
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    @Test
    void appliesDiscountInBasisPoints() {
        var items = List.of(new CartTotal.Item(799, 3), new CartTotal.Item(100, 2));
        assertEquals(2337L, discounted(items, 1000));
        assertEquals(2597L, discounted(items, 0));
        assertEquals(0L, discounted(items, 10000));
    }

    @Test
    void discountRoundsDownInCents() {
        assertEquals(0L, discounted(List.of(new CartTotal.Item(1, 1)), 1));
        assertEquals(0L, discounted(List.of(), 5000));
    }

    @Test
    void discountHandlesLongMaximumWithoutIntermediateOverflow() {
        assertEquals(4611686018427387903L, discounted(List.of(new CartTotal.Item(Long.MAX_VALUE, 1)), 5000));
        assertThrows(ArithmeticException.class, () -> discounted(List.of(new CartTotal.Item(Long.MAX_VALUE, 2)), 5000));
    }

    @Test
    void rejectsDiscountOutsideBasisPointRange() {
        assertThrows(IllegalArgumentException.class, () -> discounted(List.of(), -1));
        assertThrows(IllegalArgumentException.class, () -> discounted(List.of(), 10001));
    }

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
