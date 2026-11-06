import java.awt.Component;
import java.awt.Container;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import javax.swing.*;

/** Actual Swing action tests. Run explicitly on a desktop; all data is temporary. */
public final class UiSmokeTests {
    private static int checks;
    private static MainWindow frame;
    private static Path output;
    private static final List<String> messages=new ArrayList<>();

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++; System.out.println("PASS UI "+message);
    }

    private static <T> T edt(Callable<T> action) throws Exception {
        AtomicReference<T> result=new AtomicReference<>();
        AtomicReference<Throwable> error=new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> { try { result.set(action.call()); } catch (Throwable e) { error.set(e); } });
        if (error.get()!=null) throw new AssertionError("UI action failed",error.get());
        return result.get();
    }

    private static <T extends Component> T find(Component root, String name, Class<T> type) {
        if (name.equals(root.getName()) && type.isInstance(root)) return type.cast(root);
        if (root instanceof Container container) {
            for (Component child:container.getComponents()) {
                T found=find(child,name,type); if (found!=null) return found;
            }
        }
        return null;
    }

    private static <T extends Component> T control(String name, Class<T> type) {
        T found=find(frame,name,type);
        if (found==null) throw new AssertionError("Missing control: "+name);
        return found;
    }

    private static void click(String name) { control(name,JButton.class).doClick(); }

    private static void select(JTable table, String id) {
        for (int i=0;i<table.getModel().getRowCount();i++) {
            if (id.equals(table.getModel().getValueAt(i,0))) {
                int view=table.convertRowIndexToView(i); table.setRowSelectionInterval(view,view); return;
            }
        }
        throw new AssertionError("Missing row: "+id);
    }

    private static void screenshot(Window window, String name) throws Exception {
        BufferedImage image=new BufferedImage(window.getWidth(),window.getHeight(),BufferedImage.TYPE_INT_RGB);
        var graphics=image.createGraphics(); window.printAll(graphics); graphics.dispose();
        ImageIO.write(image,"png",output.resolve(name).toFile());
    }

    private static void dialog(String name, Consumer<JDialog> action, Runnable open) {
        AtomicReference<Throwable> failure=new AtomicReference<>();
        long deadline=System.nanoTime()+10_000_000_000L;
        Timer timer=new Timer(80,null);
        timer.addActionListener(e -> {
            for (Window window:Window.getWindows()) {
                if (window instanceof JDialog d && d.isShowing() && name.equals(d.getName())) {
                    timer.stop();
                    try { action.accept(d); } catch (Throwable t) { failure.set(t); d.dispose(); }
                    return;
                }
            }
            if (System.nanoTime()>deadline) {
                failure.set(new AssertionError("Dialog did not appear: "+name)); timer.stop();
                for (Window w:Window.getWindows()) if (w instanceof JDialog) w.dispose();
            }
        });
        timer.start(); open.run(); timer.stop();
        if (failure.get()!=null) throw new AssertionError("Dialog test failed",failure.get());
    }

    private static void checkEnglish(Component component) {
        String value=null;
        if (component instanceof JLabel label) value=label.getText();
        if (component instanceof AbstractButton button) value=button.getText();
        if (component instanceof JFrame window) value=window.getTitle();
        if (value!=null && value.matches("(?s).*\\p{IsHan}.*")) throw new AssertionError("Non-English label: "+value);
        if (component instanceof Container container) for (Component c:container.getComponents()) checkEnglish(c);
    }

    public static void main(String[] args) throws Exception {
        output=Files.createTempDirectory("bill-manager-ui-");
        Path path=output.resolve("ui-test.db");
        DemoData.reset(path);
        BillService service=new BillService(new Database(path));
        MainWindow.configureAppearance();
        Models.StaffMember member=edt(() -> {
            AtomicInteger phase=new AtomicInteger();
            long deadline=System.nanoTime()+15_000_000_000L;
            Timer timer=new Timer(100,null);
            timer.addActionListener(e -> {
                for (Window window:Window.getWindows()) {
                    if (window instanceof JDialog d && d.isShowing() && "loginDialog".equals(d.getName())) {
                        JTextField user=find(d,"username",JTextField.class);
                        JPasswordField password=find(d,"password",JPasswordField.class);
                        JButton signIn=find(d,"signIn",JButton.class);
                        if (phase.get()==0) {
                            user.setText("admin"); password.setText("wrong"); phase.set(1); signIn.doClick();
                        } else if (phase.get()==1 && find(d,"loginStatus",JLabel.class).getText().contains("Incorrect")) {
                            password.setText("000000"); phase.set(2); signIn.doClick();
                        }
                        if (System.nanoTime()>deadline) { timer.stop(); d.dispose(); }
                    }
                }
            });
            timer.start(); Models.StaffMember signedIn=Ui.login(service); timer.stop();
            check(phase.get()==2 && signedIn!=null && signedIn.id().equals("admin"),"login rejects incorrect password then accepts admin");
            return signedIn;
        });
        Timer dismiss=edt(() -> {
            Timer timer=new Timer(60,e -> {
                for (Window window:Window.getWindows()) {
                    if (window instanceof JDialog d && d.isShowing()) {
                        for (Component c:d.getContentPane().getComponents()) {
                            if (c instanceof JOptionPane pane) {
                                messages.add(String.valueOf(pane.getMessage())); pane.setValue(JOptionPane.YES_OPTION); d.dispose();
                            }
                        }
                    }
                }
            });
            timer.start(); return timer;
        });
        try {
            edt(() -> {
                frame=new MainWindow(service,member); frame.setVisible(true);
                control("menuSearch",JTextField.class).setText("cHiCkEn");
                check(control("menuTable",JTable.class).getRowCount()==2,"live partial name search");
                control("menuSearch",JTextField.class).setText("");
                control("categoryFilter",JComboBox.class).setSelectedItem("Soup");
                check(control("menuTable",JTable.class).getRowCount()==2,"category selection");
                control("categoryFilter",JComboBox.class).setSelectedIndex(0);
                control("availabilityFilter",JComboBox.class).setSelectedIndex(1);
                check(control("menuTable",JTable.class).getRowCount()==1,"unavailable filter");
                control("availabilityFilter",JComboBox.class).setSelectedIndex(0);
                select(control("menuTable",JTable.class),"M001"); click("addToOrder"); click("addToOrder");
                check(control("cartTable",JTable.class).getModel().getValueAt(0,3).equals(2),"cart quantity increases");
                control("tableNumber",JTextField.class).setText("UI-T1");
                control("guestCount",JTextField.class).setText("invalid"); click("placeOrder");
                check(control("cartTable",JTable.class).getRowCount()==1 && messages.stream().anyMatch(m -> m.contains("Guest count")),"invalid guest count keeps the draft");
                control("guestCount",JTextField.class).setText("3"); click("placeOrder");
                check(control("cartTable",JTable.class).getRowCount()==0,"successful order clears the draft");
                return null;
            });
            String id=service.orders(false).stream().filter(o -> o.tableNumber().equals("UI-T1")).findFirst().orElseThrow().id();
            edt(() -> {
                control("mainTabs",JTabbedPane.class).setSelectedIndex(1);
                select(control("openOrdersTable",JTable.class),id); click("appendItems");
                check(!control("tableNumber",JTextField.class).isEditable(),"append mode locks order metadata");
                select(control("menuTable",JTable.class),"S001"); click("addToOrder"); click("placeOrder");
                check(service.order(id).totalCents()==3550,"append button persists added item");
                check(control("tableNumber",JTextField.class).isEditable(),"append mode resets after success");
                control("mainTabs",JTabbedPane.class).setSelectedIndex(1);
                select(control("openOrdersTable",JTable.class),id);
                dialog("orderDetailsDialog",d -> {
                    check(find(d,"orderDetailsTable",JTable.class).getRowCount()==2,"order details display both lines");
                    d.dispose();
                },() -> click("orderDetails"));
                control("mainTabs",JTabbedPane.class).setSelectedIndex(0);
                dialog("productDialog",d -> {
                    find(d,"productId",JTextField.class).setText("UI-PROD");
                    find(d,"productName",JTextField.class).setText("UI English Sample");
                    find(d,"productPrice",JTextField.class).setText("NaN");
                    find(d,"dialogSave",JButton.class).doClick();
                    check(d.isShowing(),"invalid menu price keeps the editor open");
                    find(d,"productPrice",JTextField.class).setText("5.25");
                    checkEnglish(d);
                    try { screenshot(d,"product-editor.png"); } catch (Exception e) { throw new RuntimeException(e); }
                    find(d,"dialogSave",JButton.class).doClick();
                },() -> click("newProduct"));
                check(service.product("UI-PROD").priceCents()==525,"new menu item dialog saves");
                select(control("menuTable",JTable.class),"UI-PROD");
                dialog("productDialog",d -> {
                    find(d,"productName",JTextField.class).setText("Updated English Sample");
                    find(d,"productPrice",JTextField.class).setText("6.25");
                    find(d,"dialogSave",JButton.class).doClick();
                },() -> click("editProduct"));
                check(service.product("UI-PROD").name().equals("Updated English Sample"),"edit dialog saves name and price");
                dialog("administratorDialog",d -> {
                    find(d,"administratorId",JTextField.class).setText("ui_admin");
                    find(d,"administratorName",JTextField.class).setText("UI Administrator");
                    find(d,"administratorPassword",JPasswordField.class).setText("123456");
                    find(d,"administratorConfirmation",JPasswordField.class).setText("123456");
                    find(d,"dialogSave",JButton.class).doClick();
                },() -> click("addAdministrator"));
                check(service.authenticate("ui_admin","123456".toCharArray()).isPresent(),"administrator dialog creates usable login");
                control("mainTabs",JTabbedPane.class).setSelectedIndex(1);
                select(control("openOrdersTable",JTable.class),id); click("settleOrder");
                check(service.order(id).settled(),"settle button completes checkout");
                control("mainTabs",JTabbedPane.class).setSelectedIndex(2);
                check(control("salesTable",JTable.class).getRowCount()==5,"settled order appears in sales history");
                screenshot(frame,"sales-history.png");
                control("mainTabs",JTabbedPane.class).setSelectedIndex(0);
                control("menuTable",JTable.class).getRowSorter().toggleSortOrder(1);
                select(control("menuTable",JTable.class),"M001"); click("addToOrder");
                check(control("cartTable",JTable.class).getModel().getValueAt(0,0).equals("M001"),"sorted table selection uses correct model row");
                var chicken=service.product("M001");
                service.saveProduct(chicken.id(),chicken.name(),chicken.category(),1600,true,false);
                control("tableNumber",JTextField.class).setText("STALE-QUOTE");
                click("placeOrder");
                check(control("cartTable",JTable.class).getRowCount()==1 && messages.stream().anyMatch(m -> m.contains("price of")),"stale price keeps draft and displays a recovery action");
                click("refreshMenu");
                check(control("draftTotal",JLabel.class).getText().contains("16.00"),"refresh updates the quoted draft total");
                click("placeOrder");
                check(service.orders(false).stream().anyMatch(o -> o.tableNumber().equals("STALE-QUOTE") && o.totalCents()==1600),"reviewed quote can be saved");
                control("mainTabs",JTabbedPane.class).setSelectedIndex(1);
                select(control("openOrdersTable",JTable.class),"DEMO-005"); click("appendItems");
                select(control("menuTable",JTable.class),"B001"); click("addToOrder"); click("cancelAddition");
                check(control("cartTable",JTable.class).getRowCount()==0 && control("tableNumber",JTextField.class).isEditable(),"cancel addition restores new-order controls");
                checkEnglish(frame); checks++;
                screenshot(frame,"main-window.png");
                return null;
            });
            System.out.println("SUCCESS: "+checks+" Swing interaction checks passed.");
            System.out.println("SCREENSHOTS "+output);
        } finally {
            edt(() -> { dismiss.stop(); for (Window window:Window.getWindows()) window.dispose(); return null; });
            Files.deleteIfExists(path);
        }
        System.exit(0);
    }
}
