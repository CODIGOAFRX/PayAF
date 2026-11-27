package dev.pedrogomez.payaf.adapter.in.rest;

import dev.pedrogomez.payaf.application.exception.ApplicationException;
import dev.pedrogomez.payaf.domain.payment.InconsistentPaymentStateException;
import dev.pedrogomez.payaf.domain.shared.DomainException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Errores de la API como {@link ProblemDetail} (RFC 9457) con una propiedad {@code code} estable,
 * que es la que deben mirar los clientes (el {@code detail} es para personas y puede cambiar).
 * <p>
 * Se traduce por el código de la excepción, nunca por su mensaje:
 * <ul>
 *   <li>{@code INVALID_VALUE}, {@code ILLEGAL_TRANSITION} y demás reglas del dominio → 422 (la
 *       petición se entiende, pero el negocio no la admite); {@code INCONSISTENT_STATE} → 500,
 *       porque es un dato corrupto nuestro.</li>
 *   <li>{@code PAYMENT_NOT_FOUND} → 404; {@code IDEMPOTENCY_CONFLICT}, {@code IDEMPOTENCY_IN_PROGRESS}
 *       y {@code CONCURRENT_MODIFICATION} → 409; {@code GATEWAY_OPERATION_FAILED} → 502 (falla la
 *       PSP, no nosotros); {@code OPERATION_UNRECORDED} y {@code AUTHORIZATION_UNRECORDED} → 500.</li>
 *   <li>Cuerpo ilegible o incompleto → 400 {@code INVALID_BODY}; sin {@code Idempotency-Key} → 400
 *       {@code MISSING_HEADER}; pasarela desconocida en el simulador → 404 {@code GATEWAY_NOT_FOUND}.</li>
 * </ul>
 * Solo se aplica a los controladores de este adaptador (no a los webhooks).
 */
@RestControllerAdvice(basePackageClasses = ApiExceptionHandler.class)
public class ApiExceptionHandler {

    @ExceptionHandler(DomainException.class)
    ResponseEntity<ProblemDetail> domain(DomainException e) {
        HttpStatus status = InconsistentPaymentStateException.CODE.equals(e.code())
                ? HttpStatus.INTERNAL_SERVER_ERROR : HttpStatus.UNPROCESSABLE_CONTENT;
        return problem(status, e.code(), e.getMessage());
    }

    @ExceptionHandler(ApplicationException.class)
    ResponseEntity<ProblemDetail> application(ApplicationException e) {
        HttpStatus status = switch (e.code()) {
            case ApplicationException.PAYMENT_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case ApplicationException.IDEMPOTENCY_CONFLICT, ApplicationException.IDEMPOTENCY_IN_PROGRESS,
                 ApplicationException.CONCURRENT_MODIFICATION -> HttpStatus.CONFLICT;
            case ApplicationException.GATEWAY_OPERATION_FAILED -> HttpStatus.BAD_GATEWAY;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        return problem(status, e.code(), e.getMessage());
    }

    @ExceptionHandler({InvalidBodyException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class})
    ResponseEntity<ProblemDetail> invalidBody(Exception e) {
        String detail = e instanceof InvalidBodyException ? e.getMessage() : "El cuerpo o los parámetros no son válidos";
        return problem(HttpStatus.BAD_REQUEST, "INVALID_BODY", detail);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<ProblemDetail> missingHeader(MissingRequestHeaderException e) {
        return problem(HttpStatus.BAD_REQUEST, "MISSING_HEADER", "Falta la cabecera " + e.getHeaderName());
    }

    @ExceptionHandler(ChaosAdmin.UnknownGatewayException.class)
    ResponseEntity<ProblemDetail> unknownGateway(ChaosAdmin.UnknownGatewayException e) {
        return problem(HttpStatus.NOT_FOUND, "GATEWAY_NOT_FOUND", e.getMessage());
    }

    static ResponseEntity<ProblemDetail> problem(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("code", code);
        return ResponseEntity.status(status).body(problem);
    }
}
