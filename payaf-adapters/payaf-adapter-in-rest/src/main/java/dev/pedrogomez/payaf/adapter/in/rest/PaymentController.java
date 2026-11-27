package dev.pedrogomez.payaf.adapter.in.rest;

import dev.pedrogomez.payaf.adapter.in.rest.ApiDtos.AuthorizationDto;
import dev.pedrogomez.payaf.adapter.in.rest.ApiDtos.CreatePaymentRequest;
import dev.pedrogomez.payaf.adapter.in.rest.ApiDtos.PaymentDto;
import dev.pedrogomez.payaf.adapter.in.rest.ApiDtos.RefundRequest;
import dev.pedrogomez.payaf.application.port.in.AuthorizationResult;
import dev.pedrogomez.payaf.application.port.in.AuthorizePaymentUseCase;
import dev.pedrogomez.payaf.application.port.in.CancelPaymentUseCase;
import dev.pedrogomez.payaf.application.port.in.CapturePaymentUseCase;
import dev.pedrogomez.payaf.application.port.in.PaymentView;
import dev.pedrogomez.payaf.application.port.in.QueryPaymentsUseCase;
import dev.pedrogomez.payaf.application.port.in.RefundPaymentUseCase;
import dev.pedrogomez.payaf.domain.payment.PaymentId;
import java.net.URI;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Adaptador de entrada (driving) REST para los pagos. Traduce HTTP a los puertos de entrada y de
 * vuelta, sin lógica de negocio: la misma que usa la CLI, con otro formato.
 */
@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    /** Cabecera que indica que la respuesta es la repetición de una orden ya procesada. */
    public static final String REPLAYED_HEADER = "Idempotent-Replayed";
    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 100;

    private final AuthorizePaymentUseCase authorize;
    private final CapturePaymentUseCase capture;
    private final CancelPaymentUseCase cancel;
    private final RefundPaymentUseCase refund;
    private final QueryPaymentsUseCase query;

    public PaymentController(AuthorizePaymentUseCase authorize, CapturePaymentUseCase capture,
                             CancelPaymentUseCase cancel, RefundPaymentUseCase refund, QueryPaymentsUseCase query) {
        this.authorize = authorize;
        this.capture = capture;
        this.cancel = cancel;
        this.refund = refund;
        this.query = query;
    }

    /** 201 con el pago nuevo; 200 y {@code Idempotent-Replayed: true} si la clave ya se usó con la misma orden. */
    @PostMapping
    public ResponseEntity<AuthorizationDto> create(@RequestHeader("Idempotency-Key") String idempotencyKey,
                                                   @RequestBody CreatePaymentRequest body) {
        AuthorizationResult result = authorize.authorize(RestMapper.toCommand(body, idempotencyKey));
        AuthorizationDto dto = RestMapper.authorization(result);
        if (result.replayed()) {
            return ResponseEntity.ok().header(REPLAYED_HEADER, "true").body(dto);
        }
        return ResponseEntity.created(URI.create("/api/payments/" + dto.payment().id())).body(dto);
    }

    @PostMapping("/{id}/capture")
    public PaymentDto capture(@PathVariable String id) {
        return RestMapper.payment(capture.capture(RestMapper.paymentId(id)));
    }

    @PostMapping("/{id}/cancel")
    public PaymentDto cancel(@PathVariable String id) {
        return RestMapper.payment(cancel.cancel(RestMapper.paymentId(id)));
    }

    /** El importe va en la divisa del pago, así que primero se consulta el pago para saber cuál es. */
    @PostMapping("/{id}/refunds")
    public ResponseEntity<PaymentDto> refund(@PathVariable String id, @RequestBody RefundRequest body) {
        if (body == null || body.amount() == null) {
            throw new InvalidBodyException("El cuerpo debe traer amount");
        }
        PaymentId paymentId = RestMapper.paymentId(id);
        PaymentView payment = query.get(paymentId);
        PaymentView refunded = refund.refund(paymentId, RestMapper.money(body.amount(), payment.amount().currency()));
        return ResponseEntity.status(HttpStatus.CREATED).body(RestMapper.payment(refunded));
    }

    @GetMapping("/{id}")
    public PaymentDto get(@PathVariable String id) {
        return RestMapper.payment(query.get(RestMapper.paymentId(id)));
    }

    /** Los últimos pagos; {@code limit} se ajusta a [1, 100]. */
    @GetMapping
    public List<PaymentDto> recent(@RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit) {
        int bounded = Math.max(1, Math.min(MAX_LIMIT, limit));
        return query.recent(bounded).stream().map(RestMapper::payment).toList();
    }
}
