package hostelbooking;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;

/**
 * Console demonstration of the corrected Hostel Room Booking design for a
 * single block (Block B, per the assignment's simplified-scope instruction).
 *
 * NOTE ON TIME: the real deposit window is 48 hours. To demonstrate expiry
 * without waiting two days, this demo constructs the system with a
 * DEMO_DEPOSIT_WINDOW of 2 seconds and simply sleeps past it. The logic
 * (HostelBookingSystem.releaseExpiredBookings) is identical to what would run
 * against a real 48-hour Duration in production.
 */
public class Main {

    private static final Duration DEMO_DEPOSIT_WINDOW = Duration.ofSeconds(2);

    public static void main(String[] args) throws Exception {
        HostelBookingSystem system = buildBlockBSystem();

        section("SETUP: Block B initial room inventory (trimmed for the demo)");
        system.printRoomStatus("B");

        demoNormalBookingAndPayment(system);
        demoSingleActiveReservationAndIdempotency(system);
        demoDepositExpiry(system);
        demoCancelAndSwitch(system);
        demoRaceCondition();

        section("FINAL AUDIT LOG");
        system.getAuditLog().printAll();
    }

    // ------------------------------------------------------------------
    // Scenario 1: normal booking -> pay deposit -> confirmed
    // ------------------------------------------------------------------
    private static void demoNormalBookingAndPayment(HostelBookingSystem system) throws Exception {
        section("SCENARIO 1: Normal booking flow (book -> pay deposit -> confirmed)");
        Booking b1 = system.bookRoom("BUIC-001", RoomType.DOUBLE, false);
        System.out.println("Booked: " + b1);
        system.payDeposit(b1.getBookingId());
        System.out.println("After payment: " + system.getBooking(b1.getBookingId()));
    }

    // ------------------------------------------------------------------
    // Scenario 2: single-active-reservation rule + idempotency on retry
    // ------------------------------------------------------------------
    private static void demoSingleActiveReservationAndIdempotency(HostelBookingSystem system) {
        section("SCENARIO 2: Single-active-reservation rule (also fixes duplicate-booking-on-retry)");
        try {
            // BUIC-001 already has an active booking from Scenario 1.
            // Simulates a student whose connection dropped and who is now retrying.
            system.bookRoom("BUIC-001", RoomType.SINGLE, false);
            System.out.println("ERROR: this should not have been allowed!");
        } catch (BookingException e) {
            System.out.println("Correctly rejected duplicate booking attempt: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Scenario 3: unpaid deposit expires automatically and frees the room
    // ------------------------------------------------------------------
    private static void demoDepositExpiry(HostelBookingSystem system) throws Exception {
        section("SCENARIO 3: Unpaid deposit auto-expiry (demo window = " + DEMO_DEPOSIT_WINDOW.getSeconds() + "s, represents 48h)");
        Booking b2 = system.bookRoom("BUIC-002", RoomType.SINGLE, false);
        System.out.println("Booked (unpaid): " + b2);
        System.out.println("Room right after booking: " + system.getRoom(b2.getRoomId()));

        System.out.println("Waiting past the deposit deadline without paying...");
        Thread.sleep(DEMO_DEPOSIT_WINDOW.toMillis() + 300);

        var expired = system.releaseExpiredBookings();
        System.out.println("Expired booking IDs released this sweep: " + expired);
        System.out.println("Booking after expiry: " + system.getBooking(b2.getBookingId()));
        System.out.println("Room after expiry: " + system.getRoom(b2.getRoomId()));
    }

    // ------------------------------------------------------------------
    // Scenario 4: cancel and switch
    // ------------------------------------------------------------------
    private static void demoCancelAndSwitch(HostelBookingSystem system) throws Exception {
        section("SCENARIO 4: Cancel and switch flows");
        Booking b3 = system.bookRoom("BUIC-003", RoomType.SHARED, false);
        System.out.println("Booked: " + b3);

        Booking switched = system.switchBooking(b3.getBookingId(), RoomType.DOUBLE);
        System.out.println("Switched " + b3.getBookingId() + " (SHARED) -> " + switched.getBookingId() + " (DOUBLE)");
        System.out.println("Old booking now: " + system.getBooking(b3.getBookingId()));
        System.out.println("New booking now: " + system.getBooking(switched.getBookingId()));

        system.cancelBooking(switched.getBookingId());
        System.out.println("Cancelled: " + system.getBooking(switched.getBookingId()));
        System.out.println("Room freed: " + system.getRoom(switched.getRoomId()));
    }

    // ------------------------------------------------------------------
    // Scenario 5: race condition - two near-simultaneous requests for the
    // SAME last available room type; only one may succeed.
    // ------------------------------------------------------------------
    private static void demoRaceCondition() throws Exception {
        section("SCENARIO 5: Race condition - two students booking the LAST available single room at once");

        // Fresh system with exactly ONE single room, to force a genuine conflict.
        HostelBookingSystem race = new HostelBookingSystem(DEMO_DEPOSIT_WINDOW);
        race.addRoom(new Room("B-S-LAST", "B", RoomType.SINGLE, 2, false));
        race.addStudent(new Student("BUIC-010", "Ali", false));
        race.addStudent(new Student("BUIC-011", "Sara", false));

        CountDownLatch startGate = new CountDownLatch(1);
        Result r1 = new Result();
        Result r2 = new Result();

        Thread t1 = new Thread(() -> {
            awaitGate(startGate);
            attemptBooking(race, "BUIC-010", r1);
        }, "Thread-Ali");
        Thread t2 = new Thread(() -> {
            awaitGate(startGate);
            attemptBooking(race, "BUIC-011", r2);
        }, "Thread-Sara");

        t1.start();
        t2.start();
        startGate.countDown(); // release both threads at (almost) the same instant
        t1.join();
        t2.join();

        System.out.println("Ali's attempt   -> " + r1);
        System.out.println("Sara's attempt  -> " + r2);

        long successes = (r1.success ? 1 : 0) + (r2.success ? 1 : 0);
        System.out.println("Successful bookings for the single room: " + successes
                + (successes == 1 ? "  [CORRECT: race condition resolved, no double-booking]"
                : "  [BUG: this should never be anything other than 1]"));
        race.printRoomStatus("B");
    }

    private static void attemptBooking(HostelBookingSystem system, String roll, Result result) {
        try {
            Booking b = system.bookRoom(roll, RoomType.SINGLE, false);
            result.success = true;
            result.message = "SUCCESS " + b.getBookingId() + " room=" + b.getRoomId();
        } catch (BookingException e) {
            result.success = false;
            result.message = "REJECTED: " + e.getMessage();
        }
    }

    private static void awaitGate(CountDownLatch gate) {
        try { gate.await(); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }

    private static class Result {
        volatile boolean success;
        volatile String message;
        @Override public String toString() { return message; }
    }

    // ------------------------------------------------------------------
    // Test data setup (subset of the assignment's Block B inventory)
    // ------------------------------------------------------------------
    private static HostelBookingSystem buildBlockBSystem() {
        HostelBookingSystem system = new HostelBookingSystem(DEMO_DEPOSIT_WINDOW);

        // Trimmed inventory for a readable demo (real Block B: 25 single, 35 double, 15 shared).
        system.addRoom(new Room("B-S-001", "B", RoomType.SINGLE, 1, false));
        system.addRoom(new Room("B-S-002", "B", RoomType.SINGLE, 1, false));
        system.addRoom(new Room("B-D-001", "B", RoomType.DOUBLE, 1, false));
        system.addRoom(new Room("B-D-002", "B", RoomType.DOUBLE, 2, false));
        system.addRoom(new Room("B-H-001", "B", RoomType.SHARED, 3, false));
        system.addRoom(new Room("B-H-002", "B", RoomType.SHARED, 3, false));

        system.addStudent(new Student("BUIC-001", "Hassan", false));
        system.addStudent(new Student("BUIC-002", "Ayesha", false));
        system.addStudent(new Student("BUIC-003", "Bilal", false));

        return system;
    }

    private static void section(String title) {
        System.out.println();
        System.out.println("=".repeat(78));
        System.out.println(title);
        System.out.println("=".repeat(78));
    }
}