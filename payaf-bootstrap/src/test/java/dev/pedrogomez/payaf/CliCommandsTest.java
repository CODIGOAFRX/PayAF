package dev.pedrogomez.payaf;

import static org.assertj.core.api.Assertions.assertThat;

import dev.pedrogomez.payaf.adapter.in.cli.PayafCli;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * La CLI con el contexto en memoria: los mismos casos de uso que la REST, llamados desde picocli.
 */
@SpringBootTest(properties = {"payaf.reaper.enabled=false", "payaf.cli.exit=false"})
@ActiveProfiles("memory")
class CliCommandsTest {

    @Autowired
    private PayafCli cli;

    private record Run(int code, String out, String err) {
    }

    private Run run(String... args) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int code = cli.execute(new PrintWriter(out), new PrintWriter(err), args);
        return new Run(code, out.toString(), err.toString());
    }

    @Test
    @DisplayName("authorize, get, capture, refund y gateways")
    void commands() {
        Run authorized = run("authorize", "--amount", "12.34", "--currency", "EUR", "--country", "ES", "--ref", "X",
                "--key", "cli-1");
        assertThat(authorized.code()).isZero();
        assertThat(authorized.out()).contains("AUTHORIZED").contains("candidata");
        Matcher m = Pattern.compile("pago ([0-9a-f-]{36})").matcher(authorized.out());
        assertThat(m.find()).isTrue();
        String id = m.group(1);

        Run replay = run("authorize", "--amount", "12.34", "--currency", "EUR", "--country", "ES", "--ref", "X",
                "--key", "cli-1");
        assertThat(replay.out()).contains(id).contains("repetición idempotente");

        assertThat(run("get", id).out()).contains("AUTHORIZED");
        assertThat(run("capture", id).out()).contains("CAPTURED");
        Run refunded = run("refund", id, "--amount", "2.00");
        assertThat(refunded.code()).isZero();
        assertThat(refunded.out()).contains("PARTIALLY_REFUNDED").contains("reembolsado 2.00");
        assertThat(run("gateways").out()).contains("stripe").contains("redsys").contains("CLOSED");
    }

    @Test
    @DisplayName("los errores del núcleo salen con su código y exit 1; el mal uso, con exit 2")
    void errors() {
        Run tooMuch = run("refund", "00000000-0000-0000-0000-000000000000", "--amount", "1.00");
        assertThat(tooMuch.code()).isEqualTo(PayafCli.BUSINESS_ERROR);
        assertThat(tooMuch.err()).contains("PAYMENT_NOT_FOUND");
        Run invalid = run("authorize", "--amount", "1.234", "--currency", "EUR", "--country", "ES", "--ref", "X",
                "--key", "k");
        assertThat(invalid.err()).contains("INVALID_VALUE");
        assertThat(run("authorize", "--amount", "1").code()).isEqualTo(2);
    }
}
