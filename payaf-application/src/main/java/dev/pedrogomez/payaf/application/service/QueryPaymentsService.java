package dev.pedrogomez.payaf.application.service;

import dev.pedrogomez.payaf.application.exception.ApplicationException;
import dev.pedrogomez.payaf.application.port.in.PaymentView;
import dev.pedrogomez.payaf.application.port.in.QueryPaymentsUseCase;
import dev.pedrogomez.payaf.application.port.out.PaymentRepository;
import dev.pedrogomez.payaf.application.port.out.TransactionRunner;
import dev.pedrogomez.payaf.domain.payment.PaymentId;
import dev.pedrogomez.payaf.domain.shared.InvalidValueException;
import java.util.List;
import java.util.Objects;

/** Consultas de pagos. Convierte cada agregado en una {@link PaymentView} antes de devolverlo. */
public final class QueryPaymentsService implements QueryPaymentsUseCase {

    private final PaymentRepository payments;
    private final TransactionRunner transactions;

    public QueryPaymentsService(PaymentRepository payments, TransactionRunner transactions) {
        this.payments = Objects.requireNonNull(payments);
        this.transactions = Objects.requireNonNull(transactions);
    }

    @Override
    public PaymentView get(PaymentId paymentId) {
        Objects.requireNonNull(paymentId, "el identificador no puede ser nulo");
        return transactions.inTransaction(() -> payments.findById(paymentId).map(PaymentView::from))
                .orElseThrow(() -> ApplicationException.paymentNotFound(paymentId));
    }

    @Override
    public List<PaymentView> recent(int limit) {
        if (limit < 1) {
            throw new InvalidValueException("El límite debe ser al menos 1: " + limit);
        }
        return transactions.inTransaction(() -> payments.findRecent(limit).stream().map(PaymentView::from).toList());
    }
}
