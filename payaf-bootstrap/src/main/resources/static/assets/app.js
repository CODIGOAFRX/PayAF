// PayAF · panel en vivo. Módulo ES sin dependencias ni build.
// Todas las rutas son relativas («api/...»): la app vive bajo /proyecto/PayAF/ en producción.

import { createHexagon } from './hexagon.js';
import { setBoard } from './board.js';

const API = 'api/';
const LOG_MAX = 200;
const POLL_MS = 5000;
const REDUCED = window.matchMedia('(prefers-reduced-motion: reduce)');

const GATEWAYS = {
  stripe: { name: 'Stripe', logo: 'assets/icons/stripe.svg', wide: true },
  adyen: { name: 'Adyen', logo: 'assets/icons/adyen.svg', wide: true },
  redsys: { name: 'Redsys', logo: 'assets/icons/redsys.svg', wide: false },
  mock: { name: 'Mock', logo: null, wide: false },
};

const REJECTION = {
  CURRENCY_NOT_SUPPORTED: 'Divisa no admitida',
  COUNTRY_NOT_SUPPORTED: 'País no admitido',
  DISABLED: 'Deshabilitada',
  CIRCUIT_OPEN: 'Circuito abierto',
};

const OUTCOME = {
  APPROVED: 'Aprobado',
  DECLINED: 'Rechazado',
  ERROR: 'Error',
  TIMEOUT: 'Timeout',
};

const COUNTRY = { ES: 'España', US: 'EE. UU.', GB: 'Reino Unido', JP: 'Japón', FR: 'Francia', DE: 'Alemania', MX: 'México' };

const PRESETS = {
  es: { amount: '100.00', currency: 'EUR', country: 'ES' },
  us: { amount: '100.00', currency: 'USD', country: 'US' },
  declined: { amount: '100.51', currency: 'EUR', country: 'ES' },
  jp: { amount: '10000', currency: 'JPY', country: 'JP' },
};

const $ = (sel, root = document) => root.querySelector(sel);

const state = {
  info: null,
  gateways: new Map(),
  chaos: new Map(),
  skewMs: 0,
  busy: false,
  limitedUntil: 0,
  last: null, // { body, key }
  payment: null,
  routing: null,
  attempts: null,
  replayed: false,
  ownIds: new Set(),
  pendingTraces: new Map(),
};

// ------------------------------------------------------------------ red

class HttpError extends Error {
  constructor(status, problem, headers) {
    super(problem?.detail || `HTTP ${status}`);
    this.status = status;
    this.problem = problem || {};
    this.headers = headers;
  }
}

function newId() {
  if (window.crypto?.randomUUID) {
    return crypto.randomUUID();
  }
  return 'id-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 10);
}

async function api(method, path, { body, headers = {}, requestId } = {}) {
  const init = { method, headers: { Accept: 'application/json', ...headers } };
  if (requestId) {
    init.headers['X-Request-Id'] = requestId;
  }
  if (body !== undefined) {
    init.headers['Content-Type'] = 'application/json';
    init.body = JSON.stringify(body);
  }
  let res;
  try {
    res = await fetch(API + path, init);
  } catch (e) {
    const err = new Error('Sin conexión con el servidor');
    err.network = true;
    throw err;
  }
  const date = res.headers.get('Date');
  if (date) {
    const server = Date.parse(date);
    if (!Number.isNaN(server)) {
      state.skewMs = server - Date.now();
    }
  }
  const text = await res.text();
  let data = null;
  if (text) {
    try {
      data = JSON.parse(text);
    } catch {
      data = null;
    }
  }
  if (!res.ok) {
    throw new HttpError(res.status, data, res.headers);
  }
  return { data, headers: res.headers, status: res.status };
}

// ------------------------------------------------------------------ formato

function money(m) {
  if (!m) return '';
  const value = Number(m.amount);
  try {
    return new Intl.NumberFormat('es-ES', {
      style: 'currency',
      currency: m.currency,
      minimumFractionDigits: m.currency === 'JPY' ? 0 : 2,
    }).format(value);
  } catch {
    return `${m.amount} ${m.currency}`;
  }
}

function pct(bp) {
  return new Intl.NumberFormat('es-ES', { minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(bp / 100) + ' %';
}

function gwName(id) {
  return GATEWAYS[id]?.name || id;
}

function time(iso) {
  const d = iso ? new Date(iso) : new Date();
  return d.toLocaleTimeString('es-ES', { hour12: false }) + '.' + String(d.getMilliseconds()).padStart(3, '0');
}

function esc(text) {
  return String(text ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
}

function shortId(id) {
  return id ? String(id).slice(0, 8) : '';
}

function now() {
  return Date.now() + state.skewMs;
}

// ------------------------------------------------------------------ avisos

let bannerTimer = 0;
let bannerRetry = null;

function showBanner(text, { kind = 'error', retry = null, autoHideMs = 0 } = {}) {
  const banner = $('#banner');
  banner.dataset.kind = kind;
  banner.setAttribute('role', kind === 'info' ? 'status' : 'alert');
  $('#banner-text').textContent = text;
  bannerRetry = retry;
  $('#banner-retry').hidden = !retry;
  banner.hidden = false;
  clearTimeout(bannerTimer);
  if (autoHideMs) {
    bannerTimer = setTimeout(hideBanner, autoHideMs);
  }
}

function hideBanner() {
  $('#banner').hidden = true;
  bannerRetry = null;
}

$('#banner-retry').addEventListener('click', () => {
  const retry = bannerRetry;
  hideBanner();
  if (retry) retry();
});

function explainError(e, what) {
  if (e.network) {
    showBanner(`No hay conexión con el servidor de PayAF (${what}). Comprueba la red y vuelve a intentarlo.`, {
      retry: () => loadAll(),
    });
    return;
  }
  if (e.status === 429 || e.problem?.code === 'RATE_LIMITED') {
    const seconds = Number(e.problem?.retryAfterSeconds || e.headers?.get('Retry-After') || 30);
    startLimit(seconds);
    return;
  }
  const code = e.problem?.code ? ` (${e.problem.code})` : '';
  showBanner(`${e.message}${code}`, { autoHideMs: 8000 });
}

// ------------------------------------------------------------------ límite de pagos

let limitTimer = 0;

function startLimit(seconds) {
  state.limitedUntil = Date.now() + seconds * 1000;
  const perMinute = state.info?.paymentsPerMinute ?? 30;
  const tick = () => {
    const left = Math.ceil((state.limitedUntil - Date.now()) / 1000);
    if (left <= 0) {
      clearInterval(limitTimer);
      state.limitedUntil = 0;
      hideBanner();
      syncLaunchControls();
      return;
    }
    // Solo cambia la cifra, que no se anuncia: el aviso se lee una vez.
    const count = document.getElementById('limit-left');
    if (count) count.textContent = `${left} s`;
  };
  clearInterval(limitTimer);
  const seconds0 = Math.ceil((state.limitedUntil - Date.now()) / 1000);
  showBanner(`Has llegado al límite de la demo: ${perMinute} pagos por minuto y por IP. Podrás volver a lanzar en unos ${seconds0} segundos; mientras, prueba el caos en las pasarelas.`, { kind: 'limit' });
  $('#banner-text').insertAdjacentHTML('beforeend', ` <strong class="limit-left" aria-hidden="true">Quedan <span id="limit-left">${seconds0} s</span></strong>`);
  $('#banner').setAttribute('role', 'status');
  tick();
  limitTimer = setInterval(tick, 1000);
  syncLaunchControls();
}

// ------------------------------------------------------------------ hexágono y traza

const hexagon = createHexagon($('#hexa-stage'), {
  reducedMotion: () => REDUCED.matches,
  onStep: (step) => {
    const now = $('#hexa-now');
    now.classList.add('is-live');
    now.innerHTML = step
      ? `${esc(step.side === 'driving' ? 'Entra por' : 'Sale por')} <strong>${esc(step.adapterLabel)}</strong> · <span class="code">${esc(step.port)}.${esc(step.operation)}</span> · ${esc(step.outcome)}`
      : 'Cada operación ilumina, en orden, los puertos y adaptadores que toca.';
    if (!step) now.classList.remove('is-live');
    highlightTraceStep(step ? step.index : -1);
    // La tarjeta de la pasarela tocada se enciende a la vez que su adaptador.
    for (const card of cards.values()) {
      card.root.classList.toggle('is-hot', Boolean(step && step.port === 'PaymentGateway' && step.adapter === card.id));
    }
  },
});

function onPortTouched(payload) {
  const id = payload.correlationId;
  if (!id) return;
  let list = state.pendingTraces.get(id);
  if (!list) {
    list = [];
    state.pendingTraces.set(id, list);
    // Una traza que nunca se completa (se cortó la conexión) no se queda para siempre.
    if (state.pendingTraces.size > 50) {
      const oldest = state.pendingTraces.keys().next().value;
      state.pendingTraces.delete(oldest);
    }
  }
  list.push(payload);
  if (payload.side === 'driving') {
    state.pendingTraces.delete(id);
    completeTrace(id, list);
  }
}

function completeTrace(id, touches) {
  const own = state.ownIds.has(id);
  state.ownIds.delete(id);
  const sorted = touches.slice().sort((a, b) => a.seq - b.seq);
  const steps = collapse(sorted);
  const total = sorted.find((t) => t.side === 'driving')?.ms ?? 0;
  const driving = sorted[0];
  logLine({
    time: new Date().toISOString(),
    type: 'Traza',
    tone: 'live',
    detail: `${adapterLabel(driving.adapter)} → <span class="code">${esc(driving.port)}.${esc(driving.operation)}</span> · ${steps.length} pasos · ${total} ms${own ? '' : ' · otra operación'}`,
  });
  hexagon.enqueue({ id, own, steps, total, onStart: () => renderTrace(steps, total, own, driving.adapter) });
}

function collapse(touches) {
  const steps = [];
  for (const t of touches) {
    const prev = steps[steps.length - 1];
    if (prev && prev.port === t.port && prev.adapter === t.adapter && prev.operation === t.operation
      && prev.outcome === t.outcome) {
      prev.count += 1;
      prev.ms += t.ms;
      continue;
    }
    steps.push({ ...t, count: 1, adapterLabel: adapterLabel(t.adapter), index: steps.length });
  }
  return steps;
}

function adapterLabel(adapter) {
  switch (adapter) {
    case 'rest': return 'REST';
    case 'webhook': return 'Webhook';
    case 'cli': return 'CLI';
    case 'memory': return 'Memoria';
    case 'postgres': return 'PostgreSQL';
    case 'outbox': return 'Outbox';
    default: return gwName(adapter);
  }
}

function outcomeTone(outcome) {
  if (['ERROR', 'TIMEOUT'].includes(outcome) || /Exception$|^[A-Z_]+_FAILED$|CONFLICT|NOT_FOUND|ILLEGAL/.test(outcome)) return 'bad';
  if (['DECLINED', 'FAILED', 'REPLAYED', 'AlreadyCompleted', 'InProgress'].includes(outcome)) return 'warn';
  if (['APPROVED', 'AUTHORIZED', 'CAPTURED', 'REFUNDED', 'PARTIALLY_REFUNDED', 'CANCELED', 'Reserved', 'APPLIED'].includes(outcome)) return 'ok';
  return '';
}

function renderTrace(steps, total, own, channel) {
  const list = $('#trace-list');
  const max = Math.max(1, ...steps.map((s) => s.ms));
  const html = steps.map((s, i) => {
    const width = Math.max(2, Math.round((s.ms / max) * 56));
    const times = s.count > 1 ? ` ×${s.count}` : '';
    const code = s.code ? ` ${esc(s.code)}` : '';
    return `<li class="trace-step" data-index="${i}">
      <span class="trace-seq">${i + 1}</span>
      <span class="trace-adapter">${esc(s.adapterLabel)}</span>
      <span class="trace-op" title="${esc(s.port)}.${esc(s.operation)}">${esc(s.port)}.${esc(s.operation)}${times}</span>
      <span class="trace-out" data-tone="${outcomeTone(s.outcome)}">${esc(s.outcome)}${code}</span>
      <span class="trace-ms"><span class="trace-bar" style="width:${width}px"></span><span class="trace-ms-val">${s.ms} ms</span></span>
    </li>`;
  }).join('');
  list.innerHTML = html;
  const origin = own ? '' : channel === 'webhook' ? ' · llega por el webhook de la PSP' : ' · operación de otra pestaña';
  $('#trace-meta').textContent = `${steps.length} pasos · ${total} ms${origin}`;
}

function highlightTraceStep(index) {
  const list = $('#trace-list');
  list.querySelector('.trace-step.is-hot')?.classList.remove('is-hot');
  if (index < 0) return;
  const row = list.querySelector(`.trace-step[data-index="${index}"]`);
  if (row) {
    row.classList.add('is-hot');
    const top = row.offsetTop - list.offsetTop;
    if (top < list.scrollTop || top > list.scrollTop + list.clientHeight - row.offsetHeight) {
      list.scrollTop = Math.max(0, top - 40);
    }
  }
}

// ------------------------------------------------------------------ pasarelas

const cards = new Map();

function renderGateways() {
  const grid = $('#gw-grid');
  if (grid.querySelector('.skeleton')) {
    grid.innerHTML = '';
    grid.removeAttribute('aria-busy');
  }
  for (const gw of state.gateways.values()) {
    let card = cards.get(gw.id);
    if (!card) {
      card = buildCard(gw);
      cards.set(gw.id, card);
      grid.appendChild(card.root);
    }
    updateCard(card, gw, state.chaos.get(gw.id));
  }
  hexagon.setGateways([...state.gateways.values()]);
}

function buildCard(gw) {
  const meta = GATEWAYS[gw.id] || { name: gw.id };
  const root = document.createElement('article');
  root.className = 'gw-card';
  root.dataset.gw = gw.id;
  const titleId = `gw-${gw.id}-title`;
  root.setAttribute('aria-labelledby', titleId);
  let logo;
  if (meta.logo && meta.wide) {
    logo = `<span class="gw-logo-tile"><img class="gw-logo" src="${meta.logo}" alt="${esc(meta.name)}" height="20"></span><span class="sr-only" id="${titleId}">${esc(meta.name)}</span>`;
  } else if (meta.logo) {
    logo = `<img class="gw-logo" src="${meta.logo}" alt="" height="20"><span id="${titleId}">${esc(meta.name)}</span>`;
  } else {
    logo = `<span class="gw-wordmark" id="${titleId}">${esc(meta.name)}</span>`;
  }
  root.innerHTML = `
    <div class="gw-head">
      <h3 class="gw-name">${logo}</h3>
      <div class="board-module" data-role="breaker" role="img"></div>
    </div>
    <p class="gw-breaker-text" data-role="breaker-text"></p>
    <dl class="gw-facts">
      <dt>Comisión</dt><dd data-role="fee"></dd>
      <dt>Divisas</dt><dd data-role="currencies"></dd>
      <dt>Países</dt><dd data-role="countries"></dd>
    </dl>
    <div class="gw-controls">
      <div class="gw-row">
        <button type="button" class="btn btn-small btn-danger" data-role="down" aria-pressed="false">
          <svg class="ic" aria-hidden="true" viewBox="0 0 24 24"><path d="M10.513 4.856L13.12 2.17a.5.5 0 0 1 .86.46l-1.377 4.317M15.656 10H20a1 1 0 0 1 .78 1.63l-1.72 1.773m-2.787 2.87L10.88 21.83a.5.5 0 0 1-.86-.46l1.92-6.02A1 1 0 0 0 11 14H4a1 1 0 0 1-.78-1.63l4.507-4.643M2 2l20 20"/></svg>
          Tumbar (503)
        </button>
        <label class="switch"><input type="checkbox" role="switch" data-role="enabled"> Habilitada</label>
      </div>
      <div class="slider">
        <div class="slider-head">
          <label for="lat-${gw.id}">Latencia</label>
          <output for="lat-${gw.id}" data-role="lat-out">0 ms</output>
        </div>
        <input type="range" id="lat-${gw.id}" min="0" max="3000" step="100" value="0" data-role="latency" aria-describedby="lat-${gw.id}-note">
        <p class="slider-note" id="lat-${gw.id}-note" data-role="lat-note"></p>
      </div>
      <div class="slider">
        <div class="slider-head">
          <label for="err-${gw.id}">Tasa de error (HTTP 500)</label>
          <output for="err-${gw.id}" data-role="err-out">0 %</output>
        </div>
        <input type="range" id="err-${gw.id}" min="0" max="100" step="5" value="0" data-role="errors">
      </div>
      <p class="gw-error" data-role="error" role="status"></p>
    </div>`;
  const card = {
    root,
    id: gw.id,
    breaker: root.querySelector('[data-role="breaker"]'),
    breakerText: root.querySelector('[data-role="breaker-text"]'),
    fee: root.querySelector('[data-role="fee"]'),
    currencies: root.querySelector('[data-role="currencies"]'),
    countries: root.querySelector('[data-role="countries"]'),
    down: root.querySelector('[data-role="down"]'),
    enabled: root.querySelector('[data-role="enabled"]'),
    latency: root.querySelector('[data-role="latency"]'),
    latOut: root.querySelector('[data-role="lat-out"]'),
    latNote: root.querySelector('[data-role="lat-note"]'),
    errors: root.querySelector('[data-role="errors"]'),
    errOut: root.querySelector('[data-role="err-out"]'),
    error: root.querySelector('[data-role="error"]'),
    lastState: null,
  };
  card.down.addEventListener('click', () => {
    const chaos = state.chaos.get(gw.id) || { down: false, latencyMs: 0, errorRate: 0 };
    pushChaos(card, { ...chaos, down: !chaos.down });
  });
  card.latency.addEventListener('input', () => showLatency(card, Number(card.latency.value)));
  card.latency.addEventListener('change', () => {
    const chaos = state.chaos.get(gw.id) || { down: false, latencyMs: 0, errorRate: 0 };
    pushChaos(card, { ...chaos, latencyMs: Number(card.latency.value) });
  });
  card.errors.addEventListener('input', () => { card.errOut.textContent = `${card.errors.value} %`; });
  card.errors.addEventListener('change', () => {
    const chaos = state.chaos.get(gw.id) || { down: false, latencyMs: 0, errorRate: 0 };
    pushChaos(card, { ...chaos, errorRate: Number(card.errors.value) / 100 });
  });
  card.enabled.addEventListener('change', () => pushEnabled(card, card.enabled.checked));
  root.addEventListener('mouseenter', () => hexagon.point(gw.id));
  root.addEventListener('mouseleave', () => hexagon.point(null));
  return card;
}

function showLatency(card, ms) {
  card.latOut.textContent = `${ms} ms`;
  const timeout = state.info?.gatewayTimeoutMs ?? 2000;
  if (ms > timeout) {
    card.latNote.textContent = `Timeout: pasa de ${timeout} ms y la llamada acaba en TIMEOUT.`;
    card.latNote.dataset.tone = 'bad';
  } else if (ms > 0) {
    card.latNote.textContent = `Por encima de ${timeout} ms habrá timeout.`;
    card.latNote.dataset.tone = '';
  } else {
    card.latNote.textContent = '';
    card.latNote.dataset.tone = '';
  }
}

function feeText(gw) {
  const fixed = gw.fixedFeeMinor?.EUR ?? Object.values(gw.fixedFeeMinor || {})[0];
  const base = pct(gw.feeBasisPoints);
  return fixed ? `${base} + ${new Intl.NumberFormat('es-ES', { minimumFractionDigits: 2 }).format(fixed / 100)} fija` : base;
}

function breakerCopy(gw) {
  const c = gw.circuit;
  const threshold = state.info?.failureThreshold ?? 3;
  switch (c.state) {
    case 'OPEN': {
      const left = c.retryAt ? Math.max(0, Math.ceil((Date.parse(c.retryAt) - now()) / 1000)) : 0;
      return { text: 'OPEN', tone: 'bad', count: `${left} s`, line: `Circuito abierto: no recibe pagos. Pasa a HALF_OPEN en ${left} s.` };
    }
    case 'HALF_OPEN':
      return { text: 'HALF_OPEN', tone: 'warn', count: '', line: 'Semiabierto: el próximo pago la prueba; si sale bien, se cierra.' };
    default: {
      const n = c.consecutiveFailures;
      const line = n === 0
        ? 'Cerrado: pasa tráfico, sin fallos.'
        : `Cerrado: ${n} de ${threshold} fallos técnicos seguidos antes de abrirse.`;
      return { text: 'CLOSED', tone: n > 0 ? 'warn' : 'ok', count: '', line };
    }
  }
}

function updateCard(card, gw, chaos) {
  const copy = breakerCopy(gw);
  const stateChanged = card.lastState !== copy.text;
  card.lastState = copy.text;
  setBoard(card.breaker, copy.text, {
    tone: copy.text === 'OPEN' ? 'bad' : '',
    lamp: copy.tone,
    count: copy.count,
    label: `Circuit breaker de ${gwName(gw.id)}: ${copy.text}`,
    animate: stateChanged && !REDUCED.matches,
  });
  card.breakerText.textContent = copy.line;
  card.fee.textContent = feeText(gw);
  card.currencies.textContent = gw.currencies.length >= 4 ? 'Todas (EUR, USD, GBP, JPY)' : gw.currencies.join(', ');
  card.countries.textContent = gw.countries.length === 0 ? 'Todos' : gw.countries.map((c) => `${c} · ${COUNTRY[c] || c}`).join(', ');
  card.root.classList.toggle('is-disabled', !gw.enabled);
  if (document.activeElement !== card.enabled) {
    card.enabled.checked = gw.enabled;
  }
  if (chaos) {
    card.down.setAttribute('aria-pressed', String(chaos.down));
    if (document.activeElement !== card.latency) {
      card.latency.value = String(chaos.latencyMs);
      showLatency(card, chaos.latencyMs);
    }
    if (document.activeElement !== card.errors) {
      card.errors.value = String(Math.round(chaos.errorRate * 100));
      card.errOut.textContent = `${Math.round(chaos.errorRate * 100)} %`;
    }
  }
}

async function pushChaos(card, settings) {
  card.error.textContent = '';
  setCardBusy(card, true);
  try {
    const { data } = await api('PUT', `chaos/${card.id}`, {
      body: { down: settings.down, latencyMs: settings.latencyMs, errorRate: settings.errorRate },
    });
    state.chaos.set(card.id, { down: data.down, latencyMs: data.latencyMs, errorRate: data.errorRate });
    updateCard(card, state.gateways.get(card.id), state.chaos.get(card.id));
    hexagon.setChaos(state.chaos);
  } catch (e) {
    card.error.textContent = e.network ? 'Sin conexión: no se ha aplicado.' : `No se ha aplicado: ${e.message}`;
    refreshGateways();
  } finally {
    setCardBusy(card, false);
  }
}

async function pushEnabled(card, enabled) {
  card.error.textContent = '';
  setCardBusy(card, true);
  try {
    await api('PUT', `gateways/${card.id}/enabled`, { body: { enabled } });
    await refreshGateways();
  } catch (e) {
    card.error.textContent = e.network ? 'Sin conexión: no se ha aplicado.' : `No se ha aplicado: ${e.message}`;
    card.enabled.checked = !enabled;
  } finally {
    setCardBusy(card, false);
  }
}

function setCardBusy(card, busy) {
  card.down.disabled = busy;
  card.enabled.disabled = busy;
  card.root.setAttribute('aria-busy', String(busy));
}

let refreshing = null;

async function refreshGateways() {
  if (refreshing) return refreshing;
  refreshing = (async () => {
    try {
      const [{ data: gws }, chaosList] = await Promise.all([
        api('GET', 'gateways'),
        Promise.all(Object.keys(GATEWAYS).map((id) => api('GET', `chaos/${id}`).then((r) => r.data).catch(() => null))),
      ]);
      state.gateways = new Map(gws.map((g) => [g.id, g]));
      for (const c of chaosList) {
        if (c) state.chaos.set(c.gateway, { down: c.down, latencyMs: c.latencyMs, errorRate: c.errorRate });
      }
      renderGateways();
      hexagon.setChaos(state.chaos);
    } finally {
      refreshing = null;
    }
  })();
  return refreshing;
}

let refreshSoonTimer = 0;
function refreshSoon() {
  clearTimeout(refreshSoonTimer);
  refreshSoonTimer = setTimeout(() => refreshGateways().catch(() => {}), 250);
}

// Cuenta atrás del breaker: se repinta cada segundo y, al llegar a cero, se pregunta al servidor.
setInterval(() => {
  let expired = false;
  for (const gw of state.gateways.values()) {
    if (gw.circuit.state !== 'OPEN') continue;
    const card = cards.get(gw.id);
    if (!card) continue;
    const copy = breakerCopy(gw);
    setBoard(card.breaker, copy.text, { tone: 'bad', lamp: copy.tone, count: copy.count, label: `Circuit breaker de ${gwName(gw.id)}: OPEN`, animate: false });
    card.breakerText.textContent = copy.line;
    if (gw.circuit.retryAt && Date.parse(gw.circuit.retryAt) <= now()) expired = true;
  }
  if (expired) refreshSoon();
}, 1000);

// ------------------------------------------------------------------ lanzar pagos

const form = $('#pay-form');

function newReference() {
  return 'pedido-' + Math.floor(1000 + Math.random() * 9000);
}

function readForm() {
  const amount = $('#f-amount').value.trim().replace(',', '.');
  const currency = $('#f-currency').value;
  const country = $('#f-country').value;
  const merchantReference = $('#f-ref').value.trim();
  let error = '';
  if (!/^\d{1,12}(\.\d{1,2})?$/.test(amount) || Number(amount) <= 0) {
    error = 'El importe tiene que ser un número positivo, como 40.00.';
  } else if (currency === 'JPY' && amount.includes('.')) {
    error = 'El yen no tiene céntimos: escribe el importe sin decimales (por ejemplo, 10000).';
  } else if (!merchantReference) {
    error = 'Pon una referencia del comercio (por ejemplo, pedido-1001).';
  }
  $('#f-amount').setAttribute('aria-invalid', String(error.startsWith('El importe') || error.startsWith('El yen')));
  $('#form-error').textContent = error;
  $('#form-error').hidden = !error;
  return error ? null : { amount, currency, country, merchantReference };
}

function syncLaunchControls() {
  const blocked = state.busy || state.limitedUntil > Date.now();
  document.querySelectorAll('[data-preset], #f-submit').forEach((b) => { b.disabled = blocked; });
  $('#replay').disabled = blocked || !state.last;
}

async function launch(body, { key = 'k-' + newId(), trigger = null } = {}) {
  if (state.busy || state.limitedUntil > Date.now()) return;
  state.busy = true;
  trigger?.classList.add('is-busy');
  $('#f-submit').classList.add('is-busy');
  syncLaunchControls();
  const requestId = newId();
  state.ownIds.add(requestId);
  hexagon.waiting('rest');
  $('#idem-key').textContent = key;
  $('#idem-key').hidden = false;
  $('#idem-none').hidden = true;
  // En pantallas estrechas el hexágono queda debajo: se lleva a la vista para ver la traza.
  const stage = $('#hexa-stage');
  const box = stage.getBoundingClientRect();
  if (box.top > window.innerHeight * 0.6 || box.bottom < 0) {
    $('.hexa').scrollIntoView({ behavior: REDUCED.matches ? 'auto' : 'smooth', block: 'start' });
  }
  try {
    const { data, status } = await api('POST', 'payments', { body, headers: { 'Idempotency-Key': key }, requestId });
    state.last = { body, key };
    state.payment = data.payment;
    state.routing = data.routing;
    state.attempts = data.attempts;
    state.replayed = data.replayed || status === 200;
    renderResult();
    refreshSoon();
  } catch (e) {
    state.ownIds.delete(requestId);
    hexagon.waiting(null);
    if (e.status === 409 || e.status === 422 || e.status === 400) {
      renderProblem(e);
    } else {
      explainError(e, 'al lanzar el pago');
    }
  } finally {
    state.busy = false;
    trigger?.classList.remove('is-busy');
    $('#f-submit').classList.remove('is-busy');
    syncLaunchControls();
  }
}

document.querySelectorAll('[data-preset]').forEach((button) => {
  button.addEventListener('click', () => {
    const preset = PRESETS[button.dataset.preset];
    $('#f-amount').value = preset.amount;
    $('#f-currency').value = preset.currency;
    $('#f-country').value = preset.country;
    $('#f-ref').value = newReference();
    const body = readForm();
    if (body) launch(body, { trigger: button });
  });
});

form.addEventListener('submit', (event) => {
  event.preventDefault();
  const body = readForm();
  if (body) {
    launch(body, { trigger: $('#f-submit') }).then(() => { $('#f-ref').value = newReference(); });
  }
});

$('#replay').addEventListener('click', () => {
  if (state.last) launch(state.last.body, { key: state.last.key, trigger: $('#replay') });
});

// ------------------------------------------------------------------ resultado

const STATUS_GLOSS = {
  PENDING: () => 'Pendiente: aún se está enrutando.',
  AUTHORIZED: (p) => `Autorizado por <strong>${esc(gwName(p.authorizedBy))}</strong>. El importe está reservado; falta capturarlo.`,
  CAPTURED: (p) => `Capturado por <strong>${esc(gwName(p.authorizedBy))}</strong>: el cobro está hecho.`,
  PARTIALLY_REFUNDED: (p) => `Reembolsado en parte: ${esc(money(p.refunded))} de ${esc(money(p.amount))}.`,
  REFUNDED: (p) => `Reembolsado por completo (${esc(money(p.refunded))}).`,
  CANCELED: () => 'Cancelado: la autorización se ha anulado y no se cobrará.',
  FAILED: (p) => failureGloss(p.failureReason),
};

function failureGloss(reason = '') {
  if (reason.startsWith('DECLINED')) {
    const code = reason.split(': ')[1];
    return `Rechazado por el emisor${code ? ` (<code>${esc(code)}</code>)` : ''}. Un rechazo es una respuesta de negocio: no hay failover y no cuenta para el breaker.`;
  }
  if (reason === 'NO_GATEWAY_AVAILABLE') {
    return 'Ninguna pasarela ha podido cobrarlo: todas las candidatas han fallado o tienen el circuito abierto.';
  }
  if (reason === 'ABANDONED') {
    return 'Abandonado: llevaba demasiado tiempo pendiente y el reaper lo ha cerrado.';
  }
  return `Fallido: ${esc(reason)}`;
}

function statusTone(status) {
  if (['AUTHORIZED', 'CAPTURED'].includes(status)) return 'ok';
  if (['FAILED'].includes(status)) return 'bad';
  if (['PARTIALLY_REFUNDED', 'REFUNDED', 'CANCELED', 'PENDING'].includes(status)) return 'neutral';
  return 'neutral';
}

let lastStatusShown = '';

function renderResult() {
  const p = state.payment;
  if (!p) return;
  const tone = statusTone(p.status);
  setBoard($('#result-status'), p.status, {
    tone: p.status === 'FAILED' ? 'bad' : '',
    lamp: tone,
    label: `Estado del pago: ${p.status}`,
    animate: lastStatusShown !== p.status && !REDUCED.matches,
  });
  lastStatusShown = p.status;
  $('#result-status').removeAttribute('aria-hidden');
  $('#result-status').setAttribute('role', 'img');

  const attempts = state.attempts || p.attempts || [];
  const routing = state.routing;
  const failed = attempts.filter((a) => a.outcome === 'ERROR' || a.outcome === 'TIMEOUT');

  let html = `<p class="result-gloss">${(STATUS_GLOSS[p.status] || (() => esc(p.status)))(p)}</p>`;
  if (state.replayed) {
    html += `<p class="replay-note"><svg class="ic" aria-hidden="true" viewBox="0 0 24 24"><path d="m17 2l4 4l-4 4"/><path d="M3 11v-1a4 4 0 0 1 4-4h14M7 22l-4-4l4-4"/><path d="M21 13v1a4 4 0 0 1-4 4H3"/></svg>
      <span><strong>Repetición idempotente</strong> (HTTP 200, <code>Idempotent-Replayed: true</code>): es el mismo pago, sin llamar a ninguna pasarela.</span></p>`;
  }
  html += `<p class="hint">${esc(money(p.amount))} · ${esc(p.country)} · ${esc(p.merchantReference)} · pago <code>${esc(shortId(p.id))}</code></p>`;

  if (routing) {
    html += `<div class="result-block"><h3 class="sub-title">Decisión de enrutado: candidatas por comisión</h3>`;
    if (routing.candidates.length) {
      html += '<ol class="route-list">' + routing.candidates.map((c, i) => `
        <li data-gw="${esc(c.gateway)}"><span class="route-rank">${i + 1}</span><span class="route-gw">${esc(gwName(c.gateway))}</span>
        <span class="route-fee">${esc(money(c.estimatedFee))}</span><span class="state-tag" data-state="${esc(c.circuitState)}">${esc(c.circuitState)}</span></li>`).join('') + '</ol>';
    } else {
      html += '<p class="hint">Ninguna pasarela puede atender este pago.</p>';
    }
    if (routing.rejections.length) {
      html += '<h3 class="sub-title" style="margin-top:10px">Descartadas</h3><ul class="reject-list">' + routing.rejections.map((r) => `
        <li data-gw="${esc(r.gateway)}"><span>${esc(gwName(r.gateway))}</span><span class="reject-reason">${esc(REJECTION[r.reason] || r.reason)}</span></li>`).join('') + '</ul>';
    }
    html += '</div>';
  }

  html += `<div class="result-block"><h3 class="sub-title">Intentos, en orden</h3>`;
  if (attempts.length) {
    html += `<table class="attempts"><thead><tr><th scope="col">#</th><th scope="col">Pasarela</th><th scope="col">Resultado</th><th scope="col">Código nativo</th><th scope="col" class="num">Latencia</th></tr></thead><tbody>`
      + attempts.map((a, i) => `<tr data-gw="${esc(a.gateway)}"><td>${i + 1}</td><td>${esc(gwName(a.gateway))}</td>
        <td><span class="outcome" data-o="${esc(a.outcome)}">${esc(OUTCOME[a.outcome] || a.outcome)}</span></td>
        <td><code>${esc(a.code || a.gatewayReference || '—')}</code></td><td class="num">${a.latencyMs} ms</td></tr>`).join('')
      + '</tbody></table>';
  } else {
    html += `<p class="hint">${state.replayed ? 'Ninguno en esta respuesta: la repetición no llama a las pasarelas.' : 'Ningún intento: no había candidatas.'}</p>`;
  }
  html += '</div>';

  html += renderActions(p);
  $('#result-body').innerHTML = html;
  wireActions(p);

  // Un resumen discreto para lectores de pantalla.
  let summary = `Pago ${p.status}.`;
  if (p.authorizedBy) summary += ` Lo cobra ${gwName(p.authorizedBy)}.`;
  if (failed.length) summary += ` Failover tras ${failed.length} fallo${failed.length > 1 ? 's' : ''} en ${[...new Set(failed.map((a) => gwName(a.gateway)))].join(' y ')}.`;
  if (state.replayed) summary += ' Repetición idempotente: sin llamadas a pasarelas.';
  $('#result-live').textContent = summary;
}

function renderActions(p) {
  const canCapture = p.status === 'AUTHORIZED';
  const canCancel = p.status === 'AUTHORIZED';
  const canRefund = p.status === 'CAPTURED' || p.status === 'PARTIALLY_REFUNDED';
  const remaining = p.remainingRefundable?.amount ?? '';
  return `<div class="actions" role="group" aria-label="Acciones sobre este pago">
    <button type="button" class="btn" data-action="capture" ${canCapture ? '' : 'disabled'}>Capturar</button>
    <button type="button" class="btn" data-action="cancel" ${canCancel ? '' : 'disabled'}>Cancelar</button>
    <div class="refund-row">
      <label class="sr-only" for="refund-amount">Importe a reembolsar</label>
      <input id="refund-amount" inputmode="decimal" value="${esc(remaining)}" ${canRefund ? '' : 'disabled'} aria-describedby="refund-help">
      <button type="button" class="btn" data-action="refund" ${canRefund ? '' : 'disabled'}>Reembolsar</button>
    </div>
    <p class="hint" id="refund-help" style="grid-column:1/-1;font-size:12px">${canRefund ? `Queda por reembolsar ${esc(money(p.remainingRefundable))}.` : 'Se puede reembolsar después de capturar.'}</p>
    <p class="action-error" id="action-error" role="status"></p>
  </div>`;
}

function wireActions(p) {
  $('#result-body').querySelectorAll('[data-action]').forEach((button) => {
    button.addEventListener('click', () => runAction(p, button.dataset.action, button));
  });
}

async function runAction(p, action, button) {
  const error = $('#action-error');
  error.textContent = '';
  const requestId = newId();
  state.ownIds.add(requestId);
  button.disabled = true;
  hexagon.waiting('rest');
  try {
    let res;
    if (action === 'refund') {
      const amount = $('#refund-amount').value.trim().replace(',', '.');
      res = await api('POST', `payments/${p.id}/refunds`, { body: { amount }, requestId });
    } else {
      res = await api('POST', `payments/${p.id}/${action}`, { requestId });
    }
    state.payment = res.data;
    state.attempts = null;
    state.replayed = false;
    renderResult();
  } catch (e) {
    state.ownIds.delete(requestId);
    hexagon.waiting(null);
    if (e.network || e.status === 429) {
      explainError(e, 'en la operación');
    } else {
      error.textContent = `${e.message}${e.problem?.code ? ` (${e.problem.code})` : ''}`;
    }
    button.disabled = false;
  }
}

function renderProblem(e) {
  const code = e.problem?.code || `HTTP ${e.status}`;
  $('#result-body').innerHTML = `<div class="result-empty"><p><strong>El núcleo ha rechazado la orden</strong> (<code>${esc(code)}</code>).</p><p>${esc(e.message)}</p></div>`;
  setBoard($('#result-status'), 'RECHAZADA', { tone: 'bad', lamp: 'bad', label: `Orden rechazada: ${code}`, animate: !REDUCED.matches });
  $('#result-live').textContent = `Orden rechazada: ${e.message}`;
}

// Señalar en el hexágono la pasarela de una fila (decisión, intentos).
$('#result-body').addEventListener('mouseover', (event) => {
  const row = event.target.closest('[data-gw]');
  hexagon.point(row ? row.dataset.gw : null);
});
$('#result-body').addEventListener('mouseleave', () => hexagon.point(null));

// ------------------------------------------------------------------ registro de eventos

const log = {
  paused: false,
  queue: [],
  held: [],
  frame: 0,
};

const EVENT_COPY = {
  PaymentCreated: (p) => ({ type: 'Pago creado', detail: `${money(p.amount)} · ${p.country} · ${esc(p.merchantReference)} · <span class="code">${shortId(p.paymentId)}</span>` }),
  GatewayAttemptRecorded: (p) => {
    const a = p.attempt || {};
    const tone = a.outcome === 'APPROVED' ? 'ok' : a.outcome === 'DECLINED' ? 'warn' : 'bad';
    return { type: 'Intento', tone, detail: `${esc(gwName(a.gateway))}: ${esc(OUTCOME[a.outcome] || a.outcome)}${a.code ? ` <span class="code">${esc(a.code)}</span>` : ''} · ${a.latencyMs} ms`, gw: a.gateway };
  },
  PaymentAuthorized: (p) => ({ type: 'Autorizado', tone: 'ok', detail: `${esc(gwName(p.gateway))} · ref <span class="code">${esc(p.gatewayReference)}</span>`, gw: p.gateway }),
  PaymentFailed: (p) => ({ type: 'Fallido', tone: 'bad', detail: `${esc(p.reason)} · <span class="code">${shortId(p.paymentId)}</span>` }),
  PaymentCaptured: (p) => ({ type: 'Capturado', tone: 'ok', detail: `${money(p.amount)} · <span class="code">${shortId(p.paymentId)}</span>` }),
  PaymentCanceled: (p) => ({ type: 'Cancelado', tone: 'warn', detail: `<span class="code">${shortId(p.paymentId)}</span>` }),
  PaymentRefunded: (p) => ({ type: 'Reembolsado', tone: 'warn', detail: `${money(p.amount)} (total ${money(p.totalRefunded)}) · <span class="code">${shortId(p.paymentId)}</span>` }),
  CircuitOpened: (p) => ({ type: 'Circuito OPEN', tone: 'bad', detail: `${esc(gwName(p.gateway))}: deja de recibir pagos`, gw: p.gateway }),
  CircuitHalfOpened: (p) => ({ type: 'Circuito HALF_OPEN', tone: 'warn', detail: `${esc(gwName(p.gateway))}: se probará con el próximo pago`, gw: p.gateway }),
  CircuitClosed: (p) => ({ type: 'Circuito CLOSED', tone: 'ok', detail: `${esc(gwName(p.gateway))}: vuelve a recibir pagos`, gw: p.gateway }),
};

function logLine(line) {
  if (log.paused) {
    log.held.push(line);
    if (log.held.length > LOG_MAX) log.held.shift();
    const pending = $('#log-pending');
    pending.hidden = false;
    pending.textContent = `${log.held.length} nuevos en pausa`;
    return;
  }
  log.queue.push(line);
  if (!log.frame) log.frame = requestAnimationFrame(flushLog);
}

function flushLog() {
  log.frame = 0;
  const list = $('#log-list');
  list.querySelector('.log-empty')?.remove();
  const fragment = document.createDocumentFragment();
  // Lo más reciente arriba: se insertan en orden inverso de llegada.
  for (let i = log.queue.length - 1; i >= Math.max(0, log.queue.length - LOG_MAX); i--) {
    const l = log.queue[i];
    const li = document.createElement('li');
    li.className = 'log-line';
    if (l.gw) li.dataset.gw = l.gw;
    li.innerHTML = `<span class="log-time">${esc(time(l.time).slice(0, 12))}</span><span class="log-type" data-tone="${l.tone || ''}">${esc(l.type)}</span><span class="log-detail">${l.detail}</span>`;
    fragment.appendChild(li);
  }
  log.queue = [];
  list.prepend(fragment);
  while (list.children.length > LOG_MAX) {
    list.lastElementChild.remove();
  }
}

$('#log-pause').addEventListener('click', () => {
  log.paused = !log.paused;
  const button = $('#log-pause');
  button.setAttribute('aria-pressed', String(log.paused));
  button.querySelector('.log-pause-label').textContent = log.paused ? 'Reanudar' : 'Pausar';
  if (!log.paused) {
    const held = log.held;
    log.held = [];
    $('#log-pending').hidden = true;
    held.forEach(logLine);
  }
});

$('#log-list').addEventListener('mouseover', (event) => {
  const row = event.target.closest('[data-gw]');
  hexagon.point(row ? row.dataset.gw : null);
});
$('#log-list').addEventListener('mouseleave', () => hexagon.point(null));

// ------------------------------------------------------------------ SSE

const sse = { source: null, retryMs: 2000, timer: 0 };

function setLive(stateName, text) {
  const el = $('#sse-status');
  el.dataset.state = stateName;
  el.querySelector('.live-text').textContent = text;
}

function connect() {
  clearTimeout(sse.timer);
  sse.source?.close();
  setLive('connecting', 'Conectando…');
  const source = new EventSource(API + 'events/stream');
  sse.source = source;
  source.onopen = () => {
    sse.retryMs = 2000;
    setLive('open', 'En vivo');
  };
  source.onerror = () => {
    if (source.readyState === EventSource.CLOSED) {
      const wait = sse.retryMs;
      sse.retryMs = Math.min(sse.retryMs * 2, 15000);
      setLive('closed', `Sin conexión en vivo · reintento en ${Math.round(wait / 1000)} s`);
      sse.timer = setTimeout(connect, wait);
    } else {
      setLive('retrying', 'Reconectando…');
    }
  };
  source.addEventListener('port.touched', (event) => {
    const data = parse(event.data);
    if (data) onPortTouched(data.payload);
  });
  for (const type of Object.keys(EVENT_COPY)) {
    source.addEventListener(type, (event) => {
      const data = parse(event.data);
      if (!data) return;
      const copy = EVENT_COPY[type](data.payload || {});
      logLine({ time: data.occurredAt, ...copy });
      if (type.startsWith('Circuit') || type === 'GatewayAttemptRecorded') refreshSoon();
    });
  }
}

function parse(text) {
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}

// ------------------------------------------------------------------ restaurar la demo

$('#reset-demo').addEventListener('click', async () => {
  const button = $('#reset-demo');
  button.disabled = true;
  try {
    const { data } = await api('POST', 'demo/reset');
    state.gateways = new Map(data.map((g) => [g.id, g]));
    await refreshGateways();
    showBanner('Demo restaurada: sin averías, las cuatro pasarelas habilitadas y todos los circuitos en CLOSED.', { kind: 'info', autoHideMs: 5000 });
  } catch (e) {
    explainError(e, 'al restaurar la demo');
  } finally {
    button.disabled = false;
  }
});

// ------------------------------------------------------------------ arranque

async function loadAll() {
  try {
    const [{ data: info }] = await Promise.all([api('GET', 'demo'), refreshGateways()]);
    state.info = info;
    $('#persistence-name').textContent = info.persistence;
    $('#persistence-chip').hidden = false;
    hexagon.setPersistence(info.persistence);
    for (const card of cards.values()) {
      showLatency(card, Number(card.latency.value));
    }
    renderGateways();
  } catch (e) {
    explainError(e, 'al cargar el panel');
    const grid = $('#gw-grid');
    if (grid.querySelector('.skeleton')) {
      grid.innerHTML = '<p class="hint">No se han podido cargar las pasarelas. Usa «Reintentar» en el aviso de arriba.</p>';
      grid.removeAttribute('aria-busy');
    }
  }
}

$('#f-ref').value = newReference();
syncLaunchControls();
loadAll();
connect();

setInterval(() => {
  if (document.visibilityState === 'visible' && !state.busy) {
    refreshGateways().catch(() => {});
  }
}, POLL_MS);

document.addEventListener('visibilitychange', () => {
  if (document.visibilityState === 'visible') refreshGateways().catch(() => {});
});
