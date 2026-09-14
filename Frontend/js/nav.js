/**
 * The shared page shell: header, navigation, footer.
 *
 * Rendered from JS rather than copy-pasted into six HTML files, so adding a nav item
 * or changing the signed-in state is a one-line change instead of six edits that can
 * drift apart. Each page calls `mountShell('events')` with its own key.
 */
import { getCurrentUser, isAdmin, isSignedIn, logout } from './api.js';
import { escapeHtml } from './format.js';

const NAV_ITEMS = [
  { key: 'events', href: 'index.html', label: 'Events' },
  { key: 'bookings', href: 'my-bookings.html', label: 'My bookings', signedInOnly: true },
  { key: 'admin', href: 'admin/dashboard.html', label: 'Admin', adminOnly: true },
];

/**
 * Renders the header into `#site-header` and the footer into `#site-footer`.
 * @param {string} activeKey which nav item to mark as current.
 */
export function mountShell(activeKey) {
  const header = document.getElementById('site-header');
  if (header) header.innerHTML = buildHeader(activeKey);

  const footer = document.getElementById('site-footer');
  if (footer) footer.innerHTML = buildFooter();

  const signOut = document.getElementById('nav-signout');
  if (signOut) {
    signOut.addEventListener('click', (event) => {
      event.preventDefault();
      logout();
      // Full navigation rather than a re-render: it guarantees no page keeps stale
      // signed-in state in a variable somewhere.
      window.location.href = resolve('index.html');
    });
  }

  const toggle = document.getElementById('nav-toggle');
  const links = document.getElementById('nav-links');
  if (toggle && links) {
    toggle.addEventListener('click', () => {
      const open = links.classList.toggle('is-open');
      toggle.setAttribute('aria-expanded', String(open));
    });
  }
}

function buildHeader(activeKey) {
  const user = getCurrentUser();
  const signedIn = isSignedIn();

  const items = NAV_ITEMS.filter((item) => {
    if (item.adminOnly) return isAdmin();
    if (item.signedInOnly) return signedIn;
    return true;
  })
    .map((item) => {
      const current = item.key === activeKey;
      return `<a class="nav-link${current ? ' is-current' : ''}" href="${resolve(item.href)}"${
        current ? ' aria-current="page"' : ''
      }>${escapeHtml(item.label)}</a>`;
    })
    .join('');

  const account = signedIn
    ? `<span class="nav-user" title="${escapeHtml(user?.email ?? '')}">${escapeHtml(
        user?.name ?? 'Account',
      )}${user?.role === 'ADMIN' ? ' <span class="badge badge-admin">Admin</span>' : ''}</span>
       <a class="btn btn-ghost btn-sm" href="#" id="nav-signout">Sign out</a>`
    : `<a class="btn btn-ghost btn-sm" href="${resolve('login.html')}">Sign in</a>
       <a class="btn btn-primary btn-sm" href="${resolve('register.html')}">Register</a>`;

  return `
    <div class="shell-bar">
      <a class="brand" href="${resolve('index.html')}">
        <span class="brand-mark" aria-hidden="true">TB</span>
        <span>Ticket Booking</span>
      </a>

      <button class="nav-toggle" id="nav-toggle" type="button"
              aria-expanded="false" aria-controls="nav-links">
        <span class="sr-only">Toggle navigation</span>
        <span class="nav-toggle-bars" aria-hidden="true"></span>
      </button>

      <nav class="nav-links" id="nav-links" aria-label="Main">
        ${items}
        <span class="nav-account">${account}</span>
      </nav>
    </div>`;
}

function buildFooter() {
  return `<p>Ticket Booking System — Kotlin/Ktor API, Supabase PostgreSQL, plain HTML/CSS/JS frontend.</p>`;
}

/**
 * Fixes links for pages nested one level down (admin/*.html).
 *
 * The admin pages live in a subfolder, so a shared nav cannot use bare relative
 * paths. Detecting the depth here keeps every page's markup identical.
 */
function resolve(href) {
  const nested = window.location.pathname.includes('/admin/');
  if (!nested) return href;
  if (href.startsWith('admin/')) return href.slice('admin/'.length);
  return `../${href}`;
}

/**
 * Shows a message in a page's status region.
 * @param {string} elementId target element.
 * @param {'info'|'error'|'success'} tone
 */
export function showStatus(elementId, message, tone = 'info') {
  const target = document.getElementById(elementId);
  if (!target) return;
  if (!message) {
    target.hidden = true;
    target.textContent = '';
    return;
  }
  target.hidden = false;
  target.className = `status status-${tone}`;
  target.textContent = message;
}

/** Renders an ApiError in a way that names the field when the backend named one. */
export function describeError(error) {
  if (!error) return 'Something went wrong.';
  if (error.field) return `${error.message} (${error.field})`;
  return error.message || 'Something went wrong.';
}
