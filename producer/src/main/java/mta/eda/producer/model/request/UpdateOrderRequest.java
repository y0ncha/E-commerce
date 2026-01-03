package mta.eda.producer.model.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

/**
 * UpdateOrderRequest - DTO for PUT /api/update-order
 * Input: orderId (string) and status (string)
 */
public record UpdateOrderRequest(

    @NotBlank(message = "orderId is required")
    @JsonProperty("orderId")
    String orderId,

    @NotBlank(message = "status is required")
    @JsonProperty("status")
    String status
) {
}