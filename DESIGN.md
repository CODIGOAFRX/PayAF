---
name: PayAF · panel en vivo
description: Demo en vivo de un enrutador de pagos multi-PSP con arquitectura hexagonal, con el aire del vestíbulo de una estación y su tablero de salidas de lamas.
colors:
  concourse-ground: "#e8ebed"
  panel: "#ffffff"
  panel-tint: "#f4f6f7"
  rule: "#d2d8dc"
  rule-strong: "#aeb7be"
  ink: "#14181c"
  ink-secondary: "#434c55"
  ink-tertiary: "#5d6872"
  board: "#16181b"
  slat: "#23262b"
  seam: "#050607"
  board-ink: "#f2eee3"
  board-dim: "#a3a9ae"
  live: "#f5c400"
  live-soft: "#fff3bf"
  live-deep: "#7a5f00"
  ok: "#1d7444"
  ok-ink: "#0f4a2a"
  ok-soft: "#e3f2e8"
  ok-lamp: "#43d17f"
  warn: "#8f5600"
  warn-ink: "#5a3600"
  warn-soft: "#fbefd9"
  warn-lamp: "#ffb21f"
  bad: "#b0241c"
  bad-ink: "#6e130d"
  bad-pressed: "#951d16"
  bad-slat: "#8f1c15"
  bad-soft: "#fbe5e3"
  bad-lamp: "#ff6a5c"
typography:
  wordmark:
    fontFamily: "Barlow Condensed, Barlow, ui-sans-serif, system-ui, sans-serif"
    fontSize: "26px"
    fontWeight: 600
    lineHeight: 1
    letterSpacing: "0.02em"
  panel-title:
    fontFamily: "Barlow Condensed, Barlow, ui-sans-serif, system-ui, sans-serif"
    fontSize: "19px"
    fontWeight: 600
    lineHeight: 1.2
    letterSpacing: "0.01em"
  flap:
    fontFamily: "Barlow Condensed, Barlow, ui-sans-serif, system-ui, sans-serif"
    fontSize: "17px"
    fontWeight: 600
    lineHeight: 1.25
    letterSpacing: "0em"
  board-label:
    fontFamily: "Barlow Condensed, Barlow, ui-sans-serif, system-ui, sans-serif"
    fontSize: "12px"
    fontWeight: 600
    lineHeight: 1.4
    letterSpacing: "0.12em"
  body:
    fontFamily: "Barlow, ui-sans-serif, system-ui, Segoe UI, Roboto, sans-serif"
    fontSize: "15px"
    fontWeight: 400
    lineHeight: 1.45
  control:
    fontFamily: "Barlow, ui-sans-serif, system-ui, Segoe UI, Roboto, sans-serif"
    fontSize: "14px"
    fontWeight: 500
    lineHeight: 1.2
  label:
    fontFamily: "Barlow, ui-sans-serif, system-ui, Segoe UI, Roboto, sans-serif"
    fontSize: "13px"
    fontWeight: 500
    lineHeight: 1.3
  code:
    fontFamily: "ui-monospace, Cascadia Mono, SF Mono, Consolas, Liberation Mono, monospace"
    fontSize: "12px"
    fontWeight: 400
    lineHeight: 1.4
rounded:
  hairline: "1px"
  cell: "2px"
  tag: "3px"
  small: "4px"
  base: "6px"
  pill: "12px"
spacing:
  xs: "4px"
  sm: "8px"
  md: "12px"
  lg: "16px"
  xl: "20px"
components:
  button:
    backgroundColor: "{colors.panel}"
    textColor: "{colors.ink}"
    typography: "{typography.control}"
    rounded: "{rounded.base}"
    padding: "7px 14px"
    height: "36px"
  button-hover:
    backgroundColor: "{colors.panel-tint}"
  button-primary:
    backgroundColor: "{colors.ink}"
    textColor: "{colors.panel}"
    rounded: "{rounded.base}"
    height: "36px"
  button-preset:
    backgroundColor: "{colors.panel-tint}"
    textColor: "{colors.ink}"
    rounded: "{rounded.base}"
    padding: "9px 11px"
    height: "62px"
  button-preset-hover:
    backgroundColor: "{colors.live-soft}"
  button-danger-pressed:
    backgroundColor: "{colors.bad}"
    textColor: "{colors.panel}"
  board-module:
    backgroundColor: "{colors.board}"
    textColor: "{colors.board-ink}"
    typography: "{typography.flap}"
    rounded: "{rounded.small}"
    padding: "5px 7px"
  board-module-open:
    backgroundColor: "{colors.bad}"
    textColor: "{colors.board-ink}"
  panel:
    backgroundColor: "{colors.panel}"
    textColor: "{colors.ink}"
    rounded: "{rounded.base}"
    padding: "16px"
  input:
    backgroundColor: "{colors.panel}"
    textColor: "{colors.ink}"
    typography: "{typography.body}"
    rounded: "{rounded.small}"
    height: "36px"
---

# Design System: PayAF · panel en vivo

## Overview

**Creative North Star: "El tablero de salidas del vestíbulo"**

El panel es el vestíbulo claro de una estación: suelo de piedra fría, paneles blancos donde se trabaja y, colgados encima, módulos negros de tablero de lamas que anuncian los estados. El dominio es el andén central del hexágono y cada pago «sale» por una pasarela. Es una superficie de modo Operate: la estructura es la de una herramienta (barra superior, tres áreas de trabajo, tarjetas de pasarela, registro) y el mundo entra solo por la tipografía, la paleta, la densidad y un movimiento firma.

Rechaza el dashboard oscuro de observabilidad con un acento neón y la rejilla de tarjetas SaaS blanca sin carácter. Cada color y cada movimiento cuenta algo que ha pasado en el backend: si no hay traza, nada se ilumina.

**Key Characteristics:**
- Vestíbulo claro con módulos de tablero negros para los estados.
- Un único amarillo de tablero para todo lo vivo.
- Verde, ámbar y rojo reservados al circuit breaker y al resultado, siempre con texto.
- Celdas de lamas que giran carácter a carácter cuando cambia un estado.
- El hexágono en SVG como pieza central, con simetría izquierda (entrada) y derecha (salida).

## Colors

Estrategia restringida con roles de estado ricos: neutros fríos del vestíbulo, el negro del tablero y un único acento vivo.

### Primary
- **Amarillo de tablero** (`live`): el color de lo que está pasando ahora. Platos y cables del hexágono durante la traza, fila activa de la traza, tarjeta de la pasarela tocada, halo de foco y selección de texto. Nunca decora. Su versión suave (`live-soft`) marca el paso activo y el hover de los presets; `live-deep` es el borde del aviso de repetición idempotente.

### Neutral
- **Suelo del vestíbulo** (`concourse-ground`), **panel** y **panel-tint**: el fondo, las áreas de trabajo y los rellenos secundarios (presets, campos desactivados).
- **Tinta** (`ink`, `ink-secondary`, `ink-tertiary`): texto principal, etiquetas y ayudas. La terciaria pasa AA sobre blanco.
- **Tablero** (`board`, `slat`, `seam`, `board-ink`, `board-dim`): barra superior, módulos de estado y el registro de eventos. Las lamas llevan una costura horizontal a media altura.

### Estado
- **ok / warn / bad** con sus versiones `-soft` (fondos de aviso), `-ink` (texto sobre esos fondos) y `-lamp` (lámparas sobre el tablero); `bad-pressed` es el hover del botón «Tumbar» pulsado y `bad-slat` las lamas del módulo OPEN. Solo para el breaker (CLOSED, HALF_OPEN, OPEN), el resultado de los intentos y los avisos.

### Named Rules
**The One Live Color Rule.** El amarillo solo marca lo que ocurre ahora (traza, foco, en curso). Si un elemento amarillo no responde a algo del backend o a la interacción, sobra.

**The Inversion Rule.** OPEN no se recolorea: el módulo entero se invierte a rojo con texto marfil y una lámpara blanca, y además dice «OPEN» y la cuenta atrás. El estado nunca depende solo del color.

## Typography

**Display/estado:** Barlow Condensed 500/600, en local (woff2).
**Cuerpo y controles:** Barlow 400/500/600, en local.
**Datos:** la monoespaciada del sistema, solo para códigos nativos de PSP, referencias, claves y nombres de puerto.

Escala fija en px (relación cercana a 1,2), sin tipos fluidos. Cifras tabulares en todo el documento.

### Hierarchy
- **Wordmark** (Barlow Condensed 600, 26px): «PayAF» en la barra.
- **Título de panel** (Barlow Condensed 600, 19px): Lanzar un pago, El hexágono, Resultado, Pasarelas, Registro.
- **Lamas** (Barlow Condensed 600, 17px, mayúsculas): estados en módulos de tablero.
- **Etiqueta de tablero** (Barlow Condensed 600, 11-12px, tracking 0.1-0.12em): cabeceras de columna del registro y títulos dentro del SVG.
- **Cuerpo** (Barlow 400, 15px; 14px en móvil), **control** (Barlow 500, 14px: botones, campos, presets) y **etiqueta** (Barlow 500, 13px).
- **Texto del hexágono**: como mínimo 13px en el sistema del SVG, para no bajar de unos 12px reales a 1440px; los puertos driving se rotulan sin el sufijo `UseCase` (el nombre completo va en el tooltip y en la traza).
- **Código** (mono 12px): `HTTP_503`, `pi_…`, `PaymentGateway.authorize`.

### Named Rules
**The Code Is Data Rule.** La monoespaciada solo para datos que se copian o se buscan (códigos, referencias, puertos). Nunca como disfraz «técnico» de un texto normal.

## Layout

Rejilla de tres columnas a partir de 1280px (`280-300px | 1fr | 320-350px`): lanzar, hexágono, resultado. Debajo, las cuatro tarjetas de pasarela en una fila y el registro de eventos a todo el ancho. Entre 760 y 1279px, el hexágono ocupa la fila de arriba y lanzar y resultado van debajo, en dos columnas. Por debajo de 760px todo va en una columna, y el hexágono se redibuja en vertical: entrada arriba, dominio en el centro y salida abajo. Al lanzar un pago en móvil la página lleva el hexágono a la vista.

Espaciado: huecos de 16px entre áreas (12px en móvil), 16px de relleno en paneles y 4-12px dentro de los grupos.

### Named Rules
**The Same Plate Rule.** Las cuatro tarjetas de pasarela tienen exactamente la misma anatomía y escala (cabecera con módulo de estado, texto del breaker de dos líneas reservadas, datos y controles), para que los estados se comparen de un vistazo.

## Elevation & Depth

Casi plano. Los paneles llevan un borde de 1px y una sombra suave y corta (`0 1px 2px` + `0 2px 8px`, opacidad 5-6%). El aviso flotante (toast) es lo único que se separa más (`0 6px 24px`). La profundidad del tablero la dan las lamas y su costura, no las sombras.

## Shapes

Esquinas de 6px en paneles, tarjetas y botones; 4px en campos, lamas y módulos; el interruptor es una píldora. El hexágono y el dominio son hexágonos regulares (con punta arriba en escritorio, de lado plano en móvil).

## Components

### Buttons
- **Botón** (blanco, borde gris, 36px): cambia de fondo en hover y se hunde al pulsar (`translateY(1px) scale(0.985)` con sombra interior).
- **Primario** (tinta sólida, ancho completo): «Lanzar pago».
- **Preset** (relleno tint, dos líneas): título del pago y detalle; hover en amarillo suave. Un clic lanza el pago.
- **Tumbar (503)**: botón conmutador con `aria-pressed`; pulsado, rojo sólido.
- **Quiet** (sobre la barra negra): «Restaurar demo».
- En curso: barrido amarillo sobre el botón que lanzó la operación; con movimiento reducido, contorno amarillo fijo.

### Inputs / Fields
Campos de 36px, borde gris fuerte, 4px de radio; inválidos con borde y fondo rojos suaves y mensaje que dice cómo arreglarlo. Deslizadores nativos con `accent-color` de tinta y su valor a la derecha. Interruptor «Habilitada» con `role="switch"`.

### Cards / Containers
Paneles blancos con borde y sombra suave. Estados vacíos con borde discontinuo que explican qué hacer. Carga con esqueletos que respetan la forma de la tarjeta.

### Navigation
Barra superior negra: wordmark, lema, persistencia activa, estado del SSE (punto + texto: En vivo, Reconectando, Sin conexión) y «Restaurar demo». Pie con GitHub y pedrogomez.dev.

### Módulo de tablero (componente firma)
Lámpara + celdas de lamas + contador opcional sobre fondo de tablero. Cuando el texto cambia, cada celda gira sobre su eje horizontal (70ms abajo, 110ms arriba, 24ms de escalonado). Lleva `aria-label` con el estado completo; las celdas son decorativas para el lector de pantalla.

### Hexágono vivo (componente firma)
SVG generado en JS. Platos de adaptador con icono (logos reales de Stripe, Adyen y Redsys; Mock como texto), puertos como puntos en el borde con su nombre de interfaz, cables curvos. La traza `port.touched` se reproduce en orden: plato, puerto y cable en amarillo con un discontinuo que fluye, ~260ms por paso; lo tocado queda en tinta con su número de orden; un fallo técnico marca el plato en rojo. Con movimiento reducido se pinta el estado final de golpe.

### Registro de eventos
Un módulo de tablero ancho con columnas Hora / Evento / Detalle, el más reciente arriba, 200 líneas como máximo, inserciones por lotes en un `requestAnimationFrame` y pausa con contador de pendientes.

## Do's and Don'ts

### Do:
- **Do** usar el amarillo solo para lo vivo: traza, paso activo, foco, en curso.
- **Do** acompañar cada color de estado con su texto (CLOSED, OPEN 12 s, HALF_OPEN; Aprobado, Error).
- **Do** sacar todo lo que se ve de la API real; las pasarelas se anuncian como simuladas en el pie.
- **Do** mantener idéntica la anatomía de las tarjetas de pasarela.
- **Do** alinear duraciones y latencias a la derecha en cifras tabulares.

### Don't:
- **Don't** usar violeta ni lavanda en la paleta propia (el logo de Stripe conserva sus colores, pequeño).
- **Don't** dibujar logos: si una PSP no tiene icono real, su nombre en texto.
- **Don't** animar nada que no cuente un cambio del backend, ni hacer aparecer contenido con fundidos al hacer scroll.
- **Don't** poner etiquetas tipo «kicker» encima de los títulos ni modales de bienvenida.
- **Don't** usar rutas absolutas en el front: todo es relativo (`api/...`, `assets/...`).
