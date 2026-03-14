package com.satya.oms.aeron;

import com.satya.oms.model.FillRecord;
import com.satya.oms.model.OrderStore;
import com.satya.oms.model.OrderRecord;
import com.satya.oms.sbe.*;

import io.aeron.Aeron;
import io.aeron.Publication;
import io.aeron.Subscription;
import io.aeron.logbuffer.FragmentHandler;
import io.aeron.logbuffer.Header;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;

import javax.swing.*;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages one Aeron context:
 *   - Publication  on channel / stream 1001  (order entry)
 *   - Subscription on channel / stream 1002  (execution reports)
 *
 * The subscriber runs on a single daemon thread and dispatches
 * store updates back to the EDT via SwingUtilities.invokeLater.
 */
public class AeronService implements AutoCloseable {

    private static final String CHANNEL     = "aeron:ipc?term-length=64k";
    private static final int    PUB_STREAM  = 1001;
    private static final int    SUB_STREAM  = 1002;
    private static final int    BUFFER_SIZE = 4096;
    private static final int    FRAGMENT_LIMIT = 10;

    // ---- SBE encoders (reused – only called from EDT) ----
    private final ByteBuffer          sendByteBuffer  = ByteBuffer.allocateDirect(BUFFER_SIZE);
    private final UnsafeBuffer        sendBuffer      = new UnsafeBuffer(sendByteBuffer);
    private final MessageHeaderEncoder headerEncoder  = new MessageHeaderEncoder();
    private final OrderEncoder         orderEncoder   = new OrderEncoder();

    // ---- SBE decoders (reused – only called from subscriber thread) ----
    private final MessageHeaderDecoder headerDecoder  = new MessageHeaderDecoder();
    private final OrderDecoder         orderDecoder   = new OrderDecoder();

    private final Aeron         aeron;
    private final Publication   publication;
    private final Subscription  subscription;
    private final OrderStore    store;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread              subThread;

    public AeronService(OrderStore store) {
        this.store = store;
        Aeron.Context ctx = new Aeron.Context();
        aeron        = Aeron.connect(ctx);
        publication  = aeron.addPublication(CHANNEL, PUB_STREAM);
        subscription = aeron.addSubscription(CHANNEL, SUB_STREAM);
        System.out.println("[AeronService] connected — pub=" + PUB_STREAM + " sub=" + SUB_STREAM);
    }

    // ------------------------------------------------------------------
    // Send an order (called from EDT)
    // ------------------------------------------------------------------
    public boolean sendOrder(long orderId, long symbolId, byte side,
                              long quantity, long price) {
        orderEncoder.wrapAndApplyHeader(sendBuffer, 0, headerEncoder)
                .orderId(orderId)
                .symbolId(symbolId)
                .side(Side.get(side))
                .quantity(quantity)
                .price(price)
                .state(OrderState.NEW)
                .filledQty(0)
                .remainingQty(quantity);

        orderEncoder.fillsCount(0);   // no fills on entry

        int length = MessageHeaderEncoder.ENCODED_LENGTH + orderEncoder.encodedLength();

        long result = publication.offer(sendBuffer, 0, length);
        if (result > 0) {
            System.out.println("[AeronService] sent orderId=" + orderId);
            return true;
        }
        System.err.println("[AeronService] offer failed result=" + result);
        return false;
    }

    // ------------------------------------------------------------------
    // Start subscriber background thread
    // ------------------------------------------------------------------
    public void startSubscriber() {
        running.set(true);
        subThread = new Thread(this::subscriberLoop, "aeron-subscriber");
        subThread.setDaemon(true);
        subThread.start();
    }

    private void subscriberLoop() {
        System.out.println("[AeronService] subscriber started");
        FragmentHandler handler = this::onFragment;

        while (running.get() && !Thread.currentThread().isInterrupted()) {
            int fragments = subscription.poll(handler, FRAGMENT_LIMIT);
            if (fragments == 0) {
                Thread.onSpinWait();
            }
        }
        System.out.println("[AeronService] subscriber stopped");
    }

    // ------------------------------------------------------------------
    // Fragment handler — called on subscriber thread
    // ------------------------------------------------------------------
    private void onFragment(DirectBuffer buffer, int offset, int length, Header header) {
        headerDecoder.wrap(buffer, offset);

        if (headerDecoder.templateId() != OrderDecoder.TEMPLATE_ID) {
            System.err.println("[AeronService] unknown templateId=" + headerDecoder.templateId());
            return;
        }

        orderDecoder.wrap(buffer,
                offset + MessageHeaderDecoder.ENCODED_LENGTH,
                headerDecoder.blockLength(),
                headerDecoder.version());

        final long   orderId      = orderDecoder.orderId();
        final String stateStr     = stateString(orderDecoder.state());
        final long   filledQty    = orderDecoder.filledQty();
        final long   remainingQty = orderDecoder.remainingQty();

        // Read fills first
        List<FillRecord> fills = new ArrayList<>();
        OrderDecoder.FillsDecoder fillsDecoder = orderDecoder.fills();
        while (fillsDecoder.hasNext()) {
            fillsDecoder.next();
            fills.add(new FillRecord(
                    fillsDecoder.executionId(),
                    fillsDecoder.fillQty(),
                    fillsDecoder.fillPrice()));
        }

        // Now rewind and capture the full SBE string representation
        // (toString() internally rewinds and re-reads everything cleanly)
        final String rawMessage = orderDecoder.sbeRewind().toString();

        // Dispatch to EDT
        final List<FillRecord> fillsCopy = List.copyOf(fills);
        SwingUtilities.invokeLater(() ->
                store.applyExecution(orderId, stateStr, filledQty, remainingQty, fillsCopy, rawMessage));
    }

    private static String stateString(OrderState s) {
        return switch (s) {
            case NEW              -> "NEW";
            case FILLED           -> "FILLED";
            case PARTIALLY_FILLED -> "PARTIAL";
            case REJECTED         -> "REJECTED";
            default               -> s.name();
        };
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------
    public boolean isConnected() {
        return publication.isConnected();
    }

    @Override
    public void close() {
        running.set(false);
        if (subThread != null) subThread.interrupt();
        publication.close();
        subscription.close();
        aeron.close();
        System.out.println("[AeronService] closed");
    }
}
