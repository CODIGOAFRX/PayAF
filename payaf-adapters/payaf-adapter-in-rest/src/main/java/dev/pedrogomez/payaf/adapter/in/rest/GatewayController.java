package dev.pedrogomez.payaf.adapter.in.rest;

import dev.pedrogomez.payaf.adapter.in.rest.ApiDtos.ChaosDto;
import dev.pedrogomez.payaf.adapter.in.rest.ApiDtos.ChaosRequest;
import dev.pedrogomez.payaf.adapter.in.rest.ApiDtos.EnabledRequest;
import dev.pedrogomez.payaf.adapter.in.rest.ApiDtos.GatewayDto;
import dev.pedrogomez.payaf.adapter.in.rest.ChaosAdmin.ChaosSettings;
import dev.pedrogomez.payaf.application.port.in.QueryGatewayHealthUseCase;
import dev.pedrogomez.payaf.domain.shared.InvalidValueException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Salud de las pasarelas (puerto de la aplicación) y administración del simulador para el panel
 * ({@link ChaosAdmin}, que no es un puerto de la aplicación).
 */
@RestController
public class GatewayController {

    private final QueryGatewayHealthUseCase health;
    private final ChaosAdmin chaos;
    private final Clock clock;

    public GatewayController(QueryGatewayHealthUseCase health, ChaosAdmin chaos, Clock clock) {
        this.health = health;
        this.chaos = chaos;
        this.clock = clock;
    }

    /** Perfil y estado efectivo del circuito de cada pasarela, en el orden del catálogo. */
    @GetMapping("/api/gateways")
    public List<GatewayDto> gateways() {
        return RestMapper.gateways(health.health(clock.instant()));
    }

    @PutMapping("/api/gateways/{id}/enabled")
    public Map<String, Object> setEnabled(@PathVariable String id, @RequestBody EnabledRequest body) {
        if (body == null || body.enabled() == null) {
            throw new InvalidBodyException("El cuerpo debe traer enabled (true o false)");
        }
        chaos.setEnabled(id, body.enabled());
        return Map.of("gateway", id, "enabled", body.enabled());
    }

    @GetMapping("/api/chaos/{gateway}")
    public ChaosDto getChaos(@PathVariable String gateway) {
        return dto(gateway, chaos.get(gateway));
    }

    @PutMapping("/api/chaos/{gateway}")
    public ChaosDto setChaos(@PathVariable String gateway, @RequestBody ChaosRequest body) {
        if (body == null) {
            throw new InvalidBodyException("El cuerpo debe traer down, latencyMs y/o errorRate");
        }
        ChaosSettings settings = new ChaosSettings(Boolean.TRUE.equals(body.down()),
                body.latencyMs() == null ? 0 : body.latencyMs(), body.errorRate() == null ? 0.0 : body.errorRate());
        try {
            return dto(gateway, chaos.set(gateway, settings));
        } catch (IllegalArgumentException e) {
            throw new InvalidValueException(e.getMessage());
        }
    }

    @DeleteMapping("/api/chaos/{gateway}")
    public ChaosDto resetChaos(@PathVariable String gateway) {
        return dto(gateway, chaos.reset(gateway));
    }

    private static ChaosDto dto(String gateway, ChaosSettings s) {
        return new ChaosDto(gateway, s.down(), s.latencyMs(), s.errorRate());
    }
}
