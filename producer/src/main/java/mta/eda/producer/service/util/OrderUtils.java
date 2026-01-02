package mta.eda.producer.service.util;

import mta.eda.producer.model.order.OrderItem;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public final class OrderUtils {

    /**
     * Private constructor to prevent instantiation
     */
    private OrderUtils() {}

    public static final Random random = new Random();
    /**
     * Generates random order items.
     *
     * @param count Number of items to generate
     * @return List of OrderItem
     */
    public static List<OrderItem> generateOrderItems(int count) {
        List<OrderItem> items = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            // Hex format: 65,536 unique IDs (0000-FFFF) vs 10,000 in decimal
            String itemId = "ITEM-" + String.format("%04X", random.nextInt(0x10000));
            int quantity = random.nextInt(10) + 1; // 1-10
            double price = roundToTwoDecimals(random.nextDouble() * 100 + 1); // 1.00-101.00
            items.add(new OrderItem(itemId, quantity, price));
        }
        return items;
    }

    /**
     * Calculates total amount from order items.
     *
     * @param items List of order items
     * @return Total amount rounded to 2 decimal places
     */
    public static double calculateTotalAmount(List<OrderItem> items) {
        double total = items.stream()
                .mapToDouble(item -> item.price() * item.quantity())
                .sum();
        return roundToTwoDecimals(total);
    }

    /**
     * Rounds a double to 2 decimal places.
     *
     * @param value Value to round
     * @return Rounded value
     */
    public static double roundToTwoDecimals(double value) {
        return BigDecimal.valueOf(value)
                .setScale(2, RoundingMode.HALF_UP)
                .doubleValue();
    }
}
