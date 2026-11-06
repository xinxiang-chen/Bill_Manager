import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/** Desktop restaurant workflow: menu, draft, open orders, checkout and sales history. */
public class MainWindow extends JFrame {
    private static final long serialVersionUID = 1L;
    private final BillService service;
    private final Models.StaffMember member;
    private final LinkedHashMap<String,DraftItem> draft=new LinkedHashMap<>();
    private record DraftItem(Models.Product product, int quantity) { }
    private String addingToOrder;

    private final Ui.TableModel menuModel=new Ui.TableModel(
            new String[]{"Item ID","Name","Category","Price","Units sold","Available"},
            String.class,String.class,String.class,Long.class,Long.class,Boolean.class);
    private final Ui.TableModel cartModel=new Ui.TableModel(
            new String[]{"Item ID","Name","Unit price","Quantity","Subtotal"},
            String.class,String.class,Long.class,Integer.class,Long.class);
    private final Ui.TableModel openModel=orderModel();
    private final Ui.TableModel historyModel=orderModel();
    private final JTable menuTable=Ui.table(menuModel,"menuTable",3);
    private final JTable cartTable=Ui.table(cartModel,"cartTable",2,4);
    private final JTable openTable=Ui.table(openModel,"openOrdersTable",4);
    private final JTable historyTable=Ui.table(historyModel,"salesTable",4);
    private final JTextField search=Ui.named(new JTextField(20),"menuSearch");
    private final JComboBox<String> category=Ui.named(new JComboBox<>(),"categoryFilter");
    private final JComboBox<String> availability=Ui.named(new JComboBox<>(new String[]{"Available","Unavailable","All items"}),"availabilityFilter");
    private final JCheckBox popular=Ui.named(new JCheckBox("Sort by sales"),"popularFirst");
    private final JTextField tableNumber=Ui.named(new JTextField(8),"tableNumber");
    private final JTextField guests=Ui.named(new JTextField("2",4),"guestCount");
    private final JLabel draftTotal=Ui.named(new JLabel("Draft total: $0.00"),"draftTotal");
    private final JLabel draftMode=Ui.named(new JLabel("New order"),"draftMode");
    private final JLabel salesTotal=Ui.named(new JLabel(),"salesTotal");
    private final JLabel status=new JLabel();
    private final JTabbedPane tabs=Ui.named(new JTabbedPane(),"mainTabs");
    private final JButton place=button("placeOrder","Place order",this::saveDraft);
    private final JButton cancelAddition=button("cancelAddition","Cancel adding items",this::cancelAddition);

    public MainWindow(BillService service, Models.StaffMember member) {
        super("Bill Manager");
        this.service=service; this.member=member;
        setName("billManagerWindow"); setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        JPanel root=new JPanel(new BorderLayout(12,12)); root.setBorder(BorderFactory.createEmptyBorder(16,18,12,18));
        JPanel heading=new JPanel(new BorderLayout());
        JLabel title=new JLabel("Bill Manager"); title.setFont(title.getFont().deriveFont(Font.BOLD,24));
        heading.add(title,BorderLayout.WEST);
        JPanel account=new JPanel(new FlowLayout(FlowLayout.RIGHT));
        account.add(new JLabel("Signed in: "+member.name()+" ("+member.id()+")"));
        account.add(button("addAdministrator","Add administrator",() -> Ui.administratorEditor(this,service)));
        heading.add(account,BorderLayout.EAST); root.add(heading,BorderLayout.NORTH);
        tabs.addTab("New order",draftPanel());
        tabs.addTab("Open orders",ordersPanel(false));
        tabs.addTab("Sales history",ordersPanel(true));
        root.add(tabs,BorderLayout.CENTER); root.add(status,BorderLayout.SOUTH);
        setContentPane(root); setMinimumSize(new Dimension(1050,650)); setSize(1280,780); setLocationRelativeTo(null);
        refreshAll();
    }

    private static Ui.TableModel orderModel() {
        return new Ui.TableModel(new String[]{"Order ID","Placed at","Table","Guests","Total"},
                String.class,String.class,String.class,Integer.class,Long.class);
    }

    private JButton button(String name, String text, Runnable action) {
        JButton b=Ui.named(new JButton(text),name); b.addActionListener(e -> Ui.run(this,action)); return b;
    }

    private JPanel draftPanel() {
        JPanel menu=new JPanel(new BorderLayout(8,8));
        JPanel filters=new JPanel(new FlowLayout(FlowLayout.LEFT));
        search.setToolTipText("Case-insensitive partial match by item name, ID or category.");
        category.addItem("All categories"); Input.CATEGORIES.forEach(category::addItem);
        filters.add(new JLabel("Search")); filters.add(search); filters.add(category); filters.add(availability); filters.add(popular);
        menu.add(filters,BorderLayout.NORTH); menu.add(new JScrollPane(menuTable),BorderLayout.CENTER);
        JPanel menuButtons=new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton add=button("addToOrder","Add to order",this::addSelectedProduct);
        JButton edit=button("editProduct","Edit selected",() -> Ui.productEditor(this,service,
                service.product(Ui.selectedId(menuTable)),this::refreshAll));
        add.setEnabled(false); edit.setEnabled(false);
        menuTable.getSelectionModel().addListSelectionListener(e -> {
            boolean selected=menuTable.getSelectedRow()>=0;
            edit.setEnabled(selected);
            add.setEnabled(selected && Boolean.TRUE.equals(menuModel.getValueAt(menuTable.convertRowIndexToModel(menuTable.getSelectedRow()),5)));
        });
        menuButtons.add(add);
        menuButtons.add(button("newProduct","Add menu item",() -> Ui.productEditor(this,service,null,this::refreshAll)));
        menuButtons.add(edit); menuButtons.add(button("refreshMenu","Refresh",this::refreshAll));
        menu.add(menuButtons,BorderLayout.SOUTH);

        JPanel cart=new JPanel(new BorderLayout(8,8));
        JPanel fields=new JPanel(new FlowLayout(FlowLayout.LEFT));
        fields.add(draftMode); fields.add(new JLabel("Table")); fields.add(tableNumber);
        fields.add(new JLabel("Guests")); fields.add(guests); cart.add(fields,BorderLayout.NORTH);
        cart.add(new JScrollPane(cartTable),BorderLayout.CENTER);
        JPanel actions=new JPanel(new GridLayout(0,1,6,6));
        JPanel quantities=new JPanel(new FlowLayout(FlowLayout.LEFT));
        quantities.add(button("increaseQuantity","+1",() -> changeQuantity(1)));
        quantities.add(button("decreaseQuantity","-1",() -> changeQuantity(-1)));
        quantities.add(button("removeItem","Remove",this::removeSelectedItem));
        quantities.add(button("clearDraft","Clear draft",() -> {
            if (draft.isEmpty() || Ui.confirm(this,"Clear all items from this draft?")) { draft.clear(); reloadCart(); }
        }));
        actions.add(quantities); actions.add(draftTotal);
        JPanel save=new JPanel(new FlowLayout(FlowLayout.RIGHT)); cancelAddition.setVisible(false);
        save.add(cancelAddition); save.add(place); actions.add(save); cart.add(actions,BorderLayout.SOUTH);
        JSplitPane split=new JSplitPane(JSplitPane.VERTICAL_SPLIT,menu,cart);
        split.setResizeWeight(0.56); split.setDividerLocation(310);
        JPanel result=new JPanel(new BorderLayout()); result.add(split);
        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { reload(); }
            @Override public void removeUpdate(DocumentEvent e) { reload(); }
            @Override public void changedUpdate(DocumentEvent e) { reload(); }
            private void reload() { Ui.run(MainWindow.this,MainWindow.this::reloadMenu); }
        });
        category.addActionListener(e -> Ui.run(this,this::reloadMenu));
        availability.addActionListener(e -> Ui.run(this,this::reloadMenu));
        popular.addActionListener(e -> Ui.run(this,this::reloadMenu));
        return result;
    }

    private JPanel ordersPanel(boolean history) {
        JPanel panel=new JPanel(new BorderLayout(8,8));
        JTable table=history ? historyTable : openTable;
        table.getColumnModel().getColumn(0).setPreferredWidth(300);
        panel.add(new JScrollPane(table),BorderLayout.CENTER);
        JPanel actions=new JPanel(new FlowLayout(FlowLayout.RIGHT));
        actions.add(button(history ? "refreshSales" : "refreshOrders","Refresh",this::refreshAll));
        JButton details=button(history ? "salesDetails" : "orderDetails","View details",() -> Ui.orderDetails(this,service,Ui.selectedId(table)));
        details.setEnabled(false); actions.add(details);
        if (history) {
            JPanel footer=new JPanel(new BorderLayout()); footer.add(salesTotal,BorderLayout.WEST); footer.add(actions,BorderLayout.EAST);
            panel.add(footer,BorderLayout.SOUTH);
            table.getSelectionModel().addListSelectionListener(e -> details.setEnabled(table.getSelectedRow()>=0));
        } else {
            JButton add=button("appendItems","Add items",this::beginAddition);
            JButton settle=button("settleOrder","Settle order",() -> {
                String id=Ui.selectedId(openTable); var order=service.order(id);
                if (Ui.confirm(this,"Settle this order for "+Input.money(order.totalCents())+"?")) {
                    service.settleOrder(id); refreshAll(); status.setText("Order settled successfully.");
                }
            });
            add.setEnabled(false); settle.setEnabled(false); actions.add(add); actions.add(settle);
            table.getSelectionModel().addListSelectionListener(e -> {
                boolean selected=table.getSelectedRow()>=0;
                details.setEnabled(selected); add.setEnabled(selected); settle.setEnabled(selected);
            });
            panel.add(actions,BorderLayout.SOUTH);
        }
        return panel;
    }

    private void reloadMenu() {
        Boolean available=switch (availability.getSelectedIndex()) { case 0 -> true; case 1 -> false; default -> null; };
        String group=category.getSelectedIndex()==0 ? "" : (String)category.getSelectedItem();
        var products=service.products(search.getText(),group,available,popular.isSelected());
        menuModel.setRowCount(0);
        for (var p:products) menuModel.addRow(new Object[]{p.id(),p.name(),p.category(),p.priceCents(),p.sales(),p.available()});
    }

    private void addSelectedProduct() {
        var product=service.product(Ui.selectedId(menuTable));
        if (!product.available()) throw new AppException("This item is currently unavailable.");
        DraftItem previous=draft.get(product.id());
        int count=Input.quantity(previous==null ? 1 : previous.quantity()+1);
        draft.put(product.id(),new DraftItem(product,count)); reloadCart();
    }

    private void changeQuantity(int delta) {
        String id=Ui.selectedId(cartTable); DraftItem item=draft.get(id);
        int count=item.quantity()+delta;
        if (count<=0) draft.remove(id);
        else draft.put(id,new DraftItem(item.product(),Input.quantity(count)));
        reloadCart();
    }

    private void removeSelectedItem() { draft.remove(Ui.selectedId(cartTable)); reloadCart(); }

    private void reloadCart() {
        cartModel.setRowCount(0); long total=0;
        for (var item:draft.values()) {
            long subtotal=Math.multiplyExact(item.product().priceCents(),item.quantity()); total=Math.addExact(total,subtotal);
            cartModel.addRow(new Object[]{item.product().id(),item.product().name(),item.product().priceCents(),item.quantity(),subtotal});
        }
        draftTotal.setText("Draft total: "+Input.money(total));
    }

    private void saveDraft() {
        Map<String,Integer> items=new LinkedHashMap<>();
        Map<String,Long> quotedPrices=new LinkedHashMap<>();
        draft.forEach((id,item) -> { items.put(id,item.quantity()); quotedPrices.put(id,item.product().priceCents()); });
        if (addingToOrder==null) {
            String id=service.createOrder(tableNumber.getText(),Input.guests(guests.getText()),member.id(),items,quotedPrices);
            status.setText("Order created: "+id);
        } else {
            service.addItems(addingToOrder,items,quotedPrices); status.setText("Items added to order: "+addingToOrder);
        }
        finishDraft(); refreshAll();
    }

    private void beginAddition() {
        String id=Ui.selectedId(openTable);
        if (!draft.isEmpty() && !Ui.confirm(this,"Discard the current draft and add items to the selected order?")) return;
        var order=service.order(id);
        if (order.settled()) throw new AppException("This order has already been settled.");
        draft.clear(); reloadCart(); addingToOrder=id;
        tableNumber.setText(order.tableNumber()); guests.setText(Integer.toString(order.guests()));
        tableNumber.setEditable(false); guests.setEditable(false);
        draftMode.setText("Adding items"); place.setText("Save added items"); cancelAddition.setVisible(true);
        tabs.setSelectedIndex(0);
    }

    private void cancelAddition() {
        if (draft.isEmpty() || Ui.confirm(this,"Discard these added items?")) finishDraft();
    }

    private void finishDraft() {
        draft.clear(); addingToOrder=null; tableNumber.setText(""); guests.setText("2");
        tableNumber.setEditable(true); guests.setEditable(true); draftMode.setText("New order");
        place.setText("Place order"); cancelAddition.setVisible(false); reloadCart();
    }

    public void refreshAll() {
        reloadMenu();
        draft.replaceAll((id,item) -> new DraftItem(service.product(id),item.quantity()));
        reloadCart();
        List<Models.Order> open=service.orders(false); List<Models.Order> history=service.orders(true);
        fillOrders(openModel,open); fillOrders(historyModel,history);
        long total=history.stream().mapToLong(Models.Order::totalCents).sum();
        salesTotal.setText("Settled revenue: "+Input.money(total)+"  |  Orders: "+history.size());
        status.setText(open.size()+" open orders  |  "+history.size()+" settled orders");
    }

    private static void fillOrders(Ui.TableModel model, List<Models.Order> orders) {
        model.setRowCount(0);
        for (var order:orders) model.addRow(new Object[]{order.id(),Ui.timestamp(order.time()),
                order.tableNumber(),order.guests(),order.totalCents()});
    }

    public static void configureAppearance() {
        Locale.setDefault(Locale.US); JComponent.setDefaultLocale(Locale.US);
        UIManager.put("OptionPane.yesButtonText","Yes"); UIManager.put("OptionPane.noButtonText","No");
        UIManager.put("OptionPane.cancelButtonText","Cancel"); UIManager.put("OptionPane.okButtonText","OK");
    }

    public static void main(String[] args) {
        configureAppearance();
        SwingUtilities.invokeLater(() -> {
            try {
                BillService service=new BillService(new Database(Database.defaultPath()));
                Models.StaffMember member=Ui.login(service);
                if (member==null) { System.exit(0); return; }
                new MainWindow(service,member).setVisible(true);
            } catch (AppException e) {
                Ui.error(null,e.getMessage()); System.exit(1);
            }
        });
    }
}
