package mta.eda.producer.model;

public record OrderItem(
        String itemId,
        int quantity,
        double price
) {}

