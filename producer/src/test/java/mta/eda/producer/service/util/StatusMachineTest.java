package mta.eda.producer.service.util;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for StatusMachine to ensure strict sequential transitions
 */
class StatusMachineTest {

    @Test
    void testValidSequentialTransitions() {
        // Valid sequential transitions
        assertTrue(StatusMachine.isValidTransition("new", "confirmed"));
        assertTrue(StatusMachine.isValidTransition("confirmed", "dispatched"));
        assertTrue(StatusMachine.isValidTransition("dispatched", "completed"));
    }

    @Test
    void testInvalidSkippingTransitions() {
        // Invalid: skipping intermediate states
        assertFalse(StatusMachine.isValidTransition("new", "dispatched"),
                "Should NOT allow NEW → DISPATCHED (must go through CONFIRMED)");
        assertFalse(StatusMachine.isValidTransition("new", "completed"),
                "Should NOT allow NEW → COMPLETED (must go through CONFIRMED and DISPATCHED)");
        assertFalse(StatusMachine.isValidTransition("confirmed", "completed"),
                "Should NOT allow CONFIRMED → COMPLETED (must go through DISPATCHED)");
    }

    @Test
    void testCanceledCanBeReachedFromAnyState() {
        // CANCELED can be reached from any state
        assertTrue(StatusMachine.isValidTransition("new", "canceled"));
        assertTrue(StatusMachine.isValidTransition("confirmed", "canceled"));
        assertTrue(StatusMachine.isValidTransition("dispatched", "canceled"));
        assertTrue(StatusMachine.isValidTransition("completed", "canceled"));
    }

    @Test
    void testBackwardTransitionsNotAllowed() {
        // Backward transitions are not allowed
        assertFalse(StatusMachine.isValidTransition("completed", "dispatched"));
        assertFalse(StatusMachine.isValidTransition("dispatched", "confirmed"));
        assertFalse(StatusMachine.isValidTransition("confirmed", "new"));
    }

    @Test
    void testFirstTimeCreationAllowsAnyValidStatus() {
        // First time creation (null status) allows any valid status
        assertTrue(StatusMachine.isValidTransition(null, "new"));
        assertTrue(StatusMachine.isValidTransition(null, "confirmed"));
        assertTrue(StatusMachine.isValidTransition(null, "dispatched"));
        assertTrue(StatusMachine.isValidTransition(null, "completed"));
        assertTrue(StatusMachine.isValidTransition(null, "canceled"));
    }

    @Test
    void testInvalidStatusesRejected() {
        // Unknown/invalid statuses should be rejected
        assertFalse(StatusMachine.isValidTransition("new", "invalid_status"));
        assertFalse(StatusMachine.isValidTransition("invalid_status", "confirmed"));
        assertFalse(StatusMachine.isValidTransition(null, "invalid_status"));
    }

    @Test
    void testCaseInsensitivity() {
        // Status comparison should be case-insensitive
        assertTrue(StatusMachine.isValidTransition("NEW", "CONFIRMED"));
        assertTrue(StatusMachine.isValidTransition("new", "Confirmed"));
        assertTrue(StatusMachine.isValidTransition("New", "canceled"));
    }

    @Test
    void testStatusOrdering() {
        // Verify status ordering
        assertEquals(0, StatusMachine.getStatusOrder("new"));
        assertEquals(1, StatusMachine.getStatusOrder("confirmed"));
        assertEquals(2, StatusMachine.getStatusOrder("dispatched"));
        assertEquals(3, StatusMachine.getStatusOrder("completed"));
        assertEquals(4, StatusMachine.getStatusOrder("canceled"));
        assertEquals(4, StatusMachine.getStatusOrder("cancelled")); // British spelling
        assertEquals(-1, StatusMachine.getStatusOrder("invalid"));
        assertEquals(-1, StatusMachine.getStatusOrder(null));
    }
}

