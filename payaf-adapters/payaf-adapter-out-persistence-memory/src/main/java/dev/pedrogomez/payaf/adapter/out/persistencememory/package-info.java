/**
 * Adaptador de salida (driven) de persistencia en memoria.
 * <p>
 * Implementa los mismos puertos de repositorio que el adaptador JPA, pero con
 * estructuras en memoria. Sirve para arrancar la aplicación sin base de datos y para
 * tests rápidos. Se activa con el perfil {@code memory} (el de por defecto).
 */
package dev.pedrogomez.payaf.adapter.out.persistencememory;
