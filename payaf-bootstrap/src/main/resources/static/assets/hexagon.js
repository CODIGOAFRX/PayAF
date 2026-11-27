// El hexágono vivo: el dominio en el centro, los puertos en el borde de la aplicación, los
// adaptadores driving a la izquierda (arriba en móvil) y los driven a la derecha (abajo en móvil).
// Cada traza port.touched del backend se reproduce en orden, paso a paso.

const SVG_NS = 'http://www.w3.org/2000/svg';
const STEP_MS = 260;
const STEP_FAST_MS = 110;

const GLYPHS = {
  globe: '<circle cx="12" cy="12" r="10"/><path d="M12 2a14.5 14.5 0 0 0 0 20a14.5 14.5 0 0 0 0-20M2 12h20"/>',
  webhook: '<path d="M18 16.98h-5.99c-1.1 0-1.95.94-2.48 1.9A4 4 0 0 1 2 17c.01-.7.2-1.4.57-2"/><path d="m6 17l3.13-5.78c.53-.97.1-2.18-.5-3.1a4 4 0 1 1 6.89-4.06"/><path d="m12 6l3.13 5.73C15.66 12.7 16.9 13 18 13a4 4 0 0 1 0 8"/>',
  terminal: '<path d="M12 19h8M4 17l6-6l-6-6"/>',
  memory: '<path d="M12 12v-2m0 8v-2m4-4v-2m0 8v-2M2 11h1.5M20 18v-2m.5-5H22M4 18v-2m4-4v-2m0 8v-2"/><rect width="20" height="10" x="2" y="6" rx="2"/>',
  database: '<ellipse cx="12" cy="5" rx="9" ry="3"/><path d="M3 5v14a9 3 0 0 0 18 0V5"/><path d="M3 12a9 3 0 0 0 18 0"/>',
  send: '<path d="M14.536 21.686a.5.5 0 0 0 .937-.024l6.5-19a.496.496 0 0 0-.635-.635l-19 6.5a.5.5 0 0 0-.024.937l7.93 3.18a2 2 0 0 1 1.112 1.11zm7.318-19.539l-10.94 10.939"/>',
};

const PORTS = {
  authorize: 'AuthorizePayment',
  ops: 'Capture · Cancel · Refund',
  notify: 'HandleGatewayNotification',
  gateway: 'PaymentGateway',
  processed: 'ProcessedNotificationStore',
  idempotency: 'IdempotencyStore',
  repository: 'PaymentRepository',
  breakers: 'CircuitBreakerStore',
  transactions: 'TransactionRunner',
  events: 'EventPublisher',
};

/** Nombre completo de la interfaz de cada puerto (tooltip; la traza lo muestra entero). */
const PORT_FULL = {
  authorize: 'AuthorizePaymentUseCase',
  ops: 'CapturePaymentUseCase · CancelPaymentUseCase · RefundPaymentUseCase',
  notify: 'HandleGatewayNotificationUseCase',
};

const PORT_BY_NAME = {
  AuthorizePaymentUseCase: 'authorize',
  CapturePaymentUseCase: 'ops',
  CancelPaymentUseCase: 'ops',
  RefundPaymentUseCase: 'ops',
  HandleGatewayNotificationUseCase: 'notify',
  PaymentGateway: 'gateway',
  ProcessedNotificationStore: 'processed',
  IdempotencyStore: 'idempotency',
  PaymentRepository: 'repository',
  CircuitBreakerStore: 'breakers',
  TransactionRunner: 'transactions',
  EventPublisher: 'events',
};

const PSPS = ['stripe', 'adyen', 'redsys', 'mock'];

const WIRES = [
  ['authorize', 'rest'], ['ops', 'rest'], ['authorize', 'cli'], ['ops', 'cli'], ['notify', 'webhook'],
  ...PSPS.map((g) => ['gateway', g]),
  ...['processed', 'idempotency', 'repository', 'breakers', 'transactions'].map((p) => [p, 'persistence']),
  ['events', 'outbox'],
];

function hexPoints(cx, cy, r, pointy) {
  const pts = [];
  for (let i = 0; i < 6; i++) {
    const a = ((pointy ? -90 : 0) + i * 60) * (Math.PI / 180);
    pts.push([cx + r * Math.cos(a), cy + r * Math.sin(a)]);
  }
  return pts;
}

const lerp = (a, b, t) => [a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t];

function wideLayout() {
  // Compacto en horizontal para que, a la anchura de la columna central, los textos se lean a
  // tamaño real (escala cercana a 1).
  const cx = 370;
  const cy = 247;
  const R = 190;
  const o = hexPoints(cx, cy, R, true); // top, ur, lr, bottom, ll, ul
  const right = o[1][0];
  const left = o[5][0];
  const ports = {
    authorize: { at: [left, 190], label: 'start' },
    ops: { at: [left, 318], label: 'start' },
    notify: { at: lerp(o[4], o[3], 0.5), label: 'start' },
    gateway: { at: lerp(o[0], o[1], 0.6), label: 'end' },
    processed: { at: [right, 170], label: 'end' },
    idempotency: { at: [right, 205], label: 'end' },
    repository: { at: [right, 240], label: 'end' },
    breakers: { at: [right, 300], label: 'end' },
    transactions: { at: [right, 332], label: 'end' },
    events: { at: lerp(o[2], o[3], 0.3), label: 'end' },
  };
  const L = { x: 2, w: 136 };
  const Rc = { x: 602, w: 136 };
  const nodes = {
    rest: { x: L.x, y: 132, w: L.w, h: 58, side: 'left' },
    cli: { x: L.x, y: 226, w: L.w, h: 58, side: 'left' },
    webhook: { x: L.x, y: 366, w: L.w, h: 58, side: 'left' },
    stripe: { x: Rc.x, y: 24, w: Rc.w, h: 46, side: 'right', compact: true },
    adyen: { x: Rc.x, y: 74, w: Rc.w, h: 46, side: 'right', compact: true },
    redsys: { x: Rc.x, y: 124, w: Rc.w, h: 46, side: 'right', compact: true },
    mock: { x: Rc.x, y: 174, w: Rc.w, h: 46, side: 'right', compact: true },
    persistence: { x: Rc.x, y: 236, w: Rc.w, h: 62, side: 'right' },
    outbox: { x: Rc.x, y: 366, w: Rc.w, h: 58, side: 'right' },
  };
  return {
    kind: 'wide',
    viewBox: '0 0 740 460',
    outer: o,
    inner: hexPoints(cx, cy, 64, true),
    center: [cx, cy],
    ringTitle: [cx, 148],
    portLabels: true,
    titles: [
      { x: L.x, y: 120, text: 'ENTRADA · DRIVING' },
      { x: Rc.x, y: 14, text: 'SALIDA · DRIVEN' },
    ],
    ports,
    nodes,
    anchor: (node) => (node.side === 'left' ? [node.x + node.w, node.y + node.h / 2] : [node.x, node.y + node.h / 2]),
    curve: (a, b) => {
      const k = (b[0] - a[0]) * 0.5;
      return `M${a[0]},${a[1]} C${a[0] + k},${a[1]} ${b[0] - k},${b[1]} ${b[0]},${b[1]}`;
    },
  };
}

function tallLayout() {
  const cx = 180;
  const cy = 278;
  const R = 150;
  const o = hexPoints(cx, cy, R, false); // r, lr, ll, l, ul, ur
  const top = o[4][1];
  const ports = {
    authorize: { at: [128, top] },
    ops: { at: [180, top] },
    notify: { at: [238, top] },
    gateway: { at: [cx, o[1][1]] },
    processed: { at: lerp(o[3], o[2], 0.14) },
    idempotency: { at: lerp(o[3], o[2], 0.32) },
    repository: { at: lerp(o[3], o[2], 0.5) },
    breakers: { at: lerp(o[3], o[2], 0.68) },
    transactions: { at: lerp(o[3], o[2], 0.86) },
    events: { at: lerp(o[0], o[1], 0.5) },
  };
  const nodes = {
    rest: { x: 4, y: 6, w: 112, h: 58, side: 'top' },
    cli: { x: 124, y: 6, w: 112, h: 58, side: 'top' },
    webhook: { x: 244, y: 6, w: 112, h: 58, side: 'top' },
    persistence: { x: 4, y: 446, w: 140, h: 58, side: 'bottom' },
    outbox: { x: 216, y: 446, w: 140, h: 58, side: 'bottom' },
    stripe: { x: 4, y: 574, w: 82, h: 50, side: 'bottom', compact: true },
    adyen: { x: 94, y: 574, w: 82, h: 50, side: 'bottom', compact: true },
    redsys: { x: 184, y: 574, w: 82, h: 50, side: 'bottom', compact: true },
    mock: { x: 274, y: 574, w: 82, h: 50, side: 'bottom', compact: true },
  };
  return {
    kind: 'tall',
    viewBox: '0 0 360 630',
    outer: o,
    inner: hexPoints(cx, cy, 66, false),
    center: [cx, cy],
    ringTitle: [cx, 186],
    portLabels: false,
    titles: [],
    ports,
    nodes,
    anchor: (node) => (node.side === 'top' ? [node.x + node.w / 2, node.y + node.h] : [node.x + node.w / 2, node.y]),
    curve: (a, b, portId) => {
      if (portId === 'gateway') {
        return `M${a[0]},${a[1]} C${a[0]},${a[1] + 135} ${b[0]},${b[1] - 54} ${b[0]},${b[1]}`;
      }
      const k = (b[1] - a[1]) * 0.5;
      return `M${a[0]},${a[1]} C${a[0]},${a[1] + k} ${b[0]},${b[1] - k} ${b[0]},${b[1]}`;
    },
  };
}

function el(name, attrs = {}, parent) {
  const node = document.createElementNS(SVG_NS, name);
  for (const [k, v] of Object.entries(attrs)) {
    if (v !== undefined && v !== null) node.setAttribute(k, String(v));
  }
  if (parent) parent.appendChild(node);
  return node;
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

export function createHexagon(stage, { reducedMotion = () => false, onStep = () => {} } = {}) {
  const model = {
    persistence: 'memory',
    gateways: new Map(),
    chaos: new Map(),
  };
  const refs = { nodes: {}, ports: {}, labels: {}, wires: {}, domain: null, orders: {} };
  // Lo que ha tocado la última traza, para repintarlo si cambia el layout.
  const touched = { nodes: new Map(), ports: new Set(), wires: new Set(), bad: new Set() };
  const queue = [];
  let playing = false;
  let layout = null;
  let waitingNode = null;

  function build() {
    const kind = stage.clientWidth < 600 ? 'tall' : 'wide';
    if (layout && layout.kind === kind) return;
    layout = kind === 'tall' ? tallLayout() : wideLayout();
    stage.textContent = '';
    const svg = el('svg', {
      viewBox: layout.viewBox,
      role: 'img',
      'aria-labelledby': 'hx-title hx-desc',
      preserveAspectRatio: 'xMidYMid meet',
    });
    const title = el('title', { id: 'hx-title' }, svg);
    title.textContent = 'Arquitectura hexagonal de PayAF';
    const desc = el('desc', { id: 'hx-desc' }, svg);
    desc.textContent = 'El dominio en el centro, rodeado por la aplicación y sus puertos. A un lado, los adaptadores de entrada: REST, webhooks y CLI. Al otro, los de salida: las pasarelas Stripe, Adyen, Redsys y Mock, la persistencia y el outbox. La lista de la traza, debajo, cuenta en texto qué puertos toca cada operación.';

    const wires = el('g', {}, svg);
    el('polygon', { class: 'hx-ring', points: layout.outer.map((p) => p.join(',')).join(' ') }, svg);
    const ringTitle = el('text', { class: 'hx-ring-title', x: layout.ringTitle[0], y: layout.ringTitle[1], 'text-anchor': 'middle' }, svg);
    ringTitle.textContent = 'APLICACIÓN · PUERTOS';

    refs.domain = el('polygon', { class: 'hx-domain', points: layout.inner.map((p) => p.join(',')).join(' ') }, svg);
    const [cx, cy] = layout.center;
    const dt = el('text', { class: 'hx-domain-title', x: cx, y: cy - 12, 'text-anchor': 'middle' }, svg);
    dt.textContent = 'DOMINIO';
    ['Payment', 'RoutingPolicy', 'CircuitBreaker'].forEach((line, i) => {
      el('text', { class: 'hx-domain-sub', x: cx, y: cy + 8 + i * 15, 'text-anchor': 'middle' }, svg).textContent = line;
    });

    for (const t of layout.titles) {
      const text = el('text', { class: 'hx-col-title', x: t.x, y: t.y }, svg);
      text.textContent = t.text;
    }

    refs.wires = {};
    for (const [portId, nodeId] of WIRES) {
      const port = layout.ports[portId];
      const node = layout.nodes[nodeId];
      const d = layout.curve(port.at, layout.anchor(node), portId);
      refs.wires[`${portId}|${nodeId}`] = el('path', { class: 'hx-wire', d }, wires);
    }

    refs.ports = {};
    refs.labels = {};
    for (const [id, port] of Object.entries(layout.ports)) {
      refs.ports[id] = el('circle', { class: 'hx-port', cx: port.at[0], cy: port.at[1], r: 5 }, svg);
      el('title', {}, refs.ports[id]).textContent = PORT_FULL[id] || PORTS[id];
      if (layout.portLabels) {
        const dx = port.label === 'end' ? -10 : 10;
        const label = el('text', {
          class: 'hx-port-label',
          x: port.at[0] + dx,
          y: port.at[1] + 4,
          'text-anchor': port.label === 'end' ? 'end' : 'start',
        }, svg);
        label.textContent = PORTS[id];
        el('title', {}, label).textContent = PORT_FULL[id] || PORTS[id];
        refs.labels[id] = label;
      }
    }

    refs.nodes = {};
    refs.orders = {};
    for (const [id, node] of Object.entries(layout.nodes)) {
      refs.nodes[id] = drawNode(svg, id, node);
    }
    stage.appendChild(svg);
    paintModel();
    repaintTouched();
  }

  function drawNode(svg, id, n) {
    const g = el('g', { class: 'hx-node', 'data-node': id }, svg);
    el('rect', { class: 'hx-plate', x: n.x, y: n.y, width: n.w, height: n.h, rx: 5 }, g);
    const midY = n.y + n.h / 2;
    const ref = { g, node: n };
    const isPsp = PSPS.includes(id);
    if (isPsp) {
      drawPsp(g, id, n, ref);
    } else {
      const glyph = { rest: 'globe', cli: 'terminal', webhook: 'webhook', outbox: 'send', persistence: 'memory' }[id];
      ref.glyph = el('g', { class: 'hx-node-glyph', transform: `translate(${n.x + 12},${midY - 10}) scale(0.8333)` }, g);
      ref.glyph.innerHTML = GLYPHS[glyph];
      if (id === 'persistence') {
        ref.logo = el('image', { x: n.x + 12, y: midY - 10, width: 20, height: 20, href: 'assets/icons/postgres.svg', visibility: 'hidden' }, g);
      }
      const name = el('text', { class: 'hx-node-name', x: n.x + 40, y: midY - 3 }, g);
      name.textContent = { rest: 'REST', cli: 'CLI', webhook: 'Webhook', outbox: 'Outbox', persistence: 'Persistencia' }[id];
      ref.sub = el('text', { class: 'hx-node-sub', x: n.x + 40, y: midY + 13 }, g);
      ref.sub.textContent = {
        rest: n.w < 130 ? 'HTTP' : 'HTTP · /api', cli: 'terminal', webhook: 'de las PSP', outbox: 'eventos → SSE', persistence: 'memory',
      }[id];
    }
    // Insignia de orden (qué paso de la traza tocó primero este adaptador).
    const badge = el('g', { class: 'hx-order-badge', visibility: 'hidden' }, g);
    el('circle', { class: 'hx-order', cx: n.x + 2, cy: n.y + 2, r: 9 }, badge);
    ref.badgeText = el('text', { class: 'hx-order-text', x: n.x + 2, y: n.y + 5.5, 'text-anchor': 'middle' }, badge);
    ref.badge = badge;
    return ref;
  }

  function drawPsp(g, id, n, ref) {
    const midY = n.y + n.h / 2;
    const logos = { stripe: ['assets/icons/stripe.svg', 40, 17], adyen: ['assets/icons/adyen.svg', 46, 15], redsys: ['assets/icons/redsys.svg', 18, 18] };
    if (n.compact) {
      // Móvil: logo o nombre arriba, estado abajo.
      if (id === 'redsys') {
        el('image', { x: n.x + 8, y: n.y + 8, width: 16, height: 16, href: logos.redsys[0] }, g);
        const t = el('text', { class: 'hx-node-name', x: n.x + 28, y: n.y + 21, style: 'font-size:14px' }, g);
        t.textContent = 'Redsys';
      } else if (logos[id]) {
        const [href, w, h] = logos[id];
        const img = el('image', { x: n.x + 8, y: n.y + 9, width: w, height: h, href }, g);
        el('title', {}, img).textContent = id === 'stripe' ? 'Stripe' : 'Adyen';
      } else {
        const t = el('text', { class: 'hx-node-name', x: n.x + 8, y: n.y + 21, style: 'font-size:14px' }, g);
        t.textContent = 'Mock';
      }
      ref.lamp = el('circle', { class: 'hx-lamp', cx: n.x + n.w - 10, cy: n.y + n.h - 13, r: 4.5, 'data-state': 'CLOSED' }, g);
      ref.sub = el('text', { class: 'hx-node-sub', x: n.x + 8, y: n.y + n.h - 9 }, g);
      return;
    }
    if (id === 'redsys') {
      el('image', { x: n.x + 12, y: midY - 9, width: 18, height: 18, href: logos.redsys[0] }, g);
      const t = el('text', { class: 'hx-node-name', x: n.x + 36, y: midY + 5 }, g);
      t.textContent = 'Redsys';
    } else if (logos[id]) {
      const [href, w, h] = logos[id];
      const img = el('image', { x: n.x + 12, y: midY - h / 2, width: w, height: h, href }, g);
      el('title', {}, img).textContent = id === 'stripe' ? 'Stripe' : 'Adyen';
    } else {
      const t = el('text', { class: 'hx-node-name', x: n.x + 12, y: midY + 5 }, g);
      t.textContent = 'Mock';
    }
    ref.lamp = el('circle', { class: 'hx-lamp', cx: n.x + n.w - 14, cy: midY, r: 5, 'data-state': 'CLOSED' }, g);
    ref.sub = el('text', { class: 'hx-node-sub', x: n.x + n.w - 26, y: midY + 4, 'text-anchor': 'end' }, g);
  }

  function pspStatus(id) {
    const gw = model.gateways.get(id);
    const chaos = model.chaos.get(id);
    if (!gw) return { text: '', state: 'CLOSED', off: false };
    let text = gw.circuit.state;
    if (!gw.enabled) text = 'inactiva';
    else if (chaos?.down) text = 'HTTP 503';
    else if (chaos && chaos.latencyMs > 2000) text = 'timeout';
    else if (chaos && chaos.errorRate > 0) text = `${Math.round(chaos.errorRate * 100)} % err.`;
    else if (chaos && chaos.latencyMs > 0) text = `+${chaos.latencyMs} ms`;
    return { text, state: gw.circuit.state, off: !gw.enabled };
  }

  function paintModel() {
    if (!layout) return;
    for (const id of PSPS) {
      const ref = refs.nodes[id];
      if (!ref) continue;
      const s = pspStatus(id);
      ref.sub.textContent = s.text;
      ref.lamp.setAttribute('data-state', s.state);
      ref.g.classList.toggle('is-off', s.off);
    }
    const p = refs.nodes.persistence;
    if (p) {
      const pg = model.persistence === 'postgres';
      p.sub.textContent = pg ? 'postgres' : 'memory';
      p.glyph.setAttribute('visibility', pg ? 'hidden' : 'visible');
      p.logo.setAttribute('visibility', pg ? 'visible' : 'hidden');
    }
  }

  function clearTouched() {
    touched.nodes.clear();
    touched.ports.clear();
    touched.wires.clear();
    touched.bad.clear();
    repaintTouched();
  }

  function repaintTouched() {
    if (!layout) return;
    for (const [id, ref] of Object.entries(refs.nodes)) {
      const order = touched.nodes.get(id);
      ref.g.classList.toggle('is-touched', order !== undefined);
      ref.g.classList.toggle('is-bad', touched.bad.has(id));
      ref.badge.setAttribute('visibility', order !== undefined ? 'visible' : 'hidden');
      ref.badgeText.textContent = order !== undefined ? String(order) : '';
    }
    for (const [id, port] of Object.entries(refs.ports)) {
      port.classList.toggle('is-touched', touched.ports.has(id));
    }
    for (const [id, wire] of Object.entries(refs.wires)) {
      wire.classList.toggle('is-touched', touched.wires.has(id));
    }
  }

  function setHot(step, on) {
    if (!step) return;
    const portId = PORT_BY_NAME[step.port];
    const nodeId = nodeFor(step);
    const bad = isBad(step.outcome);
    refs.nodes[nodeId]?.g.classList.toggle('is-hot', on);
    if (portId) {
      refs.ports[portId]?.classList.toggle('is-hot', on);
      refs.labels[portId]?.classList.toggle('is-hot', on);
      const wire = refs.wires[`${portId}|${nodeId}`];
      wire?.classList.toggle('is-hot', on);
      wire?.classList.toggle('is-bad', on && bad);
    }
  }

  function nodeFor(step) {
    if (step.side === 'driving') return step.adapter;
    if (step.port === 'PaymentGateway') return step.adapter;
    if (step.port === 'EventPublisher') return 'outbox';
    return 'persistence';
  }

  function isBad(outcome) {
    return outcome === 'ERROR' || outcome === 'TIMEOUT' || /Exception$/.test(outcome || '');
  }

  function markTouched(step, order) {
    const portId = PORT_BY_NAME[step.port];
    const nodeId = nodeFor(step);
    if (!touched.nodes.has(nodeId)) touched.nodes.set(nodeId, order);
    if (portId) {
      touched.ports.add(portId);
      touched.wires.add(`${portId}|${nodeId}`);
    }
    if (isBad(step.outcome)) touched.bad.add(nodeId);
  }

  async function play(trace) {
    playing = true;
    if (waitingNode) {
      refs.nodes[waitingNode]?.g.classList.remove('is-hot');
      waitingNode = null;
    }
    trace.onStart?.();
    clearTouched();
    const instant = reducedMotion();
    refs.domain?.classList.add('is-hot');
    let order = 0;
    for (const step of trace.steps) {
      const nodeId = nodeFor(step);
      const first = !touched.nodes.has(nodeId);
      if (first) order += 1;
      if (!instant) {
        setHot(step, true);
        onStep(step);
        await sleep(queue.length ? STEP_FAST_MS : STEP_MS);
        setHot(step, false);
      }
      markTouched(step, first ? order : touched.nodes.get(nodeId));
      repaintTouched();
    }
    refs.domain?.classList.remove('is-hot');
    if (instant) {
      onStep(trace.steps[trace.steps.length - 1] || null);
    } else {
      onStep(null);
    }
    playing = false;
    if (queue.length) play(queue.shift());
  }

  const resize = new ResizeObserver(() => build());
  resize.observe(stage);
  build();

  return {
    enqueue(trace) {
      // Las propias van antes; de las ajenas solo se guardan las dos últimas.
      if (trace.own) {
        queue.unshift(trace);
      } else {
        queue.push(trace);
        const foreign = queue.filter((t) => !t.own);
        if (foreign.length > 2) queue.splice(queue.indexOf(foreign[0]), 1);
      }
      if (!playing) play(queue.shift());
    },
    waiting(nodeId) {
      if (waitingNode) refs.nodes[waitingNode]?.g.classList.remove('is-hot');
      waitingNode = nodeId;
      if (nodeId && !playing) refs.nodes[nodeId]?.g.classList.add('is-hot');
    },
    point(nodeId) {
      for (const ref of Object.values(refs.nodes)) ref.g.classList.remove('is-pointed');
      if (nodeId) refs.nodes[nodeId]?.g.classList.add('is-pointed');
    },
    setGateways(list) {
      model.gateways = new Map(list.map((g) => [g.id, g]));
      paintModel();
    },
    setChaos(map) {
      model.chaos = new Map(map);
      paintModel();
    },
    setPersistence(name) {
      model.persistence = name;
      paintModel();
    },
  };
}
