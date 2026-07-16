package bench.saga.contracts;

import java.math.BigDecimal;
import java.util.List;

public final class SagaContracts {
    private SagaContracts() {
    }

    public enum SagaState {
        NEW, INVENTORY_RESERVED, PAYMENT_AUTHORIZED, SHIPMENT_CREATED, COMPLETED, COMPENSATING, FAILED
    }

    public enum Operation {
        INVENTORY_RESERVE,
        INVENTORY_RELEASE,
        PAYMENT_AUTHORIZE,
        PAYMENT_REFUND,
        SHIPPING_CREATE,
        SHIPPING_CANCEL,
        NOTIFICATION_SUCCESS,
        NOTIFICATION_FAILURE,
        AUDIT_APPEND
    }

    @FunctionalInterface
    public interface FailureSwitch {
        void before(Operation operation);

        static FailureSwitch none() {
            return operation -> { };
        }
    }

    public record FulfillmentRequest(String orderId, String sku, int quantity,
                                     BigDecimal amount, String idempotencyKey) {
        public FulfillmentRequest {
            if (orderId == null || orderId.isBlank()) throw new IllegalArgumentException("orderId");
            if (sku == null || sku.isBlank()) throw new IllegalArgumentException("sku");
            if (quantity <= 0) throw new IllegalArgumentException("quantity");
            if (amount == null || amount.signum() <= 0) throw new IllegalArgumentException("amount");
            if (idempotencyKey == null || idempotencyKey.isBlank()) throw new IllegalArgumentException("idempotencyKey");
        }
    }

    public record FulfillmentResult(String orderId, SagaState state, String message) {
    }

    public interface InventoryService {
        void setStock(String sku, int quantity);
        boolean reserve(String orderId, String sku, int quantity);
        void release(String orderId);
        boolean isReserved(String orderId);
        int reservationCount();
    }

    public interface PaymentService {
        String authorize(String orderId, BigDecimal amount);
        void refund(String orderId);
        boolean isAuthorized(String orderId);
        boolean isRefunded(String orderId);
        int authorizationCount();
    }

    public interface ShippingService {
        String createShipment(String orderId);
        void cancelShipment(String orderId);
        boolean hasShipment(String orderId);
        boolean isCancelled(String orderId);
        int shipmentCount();
    }

    public interface NotificationService {
        void notifySuccess(String orderId);
        void notifyFailure(String orderId, String reason);
        int successCount(String orderId);
        int failureCount(String orderId);
    }

    public interface AuditLog {
        void append(String orderId, String event);
        List<String> events(String orderId);
    }

    public interface FulfillmentOrchestrator {
        FulfillmentResult fulfill(FulfillmentRequest request);
    }
}
