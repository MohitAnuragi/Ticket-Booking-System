/**
 * Event detail and seat selection.
 *
 * Availability is fetched, not pushed (README section 8): the map is loaded on open,
 * re-fetched on demand, and re-fetched automatically after a 409 so the visitor sees
 * the seats they lost rather than a bare "try again".
 */
import { ApiError, getEvent, getSeatMap, holdSeats } from './api.js';
import { mountShell, showStatus, describeError } from './nav.js';
import { requireSignIn } from './session.js';
import {
  escapeHtml,
  formatEventTime,
  formatMoney,
  hasEventStarted,
  sumMoney,
} from './format.js';

/** Matches MAX_SEATS_PER_BOOKING on the server. */
const MAX_SEATS = 10;

const eventId = new URLSearchParams(window.location.search).get('id');

/** eventSeatId -> seat, for every seat currently rendered. */
let seatsById = new Map();
/** eventSeatIds the visitor has chosen, in click order. */
const selected = new Set();
/**
 * Seat ids laid out as rows of columns, mirroring what is on screen.
 *
 * A 2-D model rather than a flat list because rows are not all the same length: a
 * balcony row with 6 seats next to a stalls row with 20 would make "move down by N"
 * jump to the wrong seat.
 */
let seatGrid = [];
/** Which seat owns the map's single tab stop. */
let focusCell = { row: 0, col: 0 };
let currentEvent = null;

const detail = document.getElementById('event-detail');
const mapContainer = document.getElementById('seat-map');
const summaryPanel = document.getElementById('selection-summary');
const legend = document.getElementById('seat-legend');

init();

async function init() {
  mountShell('events');

  if (!eventId) {
    showStatus('page-status', 'No event was specified. Pick one from the listing.', 'error');
    detail.innerHTML = '';
    return;
  }

  document.getElementById('refresh-seats').addEventListener('click', () => loadSeatMap(true));
  document.getElementById('hold-button').addEventListener('click', onHold);
  document.getElementById('clear-selection').addEventListener('click', () => {
    selected.clear();
    renderSelectionState();
  });

  mapContainer.addEventListener('click', onSeatClick);
  mapContainer.addEventListener('keydown', onSeatKeydown);

  // Only load the map if the event resolved: a 404 on both would stack two errors,
  // the second overwriting the useful one.
  if (await loadEvent()) await loadSeatMap(false);
}

/** @returns {Promise<boolean>} whether the event loaded. */
async function loadEvent() {
  detail.innerHTML = '<div class="skeleton" style="height:120px"></div>';
  try {
    currentEvent = await getEvent(eventId);
    renderEvent(currentEvent);
    return true;
  } catch (error) {
    detail.innerHTML = '';
    showStatus(
      'page-status',
      error.status === 404 ? 'That event does not exist, or is not on sale.' : describeError(error),
      'error',
    );
    return false;
  }
}

function renderEvent(event) {
  const started = hasEventStarted(event.startTime);

  detail.innerHTML = `
    <div class="detail-head">
      <div>
        <h1>${escapeHtml(event.title)}</h1>
        <p class="detail-meta">
          ${formatEventTime(event.startTime)} — ${formatEventTime(event.endTime)}
        </p>
        <p class="detail-meta">
          ${escapeHtml(event.venue.name)}${
            event.venue.address ? `, ${escapeHtml(event.venue.address)}` : ''
          } · ${escapeHtml(event.venue.city)}
        </p>
        <p class="detail-badges">
          ${event.category ? `<span class="badge">${escapeHtml(event.category)}</span>` : ''}
          <span class="badge badge-${event.status.toLowerCase()}">${escapeHtml(event.status)}</span>
          ${started ? '<span class="badge badge-cancelled">Already started</span>' : ''}
        </p>
      </div>
      <p class="detail-price">
        <span class="price">${formatMoney(event.basePrice)}</span>
        <small>base price · tier multipliers apply</small>
      </p>
    </div>
    ${event.description ? `<p class="detail-description">${escapeHtml(event.description)}</p>` : ''}`;

  if (started) {
    showStatus(
      'page-status',
      'This event has already started, so seats can no longer be held.',
      'info',
    );
  }
}

async function loadSeatMap(announce) {
  mapContainer.setAttribute('aria-busy', 'true');
  if (!seatsById.size) {
    mapContainer.innerHTML = '<div class="skeleton" style="height:220px"></div>';
  }

  try {
    const map = await getSeatMap(eventId);
    renderSeatMap(map);
    if (announce) showStatus('page-status', 'Seat availability refreshed.', 'info');
  } catch (error) {
    mapContainer.innerHTML = '';
    showStatus('page-status', describeError(error), 'error');
  } finally {
    mapContainer.removeAttribute('aria-busy');
  }
}

function renderSeatMap(map) {
  const seats = map.seats ?? [];
  seatsById = new Map(seats.map((seat) => [seat.eventSeatId, seat]));

  // A seat that was selected and has since been taken by someone else must not stay
  // selected, or the hold would fail on a seat the visitor thinks they still have.
  for (const id of [...selected]) {
    if (seatsById.get(id)?.status !== 'AVAILABLE') selected.delete(id);
  }

  const rows = new Map();
  for (const seat of seats) {
    if (!rows.has(seat.row)) rows.set(seat.row, []);
    rows.get(seat.row).push(seat);
  }

  const widestRow = Math.max(1, ...[...rows.values()].map((row) => row.length));
  seatGrid = [...rows.values()].map((rowSeats) => rowSeats.map((seat) => seat.eventSeatId));
  clampFocusCell();

  if (seats.length === 0) {
    mapContainer.innerHTML =
      '<div class="empty">This event has no seat map yet. Check back shortly.</div>';
    renderLegend(map.summary);
    renderSelectionState();
    return;
  }

  const rowsHtml = [...rows.entries()]
    .map(
      ([label, rowSeats]) => `
      <div class="seat-row">
        <span class="seat-row-label" aria-hidden="true">${escapeHtml(label)}</span>
        <div class="seat-row-seats" style="--seat-columns:${widestRow}">
          ${rowSeats.map(seatButton).join('')}
        </div>
      </div>`,
    )
    .join('');

  // A stage marker orients the map: without it, row A could be read as the back.
  mapContainer.innerHTML = `<p class="stage" aria-hidden="true">Stage</p>${rowsHtml}`;

  renderLegend(map.summary);
  renderSelectionState();
}

function seatButton(seat) {
  const status = seat.status;
  const isSelected = selected.has(seat.eventSeatId);
  const disabled = status !== 'AVAILABLE';

  // Status is carried by the label and a glyph as well as colour, so it is not
  // conveyed by colour alone.
  const glyph = status === 'BOOKED' ? '×' : status === 'LOCKED' ? '•' : seat.number;

  const label = [
    `Row ${seat.row} seat ${seat.number}`,
    seat.type.toLowerCase(),
    formatMoney(seat.price),
    status === 'AVAILABLE' ? 'available' : status === 'LOCKED' ? 'held by someone else' : 'sold',
  ].join(', ');

  return `<button
      type="button"
      class="seat seat-${status.toLowerCase()}${isSelected ? ' is-selected' : ''}"
      data-seat-id="${escapeHtml(seat.eventSeatId)}"
      data-status="${status}"
      aria-label="${escapeHtml(label)}"
      aria-pressed="${isSelected ? 'true' : 'false'}"
      tabindex="-1"
      ${disabled ? 'disabled' : ''}
    >${escapeHtml(String(glyph))}</button>`;
}

function renderLegend(summary) {
  if (!summary) {
    legend.innerHTML = '';
    return;
  }
  legend.innerHTML = `
    <span class="legend-item"><span class="seat seat-available" aria-hidden="true">1</span>
      Available (${summary.available})</span>
    <span class="legend-item"><span class="seat seat-locked" aria-hidden="true">•</span>
      Held (${summary.locked})</span>
    <span class="legend-item"><span class="seat seat-booked" aria-hidden="true">×</span>
      Sold (${summary.booked})</span>
    <span class="legend-item">${summary.available} of ${summary.total} seats free</span>`;
}

// ---------------------------------------------------------------- selection

function onSeatClick(event) {
  const button = event.target.closest('.seat[data-seat-id]');
  if (!button || button.disabled) return;

  const id = button.dataset.seatId;
  const cell = findCell(id);
  if (cell) focusCell = cell;

  if (selected.has(id)) {
    selected.delete(id);
  } else {
    if (selected.size >= MAX_SEATS) {
      // Stopped here rather than letting the server reject the 11th with a 400:
      // the limit is knowable before the request.
      showStatus(
        'page-status',
        `A single booking can hold at most ${MAX_SEATS} seats. Deselect one first.`,
        'error',
      );
      return;
    }
    selected.add(id);
  }

  showStatus('page-status', '');
  renderSelectionState();
}

/**
 * Arrow-key navigation with a roving tabindex.
 *
 * Without this a 120-seat map would be 120 tab stops to get past. One tab stop
 * enters the map and the arrows move within it, which is the expected pattern for a
 * grid of controls.
 *
 * Moves by row and column rather than by a flat offset, so an uneven row (a short
 * balcony above a wide stalls) still moves to the seat that is visually adjacent.
 */
function onSeatKeydown(event) {
  if (seatGrid.length === 0) return;

  let { row, col } = focusCell;

  switch (event.key) {
    case 'ArrowRight':
      col += 1;
      break;
    case 'ArrowLeft':
      col -= 1;
      break;
    case 'ArrowDown':
      row += 1;
      break;
    case 'ArrowUp':
      row -= 1;
      break;
    case 'Home':
      col = 0;
      break;
    case 'End':
      col = seatGrid[row].length - 1;
      break;
    default:
      return;
  }

  if (row < 0 || row >= seatGrid.length) return;
  // Landing on a shorter row keeps the nearest seat instead of falling off the end.
  col = Math.min(Math.max(col, 0), seatGrid[row].length - 1);
  if (row === focusCell.row && col === focusCell.col) return;

  event.preventDefault();
  focusCell = { row, col };
  focusCurrentSeat();
}

/** Locates a seat id in the grid, so a click and the keyboard share one position. */
function findCell(seatId) {
  for (let row = 0; row < seatGrid.length; row++) {
    const col = seatGrid[row].indexOf(seatId);
    if (col !== -1) return { row, col };
  }
  return null;
}

/** Keeps [focusCell] inside the grid after a re-render changed its shape. */
function clampFocusCell() {
  if (seatGrid.length === 0) {
    focusCell = { row: 0, col: 0 };
    return;
  }
  const row = Math.min(focusCell.row, seatGrid.length - 1);
  const col = Math.min(focusCell.col, Math.max(0, seatGrid[row].length - 1));
  focusCell = { row, col };
}

function currentFocusId() {
  return seatGrid[focusCell.row]?.[focusCell.col] ?? null;
}

function focusCurrentSeat() {
  const id = currentFocusId();
  if (!id) return;
  const button = mapContainer.querySelector(`.seat[data-seat-id="${cssEscape(id)}"]`);
  if (!button) return;
  for (const seat of mapContainer.querySelectorAll('.seat')) seat.tabIndex = -1;
  button.tabIndex = 0;
  button.focus();
}

function renderSelectionState() {
  const focusId = currentFocusId();

  for (const button of mapContainer.querySelectorAll('.seat[data-seat-id]')) {
    const chosen = selected.has(button.dataset.seatId);
    button.classList.toggle('is-selected', chosen);
    button.setAttribute('aria-pressed', chosen ? 'true' : 'false');
    // Exactly one seat stays in the tab order.
    button.tabIndex = button.dataset.seatId === focusId ? 0 : -1;
  }

  const chosenSeats = [...selected].map((id) => seatsById.get(id)).filter(Boolean);
  const labels = chosenSeats.map((seat) => `${seat.row}${seat.number}`);
  const total = sumMoney(chosenSeats.map((seat) => seat.price));

  document.getElementById('selection-count').textContent =
    chosenSeats.length === 0
      ? 'No seats selected'
      : `${chosenSeats.length} seat${chosenSeats.length === 1 ? '' : 's'}: ${labels.join(', ')}`;
  document.getElementById('selection-total').textContent =
    chosenSeats.length === 0 ? '' : formatMoney(total);

  const blocked = currentEvent ? hasEventStarted(currentEvent.startTime) : false;
  document.getElementById('hold-button').disabled = chosenSeats.length === 0 || blocked;
  document.getElementById('clear-selection').disabled = chosenSeats.length === 0;
  summaryPanel.classList.toggle('is-active', chosenSeats.length > 0);
}

// ---------------------------------------------------------------- holding

async function onHold() {
  const button = document.getElementById('hold-button');
  const chosen = [...selected];
  if (chosen.length === 0) return;

  // Guarded here rather than on page load: browsing availability is public by design,
  // and a guard on load would send every visitor to a sign-in form.
  const user = await requireSignIn();
  if (!user) return; // A redirect is already under way.

  button.disabled = true;
  const originalLabel = button.textContent;
  button.textContent = 'Holding seats…';

  try {
    const booking = await holdSeats(eventId, chosen);
    selected.clear();
    // The hold is a PENDING booking; confirming it happens on the booking page.
    window.location.href = `booking.html?id=${encodeURIComponent(booking.id)}`;
  } catch (error) {
    button.textContent = originalLabel;
    await handleHoldError(error);
  } finally {
    renderSelectionState();
  }
}

async function handleHoldError(error) {
  if (!(error instanceof ApiError)) {
    showStatus('page-status', describeError(error), 'error');
    return;
  }

  if (error.code === 'SEATS_UNAVAILABLE') {
    const lost = new Set(error.unavailableSeats ?? []);
    const lostLabels = [...lost]
      .map((id) => seatsById.get(id))
      .filter(Boolean)
      .map((seat) => `${seat.row}${seat.number}`);

    for (const id of lost) selected.delete(id);

    showStatus(
      'page-status',
      lostLabels.length
        ? `Someone took ${lostLabels.join(', ')} first. The map has been refreshed — your other seats are still selected.`
        : 'Some of those seats were taken first. The map has been refreshed.',
      'error',
    );
    // Re-fetch so the taken seats visibly change state instead of the visitor
    // clicking the same seat again.
    await loadSeatMap(false);
    return;
  }

  const friendly = {
    EVENT_NOT_BOOKABLE: 'This event is not open for booking.',
    EVENT_ALREADY_STARTED: 'This event has already started, so seats can no longer be held.',
  }[error.code];

  showStatus('page-status', friendly || describeError(error), 'error');
  if (error.code === 'EVENT_ALREADY_STARTED') await loadSeatMap(false);
}

/** Minimal CSS.escape fallback: seat ids are UUIDs, so only quoting matters. */
function cssEscape(value) {
  return String(value).replace(/"/g, '\\"');
}
