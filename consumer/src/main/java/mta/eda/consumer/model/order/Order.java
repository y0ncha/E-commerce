package mta.eda.consumer.model.order;

import java.util.List;

public record Order(
        String orderId,
        String customerId,
        String orderDate,
        List<OrderItem> items,
        double totalAmount,
        String currency,
        String status,
        String topicName  // Topic this message was received from
) {}