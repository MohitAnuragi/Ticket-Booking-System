/**
 * Venues and their seat layouts.
 *
 * A venue is only useful once it has seats: creating an event at a seatless venue is
 * refused by the API with 409 VENUE_HAS_NO_SEATS, so this page keeps the layout form
 * next to the list and shows the real seat count per venue.
 */
import { admin } from '../js/api.js';
import { escapeHtml } from '../js/format.js';
import { requireAdminPage, reportError, submitWith, showStatus } from './admin-common.js';

const tableHost = document.getElementById('venue-table');
const venueForm = document.getElementById('venue-form');
const layoutForm = document.getElementById('layout-form');
const venueSelect = document.getElementById('layout-venue');

let venues = [];

init();

async function init() {
  const user = await requireAdminPage('venue-table');
  if (!user) return;

  venueForm.addEventListener('submit', onCreateVenue);
  layoutForm.addEventListener('submit', onCreateLayout);
  await load();
}

async function load() {
  tableHost.innerHTML = '<div class="skeleton" style="height:120px"></div>';
  try {
    venues = (await admin.listVenues()) ?? [];
    renderTable();
    renderVenueOptions();
  } catch (error) {
    tableHost.innerHTML = '';
    reportError(error);
  }
}

function renderTable() {
  if (venues.length === 0) {
    tableHost.innerHTML =
      '<div class="empty"><p>No venues yet. Create one on the right, then give it a seat layout.</p></div>';
    return;
  }

  tableHost.innerHTML = `
    <div class="table-scroll">
      <table class="data-table">
        <caption class="sr-only">Venues</caption>
        <thead>
          <tr>
            <th scope="col">Name</th><th scope="col">City</th>
            <th scope="col">Seats</th><th scope="col">Declared capacity</th>
          </tr>
        </thead>
        <tbody>
          ${venues.map(venueRow).join('')}
        </tbody>
      </table>
    </div>`;
}

function venueRow(venue) {
  // Both numbers are shown because total_capacity is advisory: the seats rows are the
  // truth, and a mismatch should be visible rather than hidden.
  const mismatch = Number(venue.seatCount) !== Number(venue.totalCapacity);
  return `
    <tr>
      <td class="wrap">${escapeHtml(venue.name)}${
        venue.address ? `<br /><small>${escapeHtml(venue.address)}</small>` : ''
      }</td>
      <td>${escapeHtml(venue.city)}</td>
      <td>${escapeHtml(String(venue.seatCount))}</td>
      <td>${escapeHtml(String(venue.totalCapacity))}${
        mismatch ? ' <span class="badge badge-draft">differs</span>' : ''
      }</td>
    </tr>`;
}

function renderVenueOptions() {
  const previous = venueSelect.value;
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
  if (previous) venueSelect.value = previous;
}

// ---------------------------------------------------------------- actions

async function onCreateVenue(event) {
  event.preventDefault();
  showStatus('page-status', '');

  const fields = venueForm.elements;
  const capacityRaw = fields.totalCapacity.value.trim();

  const payload = {
    name: fields.name.value.trim(),
    address: fields.address.value.trim() || null,
    city: fields.city.value.trim(),
    // Optional: the API derives it from the seat layout when omitted.
    totalCapacity: capacityRaw ? Number(capacityRaw) : null,
  };

  try {
    await submitWith(venueForm.querySelector('button[type="submit"]'), 'Creating…', async () => {
      const created = await admin.createVenue(payload);
      showStatus('page-status', `Created "${created.name}". Now give it a seat layout.`, 'success');
      venueForm.reset();
      await load();
      venueSelect.value = created.id;
    });
  } catch (error) {
    reportError(error);
  }
}

async function onCreateLayout(event) {
  event.preventDefault();
  showStatus('page-status', '');

  const fields = layoutForm.elements;
  const venueId = fields.venueId.value;
  if (!venueId) {
    showStatus('page-status', 'Pick the venue to add seats to.', 'error');
    return;
  }

  // "A,B,C" or "A B C" — an admin should not have to type JSON.
  const rows = fields.rows.value
    .split(/[\s,]+/)
    .map((row) => row.trim().toUpperCase())
    .filter(Boolean);

  if (rows.length === 0) {
    showStatus('page-status', 'List at least one row label, for example: A, B, C', 'error');
    return;
  }

  const payload = {
    rows,
    seatsPerRow: Number(fields.seatsPerRow.value),
    seatType: fields.seatType.value,
  };
  const multiplier = fields.priceMultiplier.value.trim();
  // Omitted means "use the tier default" (REGULAR 1.00, PREMIUM 1.50, VIP 2.50).
  if (multiplier) payload.priceMultiplier = multiplier;

  try {
    await submitWith(layoutForm.querySelector('button[type="submit"]'), 'Generating…', async () => {
      const result = await admin.createSeatLayout(venueId, payload);
      showStatus(
        'page-status',
        `Added ${result.seatsCreated} seats. This venue now has ${result.totalSeats}.`,
        'success',
      );
      layoutForm.elements.rows.value = '';
      await load();
      venueSelect.value = venueId;
    });
  } catch (error) {
    reportError(error);
  }
}
