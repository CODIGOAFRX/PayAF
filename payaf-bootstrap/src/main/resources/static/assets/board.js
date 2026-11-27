// Módulo de tablero de lamas: un texto de estado en celdas que giran carácter a carácter cuando
// cambia. Es el movimiento firma del panel y solo se usa para estados (breaker, estado del pago).

const FLIP_DOWN_MS = 70;
const FLIP_UP_MS = 110;
const STAGGER_MS = 24;

/**
 * Pinta (o actualiza) un módulo de tablero.
 * @param {HTMLElement} el contenedor .board-module
 * @param {string} text el estado, en mayúsculas
 * @param {{tone?: string, lamp?: string, count?: string, label?: string, animate?: boolean}} options
 */
export function setBoard(el, text, { tone = '', lamp = '', count = '', label = '', animate = false } = {}) {
  let lampEl = el.querySelector('.lamp');
  let flap = el.querySelector('.flap');
  let countEl = el.querySelector('.board-count');
  if (!flap) {
    el.textContent = '';
    lampEl = document.createElement('span');
    lampEl.className = 'lamp';
    lampEl.setAttribute('aria-hidden', 'true');
    flap = document.createElement('span');
    flap.className = 'flap';
    flap.setAttribute('aria-hidden', 'true');
    countEl = document.createElement('span');
    countEl.className = 'board-count';
    countEl.setAttribute('aria-hidden', 'true');
    el.append(lampEl, flap, countEl);
  }
  el.dataset.tone = tone;
  lampEl.dataset.tone = lamp;
  lampEl.hidden = !lamp;
  countEl.textContent = count;
  countEl.hidden = !count;
  if (label) {
    el.setAttribute('aria-label', label + (count ? ` (${count})` : ''));
  }

  const chars = [...String(text).toUpperCase()];
  const cells = [...flap.children];
  chars.forEach((ch, i) => {
    let cell = cells[i];
    if (!cell) {
      cell = document.createElement('span');
      cell.className = 'flap-cell';
      cell.textContent = ' ';
      flap.appendChild(cell);
    }
    if (cell.textContent !== ch) {
      flip(cell, ch, animate ? i * STAGGER_MS : -1);
    }
  });
  for (let i = cells.length - 1; i >= chars.length; i--) {
    cells[i].remove();
  }
}

function paint(cell, ch) {
  cell.textContent = ch;
  cell.classList.toggle('is-space', ch === ' ' || ch === '_');
  if (ch === '_') {
    cell.textContent = ' ';
  }
}

function flip(cell, ch, delay) {
  const target = ch === '_' ? ' ' : ch;
  if (delay < 0 || typeof cell.animate !== 'function') {
    paint(cell, ch);
    return;
  }
  if (cell.textContent === target) return;
  const down = cell.animate(
    [{ transform: 'rotateX(0deg)', filter: 'brightness(1)' }, { transform: 'rotateX(-88deg)', filter: 'brightness(0.6)' }],
    { duration: FLIP_DOWN_MS, delay, easing: 'cubic-bezier(0.5, 0, 0.75, 0)', fill: 'forwards' },
  );
  down.onfinish = () => {
    paint(cell, ch);
    down.cancel();
    cell.animate(
      [{ transform: 'rotateX(88deg)', filter: 'brightness(0.6)' }, { transform: 'rotateX(0deg)', filter: 'brightness(1)' }],
      { duration: FLIP_UP_MS, easing: 'cubic-bezier(0.16, 1, 0.3, 1)' },
    );
  };
}
