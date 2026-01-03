package mta.eda.consumer.model.order;

public record OrderItem(
        String itemId,
        int quantity,
        double price
) {}