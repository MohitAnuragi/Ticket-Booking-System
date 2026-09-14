/**
 * Hold review and confirmation — the second half of the two-step booking flow.
 *
 * A hold IS a booking in PENDING state, so this page shows a real booking and either
 * confirms it or releases it.
 */
import { ApiError, cancelBooking, confirmBooking, getBooking } from './api.js';
import { mountShell, showStatus, describeError } from './nav.js';
import { requireSignIn } from './session.js';
import {
  escapeHtml,
  formatEventTime,
  formatInstant,
  formatMoney,
  timeRemaining,
} from './format.js';

const COUNTDOWN_TICK_MS = 30_000;

const bookingId = new URLSearchParams(window.location.search).get('id');
const panel = document.getElementById('booking-panel');
const actions = document.getElementById('booking-actions');

let booking = null;
let countdownTimer = null;

init();

async function init() {
  mountShell('bookings');

  if (!bookingId) {
    showStatus('page-status', 'No booking was specified.', 'error');
    panel.innerHTML = '';
    return;
  }

  // Not a public page: a booking belongs to one person.
  const user = await requireSignIn();
  if (!user) return;

  await load();
}

async function load() {
  panel.innerHTML = '<div class="skeleton" style="height:200px"></div>';
  try {
    booking = await getBooking(bookingId);
    render();
  } catch (error) {
    panel.innerHTML = '';
    actions.innerHTML = '';
    const message =
      error.status === 404
        ? 'That booking does not exist.'
        : error.status === 403
          ? 'That booking belongs to someone else.'
          : describeError(error);
    showStatus('page-status', message, 'error');
  }
}

function render() {
  const lapsed = booking.status === 'PENDING' && !timeRemaining(booking.expiresAt);

  panel.innerHTML = `
    <div class="booking-head">
      <div>
        <p class="booking-reference">${escapeHtml(booking.bookingReference)}</p>
        <p class="detail-meta">${statusSentence(lapsed)}</p>
      </div>
      <span class="badge badge-${(lapsed ? 'expired' : booking.status).toLowerCase()}">
        ${escapeHtml(lapsed ? 'EXPIRED' : booking.status)}
      </span>
    </div>

    <dl class="booking-facts">
      <dt>Event</dt>
      <dd>
        <a href="event.html?id=${encodeURIComponent(booking.event.id)}">
          ${escapeHtml(booking.event.title)}
        </a>
      </dd>

      <dt>When</dt>
      <dd>${formatEventTime(booking.event.startTime)}</dd>

      <dt>Where</dt>
      <dd>${escapeHtml(booking.event.venueName)} · ${escapeHtml(booking.event.venueCity)}</dd>

      <dt>Seats</dt>
      <dd>${booking.seats.map(seatLine).join('')}</dd>

      <dt>Total</dt>
      <dd class="price">${formatMoney(booking.totalAmount)}</dd>

      ${
        booking.status === 'PENDING'
          ? `<dt>Held until</dt>
             <dd id="countdown" role="status" aria-live="polite">${countdownText(lapsed)}</dd>`
          : ''
      }
    </dl>`;

  renderActions(lapsed);
  startCountdown();
}

function seatLine(seat) {
  return `<span class="seat-chip">${escapeHtml(seat.label)}
    <small>${escapeHtml(seat.type.toLowerCase())} · ${formatMoney(seat.price)}</small></span>`;
}

function statusSentence(lapsed) {
  if (lapsed) return 'This hold lapsed before it was confirmed, so the seats went back on sale.';
  switch (booking.status) {
    case 'PENDING':
      return 'These seats are held for you. Confirm to complete the booking.';
    case 'CONFIRMED':
      return 'Booked. Show this reference at the venue.';
    case 'CANCELLED':
      return 'This booking was cancelled and its seats were released.';
    case 'EXPIRED':
      return 'This hold expired and its seats were released.';
    default:
      return '';
  }
}

function countdownText(lapsed) {
  if (lapsed) return 'The hold has lapsed.';
  const left = timeRemaining(booking.expiresAt);
  return `${formatInstant(booking.expiresAt)} — ${left}`;
}

function renderActions(lapsed) {
  if (booking.status === 'PENDING' && !lapsed) {
    actions.innerHTML = `
      <button type="button" class="btn btn-primary" id="confirm-button">Confirm booking</button>
      <button type="button" class="btn btn-danger" id="release-button">Release seats</button>`;
    document.getElementById('confirm-button').addEventListener('click', onConfirm);
    document.getElementById('release-button').addEventListener('click', onRelease);
    return;
  }

  if (booking.status === 'CONFIRMED') {
    actions.innerHTML = `
      <a class="btn btn-ghost" href="my-bookings.html">All my bookings</a>
      <a class="btn btn-primary" href="index.html">Browse more events</a>`;
    return;
  }

  // Lapsed, cancelled or expired: the useful next step is picking seats again.
  actions.innerHTML = `
    <a class="btn btn-ghost" href="my-bookings.html">All my bookings</a>
    <a class="btn btn-primary" href="event.html?id=${encodeURIComponent(booking.event.id)}">
      Choose seats again
    </a>`;
}

/**
 * Refreshes the "held until" line so a visitor who leaves the tab open does not see a
 * stale countdown, and the Confirm button disappears the moment the hold lapses
 * instead of failing with 409 HOLD_EXPIRED on click.
 */
function startCountdown() {
  window.clearInterval(countdownTimer);
  if (!booking || booking.status !== 'PENDING') return;

  countdownTimer = window.setInterval(() => {
    const left = timeRemaining(booking.expiresAt);
    const element = document.getElementById('countdown');
    if (element) element.textContent = countdownText(!left);

    if (!left) {
      window.clearInterval(countdownTimer);
      renderActions(true);
      showStatus(
        'page-status',
        'This hold has lapsed and the seats were released. Choose seats again to book.',
        'error',
      );
    }
  }, COUNTDOWN_TICK_MS);
}

// ---------------------------------------------------------------- actions

async function onConfirm() {
  await withBusy('confirm-button', 'Confirming…', async () => {
    try {
      booking = await confirmBooking(bookingId);
      showStatus('page-status', 'Booked. Your seats are confirmed.', 'success');
      render();
    } catch (error) {
      await handleActionError(error);
    }
  });
}

async function onRelease() {
  await withBusy('release-button', 'Releasing…', async () => {
    try {
      booking = await cancelBooking(bookingId);
      showStatus('page-status', 'Those seats have been released.', 'info');
      render();
    } catch (error) {
      await handleActionError(error);
    }
  });
}

async function handleActionError(error) {
  if (!(error instanceof ApiError)) {
    showStatus('page-status', describeError(error), 'error');
    return;
  }

  const friendly = {
    HOLD_EXPIRED:
      'This hold lapsed before it was confirmed, so the seats went back on sale. Choose seats again.',
    BOOKING_CANCELLED: 'This booking was cancelled, so it cannot be confirmed.',
    BOOKING_HAS_NO_SEATS:
      'This booking no longer holds any seats. Choose seats again to book.',
    SEATS_UNAVAILABLE:
      'Someone else took these seats while the hold was lapsing. Choose different seats.',
    EVENT_ALREADY_STARTED: 'This event has already started, so the booking can no longer change.',
  }[error.code];

  showStatus('page-status', friendly || describeError(error), 'error');

  // Re-read rather than guessing the new state: the server has just told us our copy
  // is out of date.
  await load();
}

/** Disables a button while its request is in flight, so a double-click cannot retry. */
async function withBusy(buttonId, busyLabel, action) {
  const button = document.getElementById(buttonId);
  const original = button?.textContent;
  if (button) {
    button.disabled = true;
    button.textContent = busyLabel;
  }
  try {
    await action();
  } finally {
    // render() may have replaced the button entirely; only restore it if it survived.
    const stillThere = document.getElementById(buttonId);
    if (stillThere) {
      stillThere.disabled = false;
      stillThere.textContent = original;
    }
  }
}
