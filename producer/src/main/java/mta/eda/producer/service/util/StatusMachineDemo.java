package mta.eda.producer.service.util;

/**
 * Simple demonstration of the StatusMachine fix
 */
public class StatusMachineDemo {

    public static void main(String[] args) {
        System.out.println("=== StatusMachine Sequential Transition Enforcement Demo ===\n");

        // Test valid sequential transitions
        System.out.println("✓ Valid Sequential Transitions:");
        testTransition("new", "confirmed", true);
        testTransition("confirmed", "dispatched", true);
        testTransition("dispatched", "completed", true);

        System.out.println("\n✗ Invalid Transitions (Skipping States):");
        testTransition("new", "dispatched", false);
        testTransition("new", "completed", false);
        testTransition("confirmed", "completed", false);

        System.out.println("\n✓ CANCELED Can Be Reached From Any State:");
        testTransition("new", "canceled", true);
        testTransition("confirmed", "canceled", true);
        testTransition("dispatched", "canceled", true);

        System.out.println("\n✗ Backward Transitions Not Allowed:");
        testTransition("completed", "dispatched", false);
        testTransition("dispatched", "confirmed", false);
        testTransition("confirmed", "new", false);
    }

    private static void testTransition(String from, String to, boolean expected) {
        boolean result = StatusMachine.isValidTransition(from, to);
        String icon = result == expected ? "✓" : "✗";
        String status = result ? "ALLOWED" : "BLOCKED";
        System.out.printf("%s %s → %s: %s (expected: %s)%n",
            icon, from.toUpperCase(), to.toUpperCase(), status,
            expected ? "ALLOWED" : "BLOCKED");
    }
}

