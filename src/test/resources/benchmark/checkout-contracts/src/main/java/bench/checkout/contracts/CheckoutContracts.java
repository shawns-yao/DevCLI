package bench.checkout.contracts;

import java.math.BigDecimal;
import java.util.List;

/**
 * Read-only contract for the controlled multi-tenant checkout benchmark.
 *
 * <p>Hidden validation checks only the semantics stated here. Implementations must keep all state in memory,
 * be thread-safe, and scope order state by the pair {@code (tenantId, orderId)}. A constructor accepting
 * {@link FailureSwitch} must call {@link FailureSwitch#before(Operation, String)} immediately before the
 * corresponding external-style side effect; a no-argument constructor must behave as if the switch never fails.</p>
 */
public final class CheckoutContracts {
    private CheckoutContracts() {
    }

    public enum Role {
        CUSTOMER,
        SUPPORT
    }

    public enum CheckoutState {
        COMPLETED,
        FAILED,
        REJECTED
    }

    public enum Operation {
        INVENTORY_RESERVE,
        PAYMENT_AUTHORIZE,
        SHIPMENT_CREATE,
        NOTIFICATION_SUCCESS,
        NOTIFICATION_FAILURE,
        AUDIT_APPEND
    }

    @FunctionalInterface
    public interface FailureSwitch {
        void before(Operation operation, String orderId);
    }

    /**
     * A request is valid only when every string is nonblank, quantity and amount are positive, and currency is
     * a three-letter uppercase code. A customer may create a checkout only for the same tenant as actorTenantId.
     */
    public record CheckoutRequest(
            String tenantId,
            String actorTenantId,
            Role role,
            String orderId,
            String sku,
            int quantity,
            BigDecimal amount,
            String currency,
            String idempotencyKey
    ) {
    }

    public record CheckoutResult(
            CheckoutState state,
            String tenantId,
            String orderId,
            String idempotencyKey,
            String authorizationId,
            String shipmentId
    ) {
    }

    /**
     * Access is allowed only for CUSTOMER requests whose actorTenantId equals tenantId. The policy has no
     * side effect and must never expose one tenant's result to another tenant.
     */
    public interface AccessPolicy {
        boolean permits(CheckoutRequest request);
    }

    /**
     * Reservation is idempotent for the same tenant/order pair. release is idempotent. hasActiveReservation
     * means an active reservation currently exists; hasReservationRecord means a historical record exists.
     */
    public interface InventoryService {
        void setAvailableStock(String sku, int quantity);

        int availableStock(String sku);

        boolean reserve(String tenantId, String orderId, String sku, int quantity);

        void release(String tenantId, String orderId);

        boolean hasActiveReservation(String tenantId, String orderId);

        boolean hasReservationRecord(String tenantId, String orderId);

        int activeReservationCount();
    }

    /**
     * Authorize is idempotent for the same tenant/order pair. refund is idempotent. After refund,
     * hasActiveAuthorization must be false while hasAuthorizationRecord remains true.
     */
    public interface PaymentService {
        String authorize(String tenantId, String orderId, BigDecimal amount, String currency);

        void refund(String tenantId, String orderId);

        boolean hasActiveAuthorization(String tenantId, String orderId);

        boolean hasAuthorizationRecord(String tenantId, String orderId);

        boolean isRefunded(String tenantId, String orderId);

        int authorizationCount();
    }

    /**
     * Shipment creation is idempotent for the same tenant/order pair. cancelShipment is idempotent. After
     * cancellation, hasActiveShipment must be false while hasShipmentRecord remains true.
     */
    public interface ShippingService {
        String createShipment(String tenantId, String orderId, String sku, int quantity);

        void cancelShipment(String tenantId, String orderId);

        boolean hasActiveShipment(String tenantId, String orderId);

        boolean hasShipmentRecord(String tenantId, String orderId);

        boolean isCancelled(String tenantId, String orderId);

        int shipmentCount();
    }

    /**
     * Each notification kind is idempotent for a tenant/order pair. The count methods return 0 or 1 for that
     * exact pair and never aggregate another tenant with the same order id.
     */
    public interface NotificationOutbox {
        void enqueueSuccess(String tenantId, String orderId);

        void enqueueFailure(String tenantId, String orderId, String reason);

        int successCount(String tenantId, String orderId);

        int failureCount(String tenantId, String orderId);
    }

    /** Events preserve append order and are isolated by the tenant/order pair. */
    public interface AuditTrail {
        void append(String tenantId, String orderId, String state);

        List<String> events(String tenantId, String orderId);
    }

    /**
     * checkout validates input and permission before any inventory, payment, shipment, or notification effect.
     * Invalid or unauthorized input returns REJECTED. For an accepted request the forward sequence is reserve,
     * authorize, create shipment, enqueue success, with NEW, INVENTORY_RESERVED, PAYMENT_AUTHORIZED,
     * SHIPMENT_CREATED, and COMPLETED appended in that order. Any failure returns FAILED, reverses completed
     * effects in strict reverse order (cancel shipment, refund payment, release inventory), then enqueues one
     * failure notification. Idempotency is scoped by tenant and idempotencyKey: repeated use in one tenant always
     * returns the original result without new effects, even when later request fields differ; another tenant with
     * the same key must be isolated and may execute its own checkout.
     */
    public interface CheckoutOrchestrator {
        CheckoutResult checkout(CheckoutRequest request);
    }
}
