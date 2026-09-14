/**
 * Events: create, patch, soft-delete.
 *
 * Unlike the public listing this includes DRAFT and CANCELLED events. Creating an
 * event also generates one `event_seats` row per venue seat server-side, so the
 * response's `seatsGenerated` is worth surfacing — it is the confirmation that the
 * event is actually bookable.
 */
import { admin } from '../js/api.js';
import { escapeHtml, formatEventTime, formatMoney } from '../js/format.js';
import { requireAdminPage, reportError, submitWith, toApiDateTime, showStatus } from './admin-common.js';

const tableHost = document.getElementById('event-table');
const form = document.getElementById('event-form');
const formHeading = document.getElementById('event-form-heading');
const venueSelect = document.getElementById('event-venue');
const cancelEditButton = document.getElementById('cancel-edit');

let events = [];
/** Set while editing; null means the form creates a new event. */
let editingId = null;

init();

async function init() {
  const user = await requireAdminPage('event-table');
  if (!user) return;

  form.addEventListener('submit', onSubmit);
  cancelEditButton.addEventListener('click', resetForm);
  tableHost.addEventListener('click', onTableClick);

  await Promise.all([loadVenues(), load()]);
}

async function loadVenues() {
  try {
    const venues = (await admin.listVenues()) ?? [];
    venueSelect.innerHTML =
      '<option value="">Select a venue…</option>' +
      venues
        .map(
          (venue) =>
            `<option value="${escapeHtml(venue.id)}">${escapeHtml(venue.name)} — ${escapeHtml(
              venue.city,
            )} (${venue.seatCount} seats)</option>`,
        )
        .join('');
  } catch (error) {
    reportError(error);
  }
}

async function load() {
  tableHost.innerHTML = '<div class="skeleton" style="height:140px"></div>';
  try {
    events = (await admin.listEvents()) ?? [];
    renderTable();
  } catch (error) {
    tableHost.innerHTML = '';
    reportError(error);
  }
}

function renderTable() {
  if (events.length === 0) {
    tableHost.innerHTML = '<div class="empty"><p>No events yet. Create one on the right.</p></div>';
    return;
  }

  tableHost.innerHTML = `
    <div class="table-scroll">
      <table class="data-table">
        <caption class="sr-only">All events, including drafts and cancelled</caption>
        <thead>
          <tr>
            <th scope="col">Event</th><th scope="col">Starts</th><th scope="col">Venue</th>
            <th scope="col">Base price</th><th scope="col">Status</th><th scope="col">Actions</th>
          </tr>
        </thead>
        <tbody>${events.map(eventRow).join('')}</tbody>
      </table>
    </div>`;
}

function eventRow(event) {
  return `
    <tr>
      <td class="wrap">${escapeHtml(event.title)}</td>
      <td>${formatEventTime(event.startTime)}</td>
      <td class="wrap">${escapeHtml(event.venueName)} · ${escapeHtml(event.city)}</td>
      <td>${formatMoney(event.basePrice)}</td>
      <td><span class="badge badge-${event.status.toLowerCase()}">${escapeHtml(
        event.status,
      )}</span></td>
      <td>
        <button type="button" class="btn btn-ghost btn-sm" data-edit="${escapeHtml(event.id)}">
          Edit
        </button>
        ${
          event.status === 'CANCELLED'
            ? ''
            : `<button type="button" class="btn btn-danger btn-sm" data-cancel="${escapeHtml(
                event.id,
              )}">Cancel</button>`
        }
      </td>
    </tr>`;
}

// ---------------------------------------------------------------- editing

async function onTableClick(event) {
  const editButton = event.target.closest('button[data-edit]');
  if (editButton) {
    await beginEdit(editButton.dataset.edit);
    return;
  }

  const cancelButton = event.target.closest('button[data-cancel]');
  if (cancelButton) await cancelEvent(cancelButton);
}

async function beginEdit(id) {
  showStatus('page-status', '');
  try {
    // Re-read rather than reusing the summary row: the listing has no description or
    // poster URL, and editing from a partial copy would blank them.
    const event = await admin.getEvent(id);
    editingId = id;

    const fields = form.elements;
    fields.venueId.value = event.venue.id;
    // The venue of an existing event cannot change: its seat rows are derived from
    // it, so moving the event would invalidate every booking.
    fields.venueId.disabled = true;
    fields.title.value = event.title;
    fields.category.value = event.category ?? '';
    fields.description.value = event.description ?? '';
    fields.startTime.value = toInputDateTime(event.startTime);
    fields.endTime.value = toInputDateTime(event.endTime);
    fields.basePrice.value = event.basePrice;
    fields.status.value = event.status;
    fields.posterUrl.value = event.posterUrl ?? '';

    formHeading.textContent = `Editing "${event.title}"`;
    cancelEditButton.hidden = false;
    form.querySelector('button[type="submit"]').textContent = 'Save changes';
    formHeading.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
  } catch (error) {
    reportError(error);
  }
}

function resetForm() {
  editingId = null;
  form.reset();
  form.elements.venueId.disabled = false;
  formHeading.textContent = 'New event';
  cancelEditButton.hidden = true;
  form.querySelector('button[type="submit"]').textContent = 'Create event';
  showStatus('page-status', '');
}

/** "2026-10-01T19:00:00" -> "2026-10-01T19:00" for a datetime-local input. */
function toInputDateTime(iso) {
  return String(iso ?? '').slice(0, 16);
}

// ---------------------------------------------------------------- actions

async function onSubmit(event) {
  event.preventDefault();
  showStatus('page-status', '');

  const fields = form.elements;
  const payload = {
    title: fields.title.value.trim(),
    category: fields.category.value.trim() || null,
    description: fields.description.value.trim() || null,
    startTime: toApiDateTime(fields.startTime.value),
    endTime: toApiDateTime(fields.endTime.value),
    // Sent as the typed string: money is a decimal string end to end, never a float.
    basePrice: fields.basePrice.value.trim(),
    status: fields.status.value,
    posterUrl: fields.posterUrl.value.trim() || null,
  };

  const button = form.querySelector('button[type="submit"]');

  try {
    if (editingId) {
      await submitWith(button, 'Saving…', async () => {
        const updated = await admin.updateEvent(editingId, payload);
        showStatus('page-status', `Saved "${updated.title}".`, 'success');
        resetForm();
        await load();
      });
      return;
    }

    payload.venueId = fields.venueId.value;
    if (!payload.venueId) {
      showStatus('page-status', 'Pick the venue this event runs at.', 'error');
      return;
    }

    await submitWith(button, 'Creating…', async () => {
      const result = await admin.createEvent(payload);
      showStatus(
        'page-status',
        `Created "${result.event.title}" with ${result.seatsGenerated} bookable seats.`,
        'success',
      );
      resetForm();
      await load();
    });
  } catch (error) {
    reportError(error);
  }
}

async function cancelEvent(button) {
  // Soft delete: bookings reference the event row, so this sets status to CANCELLED
  // rather than deleting. Say so, because "Cancel" could be read as "delete".
  if (
    !window.confirm(
      'Cancel this event? It stays in the records and existing bookings keep resolving, ' +
        'but it disappears from the public listing.',
    )
  ) {
    return;
  }

  const id = button.dataset.cancel;
  try {
    await submitWith(button, 'Cancelling…', async () => {
      await admin.cancelEvent(id);
      showStatus('page-status', 'That event is now cancelled.', 'success');
      await load();
    });
  } catch (error) {
    reportError(error);
  }
}
