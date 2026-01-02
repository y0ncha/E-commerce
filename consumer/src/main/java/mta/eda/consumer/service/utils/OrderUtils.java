package mta.eda.consumer.service.utils;

import mta.eda.consumer.model.order.Order;
import mta.eda.consumer.model.order.OrderItem;

public final class OrderUtils {

    /**
     * Private constructor to prevent instantiation
     */
    private  OrderUtils() {}

   /**
    * Calculates the shipping cost based on the order's total amount.
    * @param order the order for which to calculate shipping cost
    * @return the shipping cost as 2% of the order's total amount
    */
    public static double calculateShippingCost(Order order) {
        return (0.02 * order.totalAmount());
    }

}
