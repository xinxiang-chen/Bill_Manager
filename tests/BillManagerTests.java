import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** End-to-end business regressions against real, isolated SQLite databases. */
public final class BillManagerTests {
    private static int checks;
    private static BillService service;
    private static Database database;

    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
        checks++; System.out.println("PASS " + name);
    }

    private static void rejected(Runnable action, String name) {
        try { action.run(); throw new AssertionError("Expected rejection: " + name); }
        catch (AppException expected) { checks++; System.out.println("PASS " + name); }
    }

    private static long count(String table) {
        return database.read(c -> {
            try (Statement s=c.createStatement(); ResultSet r=s.executeQuery("SELECT COUNT(*) FROM "+table)) {
                r.next(); return r.getLong(1);
            }
        });
    }

    private static long sales(String id) {
        return service.products(id,"",null,false).stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow().sales();
    }

    public static void main(String[] args) throws Exception {
        Path folder=Files.createTempDirectory("bill-manager-tests-");
        Path path=folder.resolve("test.db");
        try {
            DemoData.reset(path); database=new Database(path); service=new BillService(database);
            check(count("staff")==1 && count("goods")==20 && count("orders")==6,"English synthetic dataset replaces all previous records");
            check(service.authenticate("admin","000000".toCharArray()).isPresent(),"administrator login");
            check(service.authenticate("ADMIN","000000".toCharArray()).isPresent(),"case-insensitive username");
            check(service.authenticate("admin","incorrect".toCharArray()).isEmpty(),"incorrect password rejected");
            check(service.authenticate("admin' OR 1=1 --","000000".toCharArray()).isEmpty(),"login SQL injection rejected");
            String hash=database.read(c -> {
                try (Statement s=c.createStatement(); ResultSet r=s.executeQuery("SELECT password_hash FROM staff")) {
                    r.next(); return r.getString(1);
                }
            });
            check(!hash.equals("000000") && hash.startsWith("pbkdf2-sha256$"),"password is salted and hashed");
            check(service.products("cHiCkEn","",true,false).size()==2,"case-insensitive partial name search");
            check(service.products("M00","",true,false).size()==3,"partial item ID search");
            check(service.products("","Soup",true,false).size()==2,"category filter");
            check(service.products("","",false,false).size()==1,"unavailable items filter");
            check(service.products("' OR 1=1 --","",null,false).isEmpty(),"menu SQL injection treated as literal input");
            service.saveProduct("PCT","Chef's 100%_Special","Other",123,true,true);
            check(service.products("100%_","",true,false).size()==1,"percent and underscore searched literally");
            check(service.products("Chef's","",true,false).size()==2,"apostrophe in item name and search");
            rejected(() -> service.saveProduct("BAD"," ","Other",100,true,true),"blank item name rejected");
            rejected(() -> service.saveProduct("BAD","Bad","Other",0,true,true),"zero price rejected");
            rejected(() -> service.saveProduct("BAD","Bad","Invalid",100,true,true),"invalid category rejected");
            rejected(() -> service.saveProduct("M001","Duplicate","Meat",100,true,true),"duplicate product ID rejected");
            rejected(() -> Input.parsePrice("NaN"),"non-numeric price rejected");
            rejected(() -> Input.parsePrice("1.001"),"sub-cent price rejected");
            check(Input.parsePrice("0.10")==10 && Input.money(30).equals("$0.30"),"decimal money converted without floating point error");
            rejected(() -> service.addAdministrator("admin","Duplicate","000000".toCharArray()),"duplicate administrator rejected");
            rejected(() -> service.addAdministrator("new","New","123".toCharArray()),"short password rejected");
            service.addAdministrator("operator","Demo Operator","654321".toCharArray());
            check(service.authenticate("operator","654321".toCharArray()).isPresent(),"new administrator can sign in");

            long before=count("orders");
            rejected(() -> service.createOrder("",2,"admin",Map.of("M001",1)),"empty table rejected");
            rejected(() -> service.createOrder("T1",0,"admin",Map.of("M001",1)),"zero guests rejected");
            rejected(() -> service.createOrder("T1",2,"admin",Map.of()),"empty order rejected");
            rejected(() -> service.createOrder("T1",2,"admin",Map.of("M001",-1)),"negative quantity rejected");
            rejected(() -> service.createOrder("T1",2,"admin",Map.of("O002",1)),"unavailable item rejected");
            check(count("orders")==before,"invalid orders do not leave headers behind");

            LinkedHashMap<String,Integer> broken=new LinkedHashMap<>(); broken.put("M001",1); broken.put("MISSING",1);
            long previousLines=count("order_details");
            rejected(() -> service.createOrder("Rollback",2,"admin",broken),"missing later item rolls back the entire order");
            check(count("orders")==before && count("order_details")==previousLines,"header and earlier line rolled back together");
            database.write(c -> {
                try (Statement s=c.createStatement()) {
                    s.execute("CREATE TRIGGER reject_test_line BEFORE INSERT ON order_details WHEN NEW.goods_id='B001' BEGIN SELECT RAISE(ABORT,'Test failure'); END");
                }
                return null;
            });
            LinkedHashMap<String,Integer> triggered=new LinkedHashMap<>(); triggered.put("M001",1); triggered.put("B001",1);
            rejected(() -> service.createOrder("Trigger",2,"admin",triggered),"database constraint failure rolls back the transaction");
            check(count("orders")==before && count("order_details")==previousLines,"constraint failure leaves no partial data");
            database.write(c -> { try (Statement s=c.createStatement()) { s.execute("DROP TRIGGER reject_test_line"); } return null; });

            service.saveProduct("CENT1","Ten Cent Sample","Other",10,true,true);
            service.saveProduct("CENT2","Twenty Cent Sample","Other",20,true,true);
            String cents=service.createOrder("Exact",1,"admin",Map.of("CENT1",1,"CENT2",1));
            check(service.order(cents).totalCents()==30,"order total is exactly thirty cents");
            long beforeQuoteFailure=count("orders");
            rejected(() -> service.createOrder("Quote",1,"admin",Map.of("CENT1",1),Map.of("CENT1",9L)),"stale draft price rejected");
            check(count("orders")==beforeQuoteFailure,"stale draft price does not leave an order behind");
            LinkedHashMap<String,Integer> mixedCase=new LinkedHashMap<>(); mixedCase.put("cent1",1); mixedCase.put("CENT1",2);
            String normalized=service.createOrder("Normalized",1,"admin",mixedCase);
            check(service.lines(normalized).size()==1 && service.lines(normalized).get(0).quantity()==3,"mixed-case item IDs merge into one line");
            long oldSales=sales("M001");
            String id=service.createOrder("Patio's 2",3,"admin",Map.of("M001",2));
            check(service.order(id).tableNumber().equals("Patio's 2"),"apostrophe in table number preserved");
            check(service.order(id).totalCents()==2900,"new order total");
            check(sales("M001")==oldSales,"unsettled orders do not increment sales");
            service.addItems(id,Map.of("S001",1));
            check(service.order(id).totalCents()==3550 && service.lines(id).size()==2,"append items updates open order");
            rejected(() -> service.addItems(id,Map.of("CENT2",1),Map.of("CENT2",19L)),"stale append price rejected");
            check(service.order(id).totalCents()==3550,"stale append preserves the existing total");
            service.saveProduct("M001","Renamed Chicken","Meat",1999,true,false);
            var historical=service.lines(id).stream().filter(l -> l.productId().equals("M001")).findFirst().orElseThrow();
            check(historical.name().equals("Grilled Chicken") && historical.priceCents()==1450,"historical name and price preserved after menu edit");
            service.settleOrder(id);
            check(service.order(id).settled() && sales("M001")==oldSales+2,"checkout settles and counts sales once");
            rejected(() -> service.settleOrder(id),"duplicate checkout rejected");
            check(sales("M001")==oldSales+2,"duplicate checkout does not double-count sales");
            rejected(() -> service.addItems(id,Map.of("S001",1)),"cannot add to a settled order");
            check(service.order(id).totalCents()==3550,"settled total remains unchanged");

            String pending=service.createOrder("Pending",2,"admin",Map.of("M002",1));
            previousLines=count("order_details");
            LinkedHashMap<String,Integer> appendBroken=new LinkedHashMap<>(); appendBroken.put("S001",1); appendBroken.put("MISSING",1);
            rejected(() -> service.addItems(pending,appendBroken),"failed append rolls back earlier appended lines");
            check(count("order_details")==previousLines && service.order(pending).totalCents()==1895,"failed append preserves original order");
            long beefSales=sales("M002");
            var pool=Executors.newFixedThreadPool(2);
            try {
                Callable<Boolean> checkout=() -> { try { service.settleOrder(pending); return true; } catch (AppException expected) { return false; } };
                var results=pool.invokeAll(List.of(checkout,checkout));
                int success=0; for (var result:results) if (result.get()) success++;
                check(success==1 && sales("M002")==beefSales+1,"concurrent checkout succeeds exactly once");
                List<Callable<String>> creates=new ArrayList<>();
                for (int i=0;i<8;i++) creates.add(() -> service.createOrder("Concurrent",2,"admin",Map.of("B002",1)));
                Set<String> ids=new HashSet<>();
                for (var result:pool.invokeAll(creates)) ids.add(result.get());
                check(ids.size()==8,"concurrent orders receive unique IDs");
            } finally { pool.shutdown(); pool.awaitTermination(10,TimeUnit.SECONDS); }

            long total=service.orders(true).stream().mapToLong(Models.Order::totalCents).sum();
            long actual=database.read(c -> {
                try (Statement s=c.createStatement(); ResultSet r=s.executeQuery("SELECT SUM(d.num*d.price_cents) FROM order_details d JOIN orders o ON o.id=d.order_id WHERE o.is_checked=1")) {
                    r.next(); return r.getLong(1);
                }
            });
            check(total==actual,"sales history agrees with settled line totals");
            check(database.read(c -> { try (Statement s=c.createStatement(); ResultSet r=s.executeQuery("PRAGMA foreign_key_check")) { return !r.next(); } }),"foreign key integrity");
            check(database.read(c -> { try (Statement s=c.createStatement(); ResultSet r=s.executeQuery("PRAGMA integrity_check")) { return r.next() && r.getString(1).equals("ok"); } }),"SQLite integrity");
            Ui.TableModel model=new Ui.TableModel(new String[]{"ID","Amount"},String.class,Long.class);
            model.addRow(new Object[]{"row",100L});
            check(!model.isCellEditable(0,0) && model.getColumnClass(1)==Long.class,"read-only tables retain numeric column types");
            DemoData.reset(path);
            check(count("staff")==1 && count("goods")==20 && count("orders")==6,"explicit reset removes newly added data");
            System.out.println("SUCCESS: "+checks+" regression checks passed.");
        } finally {
            try (var paths=Files.walk(folder)) {
                for (Path p:paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
            }
        }
    }
}
