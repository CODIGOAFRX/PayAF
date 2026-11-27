package dev.pedrogomez.payaf.adapter.in.cli;

import dev.pedrogomez.payaf.application.exception.ApplicationException;
import dev.pedrogomez.payaf.application.port.in.AuthorizationResult;
import dev.pedrogomez.payaf.application.port.in.AuthorizePaymentCommand;
import dev.pedrogomez.payaf.application.port.in.AuthorizePaymentUseCase;
import dev.pedrogomez.payaf.application.port.in.CapturePaymentUseCase;
import dev.pedrogomez.payaf.application.port.in.GatewayHealthView;
import dev.pedrogomez.payaf.application.port.in.PaymentView;
import dev.pedrogomez.payaf.application.port.in.QueryGatewayHealthUseCase;
import dev.pedrogomez.payaf.application.port.in.QueryPaymentsUseCase;
import dev.pedrogomez.payaf.application.port.in.RefundPaymentUseCase;
import dev.pedrogomez.payaf.domain.payment.GatewayAttempt;
import dev.pedrogomez.payaf.domain.payment.IdempotencyKey;
import dev.pedrogomez.payaf.domain.payment.PaymentId;
import dev.pedrogomez.payaf.domain.payment.PaymentRequest;
import dev.pedrogomez.payaf.domain.shared.CountryCode;
import dev.pedrogomez.payaf.domain.shared.Currency;
import dev.pedrogomez.payaf.domain.shared.DomainException;
import dev.pedrogomez.payaf.domain.shared.InvalidValueException;
import dev.pedrogomez.payaf.domain.shared.Money;
import java.io.PrintWriter;
import java.time.Clock;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Adaptador de entrada (driving) de línea de comandos, con picocli.
 * <p>
 * <strong>Usa exactamente los mismos puertos de entrada que la API REST</strong>
 * ({@link AuthorizePaymentUseCase}, {@link CapturePaymentUseCase}...). Es el punto didáctico del
 * hexágono: dos adaptadores driving muy distintos (HTTP con JSON, una terminal con texto) y un
 * solo núcleo, que no sabe quién lo llama. Ni una regla de negocio se repite aquí: este adaptador
 * solo traduce argumentos a objetos de valor y resultados a texto.
 * <p>
 * Comandos: {@code authorize --amount 12.34 --currency EUR --country ES --ref X --key K},
 * {@code capture <id>}, {@code refund <id> --amount 5.00}, {@code get <id>} y {@code gateways}.
 * Códigos de salida: 0 bien, 1 error de negocio o de aplicación (se imprime su {@code code}), 2 uso
 * incorrecto.
 */
public final class PayafCli {

    /** Salida cuando el núcleo rechaza la operación. */
    public static final int BUSINESS_ERROR = 1;

    private final AuthorizePaymentUseCase authorize;
    private final CapturePaymentUseCase capture;
    private final RefundPaymentUseCase refund;
    private final QueryPaymentsUseCase query;
    private final QueryGatewayHealthUseCase health;
    private final Clock clock;

    public PayafCli(AuthorizePaymentUseCase authorize, CapturePaymentUseCase capture, RefundPaymentUseCase refund,
                    QueryPaymentsUseCase query, QueryGatewayHealthUseCase health, Clock clock) {
        this.authorize = Objects.requireNonNull(authorize);
        this.capture = Objects.requireNonNull(capture);
        this.refund = Objects.requireNonNull(refund);
        this.query = Objects.requireNonNull(query);
        this.health = Objects.requireNonNull(health);
        this.clock = Objects.requireNonNull(clock);
    }

    /** Ejecuta un comando y devuelve su código de salida. */
    public int execute(PrintWriter out, PrintWriter err, String... args) {
        CommandLine cli = new CommandLine(new Root());
        cli.addSubcommand("authorize", new Authorize(out));
        cli.addSubcommand("capture", new Capture(out));
        cli.addSubcommand("refund", new Refund(out));
        cli.addSubcommand("get", new Get(out));
        cli.addSubcommand("gateways", new Gateways(out));
        cli.setOut(out);
        cli.setErr(err);
        cli.setExecutionExceptionHandler((e, commandLine, parseResult) -> {
            if (e instanceof DomainException d) {
                err.println("ERROR " + d.code() + ": " + d.getMessage());
            } else if (e instanceof ApplicationException a) {
                err.println("ERROR " + a.code() + ": " + a.getMessage());
            } else {
                throw e;
            }
            err.flush();
            return BUSINESS_ERROR;
        });
        int code = cli.execute(args);
        out.flush();
        err.flush();
        return code;
    }

    @Command(name = "payaf", mixinStandardHelpOptions = true, description = "PayAF desde la terminal")
    static final class Root implements Runnable {

        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @Override
        public void run() {
            throw new CommandLine.ParameterException(spec.commandLine(), "Falta el comando");
        }
    }

    @Command(description = "Autoriza un pago, con enrutado y failover")
    final class Authorize implements Callable<Integer> {

        private final PrintWriter out;

        @Option(names = "--amount", required = true, description = "Importe decimal, p. ej. 12.34")
        String amount;
        @Option(names = "--currency", required = true)
        String currency;
        @Option(names = "--country", required = true)
        String country;
        @Option(names = "--ref", required = true, description = "Referencia del comercio")
        String ref;
        @Option(names = "--key", required = true, description = "Clave de idempotencia")
        String key;

        Authorize(PrintWriter out) {
            this.out = out;
        }

        @Override
        public Integer call() {
            Currency c = currency(currency);
            AuthorizationResult result = authorize.authorize(new AuthorizePaymentCommand(new PaymentRequest(
                    money(amount, c), CountryCode.of(country), ref, IdempotencyKey.of(key))));
            print(out, result.payment());
            if (result.replayed()) {
                out.println("(repetición idempotente: no se ha vuelto a llamar a ninguna pasarela)");
            }
            result.decision().candidates().forEach(cand -> out.printf("  candidata %-8s comisión %s %s%n",
                    cand.gateway(), cand.estimatedFee().toDecimalString(), cand.estimatedFee().currency()));
            result.decision().rejections().forEach(r -> out.printf("  descartada %-7s %s%n", r.gateway(), r.reason()));
            return 0;
        }
    }

    @Command(description = "Captura un pago autorizado")
    final class Capture implements Callable<Integer> {

        private final PrintWriter out;

        @Parameters(index = "0", description = "Identificador del pago")
        String id;

        Capture(PrintWriter out) {
            this.out = out;
        }

        @Override
        public Integer call() {
            print(out, capture.capture(paymentId(id)));
            return 0;
        }
    }

    @Command(description = "Reembolsa parte o todo lo capturado")
    final class Refund implements Callable<Integer> {

        private final PrintWriter out;

        @Parameters(index = "0")
        String id;
        @Option(names = "--amount", required = true, description = "Importe en la divisa del pago")
        String amount;

        Refund(PrintWriter out) {
            this.out = out;
        }

        @Override
        public Integer call() {
            PaymentId paymentId = paymentId(id);
            Currency c = query.get(paymentId).amount().currency();
            print(out, refund.refund(paymentId, money(amount, c)));
            return 0;
        }
    }

    @Command(description = "Muestra un pago")
    final class Get implements Callable<Integer> {

        private final PrintWriter out;

        @Parameters(index = "0")
        String id;

        Get(PrintWriter out) {
            this.out = out;
        }

        @Override
        public Integer call() {
            print(out, query.get(paymentId(id)));
            return 0;
        }
    }

    @Command(description = "Salud de las pasarelas")
    final class Gateways implements Callable<Integer> {

        private final PrintWriter out;

        Gateways(PrintWriter out) {
            this.out = out;
        }

        @Override
        public Integer call() {
            for (GatewayHealthView view : health.health(clock.instant())) {
                out.printf("%-8s %-9s %-10s fallos=%d%n", view.profile().id(),
                        view.profile().enabled() ? "activa" : "inactiva", view.circuit().state(),
                        view.circuit().consecutiveFailures());
            }
            return 0;
        }
    }

    private static void print(PrintWriter out, PaymentView p) {
        out.printf("pago %s %s %s %s%n", p.id(), p.status(), p.amount().toDecimalString(), p.amount().currency());
        p.authorizedBy().ifPresent(g -> out.printf("  pasarela %s referencia %s%n", g,
                p.gatewayReference().orElse("-")));
        p.failureReason().ifPresent(r -> out.println("  motivo " + r));
        if (p.refunded().isPositive()) {
            out.printf("  reembolsado %s%n", p.refunded().toDecimalString());
        }
        for (GatewayAttempt a : p.attempts()) {
            out.printf("  intento %-8s %-8s %d ms%s%n", a.gateway(), a.outcome(), a.latency().toMillis(),
                    a.code().map(c -> " " + c).orElse(""));
        }
    }

    private static Currency currency(String code) {
        try {
            return Currency.valueOf(code.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new InvalidValueException("Divisa no admitida: «" + code + "»");
        }
    }

    private static Money money(String amount, Currency currency) {
        try {
            return Money.parse(amount, currency);
        } catch (ArithmeticException e) {
            throw new InvalidValueException("El importe «" + amount + "» es demasiado grande");
        }
    }

    private static PaymentId paymentId(String id) {
        try {
            return PaymentId.of(id);
        } catch (IllegalArgumentException e) {
            throw new InvalidValueException("Identificador de pago no válido: «" + id + "»");
        }
    }

}
