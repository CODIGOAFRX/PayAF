/**
 * Puertos de salida (driven ports).
 * <p>
 * Interfaces que la aplicación necesita del exterior: guardar pagos, hablar con un
 * PSP, publicar eventos. Las implementan los adaptadores de salida (JPA, memoria,
 * simulador de pasarela, outbox), de modo que la aplicación nunca depende de ellos. Se
 * definen aquí, en el lado de quien las usa, porque es la aplicación quien sabe qué
 * necesita; el adaptador se adapta a ella y no al revés (inversión de dependencias).
 * <p>
 * <strong>El reloj no tiene puerto propio.</strong> Los servicios reciben un
 * {@link java.time.Clock} por constructor. {@code Clock} ya es una abstracción del JDK con
 * exactamente la forma de un puerto (una interfaz que se sustituye en los tests con
 * {@code Clock.fixed} o un reloj mutable), no arrastra ninguna dependencia de
 * infraestructura y la regla de ArchUnit admite {@code java..}. Envolverlo en un
 * {@code TimeProvider} propio solo añadiría una capa sin ganar nada.
 */
package dev.pedrogomez.payaf.application.port.out;
