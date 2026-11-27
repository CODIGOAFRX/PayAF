package dev.pedrogomez.payaf.config;

import dev.pedrogomez.payaf.adapter.in.cli.PayafCli;
import dev.pedrogomez.payaf.application.port.in.AuthorizePaymentUseCase;
import dev.pedrogomez.payaf.application.port.in.CapturePaymentUseCase;
import dev.pedrogomez.payaf.application.port.in.QueryGatewayHealthUseCase;
import dev.pedrogomez.payaf.application.port.in.QueryPaymentsUseCase;
import dev.pedrogomez.payaf.application.port.in.RefundPaymentUseCase;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import dev.pedrogomez.payaf.trace.TraceContext;
import java.util.Arrays;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * La CLI: el {@link PayafCli} con los mismos casos de uso que la REST, y un
 * {@link CommandLineRunner} que lo ejecuta si entre los argumentos aparece {@code cli}.
 * <p>
 * Uso: {@code java -jar payaf.jar --spring.main.web-application-type=none
 * --spring.profiles.active=postgres cli authorize --amount 12.34 ...}. Lo que va antes de {@code cli}
 * es para Spring; lo que va después, para picocli. Al terminar cierra el contexto y sale con el código
 * del comando (salvo con {@code payaf.cli.exit=false}, que usan los tests).
 */
@Configuration(proxyBeanMethods = false)
public class CliConfig {

    static final String TRIGGER = "cli";

    @Bean
    PayafCli payafCli(AuthorizePaymentUseCase authorize, CapturePaymentUseCase capture, RefundPaymentUseCase refund,
                      QueryPaymentsUseCase query, QueryGatewayHealthUseCase health, Clock clock) {
        return new PayafCli(authorize, capture, refund, query, health, clock);
    }

    @Bean
    CommandLineRunner cliRunner(PayafCli cli, ApplicationContext context,
                                @Value("${payaf.cli.exit:true}") boolean exit) {
        return args -> {
            int index = Arrays.asList(args).indexOf(TRIGGER);
            if (index < 0) {
                return;
            }
            String[] commandArgs = Arrays.copyOfRange(args, index + 1, args.length);
            int code;
            // La CLI es otro adaptador driving: su entrada también abre una traza (canal «cli»).
            try (TraceContext.Scope ignored = TraceContext.open(UUID.randomUUID().toString(), "cli")) {
                code = cli.execute(new PrintWriter(System.out, true, StandardCharsets.UTF_8),
                        new PrintWriter(System.err, true, StandardCharsets.UTF_8), commandArgs);
            }
            if (exit) {
                System.exit(SpringApplication.exit(context, () -> code));
            }
        };
    }
}
