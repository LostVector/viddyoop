package com.rkuo.viddyoop.core.itunes;

import java.io.IOException;
import java.sql.SQLException;

/** Same receipt-guarded behavior for a Temporal activity and explicit one-shot CLI calls. */
public final class ItunesImportProcessor {
    private final ItunesDeliveryCatalog receipts;
    private final ItunesDelivery delivery;
    private final ItunesReconciler reconciler;
    private final boolean reconcileOnly;
    public ItunesImportProcessor(ItunesDeliveryCatalog receipts, ItunesDelivery delivery, ItunesReconciler reconciler) {
        this(receipts, delivery, reconciler, false);
    }
    public ItunesImportProcessor(ItunesDeliveryCatalog receipts, ItunesDelivery delivery, ItunesReconciler reconciler, boolean reconcileOnly) {
        this.receipts = receipts; this.delivery = delivery; this.reconciler = reconciler;
        this.reconcileOnly = reconcileOnly;
    }
    public ItunesDeliveryCatalog.Delivery process(String id, Runnable heartbeat) throws IOException, SQLException {
        var existing = receipts.find(id);
        if (existing != null && (existing.publishIntentAt() != null || existing.deliveredAt() != null)) {
            var result = reconciler.reconcile(id, heartbeat);
            if (result.outcome() != ItunesDeliveryCatalog.Outcome.CONFIRMED && result.outcome() != ItunesDeliveryCatalog.Outcome.REJECTED) return result;
        }
        return delivery.deliver(id, heartbeat, !reconcileOnly);
    }
}
