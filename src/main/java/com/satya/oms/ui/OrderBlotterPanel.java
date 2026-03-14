package com.satya.oms.ui;

import com.satya.oms.model.FillRecord;
import com.satya.oms.model.OrderRecord;
import com.satya.oms.model.OrderStore;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableColumnModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Order Blotter: flat JTable where each order row is immediately followed
 * by its fill sub-rows (indented, different background colour).
 *
 * The model is rebuilt on the EDT every time the OrderStore fires a change.
 */
public class OrderBlotterPanel extends JPanel {

    // ---- colour palette ----
    private static final Color ORDER_BG_ODD   = new Color(245, 247, 250);
    private static final Color ORDER_BG_EVEN  = Color.WHITE;
    private static final Color FILL_BG        = new Color(232, 243, 255);
    private static final Color FILLED_FG      = new Color(0, 140, 0);
    private static final Color REJECTED_FG    = new Color(200, 40, 40);
    private static final Color PARTIAL_FG     = new Color(180, 120, 0);
    private static final Color HEADER_BG      = new Color(50, 60, 80);
    private static final Color HEADER_FG      = Color.WHITE;

    // ---- column definitions ----
    //  Blotter has unified columns; fill rows use a subset with indent on col 0
    static final String[] COLS = {
        "Order ID / Exec ID", "Symbol", "Side", "Qty", "Price",
        "State", "Filled", "Remaining", "Time", "Raw Message"
    };

    // ---- row model ----
    private enum RowType { ORDER, FILL }

    private static class Row {
        RowType type;
        int     orderIndex;   // which order group this belongs to (for alternating colour)
        Object[] data;        // COLS.length values

        Row(RowType type, int orderIndex, Object[] data) {
            this.type = type; this.orderIndex = orderIndex; this.data = data;
        }
    }

    // ---- table model ----
    private static class BlotterTableModel extends AbstractTableModel {
        private final List<Row> rows = new ArrayList<>();

        void setRows(List<Row> newRows) {
            rows.clear();
            rows.addAll(newRows);
            fireTableDataChanged();
        }

        @Override public int getRowCount()    { return rows.size(); }
        @Override public int getColumnCount() { return COLS.length; }
        @Override public String getColumnName(int col) { return COLS[col]; }
        @Override public Object getValueAt(int r, int c) { return rows.get(r).data[c]; }

        Row getRow(int r) { return rows.get(r); }

        @Override public Class<?> getColumnClass(int c) { return String.class; }
        @Override public boolean isCellEditable(int r, int c) { return false; }
    }

    private final BlotterTableModel model = new BlotterTableModel();
    private final JTable            table = new JTable(model);
    private final JLabel            countLabel = new JLabel("0 orders");
    private final OrderStore        store;

    public OrderBlotterPanel(OrderStore store) {
        this.store = store;
        buildUI();

        // listen for changes
        store.addListener(order -> SwingUtilities.invokeLater(this::refresh));

        refresh(); // initial (empty) render
    }

    // ------------------------------------------------------------------
    // Build Swing components
    // ------------------------------------------------------------------
    private void buildUI() {
        setLayout(new BorderLayout());
        setBorder(new EmptyBorder(8, 8, 8, 8));

        // ---- header bar ----
        JPanel headerBar = new JPanel(new BorderLayout());
        headerBar.setOpaque(false);
        headerBar.setBorder(new EmptyBorder(0, 2, 8, 2));

        JLabel title = new JLabel("Order Blotter");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 16f));
        headerBar.add(title, BorderLayout.WEST);

        countLabel.setFont(countLabel.getFont().deriveFont(12f));
        countLabel.setForeground(Color.GRAY);
        headerBar.add(countLabel, BorderLayout.EAST);
        add(headerBar, BorderLayout.NORTH);

        // ---- table ----
        table.setRowHeight(22);
        table.setShowHorizontalLines(true);
        table.setShowVerticalLines(false);
        table.setGridColor(new Color(220, 220, 220));
        table.setIntercellSpacing(new Dimension(0, 1));
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFocusable(false);

        // custom header renderer
        table.getTableHeader().setDefaultRenderer(new DefaultTableCellRenderer() {
            {
                setHorizontalAlignment(CENTER);
                setOpaque(true);
            }
            @Override
            public Component getTableCellRendererComponent(JTable t, Object val,
                    boolean sel, boolean focus, int row, int col) {
                super.getTableCellRendererComponent(t, val, sel, focus, row, col);
                setBackground(HEADER_BG);
                setForeground(HEADER_FG);
                setFont(getFont().deriveFont(Font.BOLD, 12f));
                setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
                return this;
            }
        });

        // custom cell renderer
        table.setDefaultRenderer(String.class, new BlotterCellRenderer());

        // column widths
        int[] widths = {140, 70, 55, 70, 80, 90, 70, 90, 90, 420};
        TableColumnModel cm = table.getColumnModel();
        for (int i = 0; i < widths.length; i++) {
            cm.getColumn(i).setPreferredWidth(widths[i]);
        }

        JScrollPane scroll = new JScrollPane(table);
        scroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scroll.getViewport().setBackground(Color.WHITE);
        add(scroll, BorderLayout.CENTER);
    }

    // ------------------------------------------------------------------
    // Rebuild rows from the store (called on EDT)
    // ------------------------------------------------------------------
    private void refresh() {
        List<OrderRecord> orders = store.getOrders();
        List<Row> rows = new ArrayList<>();

        for (int i = 0; i < orders.size(); i++) {
            OrderRecord o = orders.get(i);

            // --- order row ---
            Object[] od = {
                "#" + o.getOrderId(),
                o.getSymbol(),
                o.sideDisplay(),
                String.valueOf(o.getQuantity()),
                o.priceDisplay(),
                o.getState(),
                String.valueOf(o.getFilledQty()),
                String.valueOf(o.getRemainingQty()),
                o.getSubmitTime(),
                o.getRawMessage()
            };
            rows.add(new Row(RowType.ORDER, i, od));

            // --- fill sub-rows ---
            for (FillRecord f : o.getFills()) {
                Object[] fd = {
                    "  ↳ exec #" + f.getExecutionId(),
                    "", "", String.valueOf(f.getFillQty()),
                    f.fillPriceDisplay(),
                    "FILL", "", "", "", ""
                };
                rows.add(new Row(RowType.FILL, i, fd));
            }
        }

        model.setRows(rows);
        countLabel.setText(orders.size() + " order" + (orders.size() == 1 ? "" : "s"));
    }

    // ------------------------------------------------------------------
    // Cell renderer
    // ------------------------------------------------------------------
    private class BlotterCellRenderer extends DefaultTableCellRenderer {

        @Override
        public Component getTableCellRendererComponent(JTable t, Object val,
                boolean sel, boolean focus, int row, int col) {
            super.getTableCellRendererComponent(t, val, sel, focus, row, col);

            setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
            setFont(getFont().deriveFont(12f));

            if (row >= model.getRowCount()) return this;
            Row r = model.getRow(row);

            // ---- background ----
            if (!sel) {
                if (r.type == RowType.FILL) {
                    setBackground(FILL_BG);
                } else {
                    setBackground(r.orderIndex % 2 == 0 ? ORDER_BG_EVEN : ORDER_BG_ODD);
                }
            }

            // ---- foreground / style ----
            if (r.type == RowType.FILL) {
                setForeground(new Color(60, 100, 160));
                setFont(getFont().deriveFont(Font.ITALIC, 12f));
            } else {
                // colour code state column (col 5)
                String stateVal = (String) model.getValueAt(row, 5);
                if (col == 5) {
                    switch (stateVal) {
                        case "FILLED"   -> setForeground(FILLED_FG);
                        case "REJECTED" -> setForeground(REJECTED_FG);
                        case "PARTIAL"  -> setForeground(PARTIAL_FG);
                        default         -> setForeground(getForeground());
                    }
                } else {
                    setForeground(Color.DARK_GRAY);
                }
                // bold the Order ID cell
                if (col == 0) setFont(getFont().deriveFont(Font.BOLD, 12f));
            }

            // right-align numbers
            if (col == 3 || col == 4 || col == 6 || col == 7) {
                setHorizontalAlignment(RIGHT);
            } else {
                setHorizontalAlignment(LEFT);
            }

            // Raw Message column — monospace, muted colour
            if (col == 9 && r.type == RowType.ORDER) {
                setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
                setForeground(new Color(150, 160, 175));
            }

            return this;
        }
    }
}
