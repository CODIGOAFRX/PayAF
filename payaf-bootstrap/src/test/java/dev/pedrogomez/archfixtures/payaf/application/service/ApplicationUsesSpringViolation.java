package dev.pedrogomez.archfixtures.payaf.application.service;

import org.springframework.stereotype.Service;

/** Viola la regla: la aplicación se anota con Spring (solo bootstrap cablea con Spring). */
@Service
public class ApplicationUsesSpringViolation {
}
