package hostelbooking;

/**
 * Represents a student. activeBookingId enforces the "one active reservation
 * at a time" business rule described in the case study.
 */
public class Student {
    private final String rollNumber;
    private final String name;
    private final boolean needsAccessible;
    private String activeBookingId; // null when the student holds no active booking

    public Student(String rollNumber, String name, boolean needsAccessible) {
        this.rollNumber = rollNumber;
        this.name = name;
        this.needsAccessible = needsAccessible;
    }

    public String getRollNumber() { return rollNumber; }
    public String getName() { return name; }
    public boolean needsAccessible() { return needsAccessible; }
    public String getActiveBookingId() { return activeBookingId; }
    public void setActiveBookingId(String activeBookingId) { this.activeBookingId = activeBookingId; }
    public boolean hasActiveBooking() { return activeBookingId != null; }

    @Override
    public String toString() {
        return String.format("%s (%s)%s", rollNumber, name, needsAccessible ? " [accessibility]" : "");
    }
}