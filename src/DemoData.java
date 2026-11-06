import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.UUID;

/** Explicit reset utility. Normal application startup never resets data. */
public final class DemoData {
    private DemoData() { }

    public static void reset(Path destination) throws Exception {
        destination = destination.toAbsolutePath().normalize();
        Files.createDirectories(destination.getParent());
        Path temporary = destination.resolveSibling(".demo-" + UUID.randomUUID() + ".db");
        try {
            try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + temporary)) {
                Database.createSchema(c);
                c.setAutoCommit(false);
                try (PreparedStatement s = c.prepareStatement("INSERT INTO staff VALUES(?,?,?)")) {
                    s.setString(1, "admin"); s.setString(2, "Administrator");
                    s.setString(3, Passwords.hash("000000".toCharArray())); s.executeUpdate();
                }
                String[][] products = {
                    {"M001","Grilled Chicken","Meat","1450"},
                    {"M002","Roast Beef","Meat","1895"},
                    {"M003","Lemon Salmon","Meat","1750"},
                    {"V001","Garden Salad","Vegetarian","850"},
                    {"V002","Roasted Vegetables","Vegetarian","925"},
                    {"V003","Chef's Mushroom Plate","Vegetarian","1100"},
                    {"S001","Tomato Soup","Soup","650"},
                    {"S002","Chicken Noodle Soup","Soup","750"},
                    {"C001","Margherita Pizza","Main course","1350"},
                    {"C002","Pesto Pasta","Main course","1275"},
                    {"C003","Vegetable Rice Bowl","Main course","1050"},
                    {"C004","Classic Burger","Main course","1425"},
                    {"D001","Chocolate Brownie","Dessert","575"},
                    {"D002","Vanilla Cheesecake","Dessert","695"},
                    {"D003","Fruit Sorbet","Dessert","525"},
                    {"B001","Sparkling Water","Beverage","325"},
                    {"B002","Fresh Lemonade","Beverage","450"},
                    {"B003","Iced Coffee","Beverage","425"},
                    {"O001","Garlic Bread","Other","475"},
                    {"O002","Seasonal Special","Other","1600"}
                };
                try (PreparedStatement s = c.prepareStatement("INSERT INTO goods VALUES(?,?,?,?,?)")) {
                    for (String[] item : products) {
                        s.setString(1,item[0]); s.setString(2,item[1]); s.setString(3,item[2]);
                        s.setLong(4,Long.parseLong(item[3])); s.setInt(5,item[0].equals("O002") ? 0 : 1); s.executeUpdate();
                    }
                }
                String[][] orderItems = {
                    {"M001","V001","B002"}, {"C001","S001","B001"},
                    {"M002","O001","D001"}, {"C002","V003","B003"},
                    {"C003","S002","B001"}, {"M003","D002","B002"}
                };
                for (int i=0;i<orderItems.length;i++) {
                    String id=String.format("DEMO-%03d",i+1);
                    try (PreparedStatement s=c.prepareStatement("INSERT INTO orders VALUES(?,?,?,?,?,?)")) {
                        s.setString(1,id); s.setString(2,String.format("2026-11-%02dT12:15:00",i+1));
                        s.setString(3,"T"+(i+1)); s.setInt(4,2+i%3); s.setString(5,"admin");
                        s.setInt(6,i<4 ? 1 : 0); s.executeUpdate();
                    }
                    try (PreparedStatement s=c.prepareStatement(
                            "INSERT INTO order_details(order_id,goods_id,name,price_cents,num) "
                            + "SELECT ?,id,name,price_cents,? FROM goods WHERE id=?")) {
                        for (String item:orderItems[i]) {
                            s.setString(1,id); s.setInt(2,1+i%2); s.setString(3,item); s.executeUpdate();
                        }
                    }
                }
                c.commit();
            }
            Files.move(temporary,destination,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || !args[0].equals("--reset")) {
            System.err.println("Usage: DemoData --reset [database-path]");
            System.exit(1);
        }
        Path target=args.length>1 ? Path.of(args[1]) : Database.defaultPath();
        reset(target);
        System.out.println("English demo database created: "+target.toAbsolutePath());
        System.out.println("20 menu items, 6 sample orders, administrator: admin / 000000");
    }
}
