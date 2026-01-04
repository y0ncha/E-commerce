package mta.eda.producer.exception;

import mta.eda.producer.service.util.StatusMachine;

/**
 * Demo showing clear error messages for status validation failures.
 * This demonstrates the error handling improvements.
 */
public class StatusValidationDemo {

    public static void main(String[] args) {
        System.out.println("=== Status Validation Error Handling Demo ===\n");

        System.out.println("1. Testing Invalid Status Values:");
        testInvalidStatus("invalid_status");
        testInvalidStatus("pending");
        testInvalidStatus("shipped");

        System.out.println("\n2. Testing Invalid Status Transitions:");
        testInvalidTransition("ORD-001", "new", "dispatched");
        testInvalidTransition("ORD-002", "new", "completed");
        testInvalidTransition("ORD-003", "confirmed", "completed");
        testInvalidTransition("ORD-004", "completed", "dispatched");

        System.out.println("\n3. Testing Valid Transitions:");
        testValidTransition("ORD-005", "new", "confirmed");
        testValidTransition("ORD-006", "confirmed", "dispatched");
        testValidTransition("ORD-007", "dispatched", "completed");
        testValidTransition("ORD-008", "new", "canceled");
    }

    private static void testInvalidStatus(String status) {
        int statusOrder = StatusMachine.getStatusOrder(status);
        if (statusOrder == -1) {
            InvalidStatusException ex = new InvalidStatusException("ORD-TEST", status);
            System.out.println("❌ " + ex.getMessage());
        }
    }

    private static void testInvalidTransition(String orderId, String from, String to) {
        if (!StatusMachine.isValidTransition(from, to)) {
            InvalidStatusTransitionException ex = new InvalidStatusTransitionException(orderId, from, to);
            System.out.println("❌ " + ex.getMessage());
        }
    }

    private static void testValidTransition(String orderId, String from, String to) {
        if (StatusMachine.isValidTransition(from, to)) {
            System.out.println("✅ Valid transition for " + orderId + ": " + from + " → " + to);
        }
    }
}

