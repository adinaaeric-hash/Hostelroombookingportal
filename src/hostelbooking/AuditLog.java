package hostelbooking;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A simple, append-only, thread-safe audit trail. This directly addresses the
 * case-study flaw where the hostel office had no record and had to reconcile
 * bookings by hand in a spreadsheet.
 */
public class AuditLog {
    private static final DateTimeFormatter FMT = DateTimeFormatter.ISO_INSTANT;
    private final List<String> entries = Collections.synchronizedList(new ArrayList<>());

    public synchronized void log(String event, String actor, String detail) {
        String line = String.format("[%s] %-18s actor=%-10s %s",
                FMT.format(Instant.now()), event, actor, detail);
        entries.add(line);
    }

    public void printAll() {
        System.out.println("----- AUDIT LOG (" + entries.size() + " entries) -----");
        synchronized (entries) {
            for (String e : entries) {
                System.out.println(e);
            }
        }
        System.out.println("-------------------------------------------");
    }

    /** Returns a snapshot copy of all entries, oldest first — used by the GUI to render the log. */
    public List<String> snapshot() {
        synchronized (entries) {
            return new ArrayList<>(entries);
        }
    }
}