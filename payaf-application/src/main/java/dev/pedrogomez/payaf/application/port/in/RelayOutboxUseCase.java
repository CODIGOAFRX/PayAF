package dev.pedrogomez.payaf.application.port.in;

/**
 * Puerto de entrada (driving): repartir los eventos pendientes del outbox.
 * <p>
 * Lo invoca un planificador de bootstrap, no un comercio. En cada pasada lee como mucho
 * {@code batch} mensajes pendientes, los entrega en orden al {@code EventStream} y los marca como
 * publicados, todo en una misma transacción. Si algo falla a medias, se reintentan en la pasada
 * siguiente: un suscriptor puede recibir un evento repetido (lo reconoce por su {@code id}), pero
 * nunca se pierde uno ya confirmado.
 */
public interface RelayOutboxUseCase {

    /**
     * @param batch cuántos mensajes como mucho; al menos 1
     * @return cuántos se han entregado
     */
    int relay(int batch);
}
