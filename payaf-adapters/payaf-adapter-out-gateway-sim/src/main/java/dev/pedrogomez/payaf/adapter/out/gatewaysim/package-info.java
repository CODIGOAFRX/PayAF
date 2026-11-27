/**
 * Adaptador de salida (driven) que simula pasarelas de pago (PSP).
 * <p>
 * Implementa el puerto de salida hacia los PSP con proveedores falsos y
 * configurables (latencia, tasa de error, caídas) para poder probar el enrutado y
 * el failover sin tocar pasarelas reales.
 */
package dev.pedrogomez.payaf.adapter.out.gatewaysim;
