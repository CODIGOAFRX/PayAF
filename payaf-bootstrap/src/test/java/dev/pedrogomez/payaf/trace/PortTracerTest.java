package dev.pedrogomez.payaf.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.pedrogomez.payaf.application.port.out.TransactionRunner;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PortTracerTest {

    /** Un puerto de entrada de juguete. */
    interface Greet {
        String greet(String name);
    }

    /** Un puerto de salida de juguete. */
    interface Store {
        String load(String key);
    }

    private final List<PortTracer.PortTouch> touches = new ArrayList<>();
    private final PortTracer tracer = new PortTracer(touches::add);

    private Greet useCase(Store store, TransactionRunner tx) {
        return tracer.driving(Greet.class,
                name -> tx.inTransaction(() -> store.load(name) + "+" + store.load(name.toUpperCase())));
    }

    private Store store() {
        return tracer.driven(Store.class, key -> "v-" + key, "memory");
    }

    private TransactionRunner tx() {
        return tracer.driven(TransactionRunner.class, new TransactionRunner() {
            @Override
            public <T> T inTransaction(Supplier<T> work) {
                return work.get();
            }
        }, "memory");
    }

    @Test
    @DisplayName("dentro de una petición, cada puerto tocado se emite con su orden de inicio y el driving va el último")
    void emitsTouchesInOrder() {
        Greet greet = useCase(store(), tx());
        String result;
        try (TraceContext.Scope ignored = TraceContext.open("req-1", "rest")) {
            result = greet.greet("ana");
        }
        assertThat(result).isEqualTo("v-ana+v-ANA");
        assertThat(touches).hasSize(4);
        PortTracer.PortTouch driving = touches.getLast();
        assertThat(driving.side()).isEqualTo(PortTracer.Side.DRIVING);
        assertThat(driving.port()).isEqualTo("Greet");
        assertThat(driving.adapter()).isEqualTo("rest");
        assertThat(driving.seq()).isZero();
        assertThat(touches).allSatisfy(t -> assertThat(t.correlationId()).isEqualTo("req-1"));
        // La transacción empieza antes que las cargas que contiene, aunque termine después.
        assertThat(touches.stream().filter(t -> t.side() == PortTracer.Side.DRIVEN)
                .sorted((a, b) -> Integer.compare(a.seq(), b.seq())).map(PortTracer.PortTouch::port).toList())
                .containsExactly("TransactionRunner", "Store", "Store");
        assertThat(touches.stream().map(PortTracer.PortTouch::seq).distinct().count()).isEqualTo(4);
    }

    @Test
    @DisplayName("fuera de una petición (sin contexto o sin canal) no se emite nada: el reaper o el relay no ensucian la traza")
    void silentWithoutContext() {
        Greet greet = useCase(store(), tx());
        assertThat(greet.greet("ana")).isEqualTo("v-ana+v-ANA");
        try (TraceContext.Scope ignored = TraceContext.open("req-2", null)) {
            greet.greet("eva");
        }
        assertThat(touches).isEmpty();
    }

    @Test
    @DisplayName("las excepciones pasan intactas y quedan en la traza como resultado")
    void exceptionsPassThrough() {
        Store failing = tracer.driven(Store.class, key -> {
            throw new IllegalStateException("caída");
        }, "memory");
        Greet greet = tracer.driving(Greet.class, failing::load);
        try (TraceContext.Scope ignored = TraceContext.open("req-3", "cli")) {
            assertThatThrownBy(() -> greet.greet("x")).isInstanceOf(IllegalStateException.class).hasMessage("caída");
        }
        assertThat(touches).extracting(PortTracer.PortTouch::outcome)
                .containsExactly("IllegalStateException", "IllegalStateException");
        assertThat(TraceContext.current()).isEmpty();
    }

    @Test
    @DisplayName("el contexto se restaura al cerrarse")
    void scopeRestores() {
        try (TraceContext.Scope outer = TraceContext.open("a", "rest")) {
            try (TraceContext.Scope inner = TraceContext.open("b", "webhook")) {
                assertThat(TraceContext.current()).get().extracting(TraceContext.State::correlationId).isEqualTo("b");
            }
            assertThat(TraceContext.current()).get().extracting(TraceContext.State::correlationId).isEqualTo("a");
        }
        assertThat(TraceContext.current()).isEmpty();
    }

    @Test
    @DisplayName("el adaptador de persistencia sale del paquete de la clase")
    void persistenceAdapterName() {
        assertThat(PortTracer.persistenceAdapter(
                new dev.pedrogomez.payaf.adapter.out.persistencememory.InMemoryTransactionRunner())).isEqualTo("memory");
        assertThat(PortTracer.persistenceAdapter("texto")).isEqualTo("String");
    }
}
