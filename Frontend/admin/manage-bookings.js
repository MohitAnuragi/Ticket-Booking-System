/**
 * Booking oversight across every user.
 *
 * Two things distinguish this from the customer's own history: each row names the
 * customer (a block the user-facing responses omit), and an admin may cancel any
 * booking with no start-time window.
 *
 * Paginated because `total` counts every match rather than the current page, which is
 * what makes "showing 1-50 of 812" possible.
 */
import { admin } from '../js/api.js';
import { escapeHtml, formatEventTime, formatMoney } from '../js/format.js';
import { requireAdminPage, reportError, submitWith, showStatus } from './admin-common.js';

const PAGE_SIZE = 25;

const tableHost = document.getElementById('booking-table');
const filterForm = document.getElementById('booking-filters');
const pagerInfo = document.getElementById('pager-info');
const previousButton = document.getElementById('page-previous');
const nextButton = document.getElementById('page-next');
const eventSelect = document.getElementById('filter-event');

let offset = 0;
let lastPage = { bookings: [], total: 0, limit: PAGE_SIZE, offset: 0 };

init();

async function init() {
  const user = await requireAdminPage('booking-table');
  if (!user) return;

  filterForm.addEventListener('submit', (event) => {
    event.preventDefault();
    offset = 0; // A new filter starts at the first page, not wherever we were.
    load();
  });
  filterForm.addEventListener('reset', () => {
    offset = 0;
    setTimeout(load, 0);
  });

  previousButton.addEventListener('click', () => {
    offset = Math.max(0, offset - PAGE_SIZE);
    load();
  });
  nextButton.addEventListener('click', () => {
    offset += PAGE_SIZE;
    load();
  });

  tableHost.addEventListener('click', onTableClick);

  await loadEventOptions();
  await load();
}

/** Filter by event using real events rather than making an admin paste a UUID. */
async function loadEventOptions() {
  try {
    const events = (await admin.listEvents()) ?? [];
    eventSelect.innerHTML =
      '<option value="">All events</option>' +
      events
        .map(
          (event) =>
            `<option value="${escapeHtml(event.id)}">${escapeHtml(event.title)} — ${escapeHtml(
              event.city,
            )}</option>`,
        )
        .join('');
  } catch (error) {
    reportError(error);
  }
}

async function load() {
  tableHost.innerHTML = '<div class="skeleton" style="height:160px"></div>';

  const fields = filterForm.elements;
  try {
    lastPage = await admin.listBookings({
      eventId: fields.eventId.value,
      status: fields.status.value,
      limit: PAGE_SIZE,
      offset,
    });
    renderTable();
    renderPager();
  } catch (error) {
    tableHost.innerHTML = '';
    pagerInfo.textContent = '';
    reportError(error);
  }
}

function renderTable() {
  const rows = lastPage.bookings ?? [];

  if (rows.length === 0) {
    tableHost.innerHTML = `<div class="empty"><p>${
      lastPage.total > 0
        ? 'No bookings on this page. Go back a page.'
        : 'No bookings match those filters.'
    }</p></div>`;
    return;
  }

  tableHost.innerHTML = `
    <div class="table-scroll">
      <table class="data-table">
        <caption class="sr-only">Bookings across all users</caption>
        <thead>
          <tr>
            <th scope="col">Reference</th><th scope="col">Customer</th><th scope="col">Event</th>
            <th scope="col">Seats</th><th scope="col">Total</th>
            <th scope="col">Status</th><th scope="col">Actions</th>
          </tr>
        </thead>
        <tbody>${rows.map(bookingRow).join('')}</tbody>
      </table>
    </div>`;
}

function bookingRow(booking) {
  const cancellable = booking.status === 'PENDING' || booking.status === 'CONFIRMED';

  return `
    <tr>
      <td><code>${escapeHtml(booking.bookingReference)}</code></td>
      <td class="wrap">${
        booking.user
          ? `${escapeHtml(booking.user.name)}<br /><small>${escapeHtml(booking.user.email)}</small>`
          : '<small>unknown</small>'
      }</td>
      <td class="wrap">${escapeHtml(booking.event.title)}<br />
        <small>${formatEventTime(booking.event.startTime)}</small></td>
      <td class="wrap">${escapeHtml(booking.seatLabels.join(', '))}</td>
      <td>${formatMoney(booking.totalAmount)}</td>
      <td><span class="badge badge-${booking.status.toLowerCase()}">${escapeHtml(
        booking.status,
      )}</span></td>
      <td>${
        cancellable
          ? `<button type="button" class="btn btn-danger btn-sm" data-cancel="${escapeHtml(
              booking.id,
            )}">Cancel</button>`
          : ''
      }</td>
    </tr>`;
}

function renderPager() {
  const total = Number(lastPage.total ?? 0);
  const shown = (lastPage.bookings ?? []).length;
  const from = total === 0 ? 0 : offset + 1;
  const to = offset + shown;

  pagerInfo.textContent = total === 0 ? 'No bookings' : `Showing ${from}–${to} of ${total}`;
  previousButton.disabled = offset === 0;
  nextButton.disabled = to >= total;
}

async function onTableClick(event) {
  const button = event.target.closest('button[data-cancel]');
  if (!button) return;

  // An admin cancelling someone else's ticket is recorded server-side against their
  // id, so make the action deliberate.
  if (!window.confirm("Cancel this customer's booking? Their seats go back on sale.")) return;

  try {
    await submitWith(button, 'Cancelling…', async () => {
      await admin.cancelBooking(button.dataset.cancel);
      showStatus('page-status', 'That booking was cancelled and its seats released.', 'success');
      await load();
    });
  } catch (error) {
    reportError(error);
    await load();
  }
}
