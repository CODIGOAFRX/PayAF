package dev.pedrogomez.payaf.application.fake;

import dev.pedrogomez.payaf.application.port.out.GatewayAuthorization;
import dev.pedrogomez.payaf.application.port.out.GatewayResponse;
import dev.pedrogomez.payaf.application.port.out.PaymentGateway;
import dev.pedrogomez.payaf.domain.shared.GatewayId;
import dev.pedrogomez.payaf.domain.shared.Money;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Pasarela cuyo comportamiento es una función (que puede lanzar, bloquearse o devolver
 * {@code null}). Es segura para hilos: sirve para las pruebas de propiedades y de
 * concurrencia. Cuenta las llamadas y avisa al {@link FakeTransactionRunner} de cada una.
 * No avanza el reloj.
 */
public final class FunctionalGateway implements PaymentGateway {

    private final GatewayId id;
    private final FakeTransactionRunner transactions;
    private final Function<GatewayAuthorization, GatewayResponse> onAuthorize;
    private volatile Function<String, GatewayResponse> onOperation;
    private final AtomicInteger authorizeCalls = new AtomicInteger();
    private final AtomicInteger operationCalls = new AtomicInteger();
    private final List<GatewayAuthorization> authorizations = new CopyOnWriteArrayList<>();

    public FunctionalGateway(GatewayId id, FakeTransactionRunner transactions,
                             Function<GatewayAuthorization, GatewayResponse> onAuthorize) {
        this.id = id;
        this.transactions = transactions;
        this.onAuthorize = onAuthorize;
        this.onOperation = op -> {
            throw new AssertionError("Operación inesperada en " + id + ": " + op);
        };
    }

    public FunctionalGateway onOperation(Function<String, GatewayResponse> behaviour) {
        this.onOperation = behaviour;
        return this;
    }

    public int authorizeCalls() {
        return authorizeCalls.get();
    }

    public int operationCalls() {
        return operationCalls.get();
    }

    public List<GatewayAuthorization> authorizations() {
        return List.copyOf(authorizations);
    }

    @Override
    public GatewayId id() {
        return id;
    }

    @Override
    public GatewayResponse authorize(GatewayAuthorization authorization) {
        transactions.requireNoTransaction(id + ".AUTHORIZE");
        authorizeCalls.incrementAndGet();
        authorizations.add(authorization);
        return onAuthorize.apply(authorization);
    }

    @Override
    public GatewayResponse capture(String gatewayReference, Money amount) {
        return operation("capture " + gatewayReference + " " + amount);
    }

    @Override
    public GatewayResponse refund(String gatewayReference, Money amount) {
        return operation("refund " + gatewayReference + " " + amount);
    }

    @Override
    public GatewayResponse cancel(String gatewayReference) {
        return operation("cancel " + gatewayReference);
    }

    private GatewayResponse operation(String detail) {
        transactions.requireNoTransaction(id + "." + detail);
        operationCalls.incrementAndGet();
        return onOperation.apply(detail);
    }

    /**
     * Lanza una excepción comprobada sin declararla, como puede hacer un adaptador escrito en
     * Kotlin, con Lombok {@code @SneakyThrows} o con una librería que la relanza así.
     */
    @SuppressWarnings("unchecked")
    public static <E extends Throwable> RuntimeException sneakyThrow(Throwable e) throws E {
        throw (E) e;
    }
}
