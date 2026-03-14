package com.satya.oms.model;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Mutable order record that accumulates fills as execution reports arrive.
 * All mutation happens on the EDT after the Aeron subscriber dispatches via SwingUtilities.invokeLater.
 */
public class OrderRecord {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    public enum Side { BUY, SELL }

    private final long   orderId;
    private final String symbol;
    private final Side   side;
    private final long   quantity;
    private final long   price;      // ticks
    private final String submitTime;

    // updated by execution reports
    private String state        = "NEW";
    private long   filledQty    = 0;
    private long   remainingQty;
    private String rawMessage   = "";

    private final List<FillRecord> fills = new ArrayList<>();

    public OrderRecord(long orderId, String symbol, Side side, long quantity, long price) {
        this.orderId      = orderId;
        this.symbol       = symbol;
        this.side         = side;
        this.quantity     = quantity;
        this.price        = price;
        this.remainingQty = quantity;
        this.submitTime   = LocalTime.now().format(TIME_FMT);
    }

    // ---- mutation called from EDT ----

    public void applyExecution(String state, long filledQty, long remainingQty,
                               List<FillRecord> newFills, String rawMessage) {
        this.state        = state;
        this.filledQty    = filledQty;
        this.remainingQty = remainingQty;
        this.rawMessage   = rawMessage;
        this.fills.addAll(newFills);
    }

    // ---- accessors ----

    public long   getOrderId()     { return orderId; }
    public String getSymbol()      { return symbol; }
    public Side   getSide()        { return side; }
    public long   getQuantity()    { return quantity; }
    public long   getPrice()       { return price; }
    public String getState()       { return state; }
    public long   getFilledQty()   { return filledQty; }
    public long   getRemainingQty(){ return remainingQty; }
    public String getSubmitTime()  { return submitTime; }

    public List<FillRecord> getFills()     { return Collections.unmodifiableList(fills); }
    public String getRawMessage()          { return rawMessage; }

    /** Display price as decimal (ticks / 100). */
    public String priceDisplay() {
        return String.format("%.2f", price / 100.0);
    }

    public String sideDisplay() { return side.name(); }
}
