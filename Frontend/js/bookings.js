/**
 * Booking history, with cancellation.
 *
 * Two server behaviours shape this page:
 *
 *  - `GET /bookings/me` is read-only, so a hold whose 24 hours lapsed but which the
 *    sweeper has not processed yet still arrives as PENDING with a past `expiresAt`.
 *    That is compared against now here and shown as lapsed, rather than offering a
 *    Confirm that would fail.
 *  - A customer may cancel a CONFIRMED booking only before the event starts, so the
 *    Cancel button is not offered afterwards (the server would answer
 *    409 EVENT_ALREADY_STARTED).
 */
import { ApiError, cancelBooking, listMyBookings } from './api.js';
import { mountShell, showStatus, describeError } from './nav.js';
import { requireSignIn } from './session.js';
import {
  escapeHtml,
  formatEventTime,
  formatInstant,
  formatMoney,
  hasEventStarted,
  timeRemaining,
} from './format.js';

const list = document.getElementById('booking-list');
const summary = document.getElementById('booking-summary');

init();

async function init() {
  mountShell('bookings');

  const user = await requireSignIn();
  if (!user) return;

  list.addEventListener('click', onListClick);
  await load();
}

async function load() {
  list.innerHTML = '<div class="skeleton" style="height:110px"></div>'.repeat(2);
  try {
    const bookings = await listMyBookings();
    render(bookings ?? []);
  } catch (error) {
    list.innerHTML = '';
    summary.textContent = '';
    showStatus('page-status', describeError(error), 'error');
  }
}

function render(bookings) {
  if (bookings.length === 0) {
    summary.textContent = '';
    list.innerHTML = `
      <div class="empty">
        <h2>No bookings yet</h2>
        <p>Once you hold or book seats they will appear here.</p>
        <p><a class="btn btn-primary" href="index.html">Browse events</a></p>
      </div>`;
    return;
  }

  summary.textContent = `${bookings.length} booking${bookings.length === 1 ? '' : 's'}, newest first`;
  list.innerHTML = bookings.map(bookingCard).join('');
}

function bookingCard(booking) {
  const view = describeBooking(booking);

  return `
    <article class="booking-card card">
      <div class="booking-card-main">
        <div class="booking-head">
          <div>
            <p class="booking-reference">${escapeHtml(booking.bookingReference)}</p>
            <h2 class="event-card-title">
              <a href="event.html?id=${encodeURIComponent(booking.event.id)}">
                ${escapeHtml(booking.event.title)}
              </a>
            </h2>
          </div>
          <span class="badge badge-${view.badge.toLowerCase()}">${escapeHtml(view.badge)}</span>
        </div>

        <p class="detail-meta">${formatEventTime(booking.event.startTime)}</p>
        <p class="detail-meta">
          ${escapeHtml(booking.event.venueName)} · ${escapeHtml(booking.event.venueCity)}
        </p>
        <p class="booking-seats">
          ${booking.seatLabels.map((label) => `<span class="seat-chip">${escapeHtml(label)}</span>`).join('')}
        </p>
        ${view.note ? `<p class="booking-note">${escapeHtml(view.note)}</p>` : ''}
      </div>

      <div class="booking-card-side">
        <p class="price">${formatMoney(booking.totalAmount)}</p>
        <div class="booking-card-actions">
          ${
            view.canConfirm
              ? `<a class="btn btn-primary btn-sm" href="booking.html?id=${encodeURIComponent(
                  booking.id,
                )}">Review &amp; confirm</a>`
              : ''
          }
          ${
            view.canCancel
              ? `<button type="button" class="btn btn-danger btn-sm" data-cancel="${escapeHtml(
                  booking.id,
                )}">${view.cancelLabel}</button>`
              : ''
          }
          ${
            !view.canConfirm && !view.canCancel
              ? `<a class="btn btn-ghost btn-sm" href="booking.html?id=${encodeURIComponent(
                  booking.id,
                )}">View</a>`
              : ''
          }
        </div>
      </div>
    </article>`;
}

/** Works out what this booking currently is, and what may be done with it. */
function describeBooking(booking) {
  const started = hasEventStarted(booking.event.startTime);

  if (booking.status === 'PENDING') {
    const left = timeRemaining(booking.expiresAt);
    if (!left) {
      // Lapsed but not yet swept.
      return {
        badge: 'EXPIRED',
        note: 'This hold lapsed and the seats went back on sale.',
        canConfirm: false,
        canCancel: false,
      };
    }
    return {
      badge: 'PENDING',
      note: `Held until ${formatInstant(booking.expiresAt)} — ${left}. Confirm to complete it.`,
      canConfirm: true,
      // Releasing a hold is always allowed, even after the event starts: freeing
      // seats is never harmful.
      canCancel: true,
      cancelLabel: 'Release',
    };
  }

  if (booking.status === 'CONFIRMED') {
    return {
      badge: 'CONFIRMED',
      note: started ? 'This event has started, so it can no longer be cancelled.' : '',
      canConfirm: false,
      canCancel: !started,
      cancelLabel: 'Cancel booking',
    };
  }

  return {
    badge: booking.status,
    note:
      booking.status === 'CANCELLED'
        ? 'Cancelled. The seats were released.'
        : 'This hold expired and the seats were released.',
    canConfirm: false,
    canCancel: false,
  };
}

async function onListClick(event) {
  const button = event.target.closest('button[data-cancel]');
  if (!button) return;

  const id = button.dataset.cancel;
  const releasing = button.textContent.trim() === 'Release';

  // Cancelling a confirmed ticket is not reversible from the UI, so ask first.
  // Releasing an unconfirmed hold is low stakes, so it goes straight through.
  if (!releasing && !window.confirm('Cancel this booking? The seats go back on sale.')) return;

  button.disabled = true;
  const original = button.textContent;
  button.textContent = releasing ? 'Releasing…' : 'Cancelling…';

  try {
    await cancelBooking(id);
    showStatus(
      'page-status',
      releasing ? 'Those seats have been released.' : 'Your booking was cancelled.',
      'success',
    );
  } catch (error) {
    const friendly =
      error instanceof ApiError
        ? {
            EVENT_ALREADY_STARTED:
              'That event has already started, so the booking can no longer be cancelled.',
            HOLD_EXPIRED: 'That hold had already lapsed, so there was nothing to cancel.',
          }[error.code]
        : null;
    showStatus('page-status', friendly || describeError(error), 'error');
    button.disabled = false;
    button.textContent = original;
  }

  // Re-read either way: on success to show the new status, on failure because the
  // server has just told us our copy is stale.
  await load();
}
