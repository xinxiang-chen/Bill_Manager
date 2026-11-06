import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

/** Each operation owns its connection. Writes commit or roll back as one transaction. */
public final class Database {
    public static final int SCHEMA_VERSION = 2;
    private final Path path;

    @FunctionalInterface
    public interface SqlTask<T> { T run(Connection connection) throws SQLException; }

    public Database(Path path) { this.path = path.toAbsolutePath().normalize(); }

    public static Path defaultPath() {
        String configured = System.getProperty("rms.database");
        if (configured != null && !configured.isBlank()) return Path.of(configured).toAbsolutePath();
        try {
            Path location = Path.of(Database.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (!Files.isDirectory(location)) location = location.getParent();
            for (int i = 0; location != null && i < 7; i++, location = location.getParent()) {
                if (Files.isRegularFile(location.resolve("RMS.db"))) return location.resolve("RMS.db");
                if (Files.isDirectory(location.resolve("src")) && Files.isDirectory(location.resolve("lib"))) {
                    return location.resolve("RMS.db");
                }
            }
        } catch (Exception e) {
            throw new AppException("Cannot locate the project database. Set -Drms.database=/path/to/RMS.db.", e);
        }
        return Path.of("RMS.db").toAbsolutePath();
    }

    public void verify() {
        read(c -> {
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("PRAGMA user_version")) {
                if (!r.next() || r.getInt(1) != SCHEMA_VERSION) {
                    throw new AppException("This database uses an old schema. Run ./reset-demo.sh to create English demo data.");
                }
            }
            return null;
        });
    }

    private Connection connect() throws SQLException {
        if (!Files.isRegularFile(path)) {
            throw new AppException("Database not found at " + path + ". Run ./reset-demo.sh first.");
        }
        Properties properties = new Properties();
        properties.setProperty("foreign_keys", "true");
        properties.setProperty("busy_timeout", "5000");
        properties.setProperty("transaction_mode", "IMMEDIATE");
        return DriverManager.getConnection("jdbc:sqlite:" + path.toUri() + "?mode=rw", properties);
    }

    public <T> T read(SqlTask<T> task) {
        try (Connection c = connect()) {
            return task.run(c);
        } catch (SQLException e) {
            throw failure(e);
        }
    }

    public <T> T write(SqlTask<T> task) {
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            try {
                T result = task.run(c);
                c.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                try { c.rollback(); } catch (SQLException rollback) { e.addSuppressed(rollback); }
                throw e;
            }
        } catch (SQLException e) {
            throw failure(e);
        }
    }

    private static AppException failure(SQLException e) {
        String message = switch (e.getErrorCode()) {
            case 5, 6 -> "The database is busy. Please try again.";
            case 19 -> "The record already exists or contains invalid data.";
            default -> "The database operation failed. No partial changes were saved.";
        };
        return new AppException(message, e);
    }

    static void createSchema(Connection c) throws SQLException {
        String[] definitions = {
            "CREATE TABLE staff (id TEXT PRIMARY KEY COLLATE NOCASE, name TEXT NOT NULL, password_hash TEXT NOT NULL)",
            "CREATE TABLE goods (id TEXT PRIMARY KEY COLLATE NOCASE, name TEXT NOT NULL CHECK(length(trim(name))>0), "
                + "category TEXT NOT NULL CHECK(category IN ('Meat','Vegetarian','Soup','Main course','Dessert','Beverage','Other')), "
                + "price_cents INTEGER NOT NULL CHECK(price_cents BETWEEN 1 AND 1000000), "
                + "is_on_sale INTEGER NOT NULL DEFAULT 1 CHECK(is_on_sale IN (0,1)))",
            "CREATE TABLE orders (id TEXT PRIMARY KEY, time TEXT NOT NULL, table_no TEXT NOT NULL CHECK(length(trim(table_no))>0), "
                + "people_num INTEGER NOT NULL CHECK(people_num BETWEEN 1 AND 1000), staff_id TEXT NOT NULL REFERENCES staff(id), "
                + "is_checked INTEGER NOT NULL DEFAULT 0 CHECK(is_checked IN (0,1)))",
            "CREATE TABLE order_details (id INTEGER PRIMARY KEY, order_id TEXT NOT NULL REFERENCES orders(id) ON DELETE CASCADE, "
                + "goods_id TEXT NOT NULL REFERENCES goods(id), name TEXT NOT NULL, price_cents INTEGER NOT NULL "
                + "CHECK(price_cents BETWEEN 1 AND 1000000), num INTEGER NOT NULL CHECK(num BETWEEN 1 AND 999))",
            "CREATE INDEX order_status ON orders(is_checked,time)",
            "CREATE INDEX line_order ON order_details(order_id)",
            "CREATE INDEX line_product ON order_details(goods_id)",
            "PRAGMA user_version = " + SCHEMA_VERSION
        };
        try (Statement s = c.createStatement()) {
            s.execute("PRAGMA foreign_keys = ON");
            for (String definition : definitions) s.execute(definition);
        }
    }
}
