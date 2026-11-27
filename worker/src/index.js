// Worker de PayAF: recibe las peticiones de pedrogomez.dev/proyecto/PayAF* y las pasa
// al contenedor con la app Spring Boot. Es una sola instancia con nombre fijo, porque el
// estado de la demo (el caos y los circuit breakers) tiene que ser el mismo para todos
// los visitantes.
import { Container, getContainer } from "@cloudflare/containers";

export class PayafContainer extends Container {
  defaultPort = 8080;
  // La JVM tarda en arrancar: mejor que el contenedor siga despierto un rato tras la última visita.
  sleepAfter = "20m";
  // Hace falta salida a internet para llegar a Neon.
  enableInternet = true;

  constructor(ctx, env) {
    super(ctx, env);
    this.envVars = {
      SPRING_PROFILES_ACTIVE: "postgres",
      SERVER_SERVLET_CONTEXT_PATH: "/proyecto/PayAF",
      // La conexión directa de Neon (DATABASE_URL_UNPOOLED), guardada como secreto del Worker.
      PAYAF_DATABASE_URL: env.PAYAF_DATABASE_URL ?? "",
      JAVA_TOOL_OPTIONS: "-XX:MaxRAMPercentage=70 -XX:+UseSerialGC -XX:TieredStopAtLevel=1",
    };
  }

  onStart() {
    console.log("payaf: contenedor arrancado");
  }

  onStop(params) {
    console.log("payaf: contenedor parado", JSON.stringify(params));
  }

  onError(error) {
    console.error("payaf: error del contenedor", error?.message ?? String(error));
    throw error;
  }
}

const PREFIX = "/proyecto/PayAF";

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    // workers.dev o cualquier ruta fuera del prefijo: se manda a la app.
    if (!url.pathname.startsWith(PREFIX)) {
      return Response.redirect(`${url.origin}${PREFIX}/`, 302);
    }
    return getContainer(env.PAYAF, "payaf").fetch(request);
  },
};
