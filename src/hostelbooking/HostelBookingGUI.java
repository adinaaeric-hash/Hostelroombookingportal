package hostelbooking;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.plaf.FontUIResource;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;

/**
 * Swing GUI for the corrected Aurora Hostel Booking system.
 *
 * Pages (all inside this one window):
 *   - Book a Room        : the Blocks & Rooms browser. Pick a block, click a free room, confirm -> booked.
 *                          All three blocks are shown. Block C is locked for normal students and
 *                          only bookable by students with an accessibility requirement (special booking).
 *                          If you already hold a reservation, clicking another free room switches to it.
 *   - My Reservation     : status of the current reservation (pay deposit / cancel / switch) plus the
 *                          deposit-expiry sweep button.
 *   - Dashboard, Race-Condition Demo, Audit Log.
 *
 * All booking rules live in HostelBookingSystem (bookRoomById / switchBookingToRoom).
 */
public class HostelBookingGUI extends JFrame {

    private static final Duration DEMO_DEPOSIT_WINDOW = Duration.ofSeconds(25);

    // ---- palette: Dark Teal / Dark Slate Grey / Pale Slate / Lavender ----
    private static final Color DEEP_SPACE_BLUE = new Color(0x0B, 0x39, 0x48); // Dark Teal
    private static final Color DARK_TEAL       = new Color(0x41, 0x61, 0x65); // Dark Slate Grey
    private static final Color PACIFIC_CYAN    = new Color(0xAC, 0xB0, 0xBD); // Pale Slate
    private static final Color MINT_LEAF       = new Color(0x41, 0x61, 0x65); // Dark Slate Grey (primary action)
    private static final Color PEARL_AQUA      = new Color(0xD0, 0xCD, 0xD7); // Pale Slate

    private static final Color PAGE_BG     = new Color(0xD9, 0xDB, 0xF1); // Lavender
    private static final Color CARD_BG     = Color.WHITE;
    private static final Color CARD_BORDER = new Color(0xD0, 0xCD, 0xD7); // Pale Slate
    private static final Color TEXT_MUTED  = new Color(0x5A, 0x64, 0x72); // muted slate (derived)
    private static final Color AMBER = new Color(0xC9, 0x8A, 0x45); // warm amber (derived, HOLD status)
    private static final Color RED   = new Color(0xB2, 0x3A, 0x56); // deep rose-red (derived, EXPIRED status)

    private final HostelBookingSystem system = buildBlockBSystem();

    // ---- login / session ----
    private final String loggedInRoll;
    private Timer timer;

    // ---- navigation ----
    private JPanel contentPanel;
    private CardLayout contentLayout;
    private NavButton navBook, navReservation, navDashboard, navRace, navLog;

    // ---- UI components referenced across methods ----
    private BlocksPanel blocksPanel;
    private boolean switchMode = false;   // true only after the student clicks "Switch Room"

    // ---- dashboard ----
    private JLabel dashTotal, dashOccupancy, dashActive, dashPending;
    private DonutChart statusDonut;
    private BarChart blockChart, typeChart, bookingChart;

    private JLabel myBookingIdLabel, myRoomLabel, myDeadlineLabel;
    private Chip myStatusChip;
    private JButton payButton, cancelButton, switchRoomButton;
    private JPanel myReservationDetails;
    private ShadowCard myReservationCard;

    // ---- audit log page ----
    private DefaultTableModel auditModel;
    private JTable auditTable;
    private JScrollPane auditScroll;
    private JComboBox<String> auditEventFilter;
    private JTextField auditSearch;
    private JCheckBox auditNewestFirst;
    private JLabel auditSummaryLabel;
    private final List<String[]> auditRows = new ArrayList<>();   // {#, time, event, student, details}
    private String auditSignature = "";
    private String auditCountsText = "";
    private JTextArea raceArea;

    public HostelBookingGUI(String loggedInRoll) {
        super("Aurora University Hostel Booking Portal \u2014");
        this.loggedInRoll = loggedInRoll;
        AuthService.syncStudents(system);   // adds students registered on the login screen

        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout());
        getContentPane().setBackground(PAGE_BG);

        add(buildHeaderBar(), BorderLayout.NORTH);

        JPanel body = new JPanel(new BorderLayout());
        body.add(buildSidebar(), BorderLayout.WEST);

        contentLayout = new CardLayout();
        contentPanel = new JPanel(contentLayout);
        contentPanel.setBackground(PAGE_BG);
        contentPanel.add(buildBookingPage(), "BOOK");
        contentPanel.add(buildReservationPage(), "RESERVATION");
        contentPanel.add(buildDashboardPage(), "DASH");
        contentPanel.add(buildRaceDemoPage(), "RACE");
        contentPanel.add(buildAuditPage(), "LOG");
        body.add(contentPanel, BorderLayout.CENTER);

        add(body, BorderLayout.CENTER);

        // Auto-refresh every second so deposit countdowns and expiries are visible live.
        timer = new Timer(1000, e -> refreshAll());
        timer.start();

        refreshAll();

        setSize(1180, 780);
        setMinimumSize(new Dimension(1000, 620));
        setLocationRelativeTo(null);
    }

    // ================= Header + sidebar =================

    private JComponent buildHeaderBar() {
        GradientPanel bar = new GradientPanel(DEEP_SPACE_BLUE, DARK_TEAL, true);
        bar.setLayout(new BorderLayout());
        bar.setBorder(new EmptyBorder(14, 22, 14, 22));

        JPanel titleBox = new JPanel();
        titleBox.setOpaque(false);
        titleBox.setLayout(new BoxLayout(titleBox, BoxLayout.Y_AXIS));
        JLabel title = new JLabel("\uD83C\uDFE8  Aurora University Hostel Booking Portal");
        title.setForeground(Color.WHITE);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 19f));
        titleBox.add(title);
        titleBox.add(Box.createVerticalStrut(3));

        bar.add(titleBox, BorderLayout.WEST);
        return bar;
    }

    private JComponent buildSidebar() {
        GradientPanel sidebar = new GradientPanel(DEEP_SPACE_BLUE, DARK_TEAL, false);
        sidebar.setLayout(new BoxLayout(sidebar, BoxLayout.Y_AXIS));
        sidebar.setPreferredSize(new Dimension(220, 10));
        sidebar.setBorder(new EmptyBorder(18, 12, 18, 12));

        ButtonGroup group = new ButtonGroup();
        navBook = new NavButton("\uD83C\uDFD8", "Book a Room");
        navReservation = new NavButton("\uD83D\uDCCB", "My Reservation");
        navDashboard = new NavButton("\uD83D\uDCCA", "Dashboard");
        navRace = new NavButton("\u26A1", "Race-Condition Demo");
        navLog = new NavButton("\uD83D\uDCDC", "Audit Log");

        navBook.addActionListener(e -> contentLayout.show(contentPanel, "BOOK"));
        navReservation.addActionListener(e -> contentLayout.show(contentPanel, "RESERVATION"));
        navDashboard.addActionListener(e -> contentLayout.show(contentPanel, "DASH"));
        navRace.addActionListener(e -> contentLayout.show(contentPanel, "RACE"));
        navLog.addActionListener(e -> contentLayout.show(contentPanel, "LOG"));

        for (NavButton nb : new NavButton[]{navBook, navReservation, navDashboard, navRace, navLog}) {
            group.add(nb);
            nb.setAlignmentX(Component.LEFT_ALIGNMENT);
            nb.setMaximumSize(new Dimension(Integer.MAX_VALUE, 46));
            sidebar.add(nb);
            sidebar.add(Box.createVerticalStrut(6));
        }
        navBook.setSelected(true);

        // Log out button
        JButton logout = new JButton("\u21A9  Log out");
        styleSecondaryButton(logout);
        logout.setAlignmentX(Component.LEFT_ALIGNMENT);
        logout.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        logout.addActionListener(e -> {
            timer.stop();
            dispose();
            new LoginFrame().setVisible(true);
        });

        sidebar.add(Box.createVerticalGlue());
        sidebar.add(logout);
        JLabel footer = new JLabel("<html>Aurora Hostel Office<br/></html>");
        footer.setForeground(new Color(255, 255, 255, 140));
        footer.setFont(footer.getFont().deriveFont(Font.PLAIN, 11f));
        footer.setAlignmentX(Component.LEFT_ALIGNMENT);
        footer.setBorder(new EmptyBorder(8, 8, 4, 8));
        sidebar.add(footer);

        return sidebar;
    }

    private void showPage(String key, NavButton nav) {
        nav.setSelected(true);
        contentLayout.show(contentPanel, key);
    }

    // ================= Page 1: Book a Room (Blocks & Rooms, click to book) =================
    private JComponent buildBookingPage() {
        blocksPanel = new BlocksPanel(system, loggedInRoll, this::onRoomClicked, this::cancelSwitchMode);
        return blocksPanel;
    }

    // ================= Page 2: My Reservation (status + sweep button) =================
    private JComponent buildReservationPage() {
        JPanel page = new JPanel(new BorderLayout());
        page.setBackground(PAGE_BG);
        page.setBorder(new EmptyBorder(18, 20, 16, 20));

        myReservationCard = (ShadowCard) card("\uD83D\uDCCB  My Current Reservation", buildMyReservationContent(), DARK_TEAL);

        // Sits outside the card's empty/details switch so it is visible even with no active reservation.
        JButton sweepBtn = new JButton("Run Deposit-Expiry Sweep Now");
        stylePrimaryButton(sweepBtn);
        sweepBtn.addActionListener(e -> {
            List<String> expired = system.releaseExpiredBookings();
            refreshAll();
            JOptionPane.showMessageDialog(this,
                    expired.isEmpty() ? "No pending bookings were past their deposit deadline."
                            : "Expired and released: " + expired,
                    "Deposit-Expiry Sweep", JOptionPane.INFORMATION_MESSAGE);
        });
        JLabel sweepNote = new JLabel("  (also runs automatically every second)");
        sweepNote.setForeground(TEXT_MUTED);
        JPanel sweepRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        sweepRow.setOpaque(false);
        sweepRow.add(sweepBtn);
        sweepRow.add(sweepNote);

        JPanel stack = new JPanel(new BorderLayout(0, 12));
        stack.setOpaque(false);
        stack.add(myReservationCard, BorderLayout.CENTER);
        stack.add(sweepRow, BorderLayout.SOUTH);

        page.add(topPinned(stack), BorderLayout.CENTER);
        return page;
    }

    private JComponent buildMyReservationContent() {
        JPanel wrapper = new JPanel(new CardLayout());
        wrapper.setOpaque(false);

        JPanel emptyState = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        emptyState.setOpaque(false);
        JPanel emptyBox = new JPanel();
        emptyBox.setOpaque(false);
        emptyBox.setLayout(new BoxLayout(emptyBox, BoxLayout.Y_AXIS));
        JLabel emptyLabel = new JLabel("You have no active reservation.");
        emptyLabel.setForeground(TEXT_MUTED);
        emptyLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        JButton browse = new JButton("Browse rooms \u2192");
        stylePrimaryButton(browse);
        browse.setAlignmentX(Component.LEFT_ALIGNMENT);
        browse.addActionListener(e -> showPage("BOOK", navBook));
        emptyBox.add(emptyLabel);
        emptyBox.add(Box.createVerticalStrut(10));
        emptyBox.add(browse);
        emptyState.add(emptyBox);

        myReservationDetails = new JPanel(new GridBagLayout());
        myReservationDetails.setOpaque(false);
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(4, 4, 8, 10);
        gc.anchor = GridBagConstraints.WEST;

        myBookingIdLabel = new JLabel("(none)");
        myRoomLabel = new JLabel("(none)");
        myStatusChip = new Chip("(none)", TEXT_MUTED);
        myDeadlineLabel = new JLabel("(none)");

        int row = 0;
        gc.gridx = 0; gc.gridy = row; myReservationDetails.add(bold("Booking ID:"), gc);
        gc.gridx = 1; myReservationDetails.add(myBookingIdLabel, gc);
        row++;
        gc.gridx = 0; gc.gridy = row; myReservationDetails.add(bold("Room:"), gc);
        gc.gridx = 1; myReservationDetails.add(myRoomLabel, gc);
        row++;
        gc.gridx = 0; gc.gridy = row; myReservationDetails.add(bold("Status:"), gc);
        gc.gridx = 1; myReservationDetails.add(myStatusChip, gc);
        row++;
        gc.gridx = 0; gc.gridy = row; myReservationDetails.add(bold("Deposit deadline:"), gc);
        gc.gridx = 1; myReservationDetails.add(myDeadlineLabel, gc);
        row++;

        JPanel actionRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        actionRow.setOpaque(false);
        payButton = new JButton("Pay Deposit");
        stylePrimaryButton(payButton);
        payButton.addActionListener(e -> doPay());
        cancelButton = new JButton("Cancel Reservation");
        styleSecondaryButton(cancelButton);
        cancelButton.addActionListener(e -> doCancel());
        switchRoomButton = new JButton("Switch Room");
        styleSecondaryButton(switchRoomButton);
        switchRoomButton.addActionListener(e -> doStartSwitch());
        actionRow.add(payButton);
        actionRow.add(cancelButton);
        actionRow.add(switchRoomButton);
        gc.gridx = 0; gc.gridy = row; gc.gridwidth = 2;
        myReservationDetails.add(actionRow, gc);
        row++;

        JLabel hint = new JLabel("Switch Room takes you to Book a Room, where you pick the free room you want to move to.");
        hint.setForeground(TEXT_MUTED);
        gc.gridx = 0; gc.gridy = row; gc.gridwidth = 2;
        myReservationDetails.add(hint, gc);

        wrapper.add(emptyState, "EMPTY");
        wrapper.add(myReservationDetails, "DETAILS");
        return wrapper;
    }

    // ================= Dashboard =================
    private JComponent buildDashboardPage() {
        JPanel page = new JPanel(new BorderLayout(0, 14));
        page.setBackground(PAGE_BG);
        page.setBorder(new EmptyBorder(18, 20, 16, 20));

        dashTotal = new JLabel("0");
        dashOccupancy = new JLabel("0%");
        dashActive = new JLabel("0");
        dashPending = new JLabel("0");
        JPanel kpis = new JPanel(new GridLayout(1, 4, 12, 0));
        kpis.setOpaque(false);
        kpis.add(statCard("TOTAL ROOMS", dashTotal, DEEP_SPACE_BLUE));
        kpis.add(statCard("OCCUPANCY (HELD + BOOKED)", dashOccupancy, PACIFIC_CYAN));
        kpis.add(statCard("ACTIVE RESERVATIONS", dashActive, MINT_LEAF));
        kpis.add(statCard("AWAITING DEPOSIT", dashPending, AMBER));

        statusDonut = new DonutChart();
        blockChart = new BarChart();
        typeChart = new BarChart();
        bookingChart = new BarChart();

        JPanel charts = new JPanel(new GridLayout(2, 2, 14, 14));
        charts.setOpaque(false);
        charts.add(chartCard("Room Status \u2014 All Blocks", statusDonut, MINT_LEAF));
        charts.add(chartCard("Availability by Block", blockChart, PACIFIC_CYAN));
        charts.add(chartCard("Rooms by Type", typeChart, DARK_TEAL));
        charts.add(chartCard("Reservations by Status", bookingChart, AMBER));

        page.add(kpis, BorderLayout.NORTH);
        page.add(charts, BorderLayout.CENTER);
        return page;
    }

    private JComponent chartCard(String title, JComponent chart, Color accent) {
        ShadowCard c = new ShadowCard(new BorderLayout(0, 6), 16, CARD_BG, accent);
        c.setBorder(new EmptyBorder(12, 20, 16, 16));
        JLabel header = new JLabel(title);
        header.setFont(header.getFont().deriveFont(Font.BOLD, 14f));
        header.setForeground(DEEP_SPACE_BLUE);
        c.add(header, BorderLayout.NORTH);
        c.add(chart, BorderLayout.CENTER);
        return c;
    }

    /** Recomputes every number and chart on the dashboard from the live system state. */
    private void refreshDashboard() {
        if (dashTotal == null) return;
        String[] blockNames = {"A", "B", "C"};
        String[] typeNames = {"SINGLE", "DOUBLE", "SHARED"};
        int[][] byBlock = new int[3][3];   // [status: free, held, booked][block]
        int[][] byType = new int[3][3];    // [status][type]
        int[] totals = new int[3];

        for (Room r : system.allRooms()) {
            int st = r.getStatus() == RoomStatus.AVAILABLE ? 0 : (r.getStatus() == RoomStatus.HELD ? 1 : 2);
            totals[st]++;
            for (int i = 0; i < 3; i++) {
                if (blockNames[i].equals(r.getBlock())) byBlock[st][i]++;
                if (typeNames[i].equals(r.getType().name())) byType[st][i]++;
            }
        }
        int total = totals[0] + totals[1] + totals[2];

        int[] bk = new int[5];   // pending, confirmed, expired, cancelled, switched
        for (Booking b : system.allBookings()) {
            switch (b.getStatus()) {
                case PENDING_PAYMENT: bk[0]++; break;
                case CONFIRMED: bk[1]++; break;
                case EXPIRED: bk[2]++; break;
                case CANCELLED: bk[3]++; break;
                case SWITCHED: bk[4]++; break;
                default: break;
            }
        }

        dashTotal.setText(String.valueOf(total));
        dashOccupancy.setText(total == 0 ? "0%" : ((totals[1] + totals[2]) * 100 / total) + "%");
        dashActive.setText(String.valueOf(bk[0] + bk[1]));
        dashPending.setText(String.valueOf(bk[0]));

        String[] statusNames = {"Free", "On hold", "Booked"};
        Color[] statusColors = {MINT_LEAF, AMBER, DEEP_SPACE_BLUE};

        statusDonut.setData(statusNames, totals, statusColors, String.valueOf(total), "rooms");
        blockChart.setData(new String[]{"Block A", "Block B", "Block C"}, statusNames, statusColors, byBlock, true, null);
        typeChart.setData(new String[]{"Single", "Double", "Shared"}, statusNames, statusColors, byType, false, null);
        bookingChart.setData(new String[]{"Pending", "Confirmed", "Expired", "Cancelled", "Switched"},
                new String[]{"Reservations"}, new Color[]{PACIFIC_CYAN}, new int[][]{bk}, false,
                new Color[]{statusColor(BookingStatus.PENDING_PAYMENT), statusColor(BookingStatus.CONFIRMED),
                        statusColor(BookingStatus.EXPIRED), statusColor(BookingStatus.CANCELLED),
                        statusColor(BookingStatus.SWITCHED)});
    }

    private JComponent statCard(String label, JLabel countLabel, Color color) {
        ShadowCard c = new ShadowCard(new BorderLayout(4, 2), 14, CARD_BG, color);
        c.setBorder(new EmptyBorder(12, 16, 12, 16));
        countLabel.setFont(countLabel.getFont().deriveFont(Font.BOLD, 30f));
        countLabel.setForeground(color.darker());
        JLabel lbl = new JLabel(label);
        lbl.setForeground(TEXT_MUTED);
        lbl.setFont(lbl.getFont().deriveFont(Font.BOLD, 11.5f));
        JPanel inner = new JPanel();
        inner.setOpaque(false);
        inner.setLayout(new BoxLayout(inner, BoxLayout.Y_AXIS));
        countLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        lbl.setAlignmentX(Component.LEFT_ALIGNMENT);
        inner.add(countLabel);
        inner.add(lbl);
        c.add(inner, BorderLayout.CENTER);
        return c;
    }

    // ================= Page 3: Race-Condition Demo =================
    private JComponent buildRaceDemoPage() {
        JPanel page = new JPanel(new BorderLayout(0, 10));
        page.setBackground(PAGE_BG);
        page.setBorder(new EmptyBorder(18, 20, 16, 20));

        JButton runBtn = new JButton("\u25B6  Simulate: two students click \u201cConfirm\u201d on the LAST single room at the same instant");
        stylePrimaryButton(runBtn);
        runBtn.addActionListener(e -> runRaceDemo(runBtn));
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
        top.setOpaque(false);
        top.add(runBtn);

        raceArea = new JTextArea();
        raceArea.setEditable(false);
        raceArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        raceArea.setBackground(CARD_BG);
        raceArea.setForeground(DEEP_SPACE_BLUE);
        raceArea.setMargin(new Insets(10, 10, 10, 10));
        raceArea.setText("Click the button above to run the simulation.\n\n" +
                "This creates a brand-new isolated system with exactly ONE single room and two\n" +
                "students, fires both booking requests from separate threads released at the same\n" +
                "instant, and reports the outcome. Because HostelBookingSystem.bookRoom() is a\n" +
                "synchronized atomic check-and-hold, exactly one request can ever succeed - this is\n" +
                "the fix for the Block B double-booking incident in the case study.");
        JScrollPane scroll = new JScrollPane(raceArea);
        scroll.setPreferredSize(new Dimension(900, 300));
        scroll.setBorder(BorderFactory.createLineBorder(CARD_BORDER));

        JPanel content = new JPanel(new BorderLayout(0, 10));
        content.setOpaque(false);
        content.add(top, BorderLayout.NORTH);
        content.add(scroll, BorderLayout.CENTER);

        page.add(card("\u26A1  Concurrent Booking Simulation", content, PACIFIC_CYAN), BorderLayout.CENTER);
        return page;
    }

    private void runRaceDemo(JButton runBtn) {
        runBtn.setEnabled(false);
        HostelBookingSystem race = new HostelBookingSystem(DEMO_DEPOSIT_WINDOW);
        race.addRoom(new Room("B-S-LAST", "B", RoomType.SINGLE, 2, false));
        race.addStudent(new Student("RACE-A", "Student A", false));
        race.addStudent(new Student("RACE-B", "Student B", false));

        final String[] resultA = {null};
        final String[] resultB = {null};
        CountDownLatch gate = new CountDownLatch(1);

        Runnable attemptA = () -> {
            try { gate.await(); } catch (InterruptedException ignored) {}
            try {
                Booking b = race.bookRoom("RACE-A", RoomType.SINGLE, false);
                resultA[0] = "SUCCESS -> " + b.getBookingId() + " room=" + b.getRoomId();
            } catch (BookingException ex) {
                resultA[0] = "REJECTED -> " + ex.getMessage();
            }
        };
        Runnable attemptB = () -> {
            try { gate.await(); } catch (InterruptedException ignored) {}
            try {
                Booking b = race.bookRoom("RACE-B", RoomType.SINGLE, false);
                resultB[0] = "SUCCESS -> " + b.getBookingId() + " room=" + b.getRoomId();
            } catch (BookingException ex) {
                resultB[0] = "REJECTED -> " + ex.getMessage();
            }
        };

        Thread tA = new Thread(attemptA, "Thread-StudentA");
        Thread tB = new Thread(attemptB, "Thread-StudentB");
        tA.start();
        tB.start();
        gate.countDown();

        new Thread(() -> {
            try { tA.join(); tB.join(); } catch (InterruptedException ignored) {}
            SwingUtilities.invokeLater(() -> {
                int successes = (resultA[0].startsWith("SUCCESS") ? 1 : 0) + (resultB[0].startsWith("SUCCESS") ? 1 : 0);
                StringBuilder sb = new StringBuilder();
                sb.append("Student A: ").append(resultA[0]).append('\n');
                sb.append("Student B: ").append(resultB[0]).append("\n\n");
                sb.append("Successful bookings for the single room: ").append(successes).append('\n');
                sb.append(successes == 1
                        ? "CORRECT: exactly one booking succeeded - no double-booking occurred."
                        : "BUG: this should never be anything other than 1.");
                sb.append("\n\n--- Audit log for this isolated run ---\n");
                for (String line : race.getAuditLog().snapshot()) sb.append(line).append('\n');
                raceArea.setText(sb.toString());
                runBtn.setEnabled(true);
            });
        }).start();
    }

    // ================= Page 4: Audit Log =================

    private static final String[] AUDIT_EVENTS = {
            "BOOKED", "PAYMENT_RECEIVED", "SWITCHED", "CANCELLED", "EXPIRED", "BOOK_REJECTED", "SWITCH_REJECTED"};
    private static final Pattern AUDIT_EVENT_PATTERN = Pattern.compile(
            "\\b(BOOKED|PAYMENT_RECEIVED|SWITCHED|CANCELLED|EXPIRED|BOOK_REJECTED|SWITCH_REJECTED)\\b");
    private static final DateTimeFormatter AUDIT_TIME_FMT =
            DateTimeFormatter.ofPattern("dd MMM  HH:mm:ss").withZone(ZoneId.systemDefault());

    private JComponent buildAuditPage() {
        JPanel page = new JPanel(new BorderLayout(0, 10));
        page.setBackground(PAGE_BG);
        page.setBorder(new EmptyBorder(18, 20, 16, 20));

        auditModel = new DefaultTableModel(new Object[]{"#", "Time", "Event", "Student", "Details"}, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        auditTable = new JTable(auditModel) {
            @Override public String getToolTipText(MouseEvent e) {
                int r = rowAtPoint(e.getPoint()), c = columnAtPoint(e.getPoint());
                if (r < 0 || c < 0) return null;
                Object v = getValueAt(r, c);
                if (v == null) return null;
                return "<html><body style='width:380px'>"
                        + String.valueOf(v).replace("&", "&amp;").replace("<", "&lt;") + "</body></html>";
            }
        };
        auditTable.setRowHeight(27);
        auditTable.setFillsViewportHeight(true);
        auditTable.setSelectionBackground(PEARL_AQUA);
        auditTable.setSelectionForeground(DEEP_SPACE_BLUE);
        auditTable.setGridColor(CARD_BORDER);
        auditTable.setShowVerticalLines(false);
        auditTable.getTableHeader().setFont(auditTable.getTableHeader().getFont().deriveFont(Font.BOLD, 12f));
        auditTable.getTableHeader().setBackground(DEEP_SPACE_BLUE);
        auditTable.getTableHeader().setForeground(Color.WHITE);
        auditTable.getColumnModel().getColumn(2).setCellRenderer(new AuditEventRenderer());
        int[] widths = {46, 130, 150, 110, 520};
        for (int i = 0; i < widths.length; i++) auditTable.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        auditScroll = new JScrollPane(auditTable);
        auditScroll.setBorder(BorderFactory.createLineBorder(CARD_BORDER));
        auditScroll.setPreferredSize(new Dimension(900, 300));

        auditEventFilter = new JComboBox<>();
        auditEventFilter.addItem("All events");
        for (String ev : AUDIT_EVENTS) auditEventFilter.addItem(ev);
        auditEventFilter.addActionListener(e -> applyAuditFilter());

        auditSearch = new JTextField(18);
        auditSearch.setToolTipText("Search by student, room, booking ID or any text");
        auditSearch.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { applyAuditFilter(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { applyAuditFilter(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { applyAuditFilter(); }
        });

        auditNewestFirst = new JCheckBox("Newest first", true);
        auditNewestFirst.setOpaque(false);
        auditNewestFirst.addActionListener(e -> applyAuditFilter());

        JPanel filters = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        filters.setOpaque(false);
        filters.add(new JLabel("Event:"));
        filters.add(auditEventFilter);
        filters.add(new JLabel("Search:"));
        filters.add(auditSearch);
        filters.add(auditNewestFirst);

        auditSummaryLabel = new JLabel(" ");
        auditSummaryLabel.setForeground(TEXT_MUTED);
        auditSummaryLabel.setBorder(new EmptyBorder(0, 8, 0, 0));

        JPanel north = new JPanel(new BorderLayout(0, 2));
        north.setOpaque(false);
        north.add(filters, BorderLayout.NORTH);
        north.add(auditSummaryLabel, BorderLayout.SOUTH);

        JLabel note = new JLabel("Every booking event is timestamped here and auto-refreshes. Hover a row to read long details.");
        note.setForeground(TEXT_MUTED);

        JPanel content = new JPanel(new BorderLayout(0, 8));
        content.setOpaque(false);
        content.add(north, BorderLayout.NORTH);
        content.add(auditScroll, BorderLayout.CENTER);
        content.add(note, BorderLayout.SOUTH);

        page.add(card("\uD83D\uDCDC  Full Audit Trail", content, DARK_TEAL), BorderLayout.CENTER);
        return page;
    }

    /**
     * Splits one raw audit line into {time, event, student, details} by locating the event keyword.
     * If no known event is found the whole line is kept as details, so nothing is ever lost.
     */
    private static String[] parseAuditLine(String line) {
        Matcher m = AUDIT_EVENT_PATTERN.matcher(line);
        if (!m.find()) return new String[]{"", "OTHER", "", line};

        String time = line.substring(0, m.start()).replaceAll("[\\s\\[\\]|:-]+$", "").replaceAll("^[\\s\\[]+", "").trim();
        try {
            time = AUDIT_TIME_FMT.format(Instant.parse(time));
        } catch (RuntimeException ignored) {
            // not an ISO instant: show as-is
        }

        String rest = line.substring(m.end()).replaceFirst("^[\\s|:>-]+", "");
        String[] parts = rest.split("[\\s|]+", 2);
        String student = parts.length > 0 ? parts[0].replaceAll("[:,]+$", "") : "";
        if (student.contains("=")) student = student.substring(student.indexOf('=') + 1);
        String details = parts.length > 1 ? parts[1].replaceFirst("^[\\s|:-]+", "") : "";
        return new String[]{time, m.group(1), student, details};
    }

    private void refreshAuditLog() {
        List<String> lines = new ArrayList<>();
        for (String line : system.getAuditLog().snapshot()) lines.add(line);
        String sig = lines.size() + "|" + (lines.isEmpty() ? "" : lines.get(lines.size() - 1));
        if (sig.equals(auditSignature)) return;   // nothing new: leave scroll position and selection alone
        auditSignature = sig;

        auditRows.clear();
        int booked = 0, paid = 0, switched = 0, cancelled = 0, expired = 0, rejected = 0;
        int seq = 1;
        for (String line : lines) {
            String[] p = parseAuditLine(line);
            auditRows.add(new String[]{String.valueOf(seq++), p[0], p[1], p[2], p[3]});
            switch (p[1]) {
                case "BOOKED": booked++; break;
                case "PAYMENT_RECEIVED": paid++; break;
                case "SWITCHED": switched++; break;
                case "CANCELLED": cancelled++; break;
                case "EXPIRED": expired++; break;
                case "BOOK_REJECTED":
                case "SWITCH_REJECTED": rejected++; break;
                default: break;
            }
        }
        auditCountsText = booked + " booked  \u00B7  " + paid + " paid  \u00B7  " + switched + " switched  \u00B7  "
                + cancelled + " cancelled  \u00B7  " + expired + " expired  \u00B7  " + rejected + " rejected";
        applyAuditFilter();
    }

    /** Rebuilds the visible table rows from the parsed log using the event / search / order controls. */
    private void applyAuditFilter() {
        if (auditModel == null) return;
        String ev = (String) auditEventFilter.getSelectedItem();
        boolean allEvents = ev == null || ev.startsWith("All");
        String q = auditSearch.getText().trim().toLowerCase();
        boolean newestFirst = auditNewestFirst.isSelected();
        int scrollPos = auditScroll.getVerticalScrollBar().getValue();

        auditModel.setRowCount(0);
        int n = auditRows.size();
        for (int k = 0; k < n; k++) {
            String[] r = auditRows.get(newestFirst ? n - 1 - k : k);
            if (!allEvents && !ev.equals(r[2])) continue;
            if (!q.isEmpty() && !String.join(" ", r).toLowerCase().contains(q)) continue;
            auditModel.addRow(r);
        }
        auditSummaryLabel.setText("Showing " + auditModel.getRowCount() + " of " + n + " events     "
                + auditCountsText);
        SwingUtilities.invokeLater(() -> auditScroll.getVerticalScrollBar().setValue(scrollPos));
    }

    /** Colours the Event column: green for payments, red for expiries, amber for rejections, and so on. */
    private static class AuditEventRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                         boolean hasFocus, int row, int column) {
            JLabel label = (JLabel) super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            label.setFont(label.getFont().deriveFont(Font.BOLD, 11.5f));
            String ev = String.valueOf(value);
            Color c;
            switch (ev) {
                case "PAYMENT_RECEIVED": c = MINT_LEAF.darker(); break;
                case "BOOKED": c = DARK_TEAL; break;
                case "SWITCHED": c = PACIFIC_CYAN.darker(); break;
                case "EXPIRED": c = RED; break;
                case "BOOK_REJECTED":
                case "SWITCH_REJECTED": c = AMBER; break;
                default: c = TEXT_MUTED; break;
            }
            label.setForeground(c);
            return label;
        }
    }

    // ================= Small styling helpers =================

    private JComponent card(String title, JComponent content, Color accent) {
        ShadowCard outer = new ShadowCard(new BorderLayout(0, 10), 16, CARD_BG, accent);
        outer.setBorder(new EmptyBorder(14, 22, 16, 18));
        JLabel header = new JLabel(title);
        header.setFont(header.getFont().deriveFont(Font.BOLD, 15f));
        header.setForeground(DEEP_SPACE_BLUE);
        outer.add(header, BorderLayout.NORTH);
        content.setOpaque(false);
        outer.add(content, BorderLayout.CENTER);
        return outer;
    }

    private JPanel topPinned(JComponent inner) {
        JPanel wrap = new JPanel(new BorderLayout());
        wrap.setOpaque(false);
        wrap.add(inner, BorderLayout.NORTH);
        return wrap;
    }

    private JLabel bold(String text) {
        JLabel l = new JLabel(text);
        l.setFont(l.getFont().deriveFont(Font.BOLD));
        return l;
    }

    private void stylePrimaryButton(JButton b) {
        b.setBackground(MINT_LEAF);
        b.setForeground(Color.WHITE);
        b.setFocusPainted(false);
        b.setFont(b.getFont().deriveFont(Font.BOLD));
        b.setBorder(new EmptyBorder(9, 18, 9, 18));
        b.setOpaque(true);
        b.setContentAreaFilled(true);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        Color base = MINT_LEAF, hover = MINT_LEAF.brighter();
        b.addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { b.setBackground(hover); }
            @Override public void mouseExited(MouseEvent e) { b.setBackground(base); }
        });
    }

    private void styleSecondaryButton(JButton b) {
        Color textColor = DARK_TEAL;
        b.setForeground(textColor);
        b.setBackground(CARD_BG);
        b.setFocusPainted(false);
        b.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(PACIFIC_CYAN, 1, true),
                new EmptyBorder(8, 15, 8, 15)));
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { b.setBackground(new Color(0xEE, 0xF0, 0xFA)); }
            @Override public void mouseExited(MouseEvent e) { b.setBackground(CARD_BG); }
        });
    }

    /** Sidebar navigation item: a toggle button with a rounded selection pill and a hover tint. */
    private static class NavButton extends JToggleButton {
        NavButton(String icon, String text) {
            super("  " + icon + "   " + text);
            setHorizontalAlignment(SwingConstants.LEFT);
            setFocusPainted(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setOpaque(false);
            setBorder(new EmptyBorder(4, 4, 4, 4));
            setFont(getFont().deriveFont(Font.BOLD, 13.5f));
            setForeground(Color.WHITE);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }
        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if (isSelected()) {
                g2.setColor(new Color(0xD9, 0xDB, 0xF1, 70));
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12);
                setForeground(Color.WHITE);
            } else if (getModel().isRollover()) {
                g2.setColor(new Color(255, 255, 255, 28));
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12);
                setForeground(Color.WHITE);
            } else {
                setForeground(new Color(255, 255, 255, 225));
            }
            g2.dispose();
            super.paintComponent(g);
        }
    }

    /** Panel painted with a linear gradient between two colors (used for the header bar and sidebar). */
    private static class GradientPanel extends JPanel {
        private final Color from, to;
        private final boolean horizontal;
        GradientPanel(Color from, Color to, boolean horizontal) {
            this.from = from;
            this.to = to;
            this.horizontal = horizontal;
            setOpaque(false);
        }
        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            GradientPaint gp = horizontal
                    ? new GradientPaint(0, 0, from, getWidth(), 0, to)
                    : new GradientPaint(0, 0, from, 0, getHeight(), to);
            g2.setPaint(gp);
            g2.fillRect(0, 0, getWidth(), getHeight());
            g2.dispose();
            super.paintComponent(g);
        }
    }

    /** A rounded white card with a soft drop shadow and a colored left accent strip. */
    private static class ShadowCard extends JPanel {
        private final int radius;
        private final Color bg;
        private Color accent;
        ShadowCard(LayoutManager lm, int radius, Color bg, Color accent) {
            super(lm);
            this.radius = radius;
            this.bg = bg;
            this.accent = accent;
            setOpaque(false);
        }
        void setAccent(Color c) { this.accent = c; repaint(); }
        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            int shadowDepth = 5;
            for (int i = shadowDepth; i >= 1; i--) {
                g2.setColor(new Color(0x0B, 0x39, 0x48, 5 + i));
                g2.fillRoundRect(i, i + 2, Math.max(w - 2 * i, 0), Math.max(h - 2 * i, 0), radius, radius);
            }
            g2.setColor(bg);
            g2.fillRoundRect(0, 0, w - shadowDepth, h - shadowDepth, radius, radius);
            g2.setColor(CARD_BORDER);
            g2.drawRoundRect(0, 0, w - shadowDepth - 1, h - shadowDepth - 1, radius, radius);
            g2.setColor(accent);
            g2.fillRoundRect(0, 8, 5, Math.max(h - shadowDepth - 16, 0), 5, 5);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    /** Small rounded, colored pill used to show a booking status at a glance. */
    private static class Chip extends JLabel {
        private Color bg;
        Chip(String text, Color bg) {
            super(text);
            this.bg = bg;
            setForeground(Color.WHITE);
            setFont(getFont().deriveFont(Font.BOLD, 12f));
            setBorder(new EmptyBorder(3, 10, 3, 10));
        }
        void setStatus(String text, Color bg) {
            setText(text);
            this.bg = bg;
            repaint();
        }
        @Override public Dimension getPreferredSize() {
            Dimension d = super.getPreferredSize();
            return new Dimension(d.width, Math.max(d.height, 22));
        }
        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(bg);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), getHeight(), getHeight());
            g2.dispose();
            super.paintComponent(g);
        }
    }

    private static Color statusColor(BookingStatus status) {
        switch (status) {
            case PENDING_PAYMENT: return AMBER;
            case CONFIRMED: return MINT_LEAF;
            case EXPIRED: return RED;
            case CANCELLED:
            case SWITCHED:
            default: return PACIFIC_CYAN;
        }
    }

    /**
     * True when this student may NOT book this room. Block C is for special (accessibility)
     * bookings only, so normal students are restricted from it. The (currently impossible)
     * case of a non-accessible room for a student who needs one is also restricted.
     */
    private static boolean restrictedFor(Room r, Student s) {
        boolean needsAccessible = s != null && s.needsAccessible();
        if (!needsAccessible && "C".equals(r.getBlock())) return true;   // Block C = special bookings only
        return needsAccessible && !r.isAccessible();
    }

    // ================= Actions =================

    private String currentRoll() {
        return loggedInRoll;
    }

    /** Called when the student clicks a room tile in Book a Room. Books it, or switches to it if they already hold one. */
    private void onRoomClicked(String roomId) {
        Room room = system.getRoom(roomId);
        Student me = system.getStudent(currentRoll());
        if (room == null || me == null) { warn("Register or log in as a student first."); return; }

        // A student who already holds a room may only pick another one after pressing "Switch Room".
        if (me.hasActiveBooking() && !switchMode) {
            Booking held = system.getBooking(me.getActiveBookingId());
            warn("You already have a reservation" + (held == null ? "" : " (" + held.getRoomId() + ")") + ".\n"
                    + "To move to a different room, open My Reservation and click Switch Room first.");
            return;
        }

        if (restrictedFor(room, me)) {
            warn("C".equals(room.getBlock())
                    ? "Block C is reserved for special (accessibility) bookings. Log in as a student with an accessibility requirement to book here."
                    : "Room " + roomId + " is not available for your booking type.");
            return;
        }
        if (room.getStatus() != RoomStatus.AVAILABLE) {
            warn("Room " + roomId + " is not available right now (" + room.getStatus() + ").");
            return;
        }

        try {
            if (me.hasActiveBooking()) {
                Booking cur = system.getBooking(me.getActiveBookingId());
                if (cur.getRoomId().equals(roomId)) {
                    warn("You are already in room " + roomId + ". Pick a different free room.");
                    return;
                }
                String extra = cur.getStatus() == BookingStatus.CONFIRMED
                        ? "\nYour deposit for the current room will not carry over; a new deposit will be due." : "";
                int ans = JOptionPane.showConfirmDialog(this,
                        "Switch from " + cur.getRoomId() + " to " + roomId + "?\nYour current room will be released." + extra,
                        "Confirm switch", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
                if (ans != JOptionPane.YES_OPTION) return;
                Booking nb = system.switchBookingToRoom(cur.getBookingId(), roomId);
                setSwitchMode(false);
                refreshAll();
                info("Switched to room " + nb.getRoomId() + " (" + nb.getBookingId() + "). Deposit due by " + nb.getDepositDeadline());
            } else {
                int ans = JOptionPane.showConfirmDialog(this,
                        "Book room " + roomId + " (" + room.getType() + ", floor " + room.getFloor() + ")?\n"
                                + "You will have to pay the deposit before the deadline or the room is released.",
                        "Confirm booking", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
                if (ans != JOptionPane.YES_OPTION) return;
                Booking b = system.bookRoomById(currentRoll(), roomId);
                refreshAll();
                info("Booked " + b.getRoomId() + " as " + b.getBookingId() + ". Deposit due by " + b.getDepositDeadline());
            }
            showPage("RESERVATION", navReservation);
        } catch (BookingException ex) {
            warn(ex.getMessage());
        }
        refreshAll();
    }

    private void doPay() {
        Student s = system.getStudent(currentRoll());
        if (s == null || !s.hasActiveBooking()) { warn("No active booking to pay for."); return; }
        try {
            system.payDeposit(s.getActiveBookingId());
            info("Deposit received. Booking confirmed.");
        } catch (BookingException ex) {
            warn(ex.getMessage());
        }
        refreshAll();
    }

    private void doCancel() {
        Student s = system.getStudent(currentRoll());
        if (s == null || !s.hasActiveBooking()) { warn("No active booking to cancel."); return; }
        try {
            system.cancelBooking(s.getActiveBookingId());
            info("Reservation cancelled and room released.");
        } catch (BookingException ex) {
            warn(ex.getMessage());
        }
        refreshAll();
    }

    /** "Switch Room" on My Reservation: unlocks room-picking on Book a Room for this one switch. */
    private void doStartSwitch() {
        Student s = system.getStudent(currentRoll());
        if (s == null || !s.hasActiveBooking()) { warn("No active booking to switch."); return; }
        setSwitchMode(true);
        showPage("BOOK", navBook);
    }

    private void cancelSwitchMode() {
        setSwitchMode(false);
    }

    private void setSwitchMode(boolean on) {
        switchMode = on;
        if (blocksPanel != null) blocksPanel.setSwitchMode(on);
    }

    private void refreshAll() {
        // Automatic deposit expiry: every tick, release any unpaid booking past its deadline.
        system.releaseExpiredBookings();
        // If the reservation disappeared (cancelled / expired) while in switch mode, leave switch mode.
        Student me = system.getStudent(currentRoll());
        if (switchMode && (me == null || !me.hasActiveBooking())) setSwitchMode(false);
        refreshMyBooking();
        refreshAuditLog();
        refreshDashboard();
        if (blocksPanel != null) blocksPanel.refresh();
    }

    private void refreshMyBooking() {
        Student s = system.getStudent(currentRoll());
        JPanel wrapper = (JPanel) myReservationDetails.getParent();
        CardLayout cl = (CardLayout) wrapper.getLayout();
        if (s == null || !s.hasActiveBooking()) {
            cl.show(wrapper, "EMPTY");
            myReservationCard.setAccent(DARK_TEAL);
            return;
        }
        Booking b = system.getBooking(s.getActiveBookingId());
        cl.show(wrapper, "DETAILS");
        setBookingLabels(b);
    }

    private void setBookingLabels(Booking b) {
        myBookingIdLabel.setText(b.getBookingId());
        myRoomLabel.setText(b.getRoomId());
        Color color = statusColor(b.getStatus());
        myStatusChip.setStatus(b.getStatus().toString(), color);
        myReservationCard.setAccent(color);
        if (b.getStatus() == BookingStatus.PENDING_PAYMENT) {
            long secondsLeft = Duration.between(Instant.now(), b.getDepositDeadline()).getSeconds();
            myDeadlineLabel.setText(Math.max(secondsLeft, 0) + "s left  (" + b.getDepositDeadline() + ")");
        } else {
            myDeadlineLabel.setText(b.getDepositDeadline().toString());
        }
        payButton.setEnabled(b.getStatus() == BookingStatus.PENDING_PAYMENT);
        cancelButton.setEnabled(b.getStatus() == BookingStatus.PENDING_PAYMENT || b.getStatus() == BookingStatus.CONFIRMED);
        switchRoomButton.setEnabled(b.getStatus() == BookingStatus.PENDING_PAYMENT || b.getStatus() == BookingStatus.CONFIRMED);
    }

    private void info(String msg) {
        JOptionPane.showMessageDialog(this, msg, "Success", JOptionPane.INFORMATION_MESSAGE);
    }

    private void warn(String msg) {
        JOptionPane.showMessageDialog(this, msg, "Rejected", JOptionPane.WARNING_MESSAGE);
    }

    // ================= Demo data =================
    /** Adds `count` rooms of one type, spread evenly over `floors` floors starting at `firstFloor`. */
    private static void addRooms(HostelBookingSystem sys, String block, RoomType type, String code,
                                 int count, int firstFloor, int floors, boolean accessible) {
        for (int i = 0; i < count; i++) {
            int floor = firstFloor + (i * floors) / count;
            sys.addRoom(new Room(String.format("%s-%s-%03d", block, code, i + 1), block, type, floor, accessible));
        }
    }

    private static HostelBookingSystem buildBlockBSystem() {
        HostelBookingSystem system = new HostelBookingSystem(DEMO_DEPOSIT_WINDOW);

        // Every room in every block is wheelchair-accessible.
        // Block A: 40 single, 30 double, 20 shared
        addRooms(system, "A", RoomType.SINGLE, "S", 40, 1, 2, true);
        addRooms(system, "A", RoomType.DOUBLE, "D", 30, 2, 2, true);
        addRooms(system, "A", RoomType.SHARED, "H", 20, 3, 2, true);
        // Block B: 25 single, 35 double, 15 shared
        addRooms(system, "B", RoomType.SINGLE, "S", 25, 1, 2, true);
        addRooms(system, "B", RoomType.DOUBLE, "D", 35, 2, 2, true);
        addRooms(system, "B", RoomType.SHARED, "H", 15, 3, 2, true);
        // Block C, ground floor (special bookings only): 20 single, 10 double
        addRooms(system, "C", RoomType.SINGLE, "S", 20, 0, 1, true);
        addRooms(system, "C", RoomType.DOUBLE, "D", 10, 0, 1, true);

        system.addStudent(new Student("BUIC-001", "Hassan", false));
        system.addStudent(new Student("BUIC-002", "Ayesha", false));
        system.addStudent(new Student("BUIC-003", "Bilal", false));
        system.addStudent(new Student("BUIC-004", "Zara", true));

        return system;
    }

    /** Starts the app at the login screen. */
    public static void main(String[] args) {
        applyTheme();
        SwingUtilities.invokeLater(() -> new LoginFrame().setVisible(true));
    }

    /** Nimbus retinted with the brand palette, plus a nicer default font where available. */
    static void applyTheme() {
        try {
            for (UIManager.LookAndFeelInfo info : UIManager.getInstalledLookAndFeels()) {
                if ("Nimbus".equals(info.getName())) {
                    UIManager.setLookAndFeel(info.getClassName());
                    break;
                }
            }
            UIManager.put("nimbusBase", DARK_TEAL);
            UIManager.put("nimbusBlueGrey", PEARL_AQUA.darker());
            UIManager.put("control", PAGE_BG);
            UIManager.put("nimbusSelectionBackground", MINT_LEAF);
            UIManager.put("nimbusSelectedText", Color.WHITE);
            UIManager.put("nimbusFocus", MINT_LEAF);
            UIManager.put("info", CARD_BG);
        } catch (Exception ignored) {
            // Fall back to the platform default look and feel if Nimbus isn't available.
        }
        applyPreferredFont("Segoe UI", "Helvetica Neue", "Verdana");
    }

    /** Swaps every UIManager font for the first available family in the preference list, keeping size/style. */
    private static void applyPreferredFont(String... preferredFamilies) {
        String chosen = null;
        String[] available = GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames();
        outer:
        for (String want : preferredFamilies) {
            for (String have : available) {
                if (have.equalsIgnoreCase(want)) { chosen = have; break outer; }
            }
        }
        if (chosen == null) return;
        Enumeration<Object> keys = UIManager.getDefaults().keys();
        java.util.List<Object> keyList = new java.util.ArrayList<>();
        while (keys.hasMoreElements()) keyList.add(keys.nextElement());
        for (Object key : keyList) {
            Object value = UIManager.get(key);
            if (value instanceof FontUIResource) {
                FontUIResource orig = (FontUIResource) value;
                UIManager.put(key, new FontUIResource(chosen, orig.getStyle(), orig.getSize()));
            }
        }
    }

    // ================= Chart components =================

    /** Donut chart with a centre total and a legend showing count and percentage. */
    private static class DonutChart extends JComponent {
        private String[] labels = new String[0];
        private int[] values = new int[0];
        private Color[] colors = new Color[0];
        private String centerTop = "", centerBottom = "";

        void setData(String[] labels, int[] values, Color[] colors, String centerTop, String centerBottom) {
            this.labels = labels; this.values = values; this.colors = colors;
            this.centerTop = centerTop; this.centerBottom = centerBottom;
            repaint();
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            int size = Math.min(h - 8, w / 2);
            if (size < 60) { g2.dispose(); return; }
            int x = 8, y = (h - size) / 2;

            int total = 0;
            for (int v : values) total += v;
            if (total == 0) {
                g2.setColor(new Color(0xE6, 0xE8, 0xF2));
                g2.fillOval(x, y, size, size);
            } else {
                double start = 90;
                for (int i = 0; i < values.length; i++) {
                    double ext = -360.0 * values[i] / total;
                    g2.setColor(colors[i]);
                    g2.fill(new java.awt.geom.Arc2D.Double(x, y, size, size, start, ext, java.awt.geom.Arc2D.PIE));
                    start += ext;
                }
            }
            int hole = (int) (size * 0.62);
            g2.setColor(CARD_BG);
            g2.fillOval(x + (size - hole) / 2, y + (size - hole) / 2, hole, hole);

            int cx = x + size / 2, cy = y + size / 2;
            g2.setColor(DEEP_SPACE_BLUE);
            g2.setFont(getFont().deriveFont(Font.BOLD, 26f));
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(centerTop, cx - fm.stringWidth(centerTop) / 2, cy + 6);
            g2.setColor(TEXT_MUTED);
            g2.setFont(getFont().deriveFont(Font.PLAIN, 12f));
            fm = g2.getFontMetrics();
            g2.drawString(centerBottom, cx - fm.stringWidth(centerBottom) / 2, cy + 24);

            int lx = x + size + 28;
            int rowH = 28;
            int ly = (h - rowH * labels.length) / 2 + 16;
            for (int i = 0; i < labels.length; i++) {
                int yy = ly + i * rowH;
                g2.setColor(colors[i]);
                g2.fillRoundRect(lx, yy - 11, 14, 14, 5, 5);
                g2.setColor(DEEP_SPACE_BLUE);
                g2.setFont(getFont().deriveFont(Font.BOLD, 13f));
                g2.drawString(labels[i], lx + 22, yy);
                int pct = total == 0 ? 0 : Math.round(values[i] * 100f / total);
                g2.setColor(TEXT_MUTED);
                g2.setFont(getFont().deriveFont(Font.PLAIN, 12f));
                g2.drawString(values[i] + "  (" + pct + "%)", lx + 22 + 74, yy);
            }
            g2.dispose();
        }
    }

    /** Vertical bar chart: grouped or stacked series, gridlines, value labels and a legend. */
    private static class BarChart extends JComponent {
        private String[] cats = new String[0];
        private String[] seriesNames = new String[0];
        private Color[] seriesColors = new Color[0];
        private int[][] data = new int[0][0];   // [series][category]
        private boolean stacked;
        private Color[] catColors;              // optional: one colour per bar when there is a single series

        void setData(String[] cats, String[] seriesNames, Color[] seriesColors, int[][] data,
                     boolean stacked, Color[] catColors) {
            this.cats = cats; this.seriesNames = seriesNames; this.seriesColors = seriesColors;
            this.data = data; this.stacked = stacked; this.catColors = catColors;
            repaint();
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            int n = seriesNames.length;
            boolean legend = n > 1;
            int padL = 34, padR = 10, padT = 16, padB = legend ? 50 : 28;
            int pw = w - padL - padR, ph = h - padT - padB;
            if (pw < 30 || ph < 30 || cats.length == 0) { g2.dispose(); return; }

            int max = 0;
            for (int c = 0; c < cats.length; c++) {
                int sum = 0, mx = 0;
                for (int s = 0; s < n; s++) { sum += data[s][c]; mx = Math.max(mx, data[s][c]); }
                max = Math.max(max, stacked ? sum : mx);
            }
            int[] steps = {1, 2, 5};
            int step = 1;
            outer:
            for (int mag = 1; ; mag *= 10) {
                for (int m : steps) {
                    int cand = m * mag;
                    if ((max + cand - 1) / cand <= 5) { step = cand; break outer; }
                }
            }
            int lines = Math.max(1, (max + step - 1) / step);
            int top = lines * step;

            g2.setFont(getFont().deriveFont(Font.PLAIN, 10.5f));
            FontMetrics fm = g2.getFontMetrics();
            for (int i = 0; i <= lines; i++) {
                int yy = padT + ph - ph * i / lines;
                g2.setColor(i == 0 ? new Color(0xAC, 0xB0, 0xBD) : CARD_BORDER);
                g2.drawLine(padL, yy, padL + pw, yy);
                String lab = String.valueOf(i * step);
                g2.setColor(TEXT_MUTED);
                g2.drawString(lab, padL - 6 - fm.stringWidth(lab), yy + 4);
            }

            int cw = pw / cats.length;
            for (int c = 0; c < cats.length; c++) {
                if (stacked) {
                    int barW = Math.min((int) (cw * 0.5), 70);
                    int bx = padL + c * cw + (cw - barW) / 2;
                    int accum = 0;
                    for (int s = 0; s < n; s++) {
                        int v = data[s][c];
                        if (v == 0) continue;
                        int y0 = padT + ph - (int) ((long) ph * (accum + v) / top);
                        int y1 = padT + ph - (int) ((long) ph * accum / top);
                        g2.setColor(seriesColors[s]);
                        g2.fillRect(bx, y0, barW, y1 - y0);
                        if (y1 - y0 >= 14) {
                            g2.setFont(getFont().deriveFont(Font.BOLD, 11f));
                            g2.setColor(Color.WHITE);
                            String t = String.valueOf(v);
                            FontMetrics f2 = g2.getFontMetrics();
                            g2.drawString(t, bx + (barW - f2.stringWidth(t)) / 2, y0 + (y1 - y0 + f2.getAscent()) / 2 - 2);
                        }
                        accum += v;
                    }
                } else {
                    int barW = Math.min((int) (cw * 0.7 / n), 38);
                    int gap = 4;
                    int groupW = barW * n + gap * (n - 1);
                    int gx = padL + c * cw + (cw - groupW) / 2;
                    for (int s = 0; s < n; s++) {
                        int v = data[s][c];
                        int bh = (int) ((long) ph * v / top);
                        int bx = gx + s * (barW + gap);
                        g2.setColor(catColors != null && n == 1 ? catColors[c] : seriesColors[s]);
                        g2.fillRect(bx, padT + ph - bh, barW, bh);
                        g2.setFont(getFont().deriveFont(Font.BOLD, 11f));
                        g2.setColor(DEEP_SPACE_BLUE);
                        String t = String.valueOf(v);
                        FontMetrics f2 = g2.getFontMetrics();
                        g2.drawString(t, bx + (barW - f2.stringWidth(t)) / 2, padT + ph - bh - 4);
                    }
                }
                g2.setFont(getFont().deriveFont(Font.PLAIN, 11.5f));
                g2.setColor(DEEP_SPACE_BLUE);
                FontMetrics f3 = g2.getFontMetrics();
                g2.drawString(cats[c], padL + c * cw + (cw - f3.stringWidth(cats[c])) / 2, padT + ph + 18);
            }

            if (legend) {
                g2.setFont(getFont().deriveFont(Font.PLAIN, 11.5f));
                FontMetrics f4 = g2.getFontMetrics();
                int total = 0;
                for (String nm : seriesNames) total += 18 + f4.stringWidth(nm) + 16;
                int lx = padL + (pw - total + 16) / 2;
                int ly = h - 10;
                for (int s = 0; s < n; s++) {
                    g2.setColor(seriesColors[s]);
                    g2.fillRoundRect(lx, ly - 10, 12, 12, 4, 4);
                    g2.setColor(TEXT_MUTED);
                    g2.drawString(seriesNames[s], lx + 18, ly);
                    lx += 18 + f4.stringWidth(seriesNames[s]) + 16;
                }
            }
            g2.dispose();
        }
    }

    // ================= Blocks & Rooms panel (embedded in the main window) =================

    /**
     * Shows the hostel as block cards. Block C is shown to everyone but locked for normal students;
     * only students with an accessibility requirement (special booking) can book it. Pick a block card at the top
     * and its rooms are drawn floor by floor. Clicking a room tile calls onRoomClick(roomId); the
     * main window handles the booking. The main window's 1-second timer calls refresh().
     */
    private static class BlocksPanel extends JPanel {

        private static final Color AMBER_TINT = new Color(0xFC, 0xEE, 0xDA);
        private static final Color MINT_TINT  = new Color(0xE1, 0xE9, 0xEA);
        private static final Color LOCKED_BG  = new Color(0xE6, 0xE4, 0xEC);

        private static final int TILES_PER_ROW = 10;

        private final String[] blocks;

        private final HostelBookingSystem system;
        private final String roll;
        private final Consumer<String> onRoomClick;

        private String selectedBlock = "A";
        private RoomType typeFilter = null;      // null = all types
        private boolean onlyAvailable = false;

        private final Map<String, BlockCard> cards = new LinkedHashMap<>();
        private final Map<String, RoomTile> tiles = new HashMap<>();
        private final JPanel floorsPanel = new JPanel();
        private final JLabel headline = new JLabel(" ");
        private final JLabel subline = new JLabel(" ");
        private final JLabel yourRoomLabel = new JLabel(" ");
        private final JLabel lockText = new JLabel(" ");
        private final JPanel lockBanner = new JPanel(new BorderLayout());
        private final JPanel switchBanner = new JPanel(new BorderLayout());
        private final Runnable onCancelSwitch;
        private boolean switchMode = false;
        private String lastSignature = "";

        BlocksPanel(HostelBookingSystem system, String roll, Consumer<String> onRoomClick, Runnable onCancelSwitch) {
            super(new BorderLayout(0, 12));
            this.system = system;
            this.roll = roll;
            this.onRoomClick = onRoomClick;
            this.onCancelSwitch = onCancelSwitch;

            this.blocks = new String[]{"A", "B", "C"};   // all blocks are shown; C is locked for normal students

            setBackground(PAGE_BG);
            setBorder(new EmptyBorder(16, 20, 12, 20));
            add(buildBlockCards(), BorderLayout.NORTH);
            add(buildFloorArea(), BorderLayout.CENTER);
            add(buildLegend(), BorderLayout.SOUTH);
            refresh();
        }

        /** Switch mode is turned on from My Reservation; only then may a student with a room pick another one. */
        void setSwitchMode(boolean on) {
            this.switchMode = on;
            refresh();
        }

        // ================= Layout =================

        private JComponent buildBlockCards() {
            JPanel row = new JPanel(new GridLayout(1, 3, 14, 0));
            row.setOpaque(false);
            for (String b : blocks) {
                BlockCard c = new BlockCard(b);
                cards.put(b, c);
                row.add(c);
            }
            // Empty fillers keep the visible cards the same size when Block C is hidden.
            for (int i = blocks.length; i < 3; i++) {
                JPanel filler = new JPanel();
                filler.setOpaque(false);
                row.add(filler);
            }
            return row;
        }

        private JComponent buildFloorArea() {
            JPanel wrap = new JPanel(new BorderLayout(0, 10));
            wrap.setOpaque(false);

            JPanel top = new JPanel(new BorderLayout(0, 8));
            top.setOpaque(false);

            JPanel titleBox = new JPanel();
            titleBox.setOpaque(false);
            titleBox.setLayout(new BoxLayout(titleBox, BoxLayout.Y_AXIS));
            headline.setFont(headline.getFont().deriveFont(Font.BOLD, 17f));
            headline.setForeground(DEEP_SPACE_BLUE);
            subline.setForeground(TEXT_MUTED);
            yourRoomLabel.setForeground(DARK_TEAL);
            yourRoomLabel.setFont(yourRoomLabel.getFont().deriveFont(Font.BOLD, 12.5f));
            headline.setAlignmentX(Component.LEFT_ALIGNMENT);
            subline.setAlignmentX(Component.LEFT_ALIGNMENT);
            yourRoomLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            titleBox.add(headline);
            titleBox.add(Box.createVerticalStrut(2));
            titleBox.add(subline);
            titleBox.add(Box.createVerticalStrut(2));
            titleBox.add(yourRoomLabel);

            JPanel filters = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
            filters.setOpaque(false);
            ButtonGroup group = new ButtonGroup();
            filters.add(typeChip("All", null, group, true));
            filters.add(typeChip("Single", RoomType.SINGLE, group, false));
            filters.add(typeChip("Double", RoomType.DOUBLE, group, false));
            filters.add(typeChip("Shared", RoomType.SHARED, group, false));
            JCheckBox only = new JCheckBox("Only free rooms");
            only.setOpaque(false);
            only.setForeground(DEEP_SPACE_BLUE);
            only.setFocusPainted(false);
            only.addActionListener(e -> { onlyAvailable = only.isSelected(); refresh(); });
            filters.add(Box.createHorizontalStrut(6));
            filters.add(only);

            JPanel head = new JPanel(new BorderLayout());
            head.setOpaque(false);
            head.add(titleBox, BorderLayout.WEST);
            head.add(filters, BorderLayout.EAST);
            top.add(head, BorderLayout.NORTH);

            lockBanner.setBackground(AMBER_TINT);
            lockBanner.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(new Color(0xE0, 0xC0, 0x93), 1, true),
                    new EmptyBorder(8, 12, 8, 12)));
            lockText.setForeground(AMBER.darker());
            lockBanner.add(lockText, BorderLayout.CENTER);
            lockBanner.setVisible(false);

            JPanel switchInner = new JPanel(new BorderLayout(10, 0));
            switchInner.setBackground(MINT_TINT);
            switchInner.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(MINT_LEAF, 1, true),
                    new EmptyBorder(6, 12, 6, 8)));
            JLabel switchText = new JLabel("\uD83D\uDD01  Switch mode: click a free room to move your reservation there.");
            switchText.setForeground(DARK_TEAL);
            switchText.setFont(switchText.getFont().deriveFont(Font.BOLD));
            JButton cancelSwitch = new JButton("Cancel switch");
            cancelSwitch.setFocusPainted(false);
            cancelSwitch.addActionListener(e -> onCancelSwitch.run());
            switchInner.add(switchText, BorderLayout.CENTER);
            switchInner.add(cancelSwitch, BorderLayout.EAST);
            switchBanner.setOpaque(false);
            switchBanner.setBorder(new EmptyBorder(0, 0, 6, 0));
            switchBanner.add(switchInner, BorderLayout.CENTER);
            switchBanner.setVisible(false);

            JPanel banners = new JPanel();
            banners.setOpaque(false);
            banners.setLayout(new BoxLayout(banners, BoxLayout.Y_AXIS));
            switchBanner.setAlignmentX(Component.LEFT_ALIGNMENT);
            lockBanner.setAlignmentX(Component.LEFT_ALIGNMENT);
            banners.add(switchBanner);
            banners.add(lockBanner);
            top.add(banners, BorderLayout.SOUTH);
            wrap.add(top, BorderLayout.NORTH);

            floorsPanel.setOpaque(false);
            floorsPanel.setLayout(new BoxLayout(floorsPanel, BoxLayout.Y_AXIS));
            JPanel pinned = new JPanel(new BorderLayout());
            pinned.setOpaque(false);
            pinned.setBorder(new EmptyBorder(6, 4, 6, 8));
            pinned.add(floorsPanel, BorderLayout.NORTH);

            JScrollPane scroll = new JScrollPane(pinned,
                    ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                    ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
            scroll.getVerticalScrollBar().setUnitIncrement(18);
            scroll.setBorder(BorderFactory.createLineBorder(CARD_BORDER));
            scroll.getViewport().setBackground(CARD_BG);
            wrap.add(scroll, BorderLayout.CENTER);
            return wrap;
        }

        private JComponent buildLegend() {
            JPanel legend = new JPanel(new FlowLayout(FlowLayout.LEFT, 16, 4));
            legend.setOpaque(false);
            legend.add(new LegendItem("Free (click to book)", MINT_TINT, MINT_LEAF));
            legend.add(new LegendItem("On hold (deposit pending)", AMBER_TINT, AMBER));
            legend.add(new LegendItem("Booked", DEEP_SPACE_BLUE, DEEP_SPACE_BLUE));
            legend.add(new LegendItem("Restricted", LOCKED_BG, TEXT_MUTED));
            legend.add(new LegendItem("Your room", CARD_BG, PEARL_AQUA));
            JLabel beds = new JLabel("\u25CF = one bed");
            beds.setForeground(TEXT_MUTED);
            legend.add(beds);
            return legend;
        }

        private JToggleButton typeChip(String text, RoomType type, ButtonGroup group, boolean selected) {
            PillToggle b = new PillToggle(text);
            b.setSelected(selected);
            group.add(b);
            b.addActionListener(e -> { typeFilter = type; refresh(); });
            return b;
        }

        // ================= Refresh =================

        void refresh() {
            List<Room> all = new ArrayList<>(system.allRooms());
            String mine = myRoomId();
            Student me = system.getStudent(roll);

            boolean special = me != null && me.needsAccessible();
            for (String b : blocks) {
                boolean restrictedBlock = "C".equals(b) && !special;
                cards.get(b).setStats(computeStats(all, b), b.equals(selectedBlock), restrictedBlock);
            }

            boolean blockRestricted = "C".equals(selectedBlock) && !special;
            lockBanner.setVisible(blockRestricted);
            switchBanner.setVisible(switchMode);
            lockText.setText(blockRestricted
                    ? "\uD83D\uDD12  Block C is reserved for special (accessibility) bookings. Rooms here are locked for your login."
                    : " ");
            yourRoomLabel.setText(mine == null
                    ? "Click a free room to book it."
                    : (switchMode
                        ? "\uD83D\uDECF  Your room: " + mine + "  \u2014  now click the free room you want to move to."
                        : "\uD83D\uDECF  Your room: " + mine + "  \u2014  to change rooms, click Switch Room on the My Reservation page."));

            List<Room> visible = new ArrayList<>();
            for (Room r : all) {
                if (!r.getBlock().equals(selectedBlock)) continue;
                if (typeFilter != null && r.getType() != typeFilter) continue;
                if (onlyAvailable && !"AVAILABLE".equals(r.getStatus().toString())) continue;
                visible.add(r);
            }
            visible.sort(Comparator.comparing(Room::getRoomId));

            StringBuilder sig = new StringBuilder(selectedBlock + "|" + typeFilter + "|" + onlyAvailable + "|");
            for (Room r : visible) sig.append(r.getRoomId()).append(',');

            Stats s = computeStats(all, selectedBlock);
            headline.setText("Block " + selectedBlock + "  \u00B7  " + s.free + " of " + s.total + " rooms free");
            subline.setText(blockBlurb(selectedBlock));

            if (!sig.toString().equals(lastSignature)) {
                lastSignature = sig.toString();
                rebuildFloors(visible, mine, me);
            } else {
                for (Room r : visible) {
                    RoomTile t = tiles.get(r.getRoomId());
                    if (t != null) t.setData(r, r.getRoomId().equals(mine), restrictedFor(r, me));
                }
            }
            floorsPanel.repaint();
        }

        private void rebuildFloors(List<Room> visible, String mine, Student me) {
            floorsPanel.removeAll();
            tiles.clear();

            if (visible.isEmpty()) {
                JLabel none = new JLabel("No rooms match these filters right now.", SwingConstants.CENTER);
                none.setForeground(TEXT_MUTED);
                none.setBorder(new EmptyBorder(40, 0, 40, 0));
                none.setAlignmentX(Component.LEFT_ALIGNMENT);
                floorsPanel.add(none);
            } else {
                TreeMap<Integer, List<Room>> byFloor = new TreeMap<>(Comparator.reverseOrder());
                for (Room r : visible) byFloor.computeIfAbsent(r.getFloor(), k -> new ArrayList<>()).add(r);

                for (Map.Entry<Integer, List<Room>> e : byFloor.entrySet()) {
                    JPanel row = new JPanel(new BorderLayout(14, 0));
                    row.setOpaque(false);
                    row.setAlignmentX(Component.LEFT_ALIGNMENT);
                    row.setBorder(BorderFactory.createCompoundBorder(
                            BorderFactory.createMatteBorder(0, 0, 1, 0, CARD_BORDER),
                            new EmptyBorder(10, 6, 10, 0)));

                    int free = 0;
                    for (Room r : e.getValue()) if ("AVAILABLE".equals(r.getStatus().toString())) free++;
                    row.add(new FloorLabel(e.getKey(), free, e.getValue().size()), BorderLayout.WEST);

                    JPanel grid = new JPanel(new GridLayout(0, TILES_PER_ROW, 6, 6));
                    grid.setOpaque(false);
                    for (Room r : e.getValue()) {
                        RoomTile t = new RoomTile(onRoomClick);
                        t.setData(r, r.getRoomId().equals(mine), restrictedFor(r, me));
                        tiles.put(r.getRoomId(), t);
                        grid.add(t);
                    }
                    int rem = e.getValue().size() % TILES_PER_ROW;
                    if (rem != 0) for (int i = rem; i < TILES_PER_ROW; i++) {
                        JPanel filler = new JPanel();
                        filler.setOpaque(false);
                        grid.add(filler);
                    }
                    row.add(grid, BorderLayout.CENTER);
                    floorsPanel.add(row);
                }
            }
            floorsPanel.revalidate();
            floorsPanel.repaint();
        }

        private String myRoomId() {
            try {
                Student s = system.getStudent(roll);
                if (s == null || !s.hasActiveBooking()) return null;
                Booking b = system.getBooking(s.getActiveBookingId());
                return b == null ? null : b.getRoomId();
            } catch (RuntimeException ex) {
                return null;
            }
        }

        private static String blockBlurb(String block) {
            switch (block) {
                case "A": return "Singles on floors 1\u20132, doubles on 2\u20133, shared 4-person rooms on 3\u20134.";
                case "B": return "Singles on floors 1\u20132, doubles on 2\u20133, shared 4-person rooms on 3\u20134.";
                default:  return "Ground-floor singles and doubles, next to the accessible entrance (special bookings only).";
            }
        }

        // ================= Stats =================

        private static final class Stats {
            int total, free, held, booked;
            final Map<String, int[]> perType = new LinkedHashMap<>();   // type -> {free, total}
            Stats() {
                perType.put("SINGLE", new int[2]);
                perType.put("DOUBLE", new int[2]);
                perType.put("SHARED", new int[2]);
            }
        }

        private static Stats computeStats(List<Room> all, String block) {
            Stats s = new Stats();
            for (Room r : all) {
                if (!r.getBlock().equals(block)) continue;
                s.total++;
                String st = r.getStatus().toString();
                boolean free = "AVAILABLE".equals(st);
                if (free) s.free++;
                else if ("HELD".equals(st)) s.held++;
                else s.booked++;
                int[] t = s.perType.get(r.getType().name());
                if (t != null) { t[1]++; if (free) t[0]++; }
            }
            return s;
        }

        // ================= Custom components =================

        /** Clickable summary card for one block: big letter, live free count and a stacked availability bar. */
        private final class BlockCard extends JComponent {
            private final String block;
            private Stats stats = new Stats();
            private boolean selected, restricted, hover;

            BlockCard(String block) {
                this.block = block;
                setPreferredSize(new Dimension(300, 132));
                setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                addMouseListener(new MouseAdapter() {
                    @Override public void mouseClicked(MouseEvent e) {
                        selectedBlock = block;
                        refresh();
                    }
                    @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                    @Override public void mouseExited(MouseEvent e) { hover = false; repaint(); }
                });
            }

            void setStats(Stats s, boolean selected, boolean restricted) {
                this.stats = s;
                this.selected = selected;
                this.restricted = restricted;
                setToolTipText("Click to view Block " + block);
                repaint();
            }

            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int w = getWidth() - 6, h = getHeight() - 6;

                for (int i = 4; i >= 1; i--) {
                    g2.setColor(new Color(0x0B, 0x39, 0x48, 5 + i));
                    g2.fillRoundRect(i, i + 2, Math.max(w - 2 * i, 0), Math.max(h - 2 * i, 0), 16, 16);
                }
                g2.setColor(selected ? DEEP_SPACE_BLUE : CARD_BG);
                g2.fillRoundRect(0, 0, w, h, 16, 16);
                g2.setColor(selected ? DEEP_SPACE_BLUE : (hover ? PACIFIC_CYAN : CARD_BORDER));
                g2.setStroke(new BasicStroke(hover && !selected ? 1.6f : 1f));
                g2.drawRoundRect(0, 0, w - 1, h - 1, 16, 16);

                Color main = selected ? Color.WHITE : DEEP_SPACE_BLUE;
                Color muted = selected ? new Color(255, 255, 255, 170) : TEXT_MUTED;

                g2.setColor(restricted ? AMBER : MINT_LEAF);
                g2.fillOval(16, 16, 46, 46);
                if (restricted) {   // padlock in the card's top-right corner
                    g2.setColor(selected ? Color.WHITE : AMBER.darker());
                    g2.setStroke(new BasicStroke(2f));
                    g2.drawArc(w - 33, 14, 12, 16, 0, 180);
                    g2.fillRoundRect(w - 36, 24, 18, 14, 4, 4);
                    g2.setStroke(new BasicStroke(1f));
                }
                g2.setColor(Color.WHITE);
                g2.setFont(getFont().deriveFont(Font.BOLD, 24f));
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString(block, 16 + (46 - fm.stringWidth(block)) / 2, 16 + (46 + fm.getAscent() - fm.getDescent()) / 2);

                g2.setColor(main);
                g2.setFont(getFont().deriveFont(Font.BOLD, 16f));
                g2.drawString("Block " + block, 74, 34);
                g2.setColor(muted);
                g2.setFont(getFont().deriveFont(Font.PLAIN, 12f));
                String tag = restricted ? "Special bookings only" : (block.equals("C") ? "Ground floor \u00B7 special" : "General");
                g2.drawString(stats.free + " of " + stats.total + " free  \u00B7  " + tag, 74, 54);

                int bx = 16, by = 74, bw = w - 32, bh = 9;
                g2.setColor(selected ? new Color(255, 255, 255, 40) : new Color(0xE6, 0xE8, 0xF2));
                g2.fillRoundRect(bx, by, bw, bh, bh, bh);
                if (stats.total > 0) {
                    int freeW = bw * stats.free / stats.total;
                    int heldW = bw * stats.held / stats.total;
                    int bookW = bw * stats.booked / stats.total;
                    Shape oldClip = g2.getClip();
                    g2.setClip(new java.awt.geom.RoundRectangle2D.Float(bx, by, bw, bh, bh, bh));
                    g2.setColor(MINT_LEAF);
                    g2.fillRect(bx, by, freeW, bh);
                    g2.setColor(AMBER);
                    g2.fillRect(bx + freeW, by, heldW, bh);
                    g2.setColor(selected ? PEARL_AQUA : DEEP_SPACE_BLUE);
                    g2.fillRect(bx + freeW + heldW, by, bookW, bh);
                    g2.setClip(oldClip);
                }

                g2.setFont(getFont().deriveFont(Font.PLAIN, 11.5f));
                int x = 16, y = 108;
                for (Map.Entry<String, int[]> e : stats.perType.entrySet()) {
                    int[] v = e.getValue();
                    if (v[1] == 0) continue;
                    String word = e.getKey().substring(0, 1) + e.getKey().substring(1).toLowerCase();
                    g2.setColor(muted);
                    String label = word + " ";
                    g2.drawString(label, x, y);
                    x += g2.getFontMetrics().stringWidth(label);
                    g2.setColor(main);
                    g2.setFont(getFont().deriveFont(Font.BOLD, 11.5f));
                    String nums = v[0] + "/" + v[1];
                    g2.drawString(nums, x, y);
                    x += g2.getFontMetrics().stringWidth(nums) + 16;
                    g2.setFont(getFont().deriveFont(Font.PLAIN, 11.5f));
                }
                g2.dispose();
            }
        }

        /** Left-hand label of a floor row. */
        private static final class FloorLabel extends JComponent {
            private final String name;
            private final String count;
            FloorLabel(int floor, int free, int total) {
                this.name = floor == 0 ? "GROUND" : "FLOOR " + floor;
                this.count = free + "/" + total + " free";
                setPreferredSize(new Dimension(92, 40));
            }
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(MINT_LEAF);
                g2.fillRoundRect(0, 2, 4, 36, 4, 4);
                g2.setColor(DEEP_SPACE_BLUE);
                g2.setFont(getFont().deriveFont(Font.BOLD, 12.5f));
                g2.drawString(name, 14, 16);
                g2.setColor(TEXT_MUTED);
                g2.setFont(getFont().deriveFont(Font.PLAIN, 11f));
                g2.drawString(count, 14, 32);
                g2.dispose();
            }
        }

        /** One room. Shows ID, one dot per bed, and a short status word. Clicking it reports the room ID. */
        private static final class RoomTile extends JComponent {
            private String id = "", type = "SINGLE", status = "AVAILABLE";
            private boolean mine, locked, hover;

            RoomTile(Consumer<String> onClick) {
                setPreferredSize(new Dimension(78, 56));
                addMouseListener(new MouseAdapter() {
                    @Override public void mouseClicked(MouseEvent e) {
                        if (onClick != null && !id.isEmpty()) onClick.accept(id);
                    }
                    @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                    @Override public void mouseExited(MouseEvent e) { hover = false; repaint(); }
                });
            }

            void setData(Room r, boolean mine, boolean locked) {
                String newStatus = r.getStatus().toString();
                boolean changed = !newStatus.equals(status) || mine != this.mine || locked != this.locked
                        || !r.getRoomId().equals(id);
                this.id = r.getRoomId();
                this.type = r.getType().name();
                this.status = newStatus;
                this.mine = mine;
                this.locked = locked;
                boolean bookable = "AVAILABLE".equals(newStatus) && !locked;
                setCursor(Cursor.getPredefinedCursor(bookable ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
                String booking = r.getCurrentBookingId();
                setToolTipText("<html><b>" + id + "</b><br>" + type.charAt(0) + type.substring(1).toLowerCase()
                        + " room \u00B7 " + beds() + (beds() == 1 ? " bed" : " beds")
                        + "<br>Floor " + r.getFloor() + " \u00B7 accessible"
                        + "<br>Status: " + newStatus.toLowerCase()
                        + (booking == null ? "" : "<br>Booking " + booking)
                        + (bookable ? "<br><i>Click to book</i>" : "") + "</html>");
                if (changed) repaint();
            }

            private int beds() {
                switch (type) {
                    case "SINGLE": return 1;
                    case "DOUBLE": return 2;
                    default:       return 4;
                }
            }

            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int w = getWidth() - 1, h = getHeight() - 1;

                boolean free = "AVAILABLE".equals(status);
                boolean held = "HELD".equals(status);
                Color bg, edge, text, dots;
                String word;
                if (locked) {
                    bg = LOCKED_BG; edge = new Color(0xC4, 0xC1, 0xCE); text = TEXT_MUTED; dots = TEXT_MUTED; word = "LOCKED";
                } else if (free) {
                    bg = MINT_TINT; edge = MINT_LEAF; text = DEEP_SPACE_BLUE; dots = MINT_LEAF; word = "FREE";
                } else if (held) {
                    bg = AMBER_TINT; edge = AMBER; text = DEEP_SPACE_BLUE; dots = AMBER; word = "HOLD";
                } else {
                    bg = DEEP_SPACE_BLUE; edge = DEEP_SPACE_BLUE; text = Color.WHITE; dots = PEARL_AQUA; word = "TAKEN";
                }

                g2.setColor(bg);
                g2.fillRoundRect(0, 0, w, h, 12, 12);
                if (locked) {
                    Shape old = g2.getClip();
                    g2.setClip(new java.awt.geom.RoundRectangle2D.Float(0, 0, w, h, 12, 12));
                    g2.setColor(new Color(0, 0, 0, 14));
                    for (int i = -h; i < w; i += 8) g2.drawLine(i, h, i + h, 0);
                    g2.setClip(old);
                }
                g2.setColor(edge);
                boolean bookable = free && !locked;
                g2.setStroke(new BasicStroke(hover && bookable ? 2.6f : (free ? 1.5f : 1f)));
                g2.drawRoundRect(0, 0, w, h, 12, 12);

                g2.setColor(text);
                g2.setFont(getFont().deriveFont(Font.BOLD, 12f));
                g2.drawString(id, 9, 21);

                g2.setColor(dots);
                int n = beds();
                for (int i = 0; i < n; i++) g2.fillOval(9 + i * 8, h - 16, 5, 5);

                g2.setFont(getFont().deriveFont(Font.BOLD, 8.5f));
                g2.setColor(locked ? TEXT_MUTED : (free ? MINT_LEAF : (held ? AMBER : PEARL_AQUA)));
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString(word, w - 8 - fm.stringWidth(word), h - 9);

                if (mine) {
                    g2.setColor(PEARL_AQUA);
                    g2.setStroke(new BasicStroke(3.2f));
                    g2.drawRoundRect(1, 1, w - 2, h - 2, 12, 12);
                    g2.setColor(DARK_TEAL);
                    g2.fillRoundRect(w - 26, -1, 26, 14, 8, 8);
                    g2.setColor(Color.WHITE);
                    g2.setFont(getFont().deriveFont(Font.BOLD, 9f));
                    g2.drawString("YOU", w - 22, 9);
                }
                g2.dispose();
            }
        }

        /** Small legend entry: rounded swatch plus text. */
        private static final class LegendItem extends JComponent {
            private final String text;
            private final Color fill, edge;
            LegendItem(String text, Color fill, Color edge) {
                this.text = text; this.fill = fill; this.edge = edge;
                setPreferredSize(new Dimension(24 + new JLabel(text).getPreferredSize().width + 8, 20));
            }
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(fill);
                g2.fillRoundRect(0, 3, 16, 14, 5, 5);
                g2.setColor(edge);
                g2.setStroke(new BasicStroke(1.4f));
                g2.drawRoundRect(0, 3, 15, 13, 5, 5);
                g2.setColor(TEXT_MUTED);
                g2.drawString(text, 24, 15);
                g2.dispose();
            }
        }

        /** Rounded filter chip that turns dark when selected. */
        private static final class PillToggle extends JToggleButton {
            PillToggle(String text) {
                super(text);
                setContentAreaFilled(false);
                setFocusPainted(false);
                setBorder(new EmptyBorder(6, 15, 6, 15));
                setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                setForeground(DEEP_SPACE_BLUE);
                addChangeListener(e -> setForeground(isSelected() ? Color.WHITE : DEEP_SPACE_BLUE));
            }
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(isSelected() ? DEEP_SPACE_BLUE : CARD_BG);
                g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, getHeight(), getHeight());
                g2.setColor(isSelected() ? DEEP_SPACE_BLUE : CARD_BORDER);
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, getHeight(), getHeight());
                g2.dispose();
                super.paintComponent(g);
            }
        }
    }
}