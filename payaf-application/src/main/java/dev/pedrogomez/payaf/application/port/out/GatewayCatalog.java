package dev.pedrogomez.payaf.application.port.out;

import dev.pedrogomez.payaf.domain.routing.GatewayProfile;
import dev.pedrogomez.payaf.domain.shared.GatewayId;
import java.util.List;
import java.util.Optional;

/**
 * Puerto de salida (driven): qué pasarelas hay configuradas y cómo hablar con cada una.
 * <p>
 * Separa dos cosas que vienen juntas en la configuración: el {@link GatewayProfile}
 * (condiciones comerciales, que son dominio y alimentan la política de enrutado) y el
 * {@link PaymentGateway} (el adaptador que habla con la PSP). La aplicación no sabe de
 * dónde salen (un YAML, una tabla): eso lo decide el adaptador que lo implemente.
 */
public interface GatewayCatalog {

    /** Perfiles de todas las pasarelas configuradas, habilitadas o no, sin repetidos. */
    List<GatewayProfile> profiles();

    /** El adaptador de la pasarela, o vacío si no hay ninguno registrado con ese identificador. */
    Optional<PaymentGateway> gateway(GatewayId id);
}
