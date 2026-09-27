package hostelbooking;

import java.time.Instant;

/**
 * Represents one booking record. Every field needed to answer "what happened,
 * and when" is stored here so the system never needs a side spreadsheet to
 * reconcile itself, unlike the original portal.
 */
public class Booking {
    private final String bookingId;
    private final String studentRoll;
    private final String roomId;
    private BookingStatus status;

    private final Instant createdAt;
    private final Instant depositDeadline;
    private Instant paymentReceivedAt;
    private Instant closedAt; // set on cancel / expiry / switch

    private String previousBookingId; // set when this booking replaces another (switch)

    public Booking(String bookingId, String studentRoll, String roomId, Instant createdAt, Instant depositDeadline) {
        this.bookingId = bookingId;
        this.studentRoll = studentRoll;
        this.roomId = roomId;
        this.status = BookingStatus.PENDING_PAYMENT;
        this.createdAt = createdAt;
        this.depositDeadline = depositDeadline;
    }

    public String getBookingId() { return bookingId; }
    public String getStudentRoll() { return studentRoll; }
    public String getRoomId() { return roomId; }
    public BookingStatus getStatus() { return status; }
    public void setStatus(BookingStatus status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getDepositDeadline() { return depositDeadline; }
    public Instant getPaymentReceivedAt() { return paymentReceivedAt; }
    public void setPaymentReceivedAt(Instant paymentReceivedAt) { this.paymentReceivedAt = paymentReceivedAt; }
    public Instant getClosedAt() { return closedAt; }
    public void setClosedAt(Instant closedAt) { this.closedAt = closedAt; }
    public String getPreviousBookingId() { return previousBookingId; }
    public void setPreviousBookingId(String previousBookingId) { this.previousBookingId = previousBookingId; }

    @Override
    public String toString() {
        return String.format("Booking[%s] student=%s room=%s status=%s deadline=%s",
                bookingId, studentRoll, roomId, status, depositDeadline);
    }
}