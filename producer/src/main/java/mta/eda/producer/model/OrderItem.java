package mta.eda.producer.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public record OrderItem(
        @NotBlank(message = "itemId is required and must not be blank")
        String itemId,

        @Positive(message = "quantity must be positive")
        int quantity,

        @Positive(message = "price must be positive")
        double price
) {}

