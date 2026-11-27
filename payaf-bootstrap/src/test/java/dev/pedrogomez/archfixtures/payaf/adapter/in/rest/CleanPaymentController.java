package dev.pedrogomez.archfixtures.payaf.adapter.in.rest;

import dev.pedrogomez.archfixtures.payaf.application.service.CleanAuthorizeService;
import dev.pedrogomez.archfixtures.payaf.domain.CleanMoney;
import java.math.BigDecimal;

/** Adaptador de entrada limpio: llama al caso de uso. */
public class CleanPaymentController {

    private final CleanAuthorizeService service;

    public CleanPaymentController(CleanAuthorizeService service) {
        this.service = service;
    }

    public void post() {
        service.authorize(new CleanMoney(BigDecimal.TEN, "EUR"));
    }
}
