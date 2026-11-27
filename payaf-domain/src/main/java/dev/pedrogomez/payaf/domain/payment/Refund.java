package dev.pedrogomez.payaf.domain.payment;

import dev.pedrogomez.payaf.domain.shared.InvalidValueException;
import dev.pedrogomez.payaf.domain.shared.Money;
import java.time.Instant;
import java.util.Objects;

/**
 * Un reembolso aplicado a un pago, identificado por la referencia que le dio la pasarela.
 * <p>
 * Es dominio porque la referencia es lo que permite saber si dos avisos hablan del mismo
 * reembolso: el que pedimos por la API y la notificación con la que la PSP lo confirma
 * después son el mismo dinero, y contarlos dos veces sería devolver de más en nuestros
 * libros. Por eso el pago guarda la lista de reembolsos y no solo el total.
 *
 * @param gatewayRefundReference referencia del reembolso en la PSP; no puede estar en blanco
 * @param amount                 importe devuelto; estrictamente positivo
 * @param at                     cuándo se aplicó al pago
 */
public record Refund(String gatewayRefundReference, Money amount, Instant at) {

    public Refund {
        Objects.requireNonNull(gatewayRefundReference, "la referencia del reembolso no puede ser nula");
        Objects.requireNonNull(amount, "el importe no puede ser nulo");
        Objects.requireNonNull(at, "el instante no puede ser nulo");
        if (gatewayRefundReference.isBlank()) {
            throw new InvalidValueException("La referencia del reembolso no puede estar en blanco");
        }
        if (!amount.isPositive()) {
            throw new InvalidValueException("El importe del reembolso debe ser mayor que cero: " + amount);
        }
    }
}
