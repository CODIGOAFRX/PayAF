package dev.pedrogomez.payaf.application.port.out;

import java.util.function.Supplier;

/**
 * Puerto de salida (driven): delimita una transacción.
 * <p>
 * La aplicación necesita decir dónde empieza y acaba cada transacción, porque es una
 * decisión del caso de uso (por ejemplo, que nunca haya una abierta mientras se llama a una
 * pasarela). Pero no puede usar {@code @Transactional} sin depender de Spring. Con este
 * puerto la frontera queda explícita en el código del servicio y el adaptador (Spring
 * {@code TransactionTemplate}, o nada en memoria) la implementa.
 * <p>
 * Contrato: si {@code work} lanza una excepción, la transacción se deshace y la excepción
 * llega tal cual a quien llamó.
 */
public interface TransactionRunner {

    <T> T inTransaction(Supplier<T> work);
}
