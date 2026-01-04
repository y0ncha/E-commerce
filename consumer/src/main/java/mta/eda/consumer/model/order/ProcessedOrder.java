package mta.eda.consumer.model.order;

import java.time.Instant;

/**
 * ProcessedOrder: Wraps an Order with its calculated shipping cost and received timestamp.
 * This record represents the complete state after Phase 2 processing.
 * The original Order remains unchanged (status field untouched),
 * while shippingCost is calculated and attached here.
 */
public record ProcessedOrder(
        Order order,
        double shippingCost,
        Instant receivedAt
) {}

