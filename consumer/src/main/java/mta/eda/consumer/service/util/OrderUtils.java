package mta.eda.consumer.service.util;

import mta.eda.consumer.model.order.Order;

public final class OrderUtils {

    /**
     * Private constructor to prevent instantiation
     */
    private OrderUtils() {}

    /**
     * Calculates the shipping cost based on the order's total amount.
     * Formula: 2% of total amount
     *
     * @param order the order for which to calculate shipping cost
     * @return the calculated shipping cost (2% of total)
     */
    public static double calculateShippingCost(Order order) {
        return 0.02 * order.totalAmount();
    }

    /**
     * Normalizes orderId by adding ORD- prefix and padding.
     * Matches the Producer's normalization logic for consistency.
     *
     * @param rawOrderId the raw order ID (hex format, may already have ORD- prefix)
     * @return normalized orderId with ORD- prefix (e.g., "ABC" -> "ORD-0ABC")
     * @throws IllegalArgumentException if orderId is not in valid hexadecimal format
     */
    public static String normalizeOrderId(String rawOrderId) {
        if (rawOrderId == null) {
            throw new IllegalArgumentException("orderId is required");
        }

        String trimmed = rawOrderId.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("orderId is required");
        }

        String withoutPrefix = trimmed.toUpperCase().startsWith("ORD-")
                ? trimmed.substring(4)
                : trimmed;

        String symbols = withoutPrefix.trim().toUpperCase();

        if (!symbols.matches("[0-9A-F]+")) {
            throw new IllegalArgumentException("Invalid orderId format: must be hexadecimal");
        }

        String padded = symbols.length() < 4
                ? String.format("%4s", symbols).replace(' ', '0')
                : symbols;

        return "ORD-" + padded;
    }
}
