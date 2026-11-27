package dev.pedrogomez.payaf.domain.shared;

/**
 * Raíz de todas las excepciones de dominio.
 * <p>
 * Es dominio porque expresa la violación de una regla de negocio, no un fallo técnico.
 * Cada subclase expone un {@link #code()} estable (por ejemplo {@code "ILLEGAL_TRANSITION"})
 * que forma parte del contrato: el adaptador REST lo traducirá a un código HTTP y a un
 * cuerpo de error sin tener que inspeccionar mensajes ni clases concretas. El mensaje,
 * en cambio, es para personas y puede cambiar.
 * <p>
 * Extiende {@link RuntimeException} para no ensuciar las firmas del dominio con
 * {@code throws}: quien quiera tratarlas las captura en el borde del hexágono.
 */
public abstract class DomainException extends RuntimeException {

    private final String code;

    protected DomainException(String code, String message) {
        super(message);
        this.code = code;
    }

    /** Código estable y legible por máquinas que identifica la regla violada. */
    public String code() {
        return code;
    }
}
