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
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Swing GUI for the corrected Aurora Hostel Booking system (Block B demo).
 *
 * This is a thin presentation layer only - all booking rules, validation
 * order, the atomic race-condition fix, deposit-expiry, cancel/switch, and
 * the audit trail live in HostelBookingSystem / Room / Student / Booking,
 * exactly as described in the redesign document. The GUI just calls those
 * methods and renders the results.
 *
 * NOTE ON TIME: the real deposit window is 48 hours. For an interactive demo
 * that window is shortened to 25 seconds so you can actually watch a booking
 * expire; the expiry logic itself (HostelBookingSystem.releaseExpiredBookings)
 * is identical to what would run in production against a real Duration.ofHours(48).
 */
public class HostelBookingGUI extends JFrame {

    private static final Duration DEMO_DEPOSIT_WINDOW = Duration.ofSeconds(25);

    // ---- palette ----
    private static final Color DEEP_SPACE_BLUE = new Color(0x17, 0x2A, 0x3A);
    private static final Color DARK_TEAL       = new Color(0x00, 0x43, 0x46);
    private static final Color PACIFIC_CYAN    = new Color(0x50, 0x89, 0x91);
    private static final Color MINT_LEAF       = new Color(0x09, 0xBC, 0x8A);
    private static final Color PEARL_AQUA      = new Color(0x75, 0xDD, 0xDD);

    private static final Color PAGE_BG     = new Color(0xF1, 0xFA, 0xF9);
    private static final Color CARD_BG     = Color.WHITE;
    private static final Color CARD_BORDER = new Color(0xD4, 0xEC, 0xEA);
    private static final Color TEXT_MUTED  = new Color(0x5C, 0x72, 0x78);
    private static final Color AMBER = new Color(0xC7, 0x77, 0x00);
    private static final Color RED   = new Color(0xC6, 0x28, 0x28);

    private final HostelBookingSystem system = buildBlockBSystem();

    // ---- navigation ----
    private JPanel contentPanel;
    private CardLayout contentLayout;
    private NavButton navBook, navRooms, navRace, navLog;

    // ---- UI components referenced across methods ----
    private JComboBox<String> studentCombo;
    private JLabel studentInfoLabel;
    private JComboBox<RoomType> bookTypeCombo;
    private JCheckBox accessibleCheck;

    private JLabel myBookingIdLabel, myRoomLabel, myDeadlineLabel;
    private Chip myStatusChip;
    private JButton payButton, cancelButton, switchButton;
    private JComboBox<RoomType> switchTypeCombo;
    private JPanel myReservationDetails;
    private ShadowCard myReservationCard;

    private DefaultTableModel roomTableModel;
    private JTable roomTable;
    private JLabel availableCountLabel, heldCountLabel, bookedCountLabel;

    private JTextArea auditArea;
    private JTextArea raceArea;

    public HostelBookingGUI() {
        super("Aurora University Hostel Booking Portal \u2014");
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
        contentPanel.add(buildRoomStatusPage(), "ROOMS");
        contentPanel.add(buildRaceDemoPage(), "RACE");
        contentPanel.add(buildAuditPage(), "LOG");
        body.add(contentPanel, BorderLayout.CENTER);

        add(body, BorderLayout.CENTER);

        // Auto-refresh: room table, my-booking panel, and audit log every second,
        // so deposit countdowns and expiries are visible live without manual clicks.
        Timer timer = new Timer(1000, e -> {
            refreshRoomTable();
            refreshMyBooking();
            refreshAuditLog();
        });
        timer.start();

        refreshStudentCombo();
        refreshRoomTable();
        refreshMyBooking();
        refreshAuditLog();

        pack();
        Dimension size = getSize();
        size.width = Math.min(Math.max(size.width, 980), 1200);
        size.height = Math.min(Math.max(size.height, 640), 780);
        setSize(size);
        setMinimumSize(new Dimension(860, 580));
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
        navBook = new NavButton("\uD83D\uDECF", "Book / My Reservation");
        navRooms = new NavButton("\uD83C\uDFE2", "Room Status (Admin)");
        navRace = new NavButton("\u26A1", "Race-Condition Demo");
        navLog = new NavButton("\uD83D\uDCDC", "Audit Log");

        navBook.addActionListener(e -> contentLayout.show(contentPanel, "BOOK"));
        navRooms.addActionListener(e -> contentLayout.show(contentPanel, "ROOMS"));
        navRace.addActionListener(e -> contentLayout.show(contentPanel, "RACE"));
        navLog.addActionListener(e -> contentLayout.show(contentPanel, "LOG"));

        for (NavButton nb : new NavButton[]{navBook, navRooms, navRace, navLog}) {
            group.add(nb);
            nb.setAlignmentX(Component.LEFT_ALIGNMENT);
            nb.setMaximumSize(new Dimension(Integer.MAX_VALUE, 46));
            sidebar.add(nb);
            sidebar.add(Box.createVerticalStrut(6));
        }
        navBook.setSelected(true);

        sidebar.add(Box.createVerticalGlue());
        JLabel footer = new JLabel("<html>Aurora Hostel Office<br/></html>");
        footer.setForeground(new Color(255, 255, 255, 140));
        footer.setFont(footer.getFont().deriveFont(Font.PLAIN, 11f));
        footer.setAlignmentX(Component.LEFT_ALIGNMENT);
        footer.setBorder(new EmptyBorder(8, 8, 4, 8));
        sidebar.add(footer);

        return sidebar;
    }

    // ================= Page 1: Book / My Reservation =================
    private JComponent buildBookingPage() {
        JPanel page = new JPanel(new BorderLayout(0, 12));
        page.setBackground(PAGE_BG);
        page.setBorder(new EmptyBorder(18, 20, 16, 20));

        JPanel loginContent = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        loginContent.setOpaque(false);
        studentCombo = new JComboBox<>();
        studentCombo.addActionListener(e -> refreshMyBooking());
        studentInfoLabel = new JLabel();
        studentInfoLabel.setForeground(TEXT_MUTED);
        JButton registerBtn = new JButton("Register New Student\u2026");
        styleSecondaryButton(registerBtn);
        registerBtn.addActionListener(e -> registerStudentDialog());
        loginContent.add(new JLabel("Roll Number:"));
        loginContent.add(studentCombo);
        loginContent.add(studentInfoLabel);
        loginContent.add(Box.createHorizontalStrut(12));
        loginContent.add(registerBtn);
        JComponent loginCard = card("\uD83C\uDF93  Logged in as", loginContent, MINT_LEAF);

        JPanel columns = new JPanel(new GridLayout(1, 2, 14, 0));
        columns.setOpaque(false);

        JPanel bookContent = new JPanel(new GridBagLayout());
        bookContent.setOpaque(false);
        GridBagConstraints bc = new GridBagConstraints();
        bc.insets = new Insets(4, 4, 8, 4);
        bc.anchor = GridBagConstraints.WEST;
        bc.gridx = 0; bc.gridy = 0;
        bookContent.add(new JLabel("Room type:"), bc);
        bc.gridx = 1;
        bookTypeCombo = new JComboBox<>(RoomType.values());
        bookContent.add(bookTypeCombo, bc);
        bc.gridx = 0; bc.gridy = 1; bc.gridwidth = 2;
        accessibleCheck = new JCheckBox("I need a ground-floor accessible room (Block C rule)");
        accessibleCheck.setOpaque(false);
        bookContent.add(accessibleCheck, bc);
        bc.gridx = 0; bc.gridy = 2; bc.gridwidth = 2;
        JButton bookBtn = new JButton("Book Room");
        stylePrimaryButton(bookBtn);
        bookBtn.addActionListener(e -> doBook());
        bookContent.add(bookBtn, bc);
        columns.add(topPinned(card("\uD83D\uDECF  Book a Room", bookContent, PACIFIC_CYAN)));

        myReservationCard = (ShadowCard) card("\uD83D\uDCCB  My Current Reservation", buildMyReservationContent(), DARK_TEAL);
        columns.add(topPinned(myReservationCard));

        JPanel middleWrap = topPinned(columns);

        JPanel stack = new JPanel();
        stack.setLayout(new BoxLayout(stack, BoxLayout.Y_AXIS));
        stack.setOpaque(false);
        loginCard.setAlignmentX(Component.LEFT_ALIGNMENT);
        middleWrap.setAlignmentX(Component.LEFT_ALIGNMENT);
        stack.add(loginCard);
        stack.add(Box.createVerticalStrut(12));
        stack.add(middleWrap);

        page.add(topPinned(stack), BorderLayout.CENTER);

 

        return page;
    }

    private JComponent buildMyReservationContent() {
        JPanel wrapper = new JPanel(new CardLayout());
        wrapper.setOpaque(false);

        JPanel emptyState = new JPanel(new BorderLayout());
        emptyState.setOpaque(false);
        JLabel emptyLabel = new JLabel("<html>You have no active reservation.<br>Book a room on the left to get started.</html>");
        emptyLabel.setForeground(TEXT_MUTED);
        emptyState.add(emptyLabel, BorderLayout.NORTH);

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
        switchTypeCombo = new JComboBox<>(RoomType.values());
        switchButton = new JButton("Switch To \u2192");
        styleSecondaryButton(switchButton);
        switchButton.addActionListener(e -> doSwitch());
        actionRow.add(payButton);
        actionRow.add(cancelButton);
        actionRow.add(new JLabel("  Switch to type:"));
        actionRow.add(switchTypeCombo);
        actionRow.add(switchButton);
        gc.gridx = 0; gc.gridy = row; gc.gridwidth = 2;
        myReservationDetails.add(actionRow, gc);

        wrapper.add(emptyState, "EMPTY");
        wrapper.add(myReservationDetails, "DETAILS");
        return wrapper;
    }

    // ================= Page 2: Room Status (Admin) =================
    private JComponent buildRoomStatusPage() {
        JPanel page = new JPanel(new BorderLayout(0, 12));
        page.setBackground(PAGE_BG);
        page.setBorder(new EmptyBorder(18, 20, 16, 20));

        availableCountLabel = new JLabel("0");
        heldCountLabel = new JLabel("0");
        bookedCountLabel = new JLabel("0");
        JPanel statRow = new JPanel(new GridLayout(1, 3, 12, 0));
        statRow.setOpaque(false);
        statRow.add(statCard("AVAILABLE", availableCountLabel, MINT_LEAF));
        statRow.add(statCard("HELD (AWAITING PAYMENT)", heldCountLabel, AMBER));
        statRow.add(statCard("BOOKED (CONFIRMED)", bookedCountLabel, DEEP_SPACE_BLUE));

        roomTableModel = new DefaultTableModel(
                new Object[]{"Room ID", "Block", "Type", "Floor", "Accessible", "Status", "Current Booking"}, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        roomTable = new JTable(roomTableModel);
        roomTable.setRowHeight(27);
        roomTable.setFillsViewportHeight(true);
        roomTable.setSelectionBackground(PEARL_AQUA);
        roomTable.setSelectionForeground(DEEP_SPACE_BLUE);
        roomTable.setGridColor(CARD_BORDER);
        roomTable.getTableHeader().setFont(roomTable.getTableHeader().getFont().deriveFont(Font.BOLD, 12f));
        roomTable.getTableHeader().setBackground(DEEP_SPACE_BLUE);
        roomTable.getTableHeader().setForeground(Color.WHITE);
        roomTable.getColumnModel().getColumn(5).setCellRenderer(new StatusCellRenderer());
        JScrollPane tableScroll = new JScrollPane(roomTable);
        tableScroll.setPreferredSize(new Dimension(900, 270));
        tableScroll.setBorder(BorderFactory.createLineBorder(CARD_BORDER));

        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        bottom.setOpaque(false);
        JButton refreshBtn = new JButton("Refresh Now");
        styleSecondaryButton(refreshBtn);
        refreshBtn.addActionListener(e -> refreshRoomTable());
        JButton sweepBtn = new JButton("Run Deposit-Expiry Sweep Now");
        stylePrimaryButton(sweepBtn);
        sweepBtn.addActionListener(e -> {
            List<String> expired = system.releaseExpiredBookings();
            refreshRoomTable();
            refreshMyBooking();
            refreshAuditLog();
            JOptionPane.showMessageDialog(this,
                    expired.isEmpty() ? "No pending bookings were past their deposit deadline."
                            : "Expired and released: " + expired,
                    "Deposit-Expiry Sweep", JOptionPane.INFORMATION_MESSAGE);
        });
        JLabel note = new JLabel("  (auto-refreshes every second)");
        note.setForeground(TEXT_MUTED);
        bottom.add(refreshBtn);
        bottom.add(sweepBtn);
        bottom.add(note);

        JPanel tableCardContent = new JPanel(new BorderLayout(0, 8));
        tableCardContent.setOpaque(false);
        tableCardContent.add(tableScroll, BorderLayout.CENTER);
        tableCardContent.add(bottom, BorderLayout.SOUTH);

        page.add(statRow, BorderLayout.NORTH);
        page.add(card("\uD83C\uDFE2  Block B \u2014 Live Room Inventory", tableCardContent, MINT_LEAF), BorderLayout.CENTER);
        return page;
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
    private JComponent buildAuditPage() {
        JPanel page = new JPanel(new BorderLayout(0, 10));
        page.setBackground(PAGE_BG);
        page.setBorder(new EmptyBorder(18, 20, 16, 20));
        auditArea = new JTextArea();
        auditArea.setEditable(false);
        auditArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        auditArea.setBackground(CARD_BG);
        auditArea.setForeground(DEEP_SPACE_BLUE);
        auditArea.setMargin(new Insets(10, 10, 10, 10));
        JScrollPane scroll = new JScrollPane(auditArea);
        scroll.setPreferredSize(new Dimension(900, 320));
        scroll.setBorder(BorderFactory.createLineBorder(CARD_BORDER));

        JLabel note = new JLabel("Every booking-related event (booked, payment received, expired, cancelled, switched, rejected) is timestamped here \u2014 auto-refreshes every second.");
        note.setForeground(TEXT_MUTED);

        JPanel content = new JPanel(new BorderLayout(0, 8));
        content.setOpaque(false);
        content.add(scroll, BorderLayout.CENTER);
        content.add(note, BorderLayout.SOUTH);

        page.add(card("\uD83D\uDCDC  Full Audit Trail", content, DARK_TEAL), BorderLayout.CENTER);
        return page;
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
        b.setForeground(DEEP_SPACE_BLUE);
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
        Color textColor = PACIFIC_CYAN.darker();
        b.setForeground(textColor);
        b.setBackground(CARD_BG);
        b.setFocusPainted(false);
        b.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(PACIFIC_CYAN, 1, true),
                new EmptyBorder(8, 15, 8, 15)));
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { b.setBackground(new Color(0xE9, 0xF6, 0xF6)); }
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
                g2.setColor(MINT_LEAF);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12);
                setForeground(DEEP_SPACE_BLUE);
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
            // soft layered shadow
            int shadowDepth = 5;
            for (int i = shadowDepth; i >= 1; i--) {
                g2.setColor(new Color(0x17, 0x2A, 0x3A, 5 + i));
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

    private static class StatusCellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                         boolean hasFocus, int row, int column) {
            JLabel label = (JLabel) super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            label.setFont(label.getFont().deriveFont(Font.BOLD));
            String status = String.valueOf(value);
            if ("AVAILABLE".equals(status)) label.setForeground(MINT_LEAF.darker());
            else if ("HELD".equals(status)) label.setForeground(AMBER);
            else if ("BOOKED".equals(status)) label.setForeground(DEEP_SPACE_BLUE);
            else label.setForeground(TEXT_MUTED);
            return label;
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

    // ================= Actions =================

    private String currentRoll() {
        return (String) studentCombo.getSelectedItem();
    }

    private void doBook() {
        String roll = currentRoll();
        if (roll == null) { warn("Register or select a student first."); return; }
        try {
            Booking b = system.bookRoom(roll, (RoomType) bookTypeCombo.getSelectedItem(), accessibleCheck.isSelected());
            info("Booked " + b.getRoomId() + " as " + b.getBookingId() + ". Deposit due by " + b.getDepositDeadline());
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

    private void doSwitch() {
        Student s = system.getStudent(currentRoll());
        if (s == null || !s.hasActiveBooking()) { warn("No active booking to switch."); return; }
        try {
            Booking nb = system.switchBooking(s.getActiveBookingId(), (RoomType) switchTypeCombo.getSelectedItem());
            info("Switched to room " + nb.getRoomId() + " (" + nb.getBookingId() + ").");
        } catch (BookingException ex) {
            warn(ex.getMessage());
        }
        refreshAll();
    }

    private void registerStudentDialog() {
        JTextField rollField = new JTextField();
        JTextField nameField = new JTextField();
        JCheckBox accCheck = new JCheckBox("Has a documented accessibility requirement");
        JPanel form = new JPanel(new GridLayout(0, 1, 4, 4));
        form.add(new JLabel("Roll Number:")); form.add(rollField);
        form.add(new JLabel("Name:")); form.add(nameField);
        form.add(accCheck);
        int result = JOptionPane.showConfirmDialog(this, form, "Register New Student", JOptionPane.OK_CANCEL_OPTION);
        if (result == JOptionPane.OK_OPTION) {
            String roll = rollField.getText().trim();
            String name = nameField.getText().trim();
            if (roll.isEmpty() || name.isEmpty()) { warn("Roll number and name are required."); return; }
            if (system.getStudent(roll) != null) { warn("A student with that roll number already exists."); return; }
            system.addStudent(new Student(roll, name, accCheck.isSelected()));
            refreshStudentCombo();
            studentCombo.setSelectedItem(roll);
        }
    }

    private void refreshAll() {
        refreshRoomTable();
        refreshMyBooking();
        refreshAuditLog();
    }

    private void refreshStudentCombo() {
        String previous = currentRoll();
        studentCombo.removeAllItems();
        for (Student s : system.allStudents()) studentCombo.addItem(s.getRollNumber());
        if (previous != null) studentCombo.setSelectedItem(previous);
    }

    private void refreshMyBooking() {
        Student s = system.getStudent(currentRoll());
        JPanel wrapper = (JPanel) myReservationDetails.getParent();
        CardLayout cl = (CardLayout) wrapper.getLayout();
        if (s == null) {
            studentInfoLabel.setText("");
            cl.show(wrapper, "EMPTY");
            myReservationCard.setAccent(DARK_TEAL);
            return;
        }
        studentInfoLabel.setText("(" + s.getName() + (s.needsAccessible() ? ", accessibility requirement" : "") + ")");
        if (!s.hasActiveBooking()) {
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
        switchButton.setEnabled(b.getStatus() == BookingStatus.PENDING_PAYMENT || b.getStatus() == BookingStatus.CONFIRMED);
    }

    private void refreshRoomTable() {
        int selectedRow = roomTable != null ? roomTable.getSelectedRow() : -1;
        roomTableModel.setRowCount(0);
        int available = 0, held = 0, booked = 0;
        for (Room r : system.allRooms()) {
            roomTableModel.addRow(new Object[]{
                    r.getRoomId(), r.getBlock(), r.getType(), r.getFloor(),
                    r.isAccessible() ? "Yes" : "No", r.getStatus().toString(),
                    r.getCurrentBookingId() == null ? "\u2014" : r.getCurrentBookingId()
            });
            switch (r.getStatus()) {
                case AVAILABLE: available++; break;
                case HELD: held++; break;
                case BOOKED: booked++; break;
            }
        }
        if (availableCountLabel != null) {
            availableCountLabel.setText(String.valueOf(available));
            heldCountLabel.setText(String.valueOf(held));
            bookedCountLabel.setText(String.valueOf(booked));
        }
        if (selectedRow >= 0 && selectedRow < roomTableModel.getRowCount()) {
            roomTable.setRowSelectionInterval(selectedRow, selectedRow);
        }
    }

    private void refreshAuditLog() {
        StringBuilder sb = new StringBuilder();
        for (String line : system.getAuditLog().snapshot()) sb.append(line).append('\n');
        String text = sb.toString();
        if (!text.equals(auditArea.getText())) {
            auditArea.setText(text);
            auditArea.setCaretPosition(auditArea.getDocument().getLength());
        }
    }

    private void info(String msg) {
        JOptionPane.showMessageDialog(this, msg, "Success", JOptionPane.INFORMATION_MESSAGE);
    }

    private void warn(String msg) {
        JOptionPane.showMessageDialog(this, msg, "Rejected", JOptionPane.WARNING_MESSAGE);
    }

    // ================= Demo data =================
    private static HostelBookingSystem buildBlockBSystem() {
        HostelBookingSystem system = new HostelBookingSystem(DEMO_DEPOSIT_WINDOW);

        system.addRoom(new Room("B-S-001", "B", RoomType.SINGLE, 1, false));
        system.addRoom(new Room("B-S-002", "B", RoomType.SINGLE, 1, false));
        system.addRoom(new Room("B-S-003", "B", RoomType.SINGLE, 2, false));
        system.addRoom(new Room("B-D-001", "B", RoomType.DOUBLE, 1, false));
        system.addRoom(new Room("B-D-002", "B", RoomType.DOUBLE, 2, false));
        system.addRoom(new Room("B-D-003", "B", RoomType.DOUBLE, 2, false));
        system.addRoom(new Room("B-H-001", "B", RoomType.SHARED, 3, false));
        system.addRoom(new Room("B-H-002", "B", RoomType.SHARED, 3, false));
        system.addRoom(new Room("C-S-001", "C", RoomType.SINGLE, 0, true));
        system.addRoom(new Room("C-D-001", "C", RoomType.DOUBLE, 0, true));

        system.addStudent(new Student("BUIC-001", "Hassan", false));
        system.addStudent(new Student("BUIC-002", "Ayesha", false));
        system.addStudent(new Student("BUIC-003", "Bilal", false));
        system.addStudent(new Student("BUIC-004", "Zara", true));

        return system;
    }

    public static void main(String[] args) {
        applyTheme();
        SwingUtilities.invokeLater(() -> new HostelBookingGUI().setVisible(true));
    }

    /** Nimbus retinted with the brand palette, plus a nicer default font where available. */
    private static void applyTheme() {
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
            UIManager.put("nimbusSelectedText", DEEP_SPACE_BLUE);
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
}