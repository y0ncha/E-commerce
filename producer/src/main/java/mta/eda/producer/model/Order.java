package mta.eda.producer.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.util.List;

public record Order(
    @NotBlank(message = "orderId is required and must not be blank")
    String orderId,

    @NotBlank(message = "customerId is required and must not be blank")
    String customerId,

    @NotBlank(message = "orderDate is required and must not be blank")
    String orderDate,

    @NotNull(message = "items list is required")
    @NotEmpty(message = "items list must contain at least one item")
    @Valid
    List<OrderItem> items,

    @PositiveOrZero(message = "totalAmount must be zero or positive")
    double totalAmount,

    @NotBlank(message = "currency is required and must not be blank")
    String currency,

    @NotBlank(message = "status is required and must not be blank")
    String status
) {}
