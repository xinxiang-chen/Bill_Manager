import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Optional;
import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;

/** Standard Swing components keep the application independent of IDE form plugins. */
public final class Ui {
    private Ui() { }

    public static final class TableModel extends DefaultTableModel {
        private static final long serialVersionUID = 1L;
        private final Class<?>[] types;
        public TableModel(String[] columns, Class<?>... types) { super(columns,0); this.types=types; }
        @Override public boolean isCellEditable(int row, int column) { return false; }
        @Override public Class<?> getColumnClass(int column) { return types[column]; }
    }

    public static <T extends Component> T named(T component, String name) {
        component.setName(name);
        return component;
    }

    public static JTable table(TableModel model, String name, int... moneyColumns) {
        JTable table=named(new JTable(model),name);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setAutoCreateRowSorter(true);
        table.setFillsViewportHeight(true);
        table.setRowHeight(27);
        table.getTableHeader().setReorderingAllowed(false);
        DefaultTableCellRenderer money=new DefaultTableCellRenderer() {
            private static final long serialVersionUID = 1L;
            @Override protected void setValue(Object value) {
                setText(value instanceof Number ? Input.money(((Number)value).longValue()) : "");
            }
        };
        money.setHorizontalAlignment(SwingConstants.RIGHT);
        for (int index:moneyColumns) table.getColumnModel().getColumn(index).setCellRenderer(money);
        return table;
    }

    public static String selectedId(JTable table) {
        int row=table.getSelectedRow();
        if (row<0) throw new AppException("Select a row first.");
        return table.getModel().getValueAt(table.convertRowIndexToModel(row),0).toString();
    }

    public static String timestamp(String value) {
        String formatted=value.replace('T',' ');
        return formatted.length()>19 ? formatted.substring(0,19) : formatted;
    }

    public static void run(Component owner, Runnable action) {
        try { action.run(); }
        catch (AppException e) { error(owner,e.getMessage()); }
        catch (RuntimeException e) {
            e.printStackTrace(System.err);
            error(owner,"The action could not be completed. Your current draft has been kept.");
        }
    }

    public static void error(Component owner, String message) {
        JOptionPane.showMessageDialog(owner,message,"Action not completed",JOptionPane.ERROR_MESSAGE);
    }

    public static boolean confirm(Component owner, String message) {
        return JOptionPane.showConfirmDialog(owner,message,"Please confirm",
                JOptionPane.YES_NO_OPTION,JOptionPane.QUESTION_MESSAGE)==JOptionPane.YES_OPTION;
    }

    public static JPanel form(Object... labelAndField) {
        JPanel panel=new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(16,18,12,18));
        GridBagConstraints g=new GridBagConstraints();
        g.insets=new Insets(6,5,6,5); g.fill=GridBagConstraints.HORIZONTAL;
        for (int i=0;i<labelAndField.length;i+=2) {
            g.gridy=i/2; g.gridx=0; g.weightx=0;
            panel.add(new JLabel(labelAndField[i].toString()),g);
            g.gridx=1; g.weightx=1;
            panel.add((Component)labelAndField[i+1],g);
        }
        return panel;
    }

    public static JDialog dialog(Window owner, String title) {
        JDialog d=new JDialog(owner,title,Dialog.ModalityType.APPLICATION_MODAL);
        d.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        d.getRootPane().registerKeyboardAction(e -> d.dispose(),KeyStroke.getKeyStroke("ESCAPE"),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        return d;
    }

    private static void footer(JDialog d, String label, Runnable save) {
        JPanel buttons=new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.setBorder(BorderFactory.createEmptyBorder(0,14,10,14));
        JButton cancel=named(new JButton("Cancel"),"dialogCancel");
        cancel.addActionListener(e -> d.dispose());
        JButton ok=named(new JButton(label),"dialogSave");
        ok.addActionListener(e -> run(d,save));
        buttons.add(cancel); buttons.add(ok);
        d.add(buttons,BorderLayout.SOUTH); d.getRootPane().setDefaultButton(ok);
    }

    private static void show(JDialog d, Window owner) {
        d.pack(); d.setLocationRelativeTo(owner); d.setVisible(true);
    }

    public static Models.StaffMember login(BillService service) {
        JDialog d=dialog(null,"Bill Manager - Sign in");
        d.setName("loginDialog");
        JTextField username=named(new JTextField("admin",22),"username");
        JPasswordField password=named(new JPasswordField(22),"password");
        JLabel status=named(new JLabel("Enter your administrator credentials."),"loginStatus");
        JPanel fields=form("Username",username,"Password",password);
        d.add(fields,BorderLayout.CENTER);
        JPanel buttons=new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.setBorder(BorderFactory.createEmptyBorder(0,18,12,18));
        JButton cancel=new JButton("Cancel"); cancel.addActionListener(e -> d.dispose());
        JButton signIn=named(new JButton("Sign in"),"signIn");
        Models.StaffMember[] result={null};
        signIn.addActionListener(e -> {
            String user=username.getText(); char[] secret=password.getPassword();
            signIn.setEnabled(false); status.setText("Signing in...");
            new SwingWorker<Optional<Models.StaffMember>,Void>() {
                @Override protected Optional<Models.StaffMember> doInBackground() {
                    try { return service.authenticate(user,secret); }
                    finally { Arrays.fill(secret,'\0'); }
                }
                @Override protected void done() {
                    if (!d.isDisplayable()) return;
                    try {
                        var member=get();
                        if (member.isPresent()) { result[0]=member.get(); d.dispose(); }
                        else { status.setText("Incorrect username or password."); password.selectAll(); password.requestFocusInWindow(); }
                    } catch (Exception failure) {
                        status.setText("Unable to sign in. Check the database and try again.");
                        failure.printStackTrace(System.err);
                    } finally { signIn.setEnabled(true); }
                }
            }.execute();
        });
        buttons.add(cancel); buttons.add(signIn);
        JPanel bottom=new JPanel(new BorderLayout());
        status.setBorder(BorderFactory.createEmptyBorder(0,23,10,23));
        bottom.add(status,BorderLayout.NORTH); bottom.add(buttons,BorderLayout.SOUTH);
        d.add(bottom,BorderLayout.SOUTH); d.getRootPane().setDefaultButton(signIn);
        d.pack(); d.setLocationRelativeTo(null);
        SwingUtilities.invokeLater(password::requestFocusInWindow);
        d.setVisible(true);
        return result[0];
    }

    public static void productEditor(Window owner, BillService service, Models.Product product, Runnable saved) {
        boolean create=product==null;
        JDialog d=dialog(owner,create ? "Add menu item" : "Edit menu item");
        d.setName("productDialog");
        JTextField id=named(new JTextField(create ? "" : product.id(),24),"productId");
        JTextField name=named(new JTextField(create ? "" : product.name(),24),"productName");
        JTextField price=named(new JTextField(create ? "" : BigDecimal.valueOf(product.priceCents(),2).toPlainString(),24),"productPrice");
        JComboBox<String> category=named(new JComboBox<>(Input.CATEGORIES.toArray(String[]::new)),"productCategory");
        JCheckBox available=named(new JCheckBox("Available to order",create || product.available()),"productAvailable");
        if (!create) category.setSelectedItem(product.category());
        id.setEditable(create);
        d.add(form("Item ID",id,"Name",name,"Category",category,"Price (USD)",price,"Availability",available),BorderLayout.CENTER);
        footer(d,"Save",() -> {
            service.saveProduct(id.getText(),name.getText(),(String)category.getSelectedItem(),
                    Input.parsePrice(price.getText()),available.isSelected(),create);
            d.dispose(); saved.run();
        });
        show(d,owner);
    }

    public static void administratorEditor(Window owner, BillService service) {
        JDialog d=dialog(owner,"Add administrator");
        d.setName("administratorDialog");
        JTextField id=named(new JTextField(24),"administratorId");
        JTextField name=named(new JTextField(24),"administratorName");
        JPasswordField password=named(new JPasswordField(24),"administratorPassword");
        JPasswordField confirmation=named(new JPasswordField(24),"administratorConfirmation");
        d.add(form("Username",id,"Display name",name,"Password",password,"Confirm password",confirmation),BorderLayout.CENTER);
        footer(d,"Create administrator",() -> {
            char[] secret=password.getPassword(); char[] repeated=confirmation.getPassword();
            try {
                if (!Arrays.equals(secret,repeated)) throw new AppException("The passwords do not match.");
                service.addAdministrator(id.getText(),name.getText(),secret);
                d.dispose();
            } finally { Arrays.fill(secret,'\0'); Arrays.fill(repeated,'\0'); }
        });
        show(d,owner);
    }

    public static void orderDetails(Window owner, BillService service, String id) {
        var order=service.order(id);
        var lines=service.lines(id);
        JDialog d=dialog(owner,"Order details"); d.setName("orderDetailsDialog");
        TableModel model=new TableModel(new String[]{"Item ID","Item name","Unit price","Quantity","Subtotal"},
                String.class,String.class,Long.class,Integer.class,Long.class);
        for (var line:lines) model.addRow(new Object[]{line.productId(),line.name(),line.priceCents(),line.quantity(),line.totalCents()});
        JTable table=table(model,"orderDetailsTable",2,4);
        JPanel panel=new JPanel(new BorderLayout(10,10)); panel.setBorder(BorderFactory.createEmptyBorder(15,15,15,15));
        panel.add(new JLabel("Order: "+order.id()+"  |  Table: "+order.tableNumber()+"  |  "+(order.settled() ? "Settled" : "Open")),BorderLayout.NORTH);
        JScrollPane scroll=new JScrollPane(table); scroll.setPreferredSize(new Dimension(760,280));
        panel.add(scroll,BorderLayout.CENTER);
        panel.add(new JLabel("Placed: "+timestamp(order.time())+"  |  Total: "+Input.money(order.totalCents())),BorderLayout.SOUTH);
        d.add(panel,BorderLayout.CENTER);
        JButton close=new JButton("Close"); close.addActionListener(e -> d.dispose());
        JPanel footer=new JPanel(new FlowLayout(FlowLayout.RIGHT)); footer.add(close); d.add(footer,BorderLayout.SOUTH);
        show(d,owner);
    }
}
