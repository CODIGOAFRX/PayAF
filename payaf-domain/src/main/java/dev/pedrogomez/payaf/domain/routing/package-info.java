/**
 * Enrutado: qué pasarelas pueden atender un pago, en qué orden probarlas y cuáles están
 * fuera de juego porque fallan.
 * <p>
 * Es dominio porque elegir la pasarela es la propuesta de valor de PayAF: la regla «la
 * más barata que admita la divisa y el país y que no esté caída» es conocimiento del
 * negocio, no un detalle técnico. Aquí solo se decide; quien llama a las pasarelas y
 * orquesta el failover es el caso de uso de la aplicación.
 */
package dev.pedrogomez.payaf.domain.routing;
