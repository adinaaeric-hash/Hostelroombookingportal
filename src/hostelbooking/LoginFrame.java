package hostelbooking;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/** Sign-in window shown before the main portal. Uses the same palette as HostelBookingGUI. */
public class LoginFrame extends JFrame {

    // ---- same palette as HostelBookingGUI ----
    private static final Color DEEP_SPACE_BLUE = new Color(0x17, 0x2A, 0x3A);
    private static final Color DARK_TEAL       = new Color(0x00, 0x43, 0x46);
    private static final Color PACIFIC_CYAN    = new Color(0x50, 0x89, 0x91);
    private static final Color MINT_LEAF       = new Color(0x09, 0xBC, 0x8A);
    private static final Color PAGE_BG         = new Color(0xF1, 0xFA, 0xF9);
    private static final Color CARD_BG         = Color.WHITE;
    private static final Color CARD_BORDER     = new Color(0xD4, 0xEC, 0xEA);
    private static final Color TEXT_MUTED      = new Color(0x5C, 0x72, 0x78);
    private static final Color RED             = new Color(0xC6, 0x28, 0x28);

    private final JTextField idField = new JTextField();
    private final JPasswordField passField = new JPasswordField();
    private final JLabel errorLabel = new JLabel(" ");

    public LoginFrame() {
        super("Aurora University Hostel Booking Portal \u2014 Sign in");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout());
        getContentPane().setBackground(PAGE_BG);

        add(buildHeaderBar(), BorderLayout.NORTH);

        JPanel center = new JPanel(new GridBagLayout());
        center.setBackground(PAGE_BG);
        center.add(buildCard());
        add(center, BorderLayout.CENTER);

        setSize(760, 700);
        setMinimumSize(new Dimension(560, 680));
        setLocationRelativeTo(null);
    }

    // ================= Layout =================

    private JComponent buildHeaderBar() {
        GradientBar bar = new GradientBar();
        bar.setLayout(new BorderLayout());
        bar.setBorder(new EmptyBorder(14, 22, 14, 22));
        JLabel title = new JLabel("\uD83C\uDFE8  Aurora University Hostel Booking Portal");
        title.setForeground(Color.WHITE);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 19f));
        bar.add(title, BorderLayout.WEST);
        return bar;
    }

    private JComponent buildCard() {
        Card card = new Card();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBorder(new EmptyBorder(26, 34, 30, 34));
        card.setPreferredSize(new Dimension(480, 560));

        JLabel title = new JLabel("Sign in");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 26f));
        title.setForeground(DEEP_SPACE_BLUE);
        left(title);

        JLabel sub = new JLabel("Use your student ID and password to continue");
        sub.setForeground(TEXT_MUTED);
        left(sub);

        card.add(title);
        card.add(Box.createVerticalStrut(2));
        card.add(sub);
        card.add(Box.createVerticalStrut(18));

        card.add(fieldLabel("Student ID"));
        card.add(Box.createVerticalStrut(4));
        styleField(idField);
        card.add(idField);
        card.add(Box.createVerticalStrut(12));

        card.add(fieldLabel("Password"));
        card.add(Box.createVerticalStrut(4));
        styleField(passField);
        card.add(passField);
        card.add(Box.createVerticalStrut(8));

        JCheckBox show = new JCheckBox("Show password");
        show.setOpaque(false);
        show.setForeground(TEXT_MUTED);
        show.setFocusPainted(false);
        char defaultEcho = passField.getEchoChar();
        show.addActionListener(e -> passField.setEchoChar(show.isSelected() ? (char) 0 : defaultEcho));
        left(show);
        card.add(show);

        errorLabel.setForeground(RED);
        errorLabel.setFont(errorLabel.getFont().deriveFont(Font.BOLD, 12f));
        left(errorLabel);
        card.add(Box.createVerticalStrut(4));
        card.add(errorLabel);
        card.add(Box.createVerticalStrut(6));

        JButton signIn = new JButton("Sign in");
        stylePrimaryButton(signIn);
        signIn.addActionListener(e -> doLogin());
        fullWidth(signIn);
        card.add(signIn);
        getRootPane().setDefaultButton(signIn);
        card.add(Box.createVerticalStrut(18));

        JLabel demoHeader = new JLabel("DEMO ACCOUNTS  \u00B7  PASSWORD: " + AuthService.DEMO_PASSWORD);
        demoHeader.setFont(demoHeader.getFont().deriveFont(Font.BOLD, 11f));
        demoHeader.setForeground(TEXT_MUTED);
        left(demoHeader);
        card.add(demoHeader);
        card.add(Box.createVerticalStrut(8));

        JPanel demoGrid = new JPanel(new GridLayout(0, 2, 8, 8));
        demoGrid.setOpaque(false);
        for (AuthService.Account a : AuthService.demoAccounts()) {
            JButton b = new JButton(a.roll + "  \u00B7  " + a.name);
            styleSecondaryButton(b);
            b.addActionListener(e -> {
                idField.setText(a.roll);
                passField.setText(AuthService.DEMO_PASSWORD);
                errorLabel.setText(" ");
                passField.requestFocusInWindow();
            });
            demoGrid.add(b);
        }
        left(demoGrid);
        demoGrid.setMaximumSize(new Dimension(Integer.MAX_VALUE, demoGrid.getPreferredSize().height));
        card.add(demoGrid);
        card.add(Box.createVerticalStrut(14));

        JButton register = new JButton("Register a new student");
        styleSecondaryButton(register);
        register.addActionListener(e -> registerDialog());
        fullWidth(register);
        card.add(register);

        return card;
    }

    // ================= Actions =================

    private void doLogin() {
        String id = idField.getText().trim();
        char[] pw = passField.getPassword();
        if (id.isEmpty() || pw.length == 0) {
            errorLabel.setText("Enter your student ID and password.");
            return;
        }
        AuthService.Account account = AuthService.authenticate(id, pw);
        java.util.Arrays.fill(pw, '\0');
        if (account == null) {
            errorLabel.setText("Incorrect student ID or password.");
            passField.setText("");
            passField.requestFocusInWindow();
            return;
        }
        dispose();
        new HostelBookingGUI(account.roll).setVisible(true);
    }

    private void registerDialog() {
        JTextField rollField = new JTextField();
        JTextField nameField = new JTextField();
        JPasswordField pw1 = new JPasswordField();
        JPasswordField pw2 = new JPasswordField();
        JCheckBox accCheck = new JCheckBox("Has a documented accessibility requirement");
        JPanel form = new JPanel(new GridLayout(0, 1, 4, 4));
        form.add(new JLabel("Student ID (e.g. BUIC-010):")); form.add(rollField);
        form.add(new JLabel("Name:")); form.add(nameField);
        form.add(new JLabel("Password (min 6 characters):")); form.add(pw1);
        form.add(new JLabel("Confirm password:")); form.add(pw2);
        form.add(accCheck);

        int result = JOptionPane.showConfirmDialog(this, form, "Register New Student",
                JOptionPane.OK_CANCEL_OPTION);
        if (result != JOptionPane.OK_OPTION) return;

        String roll = rollField.getText().trim();
        String name = nameField.getText().trim();
        char[] a = pw1.getPassword();
        char[] b = pw2.getPassword();
        try {
            if (roll.isEmpty() || name.isEmpty()) { warn("Student ID and name are required."); return; }
            if (a.length < 6) { warn("Password must be at least 6 characters."); return; }
            if (!java.util.Arrays.equals(a, b)) { warn("Passwords do not match."); return; }
            if (!AuthService.register(roll, name, accCheck.isSelected(), a)) {
                warn("A student with that ID already exists.");
                return;
            }
            idField.setText(AuthService.normalize(roll));
            passField.setText("");
            errorLabel.setText(" ");
            passField.requestFocusInWindow();
            JOptionPane.showMessageDialog(this, "Account created. You can sign in now.",
                    "Registered", JOptionPane.INFORMATION_MESSAGE);
        } finally {
            java.util.Arrays.fill(a, '\0');
            java.util.Arrays.fill(b, '\0');
        }
    }

    private void warn(String msg) {
        JOptionPane.showMessageDialog(this, msg, "Rejected", JOptionPane.WARNING_MESSAGE);
    }

    // ================= Styling helpers =================

    private JLabel fieldLabel(String text) {
        JLabel l = new JLabel(text);
        l.setFont(l.getFont().deriveFont(Font.BOLD, 12f));
        l.setForeground(DEEP_SPACE_BLUE);
        left(l);
        return l;
    }

    private void styleField(JTextField f) {
        f.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(CARD_BORDER, 1, true),
                new EmptyBorder(9, 10, 9, 10)));
        f.setAlignmentX(Component.LEFT_ALIGNMENT);
        f.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
    }

    private void left(JComponent c) { c.setAlignmentX(Component.LEFT_ALIGNMENT); }

    private void fullWidth(JButton b) {
        b.setAlignmentX(Component.LEFT_ALIGNMENT);
        b.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));
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
        b.setForeground(PACIFIC_CYAN.darker());
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

    /** Same gradient as the main window's header bar. */
    private static class GradientBar extends JPanel {
        GradientBar() { setOpaque(false); }
        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setPaint(new GradientPaint(0, 0, DEEP_SPACE_BLUE, getWidth(), 0, DARK_TEAL));
            g2.fillRect(0, 0, getWidth(), getHeight());
            g2.dispose();
            super.paintComponent(g);
        }
    }

    /** Rounded white card with soft shadow and a mint accent strip, like the cards in the main window. */
    private static class Card extends JPanel {
        Card() { setOpaque(false); }
        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight(), depth = 5, radius = 16;
            for (int i = depth; i >= 1; i--) {
                g2.setColor(new Color(0x17, 0x2A, 0x3A, 5 + i));
                g2.fillRoundRect(i, i + 2, Math.max(w - 2 * i, 0), Math.max(h - 2 * i, 0), radius, radius);
            }
            g2.setColor(CARD_BG);
            g2.fillRoundRect(0, 0, w - depth, h - depth, radius, radius);
            g2.setColor(CARD_BORDER);
            g2.drawRoundRect(0, 0, w - depth - 1, h - depth - 1, radius, radius);
            g2.setColor(MINT_LEAF);
            g2.fillRoundRect(0, 8, 5, Math.max(h - depth - 16, 0), 5, 5);
            g2.dispose();
            super.paintComponent(g);
        }
    }
}