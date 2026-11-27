package dev.pedrogomez.payaf.adapter.out.gatewaysim;

import dev.pedrogomez.payaf.application.port.out.GatewayCatalog;
import dev.pedrogomez.payaf.application.port.out.PaymentGateway;
import dev.pedrogomez.payaf.domain.routing.GatewayProfile;
import dev.pedrogomez.payaf.domain.shared.CountryCode;
import dev.pedrogomez.payaf.domain.shared.Currency;
import dev.pedrogomez.payaf.domain.shared.GatewayId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Catálogo de las pasarelas simuladas con sus condiciones comerciales (implementa el puerto
 * {@link GatewayCatalog}).
 * <p>
 * Perfiles, en este orden:
 * <table>
 *   <caption>Condiciones de cada pasarela simulada</caption>
 *   <tr><th>Pasarela</th><th>Divisas</th><th>Países</th><th>Comisión</th></tr>
 *   <tr><td>stripe</td><td>EUR, USD, GBP</td><td>todos</td><td>290 pb + 25</td></tr>
 *   <tr><td>adyen</td><td>EUR, USD, GBP, JPY</td><td>todos</td><td>250 pb + 30 (JPY, 0)</td></tr>
 *   <tr><td>redsys</td><td>EUR</td><td>ES</td><td>120 pb</td></tr>
 *   <tr><td>mock</td><td>todas</td><td>todos</td><td>900 pb</td></tr>
 * </table>
 * La comisión fija va en unidades menores de cada divisa (25 son 0,25 EUR). Todas empiezan
 * habilitadas y {@link #setEnabled} las saca o las vuelve a meter en el enrutado. Es seguro
 * para hilos.
 */
public final class SimulatedGatewayCatalog implements GatewayCatalog {

    public static final GatewayId STRIPE = GatewayId.of("stripe");
    public static final GatewayId ADYEN = GatewayId.of("adyen");
    public static final GatewayId REDSYS = GatewayId.of("redsys");
    public static final GatewayId MOCK = GatewayId.of("mock");

    private final List<GatewayId> order;
    private final Map<GatewayId, GatewayProfile> baseProfiles;
    private final Map<GatewayId, PaymentGateway> gateways;
    private final Map<GatewayId, Boolean> enabled = new ConcurrentHashMap<>();

    /**
     * @param gateways los adaptadores; cada uno tiene que tener uno de los perfiles de arriba
     * @throws IllegalArgumentException si un adaptador no tiene perfil o hay dos con el mismo id
     */
    public SimulatedGatewayCatalog(Collection<? extends PaymentGateway> gateways) {
        Map<GatewayId, GatewayProfile> all = defaultProfiles();
        Map<GatewayId, PaymentGateway> byId = new LinkedHashMap<>();
        for (PaymentGateway gateway : gateways) {
            if (!all.containsKey(gateway.id())) {
                throw new IllegalArgumentException("La pasarela simulada " + gateway.id() + " no tiene perfil");
            }
            if (byId.putIfAbsent(gateway.id(), gateway) != null) {
                throw new IllegalArgumentException("Pasarela repetida: " + gateway.id());
            }
        }
        Map<GatewayId, GatewayProfile> profiles = new LinkedHashMap<>();
        all.forEach((id, profile) -> {
            if (byId.containsKey(id)) {
                profiles.put(id, profile);
                enabled.put(id, profile.enabled());
            }
        });
        this.baseProfiles = Map.copyOf(profiles);
        this.gateways = Map.copyOf(byId);
        this.order = List.copyOf(profiles.keySet());
    }

    /** Los perfiles de las cuatro pasarelas simuladas, en el orden del catálogo. */
    public static Map<GatewayId, GatewayProfile> defaultProfiles() {
        Set<CountryCode> everywhere = Set.of();
        Map<GatewayId, GatewayProfile> profiles = new LinkedHashMap<>();
        profiles.put(STRIPE, new GatewayProfile(STRIPE, Set.of(Currency.EUR, Currency.USD, Currency.GBP), everywhere,
                290, Map.of(Currency.EUR, 25L, Currency.USD, 25L, Currency.GBP, 25L), true));
        profiles.put(ADYEN, new GatewayProfile(ADYEN,
                Set.of(Currency.EUR, Currency.USD, Currency.GBP, Currency.JPY), everywhere,
                250, Map.of(Currency.EUR, 30L, Currency.USD, 30L, Currency.GBP, 30L, Currency.JPY, 0L), true));
        profiles.put(REDSYS, new GatewayProfile(REDSYS, Set.of(Currency.EUR), Set.of(CountryCode.of("ES")),
                120, Map.of(), true));
        profiles.put(MOCK, new GatewayProfile(MOCK, EnumSet.allOf(Currency.class), everywhere, 900, Map.of(), true));
        return profiles;
    }

    @Override
    public List<GatewayProfile> profiles() {
        List<GatewayProfile> result = new ArrayList<>(order.size());
        for (GatewayId id : order) {
            GatewayProfile base = baseProfiles.get(id);
            result.add(new GatewayProfile(base.id(), base.currencies(), base.countries(), base.feeBasisPoints(),
                    base.fixedFeeMinor(), enabled.get(id)));
        }
        return List.copyOf(result);
    }

    @Override
    public Optional<PaymentGateway> gateway(GatewayId id) {
        return Optional.ofNullable(gateways.get(Objects.requireNonNull(id)));
    }

    /**
     * Habilita o deshabilita una pasarela para el enrutado. Una deshabilitada sigue teniendo
     * adaptador: las capturas, reembolsos y anulaciones de sus pagos siguen funcionando.
     *
     * @throws IllegalArgumentException si no está en el catálogo
     */
    public void setEnabled(GatewayId id, boolean value) {
        Objects.requireNonNull(id);
        if (!baseProfiles.containsKey(id)) {
            throw new IllegalArgumentException("No hay ninguna pasarela simulada " + id);
        }
        enabled.put(id, value);
    }
}
