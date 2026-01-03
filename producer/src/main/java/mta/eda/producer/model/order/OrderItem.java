package mta.eda.producer.model.order;

public record OrderItem(
        String itemId,
        int quantity,
        double price
) {}

