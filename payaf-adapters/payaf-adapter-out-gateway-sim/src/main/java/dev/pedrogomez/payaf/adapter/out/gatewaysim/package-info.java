/**
 * Adaptador de salida (driven) que simula pasarelas de pago (PSP): Stripe, Adyen, Redsys y
 * una PSP de pruebas ({@code mock}).
 * <p>
 * <strong>Es una capa anticorrupción (anti-corruption layer).</strong> Cada PSP simulada
 * habla «su idioma», como la de verdad: Stripe devuelve {@code PaymentIntent} con estados
 * {@code requires_capture} y códigos {@code decline_code}; Adyen, {@code resultCode}
 * {@code Authorised}/{@code Refused} y {@code pspReference} de 16 dígitos; Redsys, campos
 * {@code Ds_Response} numéricos. Cada subpaquete tiene dos piezas:
 * <ol>
 *   <li>Un cliente falso ({@code StripeClient}, {@code AdyenClient}...) que imita la API de la
 *       PSP con sus propios records, sus formatos de referencia y sus errores.</li>
 *   <li>Un {@code XxxPaymentGateway} que implementa el puerto
 *       {@link dev.pedrogomez.payaf.application.port.out.PaymentGateway} y traduce ese idioma a
 *       {@link dev.pedrogomez.payaf.application.port.out.GatewayResponse}.</li>
 * </ol>
 * Así ningún concepto de una PSP se filtra a la aplicación: el caso de uso solo ve
 * APPROVED/DECLINED/ERROR/TIMEOUT y un código informativo. Cambiar el simulador por un cliente
 * HTTP real de Stripe solo cambiaría la pieza 1; la traducción y el puerto se quedan igual.
 * <p>
 * <strong>Rechazo determinista.</strong> Todas las PSP simuladas rechazan (DECLINED) una
 * autorización cuyo importe en unidades menores termina en 51 ({@code amountMinor % 100 == 51},
 * por ejemplo 100,51 EUR), cada una con su código nativo. Así se puede provocar un rechazo del
 * emisor a voluntad.
 * <p>
 * <strong>Operaciones que la PSP no admite</strong> (capturar dos veces, anular algo capturado,
 * reembolsar más de lo cobrado, una referencia que no existe, un importe que no cuadra) se
 * traducen a <strong>DECLINED</strong> con el código nativo, no a ERROR. La PSP ha funcionado
 * bien y ha dicho que no (en HTTP sería un 4xx): no es un fallo técnico, así que no debe contar
 * para el circuit breaker ni justificar ningún reintento. ERROR queda para lo técnico (5xx,
 * caídas, respuestas incomprensibles) y TIMEOUT para la espera agotada.
 * <p>
 * Piezas comunes: el {@link dev.pedrogomez.payaf.adapter.out.gatewaysim.SimulatedLedger} (el
 * libro de operaciones de cada PSP), el
 * {@link dev.pedrogomez.payaf.adapter.out.gatewaysim.FaultInjector} (caídas, latencia, errores y
 * timeouts), la {@link dev.pedrogomez.payaf.adapter.out.gatewaysim.GatewayChaosControl} (la
 * administración del simulador para el panel) y el
 * {@link dev.pedrogomez.payaf.adapter.out.gatewaysim.SimulatedGatewayCatalog}. Lo monta todo
 * {@link dev.pedrogomez.payaf.adapter.out.gatewaysim.GatewaySimulator}.
 */
package dev.pedrogomez.payaf.adapter.out.gatewaysim;
