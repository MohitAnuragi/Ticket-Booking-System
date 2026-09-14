/**
 * Admin dashboard: what exists right now, and the shortest route to each task.
 */
import { admin } from '../js/api.js';
import { escapeHtml, formatEventTime } from '../js/format.js';
import { requireAdminPage, reportError, showStatus } from './admin-common.js';

const stats = document.getElementById('admin-stats');
const recent = document.getElementById('admin-recent');

init();

async function init() {
  const user = await requireAdminPage('admin-stats');
  if (!user) return;

  document.getElementById('admin-greeting').textContent = `Signed in as ${user.name}`;
  await load();
}

async function load() {
  stats.innerHTML = '<div class="skeleton" style="height:90px"></div>'.repeat(3);

  try {
    // limit=1 because only `total` is wanted here: the count of all bookings without
    // dragging a page of rows across the wire.
    const [venues, events, bookings] = await Promise.all([
      admin.listVenues(),
      admin.listEvents(),
      admin.listBookings({ limit: 1 }),
    ]);

    renderStats(venues ?? [], events ?? [], bookings);
    renderRecent(events ?? []);
  } catch (error) {
    stats.innerHTML = '';
    recent.innerHTML = '';
    reportError(error);
  }
}

function renderStats(venues, events, bookings) {
  const seatTotal = venues.reduce((sum, venue) => sum + Number(venue.seatCount ?? 0), 0);
  const published = events.filter((event) => event.status === 'PUBLISHED').length;
  const drafts = events.filter((event) => event.status === 'DRAFT').length;

  stats.innerHTML = `
    ${statCard('Venues', venues.length, `${seatTotal} seats laid out in total`, 'manage-venues.html')}
    ${statCard('Events', events.length, `${published} published · ${drafts} draft`, 'manage-events.html')}
    ${statCard('Bookings', bookings?.total ?? 0, 'across every event', 'manage-bookings.html')}`;

  showStatus('page-status', '');
}

function statCard(label, value, detail, href) {
  return `
    <a class="stat-card card" href="${href}">
      <p class="stat-label">${escapeHtml(label)}</p>
      <p class="stat-value">${escapeHtml(String(value))}</p>
      <p class="stat-detail">${escapeHtml(detail)}</p>
    </a>`;
}

function renderRecent(events) {
  const upcoming = events
    .filter((event) => event.status !== 'CANCELLED')
    .sort((a, b) => a.startTime.localeCompare(b.startTime))
    .slice(0, 5);

  if (upcoming.length === 0) {
    recent.innerHTML = `
      <div class="empty">
        <p>No events yet.</p>
        <p><a class="btn btn-primary" href="manage-events.html">Create the first one</a></p>
      </div>`;
    return;
  }

  recent.innerHTML = `
    <table class="data-table">
      <caption class="sr-only">Next events</caption>
      <thead>
        <tr><th scope="col">Event</th><th scope="col">Starts</th>
            <th scope="col">Venue</th><th scope="col">Status</th></tr>
      </thead>
      <tbody>
        ${upcoming
          .map(
            (event) => `
          <tr>
            <td>${escapeHtml(event.title)}</td>
            <td>${formatEventTime(event.startTime)}</td>
            <td>${escapeHtml(event.venueName)} · ${escapeHtml(event.city)}</td>
            <td><span class="badge badge-${event.status.toLowerCase()}">${escapeHtml(
              event.status,
            )}</span></td>
          </tr>`,
          )
          .join('')}
      </tbody>
    </table>`;
}
