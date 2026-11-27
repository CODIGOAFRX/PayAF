package dev.pedrogomez.payaf.adapter.in.rest;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Los DTO de la API: records propios, distintos de {@code PaymentView} y del dominio.
 * <p>
 * Ni el dominio ni las vistas de la aplicación se serializan tal cual: si se hiciera, renombrar
 * un campo de un record del dominio cambiaría el JSON público sin que nadie lo decidiera, y los
 * {@code Optional} o el {@code Money} en unidades menores saldrían con una forma pensada para Java
 * y no para quien consume la API. {@link RestMapper} hace la traducción, de forma explícita.
 */
public final class ApiDtos {

    private ApiDtos() {
    }

    /** Cuerpo de {@code POST /api/payments}. El importe es un decimal en texto, para no perder céntimos. */
    public record CreatePaymentRequest(String amount, String currency, String country, String merchantReference) {
    }

    /** Cuerpo de {@code POST /api/payments/{id}/refunds}, en la divisa del pago. */
    public record RefundRequest(String amount) {
    }

    /** Cuerpo de {@code PUT /api/gateways/{id}/enabled}. */
    public record EnabledRequest(Boolean enabled) {
    }

    public record MoneyDto(String amount, String currency) {
    }

    public record AttemptDto(String gateway, String outcome, long latencyMs, String code, String gatewayReference,
                             Instant at) {
    }

    public record RefundDto(String gatewayRefundReference, MoneyDto amount, Instant at) {
    }

    public record PaymentDto(String id, MoneyDto amount, String country, String merchantReference, String status,
                             String authorizedBy, String gatewayReference, String failureReason, MoneyDto refunded,
                             MoneyDto remainingRefundable, List<RefundDto> refunds, List<AttemptDto> attempts,
                             Instant createdAt, Instant updatedAt) {
    }

    public record CandidateDto(String gateway, MoneyDto estimatedFee, String circuitState) {
    }

    public record RejectionDto(String gateway, String reason) {
    }

    public record RoutingDto(List<CandidateDto> candidates, List<RejectionDto> rejections) {
    }

    /** Respuesta de autorizar: el pago, la decisión de enrutado y los intentos. */
    public record AuthorizationDto(PaymentDto payment, RoutingDto routing, List<AttemptDto> attempts,
                                   boolean replayed) {
    }

    public record CircuitDto(String state, int consecutiveFailures, Instant openedAt, Instant retryAt,
                             int trialsInFlight) {
    }

    public record GatewayDto(String id, boolean enabled, List<String> currencies, List<String> countries,
                             int feeBasisPoints, Map<String, Long> fixedFeeMinor, CircuitDto circuit) {
    }

    public record ChaosDto(String gateway, boolean down, long latencyMs, double errorRate) {
    }

    /** Cuerpo de {@code PUT /api/chaos/{gateway}}; lo que falte se toma como «sin avería». */
    public record ChaosRequest(Boolean down, Long latencyMs, Double errorRate) {
    }
}
