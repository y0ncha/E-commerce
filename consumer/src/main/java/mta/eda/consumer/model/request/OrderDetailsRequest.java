package mta.eda.consumer.model.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * OrderDetailsRequest - DTO for GET /order-details/{orderId}
 * Validates:
 * - orderId exists (not null/blank)
 * - orderId is in hexadecimal format (0-9, A-F)
 * - orderId is positive (inherent in hex)
 */
public record OrderDetailsRequest(
    @NotBlank(message = "orderId is required and cannot be empty")
    @Pattern(
        regexp = "^[0-9A-Fa-f]+$",
        message = "orderId must be in hexadecimal format (0-9, A-F)"
    )
    @JsonProperty("orderId")
    String orderId
) {}

