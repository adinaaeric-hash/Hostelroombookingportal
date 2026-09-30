package hostelbooking;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Stores student accounts and checks passwords.
 * Passwords are never kept as plain text: each one is salted and hashed (PBKDF2).
 * Accounts live in memory, just like the rest of the demo data.
 */
final class AuthService {

    static final String DEMO_PASSWORD = "pass123";

    /** One login account. */
    static final class Account {
        final String roll;
        final String name;
        final boolean needsAccessible;
        final boolean demo;
        private final byte[] salt;
        private final byte[] hash;

        private Account(String roll, String name, boolean needsAccessible, boolean demo,
                        byte[] salt, byte[] hash) {
            this.roll = roll;
            this.name = name;
            this.needsAccessible = needsAccessible;
            this.demo = demo;
            this.salt = salt;
            this.hash = hash;
        }
    }

    private static final Map<String, Account> ACCOUNTS = new LinkedHashMap<>();
    private static final SecureRandom RANDOM = new SecureRandom();

    static {
        // These match the students created in HostelBookingGUI.buildBlockBSystem()
        addAccount("BUIC-001", "Hassan", false, DEMO_PASSWORD, true);
        addAccount("BUIC-002", "Ayesha", false, DEMO_PASSWORD, true);
        addAccount("BUIC-003", "Bilal", false, DEMO_PASSWORD, true);
        addAccount("BUIC-004", "Zara", true, DEMO_PASSWORD, true);
    }

    private AuthService() {}

    static String normalize(String roll) {
        return roll == null ? "" : roll.trim().toUpperCase();
    }

    /** Returns the account if the ID and password are correct, otherwise null. */
    static synchronized Account authenticate(String roll, char[] password) {
        Account a = ACCOUNTS.get(normalize(roll));
        if (a == null) {
            // Still do the hashing work so timing doesn't reveal whether the ID exists.
            hash(password, new byte[16]);
            return null;
        }
        return MessageDigest.isEqual(a.hash, hash(password, a.salt)) ? a : null;
    }

    static synchronized boolean exists(String roll) {
        return ACCOUNTS.containsKey(normalize(roll));
    }

    /** Creates a new account. Returns false if the ID is already taken. */
    static synchronized boolean register(String roll, String name, boolean needsAccessible, char[] password) {
        String id = normalize(roll);
        if (ACCOUNTS.containsKey(id)) return false;
        addAccount(id, name, needsAccessible, new String(password), false);
        return true;
    }

    static synchronized List<Account> demoAccounts() {
        List<Account> list = new ArrayList<>();
        for (Account a : ACCOUNTS.values()) if (a.demo) list.add(a);
        return list;
    }

    /** Makes sure every account has a matching Student in the booking system. */
    static synchronized void syncStudents(HostelBookingSystem system) {
        for (Account a : ACCOUNTS.values()) {
            if (system.getStudent(a.roll) == null) {
                system.addStudent(new Student(a.roll, a.name, a.needsAccessible));
            }
        }
    }

    private static void addAccount(String roll, String name, boolean acc, String password, boolean demo) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        ACCOUNTS.put(roll, new Account(roll, name, acc, demo, salt, hash(password.toCharArray(), salt)));
    }

    private static byte[] hash(char[] password, byte[] salt) {
        try {
            KeySpec spec = new PBEKeySpec(password, salt, 65536, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("Password hashing failed", e);
        }
    }
}