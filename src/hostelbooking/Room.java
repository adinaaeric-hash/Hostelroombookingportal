package hostelbooking;

/**
 * Represents a single hostel room.
 * status tracks the room's lifecycle: AVAILABLE -> HELD -> BOOKED -> AVAILABLE (release/expiry/cancel).
 */
public class Room {
    private final String roomId;
    private final String block;
    private final RoomType type;
    private final int floor;
    private final boolean accessible;

    private RoomStatus status;
    private String currentBookingId; // null when AVAILABLE

    public Room(String roomId, String block, RoomType type, int floor, boolean accessible) {
        this.roomId = roomId;
        this.block = block;
        this.type = type;
        this.floor = floor;
        this.accessible = accessible;
        this.status = RoomStatus.AVAILABLE;
        this.currentBookingId = null;
    }

    public String getRoomId() { return roomId; }
    public String getBlock() { return block; }
    public RoomType getType() { return type; }
    public int getFloor() { return floor; }
    public boolean isAccessible() { return accessible; }
    public RoomStatus getStatus() { return status; }
    public void setStatus(RoomStatus status) { this.status = status; }
    public String getCurrentBookingId() { return currentBookingId; }
    public void setCurrentBookingId(String currentBookingId) { this.currentBookingId = currentBookingId; }

    @Override
    public String toString() {
        return String.format("%s [%s-%s, floor %d%s] status=%s", roomId, block, type, floor,
                accessible ? ", accessible" : "", status);
    }
}