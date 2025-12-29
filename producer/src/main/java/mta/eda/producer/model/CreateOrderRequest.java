package mta.eda.producer.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * CreateOrderRequest - DTO for POST /api/create-order
 * Input: orderId (string) and numItems (positive number between 1 and 100)
 */
public record CreateOrderRequest(

    @NotBlank(message = "orderId is required")
    @JsonProperty("orderId")
    String orderId,

    @NotNull(message = "numItems is required")
    @Min(value = 1, message = "numItems must be at least 1")
    @Max(value = 100, message = "numItems must not exceed 100")
    @JsonProperty("numItems")
    Integer numItems
) {}