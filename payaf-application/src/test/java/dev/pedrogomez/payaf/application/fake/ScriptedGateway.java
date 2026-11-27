package dev.pedrogomez.payaf.application.fake;

import dev.pedrogomez.payaf.application.port.out.GatewayAuthorization;
import dev.pedrogomez.payaf.application.port.out.GatewayResponse;
import dev.pedrogomez.payaf.application.port.out.PaymentGateway;
import dev.pedrogomez.payaf.domain.shared.GatewayId;
import dev.pedrogomez.payaf.domain.shared.Money;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Pasarela con guion: devuelve, por operación, las respuestas que el test le ha puesto en
 * cola, y apunta cada llamada. Si se le llama sin respuesta preparada, falla el test con un
 * {@link AssertionError} (que el servicio no captura). Avanza el reloj lo que diga la
 * latencia de cada respuesta y avisa al {@link FakeTransactionRunner} de cada llamada para
 * detectar las que se hacen con una transacción abierta.
 */
public final class ScriptedGateway implements PaymentGateway {

    public enum Operation { AUTHORIZE, CAPTURE, REFUND, CANCEL }

    public record Call(Operation operation, String detail) {
    }

    private final GatewayId id;
    private final FakeTransactionRunner transactions;
    private final MutableClock clock;
    private final Map<Operation, Deque<GatewayResponse>> script = new EnumMap<>(Operation.class);
    private final List<Call> calls = new ArrayList<>();

    public ScriptedGateway(GatewayId id, FakeTransactionRunner transactions, MutableClock clock) {
        this.id = id;
        this.transactions = transactions;
        this.clock = clock;
        for (Operation op : Operation.values()) {
            script.put(op, new ArrayDeque<>());
        }
    }

    public ScriptedGateway on(Operation operation, GatewayResponse... responses) {
        script.get(operation).addAll(List.of(responses));
        return this;
    }

    public List<Call> calls() {
        return List.copyOf(calls);
    }

    public long calls(Operation operation) {
        return calls.stream().filter(c -> c.operation() == operation).count();
    }

    @Override
    public GatewayId id() {
        return id;
    }

    @Override
    public GatewayResponse authorize(GatewayAuthorization authorization) {
        return respond(Operation.AUTHORIZE, authorization.paymentId() + " " + authorization.amount());
    }

    @Override
    public GatewayResponse capture(String gatewayReference, Money amount) {
        return respond(Operation.CAPTURE, gatewayReference + " " + amount);
    }

    @Override
    public GatewayResponse refund(String gatewayReference, Money amount) {
        return respond(Operation.REFUND, gatewayReference + " " + amount);
    }

    @Override
    public GatewayResponse cancel(String gatewayReference) {
        return respond(Operation.CANCEL, gatewayReference);
    }

    private GatewayResponse respond(Operation operation, String detail) {
        transactions.requireNoTransaction(id + "." + operation);
        calls.add(new Call(operation, detail));
        GatewayResponse response = script.get(operation).poll();
        if (response == null) {
            throw new AssertionError("Llamada inesperada a " + id + "." + operation + " (" + detail + ")");
        }
        clock.advance(response.latency());
        return response;
    }
}
