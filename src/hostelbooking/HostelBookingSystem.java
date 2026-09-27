package hostelbooking;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Core booking engine for a single hostel block.
 *
 * Concurrency note: bookRoom(), payDeposit(), cancelBooking(), switchBooking()
 * and releaseExpiredBookings() are all `synchronized` on this system instance.
 * That turns the "find an available room, then mark it booked" sequence -
 * the exact defect that caused the Block B double-booking in the case study -
 * into a single atomic critical section, so at most one caller can ever
 * successfully claim a given room, even when two requests arrive back-to-back
 * or from separate threads.
 */
public class HostelBookingSystem {

    private final Map<String, Room> rooms = new LinkedHashMap<>();
    private final Map<String, Student> students = new LinkedHashMap<>();
    private final Map<String, Booking> bookings = new LinkedHashMap<>();
    private final AuditLog auditLog = new AuditLog();
    private final Duration depositWindow; // "48 hours" in production; shortened for the console demo
    private final AtomicInteger bookingSeq = new AtomicInteger(1000);

    public HostelBookingSystem(Duration depositWindow) {
        this.depositWindow = depositWindow;
    }

    public AuditLog getAuditLog() { return auditLog; }

    // ---------- setup ----------

    public void addRoom(Room room) {
        rooms.put(room.getRoomId(), room);
    }

    public void addStudent(Student student) {
        students.put(student.getRollNumber(), student);
    }

    public Room getRoom(String roomId) { return rooms.get(roomId); }
    public Student getStudent(String rollNumber) { return students.get(rollNumber); }
    public Booking getBooking(String bookingId) { return bookings.get(bookingId); }
    public Collection<Room> allRooms() { return rooms.values(); }
    public Collection<Student> allStudents() { return students.values(); }
    public Duration getDepositWindow() { return depositWindow; }

    // ---------- core flow ----------

    /**
     * Attempts to book one room of the given type for the given student.
     * Enforces: authentication existence, single-active-reservation, and an
     * atomic check-and-hold on room availability (race-condition fix).
     */
    public synchronized Booking bookRoom(String rollNumber, RoomType type, boolean requireAccessible) throws BookingException {
        Student student = students.get(rollNumber);
        if (student == null) {
            throw new BookingException("Unknown student roll number: " + rollNumber);
        }

        // Rule: exactly one active reservation per student. This also acts as the
        // idempotency guard for a student who retries after a dropped connection -
        // they get told about their existing booking instead of receiving a duplicate.
        if (student.hasActiveBooking()) {
            Booking existing = bookings.get(student.getActiveBookingId());
            throw new BookingException("Student " + rollNumber + " already has an active reservation: "
                    + existing.getBookingId() + " (status=" + existing.getStatus() + "). Cancel or switch it first.");
        }

        Room room = findAvailableRoom(type, requireAccessible || student.needsAccessible());
        if (room == null) {
            auditLog.log("BOOK_REJECTED", rollNumber, "no AVAILABLE room of type " + type
                    + (student.needsAccessible() ? " (accessible)" : ""));
            throw new BookingException("No available " + type + " room" +
                    (student.needsAccessible() ? " with accessibility requirements" : "") + " right now.");
        }

        // --- atomic hold: this is the whole critical section that fixes the race condition ---
        room.setStatus(RoomStatus.HELD);
        String bookingId = "BK-" + bookingSeq.getAndIncrement();
        room.setCurrentBookingId(bookingId);

        Instant now = Instant.now();
        Booking booking = new Booking(bookingId, rollNumber, room.getRoomId(), now, now.plus(depositWindow));
        bookings.put(bookingId, booking);
        student.setActiveBookingId(bookingId);
        // --- end critical section ---

        auditLog.log("BOOKED", rollNumber, bookingId + " room=" + room.getRoomId()
                + " depositDeadline=" + booking.getDepositDeadline());
        return booking;
    }

    private Room findAvailableRoom(RoomType type, boolean requireAccessible) {
        for (Room r : rooms.values()) {
            if (r.getStatus() == RoomStatus.AVAILABLE && r.getType() == type) {
                if (!requireAccessible || r.isAccessible()) {
                    return r;
                }
            }
        }
        return null;
    }

    /** Confirms payment of the security deposit for a pending booking. */
    public synchronized void payDeposit(String bookingId) throws BookingException {
        Booking booking = requireBooking(bookingId);
        if (booking.getStatus() != BookingStatus.PENDING_PAYMENT) {
            throw new BookingException("Booking " + bookingId + " is not awaiting payment (status=" + booking.getStatus() + ")");
        }
        if (Instant.now().isAfter(booking.getDepositDeadline())) {
            throw new BookingException("Deposit window for booking " + bookingId + " has already expired.");
        }
        booking.setStatus(BookingStatus.CONFIRMED);
        booking.setPaymentReceivedAt(Instant.now());
        rooms.get(booking.getRoomId()).setStatus(RoomStatus.BOOKED);
        auditLog.log("PAYMENT_RECEIVED", booking.getStudentRoll(), bookingId + " room=" + booking.getRoomId());
    }

    /**
     * Sweeps all PENDING_PAYMENT bookings and expires any whose deposit
     * deadline has passed, releasing the room back to AVAILABLE. This directly
     * fixes the "rooms locked for weeks with no payment" defect in the case study.
     */
    public synchronized List<String> releaseExpiredBookings() {
        List<String> expired = new ArrayList<>();
        Instant now = Instant.now();
        for (Booking b : bookings.values()) {
            if (b.getStatus() == BookingStatus.PENDING_PAYMENT && now.isAfter(b.getDepositDeadline())) {
                b.setStatus(BookingStatus.EXPIRED);
                b.setClosedAt(now);
                Room room = rooms.get(b.getRoomId());
                room.setStatus(RoomStatus.AVAILABLE);
                room.setCurrentBookingId(null);
                Student s = students.get(b.getStudentRoll());
                if (s != null && b.getBookingId().equals(s.getActiveBookingId())) {
                    s.setActiveBookingId(null);
                }
                auditLog.log("EXPIRED", b.getStudentRoll(), b.getBookingId() + " room=" + b.getRoomId() + " released -> AVAILABLE");
                expired.add(b.getBookingId());
            }
        }
        return expired;
    }

    /** Cancels an active (pending or confirmed) booking and frees its room. */
    public synchronized void cancelBooking(String bookingId) throws BookingException {
        Booking booking = requireBooking(bookingId);
        if (booking.getStatus() != BookingStatus.PENDING_PAYMENT && booking.getStatus() != BookingStatus.CONFIRMED) {
            throw new BookingException("Booking " + bookingId + " cannot be cancelled (status=" + booking.getStatus() + ")");
        }
        booking.setStatus(BookingStatus.CANCELLED);
        booking.setClosedAt(Instant.now());
        Room room = rooms.get(booking.getRoomId());
        room.setStatus(RoomStatus.AVAILABLE);
        room.setCurrentBookingId(null);
        Student s = students.get(booking.getStudentRoll());
        if (s != null && bookingId.equals(s.getActiveBookingId())) {
            s.setActiveBookingId(null);
        }
        auditLog.log("CANCELLED", booking.getStudentRoll(), bookingId + " room=" + booking.getRoomId() + " released -> AVAILABLE");
    }

    /**
     * Atomically switches a student from their current room/type to a new
     * room type: holds the new room first, and only then releases the old
     * one, so the student is never left holding zero rooms if the new type
     * has no availability.
     */
    public synchronized Booking switchBooking(String bookingId, RoomType newType) throws BookingException {
        Booking oldBooking = requireBooking(bookingId);
        if (oldBooking.getStatus() != BookingStatus.PENDING_PAYMENT && oldBooking.getStatus() != BookingStatus.CONFIRMED) {
            throw new BookingException("Booking " + bookingId + " cannot be switched (status=" + oldBooking.getStatus() + ")");
        }
        Student student = students.get(oldBooking.getStudentRoll());

        Room newRoom = findAvailableRoom(newType, student.needsAccessible());
        if (newRoom == null) {
            auditLog.log("SWITCH_REJECTED", oldBooking.getStudentRoll(), "no AVAILABLE room of type " + newType);
            throw new BookingException("No available " + newType + " room to switch into; original booking kept.");
        }

        // Hold the new room first.
        newRoom.setStatus(RoomStatus.HELD);
        String newBookingId = "BK-" + bookingSeq.getAndIncrement();
        newRoom.setCurrentBookingId(newBookingId);
        Instant now = Instant.now();
        Booking newBooking = new Booking(newBookingId, student.getRollNumber(), newRoom.getRoomId(), now, now.plus(depositWindow));
        newBooking.setPreviousBookingId(bookingId);
        bookings.put(newBookingId, newBooking);

        // Only now release the old room and close the old booking.
        oldBooking.setStatus(BookingStatus.SWITCHED);
        oldBooking.setClosedAt(now);
        Room oldRoom = rooms.get(oldBooking.getRoomId());
        oldRoom.setStatus(RoomStatus.AVAILABLE);
        oldRoom.setCurrentBookingId(null);

        student.setActiveBookingId(newBookingId);

        auditLog.log("SWITCHED", student.getRollNumber(), bookingId + " -> " + newBookingId
                + " (" + oldBooking.getRoomId() + " -> " + newRoom.getRoomId() + ")");
        return newBooking;
    }

    private Booking requireBooking(String bookingId) throws BookingException {
        Booking b = bookings.get(bookingId);
        if (b == null) {
            throw new BookingException("Unknown booking id: " + bookingId);
        }
        return b;
    }

    public void printRoomStatus(String block) {
        System.out.println("Room status for block " + block + ":");
        for (Room r : rooms.values()) {
            if (r.getBlock().equals(block)) {
                System.out.println("  " + r);
            }
        }
    }
}