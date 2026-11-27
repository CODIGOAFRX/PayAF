package dev.pedrogomez.payaf.adapter.in.rest;

import dev.pedrogomez.payaf.adapter.in.rest.ApiDtos.AttemptDto;
import dev.pedrogomez.payaf.adapter.in.rest.ApiDtos.AuthorizationDto;
import dev.pedrogomez.payaf.adapter.in.rest.ApiDtos.CandidateDto;
import dev.pedrogomez.payaf.adapter.in.rest.ApiDtos.CircuitDto;
import dev.pedrogomez.payaf.adapter.in.rest.ApiDtos.CreatePaymentRequest;
import dev.pedrogomez.payaf.adapter.in.rest.ApiDtos.GatewayDto;
import dev.pedrogomez.payaf.adapter.in.rest.ApiDtos.MoneyDto;
import dev.pedrogomez.payaf.adapter.in.rest.ApiDtos.PaymentDto;
import dev.pedrogomez.payaf.adapter.in.rest.ApiDtos.RefundDto;
import dev.pedrogomez.payaf.adapter.in.rest.ApiDtos.RejectionDto;
import dev.pedrogomez.payaf.adapter.in.rest.ApiDtos.RoutingDto;
import dev.pedrogomez.payaf.application.port.in.AuthorizationResult;
import dev.pedrogomez.payaf.application.port.in.AuthorizePaymentCommand;
import dev.pedrogomez.payaf.application.port.in.GatewayHealthView;
import dev.pedrogomez.payaf.application.port.in.PaymentView;
import dev.pedrogomez.payaf.domain.payment.GatewayAttempt;
import dev.pedrogomez.payaf.domain.payment.IdempotencyKey;
import dev.pedrogomez.payaf.domain.payment.PaymentId;
import dev.pedrogomez.payaf.domain.payment.PaymentRequest;
import dev.pedrogomez.payaf.domain.routing.CircuitBreakerSnapshot;
import dev.pedrogomez.payaf.domain.routing.GatewayProfile;
import dev.pedrogomez.payaf.domain.routing.RoutingDecision;
import dev.pedrogomez.payaf.domain.shared.CountryCode;
import dev.pedrogomez.payaf.domain.shared.Currency;
import dev.pedrogomez.payaf.domain.shared.GatewayId;
import dev.pedrogomez.payaf.domain.shared.InvalidValueException;
import dev.pedrogomez.payaf.domain.shared.Money;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Traducción explícita entre los DTO de la API y los tipos de la aplicación y el dominio.
 * <p>
 * De entrada, construye los objetos de valor del dominio, que son los que validan: si algo no
 * vale, falla aquí con {@link InvalidValueException} (422). Un campo que falta es otra cosa, un
 * cuerpo mal formado, y se responde 400 ({@link InvalidBodyException}).
 */
public final class RestMapper {

    private RestMapper() {
    }

    // ------------------------------------------------------------------ entrada

    public static AuthorizePaymentCommand toCommand(CreatePaymentRequest body, String idempotencyKey) {
        if (body == null || body.amount() == null || body.currency() == null || body.country() == null
                || body.merchantReference() == null) {
            throw new InvalidBodyException("El cuerpo debe traer amount, currency, country y merchantReference");
        }
        Currency currency = currency(body.currency());
        return new AuthorizePaymentCommand(new PaymentRequest(money(body.amount(), currency),
                CountryCode.of(body.country()), body.merchantReference(), IdempotencyKey.of(idempotencyKey)));
    }

    public static Money money(String amount, Currency currency) {
        try {
            return Money.parse(amount, currency);
        } catch (ArithmeticException e) {
            throw new InvalidValueException("El importe «" + amount + "» es demasiado grande");
        }
    }

    public static Currency currency(String code) {
        try {
            return Currency.valueOf(code.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new InvalidValueException("Divisa no admitida: «" + code + "»");
        }
    }

    public static PaymentId paymentId(String id) {
        try {
            return PaymentId.of(id);
        } catch (IllegalArgumentException e) {
            throw new InvalidValueException("Identificador de pago no válido: «" + id + "»");
        }
    }

    // ------------------------------------------------------------------ salida

    public static MoneyDto money(Money money) {
        return new MoneyDto(money.toDecimalString(), money.currency().name());
    }

    public static AttemptDto attempt(GatewayAttempt a) {
        return new AttemptDto(a.gateway().value(), a.outcome().name(), a.latency().toMillis(), a.code().orElse(null),
                a.gatewayReference().orElse(null), a.at());
    }

    public static PaymentDto payment(PaymentView p) {
        return new PaymentDto(p.id().toString(), money(p.amount()), p.country().value(), p.merchantReference(),
                p.status().name(), p.authorizedBy().map(GatewayId::value).orElse(null),
                p.gatewayReference().orElse(null), p.failureReason().orElse(null), money(p.refunded()),
                money(p.remainingRefundable()),
                p.refunds().stream().map(r -> new RefundDto(r.gatewayRefundReference(), money(r.amount()), r.at()))
                        .toList(),
                p.attempts().stream().map(RestMapper::attempt).toList(), p.createdAt(), p.updatedAt());
    }

    public static RoutingDto routing(RoutingDecision d) {
        return new RoutingDto(
                d.candidates().stream().map(c -> new CandidateDto(c.gateway().value(), money(c.estimatedFee()),
                        c.circuitState().name())).toList(),
                d.rejections().stream().map(r -> new RejectionDto(r.gateway().value(), r.reason().name())).toList());
    }

    public static AuthorizationDto authorization(AuthorizationResult r) {
        return new AuthorizationDto(payment(r.payment()), routing(r.decision()),
                r.attempts().stream().map(RestMapper::attempt).toList(), r.replayed());
    }

    public static GatewayDto gateway(GatewayHealthView view) {
        GatewayProfile p = view.profile();
        CircuitBreakerSnapshot c = view.circuit();
        Map<String, Long> fees = new TreeMap<>();
        p.fixedFeeMinor().forEach((currency, fee) -> fees.put(currency.name(), fee));
        return new GatewayDto(p.id().value(), p.enabled(),
                p.currencies().stream().map(Enum::name).sorted().toList(),
                p.countries().stream().map(CountryCode::value).sorted().toList(),
                p.feeBasisPoints(), fees,
                new CircuitDto(c.state().name(), c.consecutiveFailures(), c.openedAt().orElse(null),
                        c.retryAt().orElse(null), c.trialsInFlight()));
    }

    static List<GatewayDto> gateways(List<GatewayHealthView> views) {
        return views.stream().map(RestMapper::gateway).toList();
    }
}
