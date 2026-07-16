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

final class SagaBenchmarkValidator {
    static final int CHECK_TOTAL = 30;
    private static final Map<String, String> IMPLEMENTATIONS = Map.of(
            "inventory", "bench.saga.inventory.InMemoryInventoryService",
            "payment", "bench.saga.payment.InMemoryPaymentService",
            "shipping", "bench.saga.shipping.InMemoryShippingService",
            "notification", "bench.saga.notification.InMemoryNotificationService",
            "audit", "bench.saga.audit.InMemoryAuditLog",
            "fulfillment", "bench.saga.fulfillment.DefaultFulfillmentOrchestrator"
    );
    private static final List<String> CHECKS = List.of(
            "architecture: contract integrity", "architecture: module boundaries",
            "architecture: public constructors", "inventory: reserve available stock",
            "inventory: reject insufficient stock", "inventory: reserve idempotently",
            "inventory: release reservation", "payment: authorize returns reference",
            "payment: authorization state", "payment: authorize idempotently",
            "payment: refund authorization", "shipping: create returns reference",
            "shipping: shipment state", "shipping: create idempotently", "shipping: cancel shipment",
            "notification: success count", "notification: failure count", "notification: failure injection",
            "audit: ordered isolated events", "audit: failure injection", "normal: completed result",
            "normal: resources retained", "normal: success notification", "normal: state audit sequence",
            "compensation: payment failure", "compensation: shipping failure",
            "compensation: success notification failure", "idempotency: repeated request",
            "idempotency: repeated key", "concurrency: same request"
    );

    private final Path workspace;
    private final ClassLoader loader;
    private final Path contractTemplate;
    private final Path contractTarget;
    private final Class<?> contracts;
    private final Class<?> failureSwitch;
    private final Class<?> request;
    private final Class<?> inventoryInterface;
    private final Class<?> paymentInterface;
    private final Class<?> shippingInterface;
    private final Class<?> notificationInterface;
    private final Class<?> auditInterface;

    SagaBenchmarkValidator(Path workspace, ClassLoader loader, Path contractTemplate, Path contractTarget)
            throws Exception {
        this.workspace = workspace;
        this.loader = loader;
        this.contractTemplate = contractTemplate;
        this.contractTarget = contractTarget;
        contracts = loader.loadClass("bench.saga.contracts.SagaContracts");
        failureSwitch = nested("FailureSwitch");
        request = nested("FulfillmentRequest");
        inventoryInterface = nested("InventoryService");
        paymentInterface = nested("PaymentService");
        shippingInterface = nested("ShippingService");
        notificationInterface = nested("NotificationService");
        auditInterface = nested("AuditLog");
    }

    Evaluation evaluate() {
        List<String> failures = new ArrayList<>();
        for (String check : CHECKS) {
            try {
                run(check);
            } catch (Throwable e) {
                failures.add(check + ": " + message(e));
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
            case "inventory: reserve available stock" -> inventoryReserve();
            case "inventory: reject insufficient stock" -> inventoryInsufficient();
            case "inventory: reserve idempotently" -> inventoryIdempotent();
            case "inventory: release reservation" -> inventoryRelease();
            case "payment: authorize returns reference" -> paymentReference();
            case "payment: authorization state" -> paymentState();
            case "payment: authorize idempotently" -> paymentIdempotent();
            case "payment: refund authorization" -> paymentRefund();
            case "shipping: create returns reference" -> shippingReference();
            case "shipping: shipment state" -> shippingState();
            case "shipping: create idempotently" -> shippingIdempotent();
            case "shipping: cancel shipment" -> shippingCancel();
            case "notification: success count" -> notificationSuccess();
            case "notification: failure count" -> notificationFailure();
            case "notification: failure injection" -> notificationInjection();
            case "audit: ordered isolated events" -> auditEvents();
            case "audit: failure injection" -> auditInjection();
            case "normal: completed result" -> normalCompleted();
            case "normal: resources retained" -> normalResources();
            case "normal: success notification" -> normalNotification();
            case "normal: state audit sequence" -> normalAudit();
            case "compensation: payment failure" -> paymentCompensation();
            case "compensation: shipping failure" -> shippingCompensation();
            case "compensation: success notification failure" -> notificationCompensation();
            case "idempotency: repeated request" -> repeatedRequest();
            case "idempotency: repeated key" -> repeatedKey();
            case "concurrency: same request" -> concurrentRequest();
            default -> throw new AssertionError("unknown check " + check);
        }
    }

    private void contractIntegrity() throws Exception {
        require(Arrays.equals(sha256(contractTemplate), sha256(workspace.resolve(contractTarget))),
                "contract content changed");
    }

    private void moduleBoundaries() throws Exception {
        Path sagaRoot = workspace.resolve("src/main/java/bench/saga");
        Set<String> allowed = Set.of("contracts", "inventory", "payment", "shipping", "notification", "audit",
                "fulfillment");
        try (Stream<Path> files = Files.walk(sagaRoot)) {
            List<String> invalid = files.filter(path -> path.toString().endsWith(".java"))
                    .map(sagaRoot::relativize)
                    .filter(path -> path.getNameCount() < 2 || !allowed.contains(path.getName(0).toString()))
                    .map(Path::toString).toList();
            require(invalid.isEmpty(), "sources outside module packages: " + invalid);
        }
        require(inventoryInterface.isAssignableFrom(impl("inventory")), "inventory interface mismatch");
        require(paymentInterface.isAssignableFrom(impl("payment")), "payment interface mismatch");
        require(shippingInterface.isAssignableFrom(impl("shipping")), "shipping interface mismatch");
        require(notificationInterface.isAssignableFrom(impl("notification")), "notification interface mismatch");
        require(auditInterface.isAssignableFrom(impl("audit")), "audit interface mismatch");
        require(nested("FulfillmentOrchestrator").isAssignableFrom(impl("fulfillment")),
                "fulfillment interface mismatch");
    }

    private void publicConstructors() throws Exception {
        for (String module : List.of("inventory", "payment", "shipping", "notification", "audit")) {
            constructor(impl(module));
            constructor(impl(module), failureSwitch);
        }
        constructor(impl("fulfillment"), inventoryInterface, paymentInterface, shippingInterface,
                notificationInterface, auditInterface);
    }

    private void inventoryReserve() throws Exception {
        Object service = service("inventory");
        call(service, "setStock", "sku", 3);
        require((boolean) call(service, "reserve", "o1", "sku", 2), "reserve returned false");
        require((boolean) call(service, "isReserved", "o1"), "reservation missing");
    }

    private void inventoryInsufficient() throws Exception {
        Object service = service("inventory");
        call(service, "setStock", "sku", 1);
        require(!(boolean) call(service, "reserve", "o1", "sku", 2), "insufficient reserve succeeded");
        require((int) call(service, "reservationCount") == 0, "reservation recorded");
    }

    private void inventoryIdempotent() throws Exception {
        Object service = service("inventory");
        call(service, "setStock", "sku", 2);
        require((boolean) call(service, "reserve", "o1", "sku", 2), "first reserve failed");
        require((boolean) call(service, "reserve", "o1", "sku", 2), "second reserve failed");
        require((int) call(service, "reservationCount") == 1, "duplicate reservation");
    }

    private void inventoryRelease() throws Exception {
        Object service = service("inventory");
        call(service, "setStock", "sku", 2);
        call(service, "reserve", "o1", "sku", 2);
        call(service, "release", "o1");
        call(service, "release", "o1");
        require(!(boolean) call(service, "isReserved", "o1"), "reservation retained");
        require((int) call(service, "reservationCount") == 0, "reservation count retained");
    }

    private void paymentReference() throws Exception {
        Object value = call(service("payment"), "authorize", "o1", BigDecimal.TEN);
        require(value instanceof String text && !text.isBlank(), "authorization reference missing");
    }

    private void paymentState() throws Exception {
        Object service = service("payment");
        call(service, "authorize", "o1", BigDecimal.TEN);
        require((boolean) call(service, "isAuthorized", "o1"), "authorization missing");
        require((int) call(service, "authorizationCount") == 1, "authorization count mismatch");
    }

    private void paymentIdempotent() throws Exception {
        Object service = service("payment");
        Object first = call(service, "authorize", "o1", BigDecimal.TEN);
        Object second = call(service, "authorize", "o1", BigDecimal.TEN);
        require(first.equals(second), "authorization reference changed");
        require((int) call(service, "authorizationCount") == 1, "duplicate authorization");
    }

    private void paymentRefund() throws Exception {
        Object service = service("payment");
        call(service, "authorize", "o1", BigDecimal.TEN);
        call(service, "refund", "o1");
        call(service, "refund", "o1");
        require((boolean) call(service, "isRefunded", "o1"), "refund missing");
        require(!(boolean) call(service, "isAuthorized", "o1"), "authorization still active");
    }

    private void shippingReference() throws Exception {
        Object value = call(service("shipping"), "createShipment", "o1");
        require(value instanceof String text && !text.isBlank(), "shipment reference missing");
    }

    private void shippingState() throws Exception {
        Object service = service("shipping");
        call(service, "createShipment", "o1");
        require((boolean) call(service, "hasShipment", "o1"), "shipment missing");
        require((int) call(service, "shipmentCount") == 1, "shipment count mismatch");
    }

    private void shippingIdempotent() throws Exception {
        Object service = service("shipping");
        Object first = call(service, "createShipment", "o1");
        Object second = call(service, "createShipment", "o1");
        require(first.equals(second), "shipment reference changed");
        require((int) call(service, "shipmentCount") == 1, "duplicate shipment");
    }

    private void shippingCancel() throws Exception {
        Object service = service("shipping");
        call(service, "createShipment", "o1");
        call(service, "cancelShipment", "o1");
        call(service, "cancelShipment", "o1");
        require((boolean) call(service, "isCancelled", "o1"), "cancellation missing");
        require(!(boolean) call(service, "hasShipment", "o1"), "shipment still active");
    }

    private void notificationSuccess() throws Exception {
        Object service = service("notification");
        call(service, "notifySuccess", "o1");
        call(service, "notifySuccess", "o1");
        require((int) call(service, "successCount", "o1") == 1, "success notification not idempotent");
    }

    private void notificationFailure() throws Exception {
        Object service = service("notification");
        call(service, "notifyFailure", "o1", "reason");
        call(service, "notifyFailure", "o1", "other");
        require((int) call(service, "failureCount", "o1") == 1, "failure notification not idempotent");
    }

    private void notificationInjection() throws Exception {
        Object service = service("notification", Set.of("NOTIFICATION_SUCCESS"));
        requireThrows(() -> call(service, "notifySuccess", "o1"), "success failure switch ignored");
    }

    private void auditEvents() throws Exception {
        Object service = service("audit");
        call(service, "append", "o1", "NEW");
        call(service, "append", "o1", "COMPLETED");
        call(service, "append", "o2", "FAILED");
        require(call(service, "events", "o1").equals(List.of("NEW", "COMPLETED")), "event order mismatch");
        require(call(service, "events", "o2").equals(List.of("FAILED")), "orders not isolated");
    }

    private void auditInjection() throws Exception {
        Object service = service("audit", Set.of("AUDIT_APPEND"));
        requireThrows(() -> call(service, "append", "o1", "NEW"), "audit failure switch ignored");
    }

    private void normalCompleted() throws Exception {
        Fixture fixture = fixture(Set.of());
        Object result = fixture.fulfill("o1", "key1");
        require("COMPLETED".equals(String.valueOf(call(result, "state"))), "result is not COMPLETED");
        require("o1".equals(call(result, "orderId")), "result order mismatch");
    }

    private void normalResources() throws Exception {
        Fixture fixture = fixture(Set.of());
        fixture.fulfill("o1", "key1");
        require((boolean) call(fixture.inventory, "isReserved", "o1"), "inventory not retained");
        require((boolean) call(fixture.payment, "isAuthorized", "o1"), "payment not retained");
        require((boolean) call(fixture.shipping, "hasShipment", "o1"), "shipment not retained");
    }

    private void normalNotification() throws Exception {
        Fixture fixture = fixture(Set.of());
        fixture.fulfill("o1", "key1");
        require((int) call(fixture.notification, "successCount", "o1") == 1, "success notification missing");
        require((int) call(fixture.notification, "failureCount", "o1") == 0, "failure notification emitted");
    }

    private void normalAudit() throws Exception {
        Fixture fixture = fixture(Set.of());
        fixture.fulfill("o1", "key1");
        @SuppressWarnings("unchecked") List<String> events = (List<String>) call(fixture.audit, "events", "o1");
        require(isSubsequence(events, List.of("NEW", "INVENTORY_RESERVED", "PAYMENT_AUTHORIZED",
                "SHIPMENT_CREATED", "COMPLETED")), "state sequence missing: " + events);
    }

    private void paymentCompensation() throws Exception {
        Fixture fixture = fixture(Set.of("PAYMENT_AUTHORIZE"));
        Object result = fixture.fulfill("o1", "key1");
        require("FAILED".equals(String.valueOf(call(result, "state"))), "result is not FAILED");
        require(!(boolean) call(fixture.inventory, "isReserved", "o1"), "inventory not released");
        require((int) call(fixture.notification, "failureCount", "o1") == 1, "failure notification missing");
    }

    private void shippingCompensation() throws Exception {
        Fixture fixture = fixture(Set.of("SHIPPING_CREATE"));
        fixture.fulfill("o1", "key1");
        require(!(boolean) call(fixture.inventory, "isReserved", "o1"), "inventory not released");
        require((boolean) call(fixture.payment, "isRefunded", "o1"), "payment not refunded");
        require((int) call(fixture.notification, "failureCount", "o1") == 1, "failure notification missing");
    }

    private void notificationCompensation() throws Exception {
        Fixture fixture = fixture(Set.of("NOTIFICATION_SUCCESS"));
        fixture.fulfill("o1", "key1");
        require(!(boolean) call(fixture.inventory, "isReserved", "o1"), "inventory not released");
        require((boolean) call(fixture.payment, "isRefunded", "o1"), "payment not refunded");
        require((boolean) call(fixture.shipping, "isCancelled", "o1"), "shipment not cancelled");
        require((int) call(fixture.notification, "failureCount", "o1") == 1, "failure notification missing");
    }

    private void repeatedRequest() throws Exception {
        Fixture fixture = fixture(Set.of());
        Object first = fixture.fulfill("o1", "key1");
        Object second = fixture.fulfill("o1", "key1");
        require(String.valueOf(call(first, "state")).equals(String.valueOf(call(second, "state"))), "result changed");
        require(fixture.effectCount() == 4, "side effects repeated");
    }

    private void repeatedKey() throws Exception {
        Fixture fixture = fixture(Set.of());
        Object first = fixture.fulfill("o1", "shared-key");
        Object second = fixture.fulfill("o2", "shared-key");
        require(call(first, "orderId").equals(call(second, "orderId")), "idempotency key not reused");
        require(fixture.effectCount() == 4, "second order executed");
    }

    private void concurrentRequest() throws Exception {
        Fixture fixture = fixture(Set.of());
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Object>> calls = Stream.generate(
                    () -> (Callable<Object>) () -> fixture.fulfill("o1", "key1")).limit(8).toList();
            List<Future<Object>> results = executor.invokeAll(calls);
            for (Future<Object> result : results) {
                require("COMPLETED".equals(String.valueOf(call(result.get(), "state"))), "concurrent result failed");
            }
            require(fixture.effectCount() == 4, "concurrent side effects repeated");
        } finally {
            executor.shutdownNow();
        }
    }

    private Fixture fixture(Set<String> failures) throws Exception {
        Object inventory = service("inventory", failures);
        call(inventory, "setStock", "sku", 100);
        Object payment = service("payment", failures);
        Object shipping = service("shipping", failures);
        Object notification = service("notification", failures);
        Object audit = service("audit", failures);
        Object orchestrator = constructor(impl("fulfillment"), inventoryInterface, paymentInterface,
                shippingInterface, notificationInterface, auditInterface)
                .newInstance(inventory, payment, shipping, notification, audit);
        return new Fixture(inventory, payment, shipping, notification, audit, orchestrator);
    }

    private Object service(String module) throws Exception {
        return service(module, Set.of());
    }

    private Object service(String module, Set<String> failures) throws Exception {
        return constructor(impl(module), failureSwitch).newInstance(failureProxy(failures));
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
            String operationName = String.valueOf(args[0]);
            if (failures.contains(operationName)) throw new InjectedFailure(operationName);
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
        private final Object inventory;
        private final Object payment;
        private final Object shipping;
        private final Object notification;
        private final Object audit;
        private final Object orchestrator;

        private Fixture(Object inventory, Object payment, Object shipping, Object notification,
                        Object audit, Object orchestrator) {
            this.inventory = inventory;
            this.payment = payment;
            this.shipping = shipping;
            this.notification = notification;
            this.audit = audit;
            this.orchestrator = orchestrator;
        }

        private Object fulfill(String orderId, String key) throws Exception {
            Object value = constructor(request, String.class, String.class, int.class, BigDecimal.class, String.class)
                    .newInstance(orderId, "sku", 2, BigDecimal.TEN, key);
            return call(orchestrator, "fulfill", value);
        }

        private int effectCount() throws Exception {
            return (int) call(inventory, "reservationCount")
                    + (int) call(payment, "authorizationCount")
                    + (int) call(shipping, "shipmentCount")
                    + (int) call(notification, "successCount", "o1");
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
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception exception) throw exception;
            if (cause instanceof Error error) throw error;
            throw e;
        }
    }

    private static boolean isSubsequence(List<String> actual, List<String> expected) {
        int index = 0;
        for (String value : actual) {
            if (index < expected.size() && expected.get(index).equals(value)) index++;
        }
        return index == expected.size();
    }

    private static byte[] sha256(Path path) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
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
