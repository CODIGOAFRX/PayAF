/**
 * Objetos de valor compartidos por todo el dominio: dinero, divisas, países e
 * identificadores de pasarela, además de la raíz de las excepciones de dominio.
 * <p>
 * Son dominio porque encierran reglas del negocio de pagos (cuántos decimales tiene
 * cada divisa, cómo se redondea una comisión, qué es un código de país válido) que
 * no dependen de cómo se guarden ni de cómo se expongan. Son inmutables y se validan
 * al construirse: si existe una instancia, es válida.
 */
package dev.pedrogomez.payaf.domain.shared;
