package mta.eda.consumer.service.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StatusMachineTest {
    @Test
    void isValidTransition_shouldAllowForwardTransitions() {
        assertTrue(StatusMachine.isValidTransition("new", "confirmed"));
        assertTrue(StatusMachine.isValidTransition("confirmed", "dispatched"));
        assertTrue(StatusMachine.isValidTransition("dispatched", "completed"));
    }

    @Test
    void isValidTransition_shouldAllowCancelFromAnyState() {
        assertTrue(StatusMachine.isValidTransition("new", "canceled"));
        assertTrue(StatusMachine.isValidTransition("confirmed", "canceled"));
        assertTrue(StatusMachine.isValidTransition("dispatched", "canceled"));
        assertTrue(StatusMachine.isValidTransition("completed", "canceled"));
    }

    @Test
    void isValidTransition_shouldRejectBackwardTransitions() {
        assertFalse(StatusMachine.isValidTransition("dispatched", "new"));
        assertFalse(StatusMachine.isValidTransition("completed", "confirmed"));
    }
}
