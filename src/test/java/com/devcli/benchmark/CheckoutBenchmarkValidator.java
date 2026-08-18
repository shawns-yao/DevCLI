package com.devcli.benchmark;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

final class CheckoutBenchmarkValidator {
    static final int CHECK_TOTAL = 38;
    private static final Map<String, String> IMPLEMENTATIONS = Map.of(
            "access", "bench.checkout.access.TenantAccessPolicy",
            "inventory", "bench.checkout.inventory.InMemoryInventoryService",
            "payment", "bench.checkout.payment.InMemoryPaymentService",
            "shipping", "bench.checkout.shipping.InMemoryShippingService",
            "notification", "bench.checkout.notification.InMemoryNotificationOutbox",
            "audit", "bench.checkout.audit.InMemoryAuditTrail",
            "checkout", "bench.checkout.orchestration.DefaultCheckoutOrchestrator"
    );
    private static final List<String> CHECKS = List.of(
            "architecture: contract integrity", "architecture: module boundaries", "architecture: public constructors",
            "access: same tenant customer allowed", "access: cross tenant customer rejected",
            "inventory: reserve available stock", "inventory: reject insufficient stock", "inventory: reserve idempotently",
            "inventory: release semantics", "inventory: failure injection",
            "payment: reference and active state", "payment: authorize idempotently", "payment: refund semantics",
            "payment: failure injection", "shipping: reference and active state", "shipping: create idempotently",
            "shipping: cancellation semantics", "shipping: failure injection", "notification: success idempotency",
            "notification: failure idempotency", "notification: failure injection", "audit: ordered isolation",
            "audit: failure injection", "normal: completed result", "normal: active resources", "normal: success notification",
            "normal: audit sequence", "compensation: inventory failure", "compensation: payment failure",
            "compensation: shipment failure", "compensation: success notification failure", "boundary: unauthorized rejected",
            "boundary: malformed rejected", "idempotency: repeated request", "idempotency: repeated key",
            "idempotency: tenant key isolation", "concurrency: same request", "concurrency: tenant order isolation"
    );

    private final Path workspace;
    private final ClassLoader loader;
    private final Path contractTemplate;
    private final Path contractTarget;
    private final Class<?> contracts;
    private final Class<?> failureSwitch;
    private final Class<?> request;
    private final Class<?> role;
    private final Class<?> accessInterface;
    private final Class<?> inventoryInterface;
    private final Class<?> paymentInterface;
    private final Class<?> shippingInterface;
    private final Class<?> notificationInterface;
    private final Class<?> auditInterface;

    CheckoutBenchmarkValidator(Path workspace, ClassLoader loader, Path contractTemplate, Path contractTarget)
            throws Exception {
        this.workspace = workspace;
        this.loader = loader;
        this.contractTemplate = contractTemplate;
        this.contractTarget = contractTarget;
        contracts = loader.loadClass("bench.checkout.contracts.CheckoutContracts");
        failureSwitch = nested("FailureSwitch");
        request = nested("CheckoutRequest");
        role = nested("Role");
        accessInterface = nested("AccessPolicy");
        inventoryInterface = nested("InventoryService");
        paymentInterface = nested("PaymentService");
        shippingInterface = nested("ShippingService");
        notificationInterface = nested("NotificationOutbox");
        auditInterface = nested("AuditTrail");
    }

    Evaluation evaluate() {
        List<String> failures = new ArrayList<>();
        for (String check : CHECKS) {
            try {
                run(check);
            } catch (Throwable error) {
                failures.add(check + ": " + message(error));
            }
        }
        return new Evaluation(CHECK_TOTAL - failures.size(), CHECK_TOTAL, List.copyOf(failures));
    }

    static Evaluation failedAll(String reason) {
        return new Evaluation(0, CHECK_TOTAL, CHECKS.stream().map(check -> check + ": " + reason).toList());
    }

    private void run(String check) throws Exception {
        switch (check) {
            case "architecture: contract integrity" -> contractIntegrity();
            case "architecture: module boundaries" -> moduleBoundaries();
            case "architecture: public constructors" -> publicConstructors();
            case "access: same tenant customer allowed" -> accessAllowed();
            case "access: cross tenant customer rejected" -> accessRejected();
            case "inventory: reserve available stock" -> inventoryReserve();
            case "inventory: reject insufficient stock" -> inventoryInsufficient();
            case "inventory: reserve idempotently" -> inventoryIdempotent();
            case "inventory: release semantics" -> inventoryRelease();
            case "inventory: failure injection" -> inventoryInjection();
            case "payment: reference and active state" -> paymentReference();
            case "payment: authorize idempotently" -> paymentIdempotent();
            case "payment: refund semantics" -> paymentRefund();
            case "payment: failure injection" -> paymentInjection();
            case "shipping: reference and active state" -> shippingReference();
            case "shipping: create idempotently" -> shippingIdempotent();
            case "shipping: cancellation semantics" -> shippingCancel();
            case "shipping: failure injection" -> shippingInjection();
            case "notification: success idempotency" -> notificationSuccess();
            case "notification: failure idempotency" -> notificationFailure();
            case "notification: failure injection" -> notificationInjection();
            case "audit: ordered isolation" -> auditEvents();
            case "audit: failure injection" -> auditInjection();
            case "normal: completed result" -> normalCompleted();
            case "normal: active resources" -> normalResources();
            case "normal: success notification" -> normalNotification();
            case "normal: audit sequence" -> normalAudit();
            case "compensation: inventory failure" -> inventoryCompensation();
            case "compensation: payment failure" -> paymentCompensation();
            case "compensation: shipment failure" -> shipmentCompensation();
            case "compensation: success notification failure" -> notificationCompensation();
            case "boundary: unauthorized rejected" -> unauthorizedRejected();
            case "boundary: malformed rejected" -> malformedRejected();
            case "idempotency: repeated request" -> repeatedRequest();
            case "idempotency: repeated key" -> repeatedKey();
            case "idempotency: tenant key isolation" -> tenantKeyIsolation();
            case "concurrency: same request" -> concurrentRequest();
            case "concurrency: tenant order isolation" -> tenantOrderIsolation();
            default -> throw new AssertionError("unknown check " + check);
        }
    }

    private void contractIntegrity() throws Exception {
        require(Arrays.equals(sha256(contractTemplate), sha256(workspace.resolve(contractTarget))),
                "contract content changed");
    }

    private void moduleBoundaries() throws Exception {
        Path root = workspace.resolve("src/main/java/bench/checkout");
        Set<String> allowed = Set.of("contracts", "access", "inventory", "payment", "shipping", "notification",
                "audit", "orchestration");
        try (Stream<Path> paths = Files.walk(root)) {
            List<String> invalid = paths.filter(path -> path.toString().endsWith(".java"))
                    .map(root::relativize)
                    .filter(path -> path.getNameCount() < 2 || !allowed.contains(path.getName(0).toString()))
                    .map(Path::toString).toList();
            require(invalid.isEmpty(), "sources outside module packages: " + invalid);
        }
        require(accessInterface.isAssignableFrom(impl("access")), "access interface mismatch");
        require(inventoryInterface.isAssignableFrom(impl("inventory")), "inventory interface mismatch");
        require(paymentInterface.isAssignableFrom(impl("payment")), "payment interface mismatch");
        require(shippingInterface.isAssignableFrom(impl("shipping")), "shipping interface mismatch");
        require(notificationInterface.isAssignableFrom(impl("notification")), "notification interface mismatch");
        require(auditInterface.isAssignableFrom(impl("audit")), "audit interface mismatch");
        require(nested("CheckoutOrchestrator").isAssignableFrom(impl("checkout")),
                "checkout interface mismatch");
    }

    private void publicConstructors() throws Exception {
        for (String module : List.of("access", "inventory", "payment", "shipping", "notification", "audit")) {
            constructor(impl(module));
            constructor(impl(module), failureSwitch);
        }
        constructor(impl("checkout"), accessInterface, inventoryInterface, paymentInterface, shippingInterface,
                notificationInterface, auditInterface);
    }

    private void accessAllowed() throws Exception {
        Object policy = service("access");
        require((boolean) call(policy, "permits", request("t1", "t1", "CUSTOMER", "o1", 1,
                BigDecimal.ONE, "USD", "k1")), "same tenant customer denied");
    }

    private void accessRejected() throws Exception {
        Object policy = service("access");
        require(!(boolean) call(policy, "permits", request("t1", "t2", "CUSTOMER", "o1", 1,
                BigDecimal.ONE, "USD", "k1")), "cross tenant customer allowed");
        require(!(boolean) call(policy, "permits", request("t1", "t1", "SUPPORT", "o1", 1,
                BigDecimal.ONE, "USD", "k1")), "support role allowed");
    }

    private void inventoryReserve() throws Exception {
        Object inventory = service("inventory");
        call(inventory, "setAvailableStock", "sku", 3);
        require((boolean) call(inventory, "reserve", "t1", "o1", "sku", 2), "reserve returned false");
        require((boolean) call(inventory, "hasActiveReservation", "t1", "o1"), "active reservation missing");
        require((int) call(inventory, "availableStock", "sku") == 1, "available stock mismatch");
    }

    private void inventoryInsufficient() throws Exception {
        Object inventory = service("inventory");
        call(inventory, "setAvailableStock", "sku", 1);
        require(!(boolean) call(inventory, "reserve", "t1", "o1", "sku", 2), "insufficient reserve succeeded");
        require((int) call(inventory, "activeReservationCount") == 0, "reservation recorded");
    }

    private void inventoryIdempotent() throws Exception {
        Object inventory = service("inventory");
        call(inventory, "setAvailableStock", "sku", 2);
        require((boolean) call(inventory, "reserve", "t1", "o1", "sku", 2), "first reserve failed");
        require((boolean) call(inventory, "reserve", "t1", "o1", "sku", 2), "second reserve failed");
        require((int) call(inventory, "availableStock", "sku") == 0, "duplicate reserve changed stock");
        require((int) call(inventory, "activeReservationCount") == 1, "duplicate reservation");
    }

    private void inventoryRelease() throws Exception {
        Object inventory = service("inventory");
        call(inventory, "setAvailableStock", "sku", 2);
        call(inventory, "reserve", "t1", "o1", "sku", 2);
        call(inventory, "release", "t1", "o1");
        call(inventory, "release", "t1", "o1");
        require(!(boolean) call(inventory, "hasActiveReservation", "t1", "o1"), "reservation remained active");
        require((boolean) call(inventory, "hasReservationRecord", "t1", "o1"), "historical record removed");
        require((int) call(inventory, "availableStock", "sku") == 2, "stock not restored");
    }

    private void inventoryInjection() throws Exception {
        Object inventory = service("inventory", Set.of("INVENTORY_RESERVE"));
        call(inventory, "setAvailableStock", "sku", 1);
        requireThrows(() -> call(inventory, "reserve", "t1", "o1", "sku", 1), "inventory switch ignored");
    }

    private void paymentReference() throws Exception {
        Object payment = service("payment");
        Object reference = call(payment, "authorize", "t1", "o1", BigDecimal.TEN, "USD");
        require(reference instanceof String text && !text.isBlank(), "authorization reference missing");
        require((boolean) call(payment, "hasActiveAuthorization", "t1", "o1"), "authorization not active");
    }

    private void paymentIdempotent() throws Exception {
        Object payment = service("payment");
        Object first = call(payment, "authorize", "t1", "o1", BigDecimal.TEN, "USD");
        Object second = call(payment, "authorize", "t1", "o1", BigDecimal.TEN, "USD");
        require(first.equals(second), "authorization reference changed");
        require((int) call(payment, "authorizationCount") == 1, "duplicate authorization");
    }

    private void paymentRefund() throws Exception {
        Object payment = service("payment");
        call(payment, "authorize", "t1", "o1", BigDecimal.TEN, "USD");
        call(payment, "refund", "t1", "o1");
        call(payment, "refund", "t1", "o1");
        require((boolean) call(payment, "isRefunded", "t1", "o1"), "refund missing");
        require(!(boolean) call(payment, "hasActiveAuthorization", "t1", "o1"), "authorization remains active");
        require((boolean) call(payment, "hasAuthorizationRecord", "t1", "o1"), "historical authorization removed");
    }

    private void paymentInjection() throws Exception {
        Object payment = service("payment", Set.of("PAYMENT_AUTHORIZE"));
        requireThrows(() -> call(payment, "authorize", "t1", "o1", BigDecimal.TEN, "USD"),
                "payment switch ignored");
    }

    private void shippingReference() throws Exception {
        Object shipping = service("shipping");
        Object reference = call(shipping, "createShipment", "t1", "o1", "sku", 2);
        require(reference instanceof String text && !text.isBlank(), "shipment reference missing");
        require((boolean) call(shipping, "hasActiveShipment", "t1", "o1"), "shipment not active");
    }

    private void shippingIdempotent() throws Exception {
        Object shipping = service("shipping");
        Object first = call(shipping, "createShipment", "t1", "o1", "sku", 2);
        Object second = call(shipping, "createShipment", "t1", "o1", "sku", 2);
        require(first.equals(second), "shipment reference changed");
        require((int) call(shipping, "shipmentCount") == 1, "duplicate shipment");
    }

    private void shippingCancel() throws Exception {
        Object shipping = service("shipping");
        call(shipping, "createShipment", "t1", "o1", "sku", 2);
        call(shipping, "cancelShipment", "t1", "o1");
        call(shipping, "cancelShipment", "t1", "o1");
        require((boolean) call(shipping, "isCancelled", "t1", "o1"), "cancellation missing");
        require(!(boolean) call(shipping, "hasActiveShipment", "t1", "o1"), "shipment remains active");
        require((boolean) call(shipping, "hasShipmentRecord", "t1", "o1"), "historical shipment removed");
    }

    private void shippingInjection() throws Exception {
        Object shipping = service("shipping", Set.of("SHIPMENT_CREATE"));
        requireThrows(() -> call(shipping, "createShipment", "t1", "o1", "sku", 2), "shipping switch ignored");
    }

    private void notificationSuccess() throws Exception {
        Object notification = service("notification");
        call(notification, "enqueueSuccess", "t1", "o1");
        call(notification, "enqueueSuccess", "t1", "o1");
        require((int) call(notification, "successCount", "t1", "o1") == 1, "success notification duplicated");
    }

    private void notificationFailure() throws Exception {
        Object notification = service("notification");
        call(notification, "enqueueFailure", "t1", "o1", "first");
        call(notification, "enqueueFailure", "t1", "o1", "second");
        require((int) call(notification, "failureCount", "t1", "o1") == 1, "failure notification duplicated");
    }

    private void notificationInjection() throws Exception {
        Object notification = service("notification", Set.of("NOTIFICATION_SUCCESS"));
        requireThrows(() -> call(notification, "enqueueSuccess", "t1", "o1"), "notification switch ignored");
    }

    private void auditEvents() throws Exception {
        Object audit = service("audit");
        call(audit, "append", "t1", "o1", "NEW");
        call(audit, "append", "t1", "o1", "COMPLETED");
        call(audit, "append", "t2", "o1", "FAILED");
        require(call(audit, "events", "t1", "o1").equals(List.of("NEW", "COMPLETED")), "event order mismatch");
        require(call(audit, "events", "t2", "o1").equals(List.of("FAILED")), "tenant audit not isolated");
    }

    private void auditInjection() throws Exception {
        Object audit = service("audit", Set.of("AUDIT_APPEND"));
        requireThrows(() -> call(audit, "append", "t1", "o1", "NEW"), "audit switch ignored");
    }

    private void normalCompleted() throws Exception {
        Fixture fixture = fixture(Set.of());
        Object result = fixture.checkout("t1", "t1", "CUSTOMER", "o1", 2, BigDecimal.TEN, "USD", "k1");
        require("COMPLETED".equals(String.valueOf(call(result, "state"))), "result is not COMPLETED");
        require("t1".equals(call(result, "tenantId")), "tenant mismatch");
        require("o1".equals(call(result, "orderId")), "order mismatch");
    }

    private void normalResources() throws Exception {
        Fixture fixture = fixture(Set.of());
        fixture.checkout("t1", "t1", "CUSTOMER", "o1", 2, BigDecimal.TEN, "USD", "k1");
        require((boolean) call(fixture.inventory, "hasActiveReservation", "t1", "o1"), "inventory not retained");
        require((boolean) call(fixture.payment, "hasActiveAuthorization", "t1", "o1"), "payment not retained");
        require((boolean) call(fixture.shipping, "hasActiveShipment", "t1", "o1"), "shipment not retained");
    }

    private void normalNotification() throws Exception {
        Fixture fixture = fixture(Set.of());
        fixture.checkout("t1", "t1", "CUSTOMER", "o1", 2, BigDecimal.TEN, "USD", "k1");
        require((int) call(fixture.notification, "successCount", "t1", "o1") == 1, "success notification missing");
        require((int) call(fixture.notification, "failureCount", "t1", "o1") == 0, "failure notification emitted");
    }

    private void normalAudit() throws Exception {
        Fixture fixture = fixture(Set.of());
        fixture.checkout("t1", "t1", "CUSTOMER", "o1", 2, BigDecimal.TEN, "USD", "k1");
        @SuppressWarnings("unchecked") List<String> events = (List<String>) call(fixture.audit, "events", "t1", "o1");
        require(isSubsequence(events, List.of("NEW", "INVENTORY_RESERVED", "PAYMENT_AUTHORIZED",
                "SHIPMENT_CREATED", "COMPLETED")), "state sequence missing: " + events);
    }

    private void inventoryCompensation() throws Exception {
        Fixture fixture = fixture(Set.of("INVENTORY_RESERVE"));
        Object result = fixture.checkout("t1", "t1", "CUSTOMER", "o1", 2, BigDecimal.TEN, "USD", "k1");
        require("FAILED".equals(String.valueOf(call(result, "state"))), "result is not FAILED");
        require((int) call(fixture.payment, "authorizationCount") == 0, "payment should not run");
        require((int) call(fixture.shipping, "shipmentCount") == 0, "shipment should not run");
        require((int) call(fixture.notification, "failureCount", "t1", "o1") == 1, "failure notification missing");
    }

    private void paymentCompensation() throws Exception {
        Fixture fixture = fixture(Set.of("PAYMENT_AUTHORIZE"));
        fixture.checkout("t1", "t1", "CUSTOMER", "o1", 2, BigDecimal.TEN, "USD", "k1");
        require(!(boolean) call(fixture.inventory, "hasActiveReservation", "t1", "o1"), "inventory not released");
        require((int) call(fixture.notification, "failureCount", "t1", "o1") == 1, "failure notification missing");
    }

    private void shipmentCompensation() throws Exception {
        Fixture fixture = fixture(Set.of("SHIPMENT_CREATE"));
        fixture.checkout("t1", "t1", "CUSTOMER", "o1", 2, BigDecimal.TEN, "USD", "k1");
        require(!(boolean) call(fixture.inventory, "hasActiveReservation", "t1", "o1"), "inventory not released");
        require((boolean) call(fixture.payment, "isRefunded", "t1", "o1"), "payment not refunded");
        require(!(boolean) call(fixture.payment, "hasActiveAuthorization", "t1", "o1"), "authorization remains active");
    }

    private void notificationCompensation() throws Exception {
        Fixture fixture = fixture(Set.of("NOTIFICATION_SUCCESS"));
        fixture.checkout("t1", "t1", "CUSTOMER", "o1", 2, BigDecimal.TEN, "USD", "k1");
        require(!(boolean) call(fixture.inventory, "hasActiveReservation", "t1", "o1"), "inventory not released");
        require((boolean) call(fixture.payment, "isRefunded", "t1", "o1"), "payment not refunded");
        require((boolean) call(fixture.shipping, "isCancelled", "t1", "o1"), "shipment not cancelled");
        require(!(boolean) call(fixture.shipping, "hasActiveShipment", "t1", "o1"), "shipment remains active");
        require((int) call(fixture.notification, "failureCount", "t1", "o1") == 1, "failure notification missing");
    }

    private void unauthorizedRejected() throws Exception {
        Fixture fixture = fixture(Set.of());
        Object result = fixture.checkout("t1", "t2", "CUSTOMER", "o1", 2, BigDecimal.TEN, "USD", "k1");
        require("REJECTED".equals(String.valueOf(call(result, "state"))), "unauthorized request was not rejected");
        require(fixture.primaryEffectCount("t1", "o1") == 0, "unauthorized request had effects");
    }

    private void malformedRejected() throws Exception {
        Fixture fixture = fixture(Set.of());
        Object result = fixture.checkout("t1", "t1", "CUSTOMER", "o1", 0, BigDecimal.ZERO, "usd", "k1");
        require("REJECTED".equals(String.valueOf(call(result, "state"))), "malformed request was not rejected");
        require(fixture.primaryEffectCount("t1", "o1") == 0, "malformed request had effects");
    }

    private void repeatedRequest() throws Exception {
        Fixture fixture = fixture(Set.of());
        Object first = fixture.checkout("t1", "t1", "CUSTOMER", "o1", 2, BigDecimal.TEN, "USD", "k1");
        Object second = fixture.checkout("t1", "t1", "CUSTOMER", "o1", 2, BigDecimal.TEN, "USD", "k1");
        require(call(first, "state").equals(call(second, "state")), "result changed");
        require(fixture.primaryEffectCount("t1", "o1") == 4, "side effects repeated");
    }

    private void repeatedKey() throws Exception {
        Fixture fixture = fixture(Set.of());
        Object first = fixture.checkout("t1", "t1", "CUSTOMER", "o1", 2, BigDecimal.TEN, "USD", "shared");
        Object second = fixture.checkout("t1", "t1", "CUSTOMER", "o2", 1, BigDecimal.ONE, "USD", "shared");
        require(call(first, "orderId").equals(call(second, "orderId")), "original result not reused");
        require(fixture.primaryEffectCount("t1", "o1") == 4, "original order effect count changed");
        require(fixture.primaryEffectCount("t1", "o2") == 0, "new order executed for duplicate key");
    }

    private void tenantKeyIsolation() throws Exception {
        Fixture fixture = fixture(Set.of());
        Object first = fixture.checkout("t1", "t1", "CUSTOMER", "o1", 2, BigDecimal.TEN, "USD", "shared");
        Object second = fixture.checkout("t2", "t2", "CUSTOMER", "o1", 2, BigDecimal.TEN, "USD", "shared");
        require("COMPLETED".equals(String.valueOf(call(first, "state"))), "first tenant failed");
        require("COMPLETED".equals(String.valueOf(call(second, "state"))), "second tenant leaked or failed");
        require("t2".equals(call(second, "tenantId")), "cross-tenant result leaked");
    }

    private void concurrentRequest() throws Exception {
        Fixture fixture = fixture(Set.of());
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Object>> calls = Stream.generate(() -> (Callable<Object>) () ->
                    fixture.checkout("t1", "t1", "CUSTOMER", "o1", 2, BigDecimal.TEN, "USD", "k1"))
                    .limit(8).toList();
            List<Future<Object>> results = executor.invokeAll(calls);
            for (Future<Object> result : results) {
                require("COMPLETED".equals(String.valueOf(call(result.get(), "state"))), "concurrent result failed");
            }
            require(fixture.primaryEffectCount("t1", "o1") == 4, "concurrent side effects repeated");
        } finally {
            executor.shutdownNow();
        }
    }

    private void tenantOrderIsolation() throws Exception {
        Fixture fixture = fixture(Set.of());
        Object first = fixture.checkout("t1", "t1", "CUSTOMER", "same", 2, BigDecimal.TEN, "USD", "k1");
        Object second = fixture.checkout("t2", "t2", "CUSTOMER", "same", 2, BigDecimal.TEN, "USD", "k2");
        require("COMPLETED".equals(String.valueOf(call(first, "state"))), "first tenant failed");
        require("COMPLETED".equals(String.valueOf(call(second, "state"))), "same order id blocked second tenant");
        require((int) call(fixture.inventory, "activeReservationCount") == 2, "tenant reservations merged");
        require((int) call(fixture.payment, "authorizationCount") == 2, "tenant authorizations merged");
        require((int) call(fixture.shipping, "shipmentCount") == 2, "tenant shipments merged");
    }

    private Fixture fixture(Set<String> failures) throws Exception {
        Object access = service("access", failures);
        Object inventory = service("inventory", failures);
        call(inventory, "setAvailableStock", "sku", 100);
        Object payment = service("payment", failures);
        Object shipping = service("shipping", failures);
        Object notification = service("notification", failures);
        Object audit = service("audit", failures);
        Object checkout = constructor(impl("checkout"), accessInterface, inventoryInterface, paymentInterface,
                shippingInterface, notificationInterface, auditInterface)
                .newInstance(access, inventory, payment, shipping, notification, audit);
        return new Fixture(access, inventory, payment, shipping, notification, audit, checkout);
    }

    private Object service(String module) throws Exception {
        return service(module, Set.of());
    }

    private Object service(String module, Set<String> failures) throws Exception {
        return constructor(impl(module), failureSwitch).newInstance(failureProxy(failures));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Object request(String tenantId, String actorTenantId, String roleName, String orderId, int quantity,
                           BigDecimal amount, String currency, String key) throws Exception {
        Object roleValue = Enum.valueOf((Class<? extends Enum>) role, roleName);
        return constructor(request, String.class, String.class, role, String.class, String.class, int.class,
                BigDecimal.class, String.class, String.class)
                .newInstance(tenantId, actorTenantId, roleValue, orderId, "sku", quantity, amount, currency, key);
    }

    private Object failureProxy(Set<String> failures) {
        return Proxy.newProxyInstance(loader, new Class<?>[]{failureSwitch}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "toString" -> "HiddenFailureSwitch";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                };
            }
            String operation = String.valueOf(args[0]);
            if (failures.contains(operation)) {
                throw new InjectedFailure(operation);
            }
            return null;
        });
    }

    private Class<?> nested(String name) throws ClassNotFoundException {
        return loader.loadClass(contracts.getName() + "$" + name);
    }

    private Class<?> impl(String module) throws ClassNotFoundException {
        return loader.loadClass(IMPLEMENTATIONS.get(module));
    }

    private final class Fixture {
        private final Object access;
        private final Object inventory;
        private final Object payment;
        private final Object shipping;
        private final Object notification;
        private final Object audit;
        private final Object checkout;

        private Fixture(Object access, Object inventory, Object payment, Object shipping, Object notification,
                        Object audit, Object checkout) {
            this.access = access;
            this.inventory = inventory;
            this.payment = payment;
            this.shipping = shipping;
            this.notification = notification;
            this.audit = audit;
            this.checkout = checkout;
        }

        private Object checkout(String tenantId, String actorTenantId, String roleName, String orderId, int quantity,
                                BigDecimal amount, String currency, String key) throws Exception {
            return call(checkout, "checkout", request(tenantId, actorTenantId, roleName, orderId, quantity,
                    amount, currency, key));
        }

        private int primaryEffectCount(String tenantId, String orderId) throws Exception {
            return ((boolean) call(inventory, "hasReservationRecord", tenantId, orderId) ? 1 : 0)
                    + ((boolean) call(payment, "hasAuthorizationRecord", tenantId, orderId) ? 1 : 0)
                    + ((boolean) call(shipping, "hasShipmentRecord", tenantId, orderId) ? 1 : 0)
                    + (int) call(notification, "successCount", tenantId, orderId);
        }
    }

    private static Constructor<?> constructor(Class<?> type, Class<?>... parameters) throws NoSuchMethodException {
        return type.getConstructor(parameters);
    }

    private static Object call(Object target, String methodName, Object... args) throws Exception {
        Method method = Arrays.stream(target.getClass().getMethods())
                .filter(candidate -> candidate.getName().equals(methodName) && candidate.getParameterCount() == args.length)
                .findFirst().orElseThrow(() -> new NoSuchMethodException(target.getClass().getName() + "." + methodName));
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            if (cause instanceof Error errorCause) {
                throw errorCause;
            }
            throw error;
        }
    }

    private static boolean isSubsequence(List<String> actual, List<String> expected) {
        int index = 0;
        for (String value : actual) {
            if (index < expected.size() && expected.get(index).equals(value)) {
                index++;
            }
        }
        return index == expected.size();
    }

    private static byte[] sha256(Path path) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void requireThrows(ThrowingAction action, String message) throws Exception {
        try {
            action.run();
        } catch (RuntimeException expected) {
            return;
        }
        throw new AssertionError(message);
    }

    private static String message(Throwable throwable) {
        Throwable current = throwable;
        while (current instanceof InvocationTargetException invocation && invocation.getCause() != null) {
            current = invocation.getCause();
        }
        String text = current.getMessage();
        return current.getClass().getSimpleName() + (text == null || text.isBlank() ? "" : " " + text);
    }

    record Evaluation(int passed, int total, List<String> failures) {
        double completionRate() {
            return total == 0 ? 0.0 : (double) passed / total;
        }
    }

    private static final class InjectedFailure extends RuntimeException {
        private InjectedFailure(String operation) {
            super(operation);
        }
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }
}
