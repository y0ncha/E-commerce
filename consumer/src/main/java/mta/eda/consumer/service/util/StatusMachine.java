package mta.eda.consumer.service.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * StatusMachine: Shared state machine for order status transitions.
 *
 * This class defines the allowed status progression for orders and provides
 * validation methods for both producer and consumer.
 *
 * Status Progression (State Machine):
 * NEW (0) → CONFIRMED (1) → DISPATCHED (2) → COMPLETED (3)
 *                        ↓
 *                     CANCELED (4) [terminal state - can be reached from any state]
 *
 * Rules:
 * 1. NEW is the initial status (first creation)
 * 2. Status can only move forward (NEW → CONFIRMED → DISPATCHED → COMPLETED)
 * 3. CANCELED is a terminal state - can be reached from any status
 * 4. Cannot transition backward (e.g., DISPATCHED → CONFIRMED is invalid)
 * 5. Duplicate status (same as current) is detected as no-op
 */
public class StatusMachine {

    private static final Logger logger = LoggerFactory.getLogger(StatusMachine.class);

    // Define allowed statuses and their progression order
    public static final String STATUS_NEW = "new";
    public static final String STATUS_CONFIRMED = "confirmed";
    public static final String STATUS_DISPATCHED = "dispatched";
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_CANCELED = "canceled";
    public static final String STATUS_CANCELLED = "cancelled";

    /**
     * Get the order of a status in the progression.
     * Lower number = earlier stage, Higher number = later stage.
     *
     * Status Progression:
     * - NEW: 0 (initial)
     * - CONFIRMED: 1 (intermediate)
     * - DISPATCHED: 2 (intermediate)
     * - COMPLETED: 3 (terminal)
     * - CANCELED/CANCELLED: 4 (terminal - can be reached from any state)
     *
     * @param status the order status
     * @return the progression order (0-4), or -1 for null
     */
    public static int getStatusOrder(String status) {
        if (status == null) {
            return -1;
        }

        return switch (status.toLowerCase()) {
            case STATUS_NEW -> 0;
            case STATUS_CONFIRMED -> 1;
            case STATUS_DISPATCHED -> 2;
            case STATUS_COMPLETED -> 3;
            case STATUS_CANCELED, STATUS_CANCELLED -> 4;
            default -> {
                logger.warn("Unknown status: '{}'. Status machine only recognizes: NEW, CONFIRMED, DISPATCHED, COMPLETED, CANCELED/CANCELLED", status);
                yield -1;
            }
        };
    }

    /**
     * Validates if a status transition is allowed.
     *
     * Rules:
     * 1. First status (null → new): ALLOWED
     * 2. Forward transition (NEW → CONFIRMED → DISPATCHED → COMPLETED): ALLOWED
     * 3. To CANCELED (from any state): ALLOWED (terminal state)
     * 4. Backward transition (COMPLETED → DISPATCHED): NOT ALLOWED
     * 5. Unknown status: NOT ALLOWED
     *
     * @param currentStatus the current status (null for initial creation)
     * @param newStatus the incoming status
     * @return true if transition is valid, false otherwise
     */
    public static boolean isValidTransition(String currentStatus, String newStatus) {
        // First-time creation: any valid status is OK
        if (currentStatus == null || currentStatus.isBlank()) {
            int newOrder = getStatusOrder(newStatus);
            if (newOrder == -1) {
                logger.warn("Invalid initial status: '{}'. Must be one of: NEW, CONFIRMED, DISPATCHED, COMPLETED, CANCELED", newStatus);
                return false;
            }
            return true;
        }

        int currentOrder = getStatusOrder(currentStatus);
        int newOrder = getStatusOrder(newStatus);

        // Unknown status
        if (currentOrder == -1 || newOrder == -1) {
            logger.warn("Cannot validate transition with unknown status. Current: '{}' ({}) → New: '{}' ({})",
                    currentStatus, currentOrder, newStatus, newOrder);
            return false;
        }

        // CANCELED is a terminal state - can be reached from any state
        if (newStatus.toLowerCase().equals(STATUS_CANCELED) ||
            newStatus.toLowerCase().equals(STATUS_CANCELLED)) {
            logger.info("Valid transition: {} → {} (terminal state)", currentStatus, newStatus);
            return true;
        }

        // All other transitions must be STRICTLY sequential (can only move to the next immediate state)
        // Allowed transitions: NEW→CONFIRMED, CONFIRMED→DISPATCHED, DISPATCHED→COMPLETED
        // Disallowed: NEW→DISPATCHED (skipping CONFIRMED), NEW→COMPLETED, CONFIRMED→COMPLETED, etc.
        boolean isValid = newOrder == currentOrder + 1;

        if (isValid) {
            logger.info("Valid transition: {} ({}) → {} ({})", currentStatus, currentOrder, newStatus, newOrder);
        } else {
            logger.warn("Invalid transition: {} ({}) → {} ({}). Status must move sequentially (one step at a time) or to CANCELED",
                    currentStatus, currentOrder, newStatus, newOrder);
        }

        return isValid;
    }

    /**
     * Check if a status is a terminal state (no further transitions possible).
     *
     * Terminal states:
     * - COMPLETED (natural end of order lifecycle)
     * - CANCELED (order canceled)
     *
     * @param status the order status
     * @return true if status is terminal
     */
    public static boolean isTerminalState(String status) {
        if (status == null) {
            return false;
        }
        String lowerStatus = status.toLowerCase();
        return lowerStatus.equals(STATUS_COMPLETED) ||
               lowerStatus.equals(STATUS_CANCELED) ||
               lowerStatus.equals(STATUS_CANCELLED);
    }

    /**
     * Get human-readable description of the allowed status progression.
     *
     * @return string describing the state machine
     */
    public static String getStateMachineDescription() {
        return """
            Status Machine: Order Lifecycle
            ================================
            NEW (0) → CONFIRMED (1) → DISPATCHED (2) → COMPLETED (3)
                                   ↓
                              CANCELED (4) [terminal - reachable from any state]
            
            Rules:
            - NEW is the initial status
            - Status progresses forward: NEW → CONFIRMED → DISPATCHED → COMPLETED
            - CANCELED can be reached from any status (terminal state)
            - Backward transitions are NOT allowed
            - Terminal states: COMPLETED, CANCELED
            """;
    }
}

