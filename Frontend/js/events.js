/**
 * Event listing: search, filter, render.
 *
 * Filters live in the URL query string, so a filtered view is shareable and the
 * back button behaves. That also makes the page's state readable in one glance
 * during a demo instead of hiding in JS variables.
 */
import { listEvents, listEventFilters } from './api.js';
import { mountShell, showStatus, describeError } from './nav.js';
import { escapeHtml, formatEventTime, formatMoney } from './format.js';

const SEARCH_DEBOUNCE_MS = 300;

const form = document.getElementById('filter-form');
const results = document.getElementById('event-results');
const countLabel = document.getElementById('result-count');

let searchTimer = null;
/** Guards against an older, slower response overwriting a newer one. */
let requestSequence = 0;

init();

async function init() {
  mountShell('events');
  applyUrlToForm();

  form.addEventListener('submit', (event) => {
    event.preventDefault();
    runSearch();
  });

  // Typing searches as you go; the debounce stops one request per keystroke.
  form.querySelector('#filter-search').addEventListener('input', () => {
    clearTimeout(searchTimer);
    searchTimer = setTimeout(runSearch, SEARCH_DEBOUNCE_MS);
  });

  for (const id of ['filter-city', 'filter-category', 'filter-date', 'filter-upcoming']) {
    form.querySelector(`#${id}`).addEventListener('change', runSearch);
  }

  form.addEventListener('reset', () => {
    // The reset happens after this handler, so defer reading the cleared values.
    setTimeout(() => {
      form.querySelector('#filter-upcoming').checked = true;
      runSearch();
    }, 0);
  });

  window.addEventListener('popstate', () => {
    applyUrlToForm();
    runSearch({ pushUrl: false });
  });

  // Dropdowns are populated from real data rather than hard-coded, so they can
  // never offer a city with no events. Failure here is not fatal: the listing
  // still works with free-text search.
  loadFilterOptions();
  runSearch({ pushUrl: false });
}

async function loadFilterOptions() {
  try {
    const { cities, categories } = await listEventFilters();
    fillSelect('filter-city', cities, 'All cities');
    fillSelect('filter-category', categories, 'All categories');
    applyUrlToForm(); // Re-apply, now that the options exist to be selected.
  } catch (error) {
    console.warn('Could not load filter options', error);
  }
}

function fillSelect(id, values, allLabel) {
  const select = document.getElementById(id);
  const current = select.value;
  select.innerHTML =
    `<option value="">${escapeHtml(allLabel)}</option>` +
    (values ?? [])
      .map((value) => `<option value="${escapeHtml(value)}">${escapeHtml(value)}</option>`)
      .join('');
  if (current) select.value = current;
}

function readForm() {
  return {
    search: form.querySelector('#filter-search').value.trim(),
    city: form.querySelector('#filter-city').value,
    category: form.querySelector('#filter-category').value,
    date: form.querySelector('#filter-date').value,
    upcomingOnly: form.querySelector('#filter-upcoming').checked,
  };
}

function applyUrlToForm() {
  const params = new URLSearchParams(window.location.search);
  form.querySelector('#filter-search').value = params.get('search') ?? '';
  form.querySelector('#filter-city').value = params.get('city') ?? '';
  form.querySelector('#filter-category').value = params.get('category') ?? '';
  form.querySelector('#filter-date').value = params.get('date') ?? '';
  form.querySelector('#filter-upcoming').checked = params.get('upcomingOnly') !== 'false';
}

function writeUrl(filters) {
  const params = new URLSearchParams();
  if (filters.search) params.set('search', filters.search);
  if (filters.city) params.set('city', filters.city);
  if (filters.category) params.set('category', filters.category);
  if (filters.date) params.set('date', filters.date);
  if (!filters.upcomingOnly) params.set('upcomingOnly', 'false');

  const query = params.toString();
  const url = query ? `?${query}` : window.location.pathname;
  window.history.pushState(null, '', url);
}

async function runSearch({ pushUrl = true } = {}) {
  const filters = readForm();
  if (pushUrl) writeUrl(filters);

  const sequence = ++requestSequence;
  showStatus('page-status', '');
  renderSkeletons();
  countLabel.textContent = 'Loading events…';

  try {
    const events = await listEvents(filters);
    if (sequence !== requestSequence) return; // A newer search already answered.
    renderEvents(events ?? []);
  } catch (error) {
    if (sequence !== requestSequence) return;
    results.innerHTML = '';
    countLabel.textContent = '';
    showStatus('page-status', describeError(error), 'error');
  }
}

function renderSkeletons() {
  results.className = 'skeleton-grid';
  results.innerHTML = '<div class="skeleton"></div>'.repeat(6);
}

function renderEvents(events) {
  results.className = 'event-grid';

  if (events.length === 0) {
    countLabel.textContent = '';
    results.innerHTML = `
      <div class="empty" style="grid-column: 1 / -1;">
        <h2>No events match those filters</h2>
        <p>Try clearing the date or searching for something broader.</p>
      </div>`;
    return;
  }

  countLabel.textContent = `${events.length} event${events.length === 1 ? '' : 's'}`;
  results.innerHTML = events.map(eventCard).join('');
}

function eventCard(event) {
  const href = `event.html?id=${encodeURIComponent(event.id)}`;
  const poster = event.posterUrl
    ? `<img src="${escapeHtml(event.posterUrl)}" alt="" loading="lazy" />`
    : 'No poster';

  return `
    <article class="event-card">
      <a class="event-card-poster" href="${href}" aria-hidden="true" tabindex="-1">${poster}</a>
      <div class="event-card-body">
        <h2 class="event-card-title">
          <a href="${href}">${escapeHtml(event.title)}</a>
        </h2>
        <p class="event-card-meta">${formatEventTime(event.startTime)}</p>
        <p class="event-card-meta">
          ${escapeHtml(event.venueName)} · ${escapeHtml(event.city)}
        </p>
        <div class="event-card-foot">
          <span class="price">${formatMoney(event.basePrice)} <small>onwards</small></span>
          ${
            event.category
              ? `<span class="badge">${escapeHtml(event.category)}</span>`
              : ''
          }
        </div>
      </div>
    </article>`;
}
