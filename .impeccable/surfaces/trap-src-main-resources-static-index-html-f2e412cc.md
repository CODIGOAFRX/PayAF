---
version: 1
slug: "trap-src-main-resources-static-index-html-f2e412cc"
primary_target: "payaf-bootstrap/src/main/resources/static/index.html"
related_targets: []
---

# Panel en vivo de PayAF

Modo: Operate. Pantalla única de app; camino code-first.
Público: recruiters y entrevistadores técnicos, 1-3 minutos, sin tutorial. Tarea: tumbar una pasarela, lanzar un pago y ver el failover y qué adaptadores tocó.
Restricciones: rutas relativas (vive bajo /proyecto/PayAF/), sin CDN, WCAG 2.2 AA, prefers-reduced-motion quieto, nada violeta.

Lista fundamentada (por resonancia): 1 enclavamiento ferroviario, 2 plano de metro, 3 centralita telefónica, 4 plano técnico ISO, 5 impresión de seguridad de billetes, 6 documentación DIN/estilo suizo, 7 panel de salidas split-flap en un vestíbulo de estación (asignado por la tirada).
Veredictos: metro-tiles declinado (se queda: un único acento posee todo estado vivo); labanotation declinado (se queda: simetría bilateral izquierda/derecha del eje del dominio y longitud = duración exacta); j-card declinado (se queda: duraciones alineadas a la derecha en cifras tabulares); doujin declinado (se queda: salto entre una fila del registro y su posición en el hexágono); folio botánico declinado (se queda: misma anatomía y escala en todas las tarjetas de pasarela); ikeda declinado (se queda: el estado OPEN invierte el módulo en vez de solo recolorearlo).

## Direction contract

THESIS: El panel es el tablero de salidas de una estación donde el dominio es el andén central y cada pago «sale» por una pasarela; rechaza el dashboard oscuro de observabilidad con acento neón y la rejilla de tarjetas SaaS blanca.
OWN-WORLD: Vestíbulo claro (gris piedra frío, paneles blancos, tinta casi negra) con módulos de tablero de lamas negras y caracteres marfil; amarillo de tablero como único color de lo vivo (traza, foco, en curso). Verde, ámbar y rojo solo para el breaker. Barlow para la interfaz, Barlow Condensed en mayúsculas en las celdas del tablero, monoespaciada del sistema solo para códigos de PSP.
STORY: El visitante pulsa «100 € en España», ve el pago salir por Redsys y el hexágono iluminarse en orden; tumba Redsys, repite y ve el failover a Adyen; a la tercera, el breaker cae a OPEN con cuenta atrás; restaura la demo.
FIRST VIEWPORT: Barra superior (PayAF, perfil, estado SSE, Restaurar demo). Tres columnas: izquierda, presets de un clic y formulario; centro, el hexágono vivo a gran escala con la traza debajo; derecha, resultado con el estado final en celdas de tablero. Arranque de la fila de pasarelas visible al pie.
FORM: panel de salidas split-flap, posición 7 de 7. seed:d24e37d9f2bcd5c1. Movimiento firma: los textos de estado (breaker y estado final) cambian con el giro de lamas, carácter a carácter.
FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance
