import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Business rules shared by the desktop UI and regression tests. */
public final class BillService {
    private final Database database;

    public BillService(Database database) { this.database = database; database.verify(); }

    public Optional<Models.StaffMember> authenticate(String username, char[] password) {
        if (username == null || password == null || password.length == 0) return Optional.empty();
        return database.read(c -> {
            try (PreparedStatement s = c.prepareStatement("SELECT id,name,password_hash FROM staff WHERE id=?")) {
                s.setString(1, username.strip());
                try (ResultSet r = s.executeQuery()) {
                    if (r.next() && Passwords.matches(password, r.getString("password_hash"))) {
                        return Optional.of(new Models.StaffMember(r.getString("id"), r.getString("name")));
                    }
                    return Optional.empty();
                }
            }
        });
    }

    public void addAdministrator(String id, String name, char[] password) {
        String validId = Input.identifier(id, "Username");
        String validName = Input.text(name, "Display name", 80);
        String hash = Passwords.hash(password);
        database.write(c -> {
            try (PreparedStatement s = c.prepareStatement("INSERT INTO staff(id,name,password_hash) VALUES(?,?,?)")) {
                s.setString(1, validId); s.setString(2, validName); s.setString(3, hash); s.executeUpdate();
            }
            return null;
        });
    }

    public List<Models.Product> products(String query, String category, Boolean available, boolean popularFirst) {
        String search = query == null ? "" : query.strip();
        String group = category == null ? "" : category.strip();
        if (search.length() > 200) throw new AppException("Search text must contain at most 200 characters.");
        return database.read(c -> {
            String sql = "SELECT g.*,COALESCE((SELECT SUM(d.num) FROM order_details d JOIN orders o ON o.id=d.order_id "
                    + "WHERE d.goods_id=g.id AND o.is_checked=1),0) AS sales FROM goods g "
                    + "WHERE instr(lower(g.name || ' ' || g.id || ' ' || g.category),lower(?))>0 "
                    + "AND (?='' OR g.category=?) AND (? IS NULL OR g.is_on_sale=?) "
                    + (popularFirst ? "ORDER BY sales DESC,g.name COLLATE NOCASE,g.id" : "ORDER BY g.name COLLATE NOCASE,g.id");
            try (PreparedStatement s = c.prepareStatement(sql)) {
                s.setString(1, search); s.setString(2, group); s.setString(3, group);
                if (available == null) { s.setNull(4, java.sql.Types.INTEGER); s.setNull(5, java.sql.Types.INTEGER); }
                else { s.setInt(4, available ? 1 : 0); s.setInt(5, available ? 1 : 0); }
                List<Models.Product> result = new ArrayList<>();
                try (ResultSet r = s.executeQuery()) {
                    while (r.next()) result.add(product(r));
                }
                return result;
            }
        });
    }

    public Models.Product product(String id) { return database.read(c -> findProduct(c, id)); }

    private static Models.Product product(ResultSet r) throws SQLException {
        return new Models.Product(r.getString("id"), r.getString("name"), r.getString("category"),
                r.getLong("price_cents"), r.getInt("is_on_sale") == 1, r.getLong("sales"));
    }

    private static Models.Product findProduct(Connection c, String id) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT *,0 AS sales FROM goods WHERE id=?")) {
            s.setString(1, id);
            try (ResultSet r = s.executeQuery()) {
                if (!r.next()) throw new AppException("The selected menu item no longer exists.");
                return product(r);
            }
        }
    }

    public void saveProduct(String id, String name, String category, long cents, boolean available, boolean create) {
        String validId = Input.identifier(id, "Item ID");
        String validName = Input.text(name, "Item name", 100);
        Input.price(cents);
        if (!Input.CATEGORIES.contains(category)) throw new AppException("Choose a valid category.");
        database.write(c -> {
            String sql = create
                    ? "INSERT INTO goods(name,category,price_cents,is_on_sale,id) VALUES(?,?,?,?,?)"
                    : "UPDATE goods SET name=?,category=?,price_cents=?,is_on_sale=? WHERE id=?";
            try (PreparedStatement s = c.prepareStatement(sql)) {
                s.setString(1, validName); s.setString(2, category); s.setLong(3, cents);
                s.setInt(4, available ? 1 : 0); s.setString(5, validId);
                if (s.executeUpdate() != 1) throw new AppException("The menu item could not be found.");
            }
            return null;
        });
    }

    public String createOrder(String table, int guests, String staffId, Map<String,Integer> requested) {
        return createOrder(table,guests,staffId,requested,null);
    }

    public String createOrder(String table, int guests, String staffId, Map<String,Integer> requested,
                              Map<String,Long> quotedPrices) {
        String validTable = Input.text(table, "Table number", 32);
        Input.guests(Integer.toString(guests));
        Map<String,Integer> items = validateCart(requested);
        String id = "ORD-" + UUID.randomUUID();
        String time = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        return database.write(c -> {
            try (PreparedStatement s = c.prepareStatement(
                    "INSERT INTO orders(id,time,table_no,people_num,staff_id,is_checked) VALUES(?,?,?,?,?,0)")) {
                s.setString(1, id); s.setString(2, time); s.setString(3, validTable);
                s.setInt(4, guests); s.setString(5, staffId); s.executeUpdate();
            }
            insertItems(c, id, items, quotedPrices);
            return id;
        });
    }

    public void addItems(String orderId, Map<String,Integer> requested) {
        addItems(orderId,requested,null);
    }

    public void addItems(String orderId, Map<String,Integer> requested, Map<String,Long> quotedPrices) {
        Map<String,Integer> items = validateCart(requested);
        database.write(c -> {
            requireOpenOrder(c, orderId);
            insertItems(c, orderId, items, quotedPrices);
            return null;
        });
    }

    private static Map<String,Integer> validateCart(Map<String,Integer> requested) {
        if (requested == null || requested.isEmpty()) throw new AppException("Add at least one item to the order.");
        Map<String,Integer> result = new LinkedHashMap<>();
        requested.forEach((id, quantity) -> {
            String normalized=Input.identifier(id, "Item ID").toUpperCase(Locale.ROOT);
            if (quantity == null) throw new AppException("Item quantity is required.");
            Input.quantity(quantity);
            result.put(normalized,Input.quantity(result.getOrDefault(normalized,0)+quantity));
        });
        return result;
    }

    private static void insertItems(Connection c, String orderId, Map<String,Integer> items,
                                    Map<String,Long> quotedPrices) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(
                "INSERT INTO order_details(order_id,goods_id,name,price_cents,num) VALUES(?,?,?,?,?)")) {
            for (var entry : items.entrySet()) {
                Models.Product item = findProduct(c, entry.getKey());
                if (!item.available()) throw new AppException(item.name() + " is currently unavailable.");
                if (quotedPrices!=null && !Long.valueOf(item.priceCents()).equals(quotedPrices.get(item.id()))) {
                    throw new AppException("The price of " + item.name() + " changed. Click Refresh to review the draft total, then try again.");
                }
                s.setString(1, orderId); s.setString(2, item.id()); s.setString(3, item.name());
                s.setLong(4, item.priceCents()); s.setInt(5, entry.getValue()); s.executeUpdate();
            }
        }
    }

    private static void requireOpenOrder(Connection c, String id) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT is_checked FROM orders WHERE id=?")) {
            s.setString(1, id);
            try (ResultSet r = s.executeQuery()) {
                if (!r.next()) throw new AppException("The order could not be found.");
                if (r.getInt(1) == 1) throw new AppException("This order has already been settled.");
            }
        }
    }

    public void settleOrder(String id) {
        database.write(c -> {
            requireOpenOrder(c, id);
            try (PreparedStatement s = c.prepareStatement("UPDATE orders SET is_checked=1 WHERE id=? AND is_checked=0")) {
                s.setString(1, id);
                if (s.executeUpdate() != 1) throw new AppException("This order has already been settled.");
            }
            return null;
        });
    }

    public List<Models.Order> orders(boolean settled) {
        return database.read(c -> {
            List<Models.Order> result = new ArrayList<>();
            try (PreparedStatement s = c.prepareStatement(
                    "SELECT o.*,COALESCE(SUM(d.num*d.price_cents),0) AS total FROM orders o "
                    + "LEFT JOIN order_details d ON d.order_id=o.id WHERE o.is_checked=? "
                    + "GROUP BY o.id ORDER BY o.time DESC,o.id")) {
                s.setInt(1, settled ? 1 : 0);
                try (ResultSet r = s.executeQuery()) {
                    while (r.next()) result.add(new Models.Order(r.getString("id"), r.getString("time"),
                            r.getString("table_no"), r.getInt("people_num"), r.getString("staff_id"),
                            r.getInt("is_checked") == 1, r.getLong("total")));
                }
            }
            return result;
        });
    }

    public Models.Order order(String id) {
        return database.read(c -> {
            try (PreparedStatement s = c.prepareStatement(
                    "SELECT o.*,COALESCE(SUM(d.num*d.price_cents),0) AS total FROM orders o "
                    + "LEFT JOIN order_details d ON d.order_id=o.id WHERE o.id=? GROUP BY o.id")) {
                s.setString(1, id);
                try (ResultSet r = s.executeQuery()) {
                    if (!r.next()) throw new AppException("The order could not be found.");
                    return new Models.Order(r.getString("id"), r.getString("time"), r.getString("table_no"),
                            r.getInt("people_num"), r.getString("staff_id"), r.getInt("is_checked") == 1, r.getLong("total"));
                }
            }
        });
    }

    public List<Models.OrderLine> lines(String id) {
        return database.read(c -> {
            List<Models.OrderLine> result = new ArrayList<>();
            try (PreparedStatement s = c.prepareStatement(
                    "SELECT goods_id,name,price_cents,num FROM order_details WHERE order_id=? ORDER BY id")) {
                s.setString(1, id);
                try (ResultSet r = s.executeQuery()) {
                    while (r.next()) result.add(new Models.OrderLine(r.getString(1), r.getString(2), r.getLong(3), r.getInt(4)));
                }
            }
            return result;
        });
    }
}
