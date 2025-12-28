package mta.eda.producer.model;

import java.util.List;

public record Order(
    String orderId,
    String customerId,
    String orderDate,
    List<OrderItem> items,
    double totalAmount,
    String currency,
    String status
) {}
