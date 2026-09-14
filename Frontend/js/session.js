/**
 * Route guards and post-sign-in redirects.
 *
 * Separate from nav.js, which is about rendering: this module is about deciding
 * whether the current page may be shown at all. Tasks 17-19 (seat map, booking,
 * admin) all depend on it.
 */
import { fetchMe, isAdmin, isSignedIn } from './api.js';

/**
 * Where to send a visitor after signing in.
 *
 * Only same-origin RELATIVE page paths are accepted. Echoing an arbitrary `next`
 * into a redirect is the classic open-redirect bug: a link like
 * `login.html?next=https://evil.example/login` would send a user who correctly
 * signed in to an attacker's copy of the site. The allowlist below cannot express
 * an absolute URL, a protocol-relative `//host` or a `..` traversal.
 */
export function safeNextPath(raw, fallback = 'index.html') {
  if (!raw) return fallback;

  const value = String(raw).trim();
  if (!value || value.includes('..') || value.startsWith('/') || value.startsWith('\\')) {
    return fallback;
  }
  // A colon anywhere would allow "javascript:" or "https:".
  if (value.includes(':')) return fallback;

  // Relative path to a .html page, optionally with a query string.
  if (!/^[A-Za-z0-9_\-./]+\.html(\?[A-Za-z0-9_\-.=&%+]*)?$/.test(value)) return fallback;

  return value;
}

/** The `next` parameter of the current URL, validated. */
export function nextFromQuery(fallback = 'index.html') {
  const params = new URLSearchParams(window.location.search);
  return safeNextPath(params.get('next'), fallback);
}

/**
 * The current page (file plus query), for handing to `login.html?next=`.
 *
 * Keeps the `admin/` prefix for nested pages. Without it, signing in from
 * `admin/manage-events.html` would send the admin to a root-level
 * `manage-events.html` that does not exist.
 */
export function currentPagePath() {
  const path = window.location.pathname;
  const file = path.split('/').pop() || 'index.html';
  const prefix = path.includes('/admin/') ? 'admin/' : '';
  return prefix + file + window.location.search;
}

/** Sends the visitor to the sign-in page, remembering where they were headed. */
export function goToLogin(reason) {
  const target = `login.html?next=${encodeURIComponent(currentPagePath())}`;
  const prefix = window.location.pathname.includes('/admin/') ? '../' : '';
  if (reason) sessionStorage.setItem('tb.authReason', reason);
  window.location.replace(prefix + target);
}

/**
 * Guards a page that needs a signed-in user.
 *
 * Checks the token with the server rather than trusting the cached user, so a token
 * the backend has since rejected is discovered on load instead of when the visitor
 * tries to act on something.
 *
 * @param {object} [options]
 * @param {boolean} [options.requireAdmin] also require role ADMIN.
 * @returns {Promise<object|null>} the user, or null if a redirect was issued.
 */
export async function requireSignIn({ requireAdmin = false } = {}) {
  if (!isSignedIn()) {
    goToLogin('Please sign in to continue.');
    return null;
  }

  let user;
  try {
    user = await fetchMe();
  } catch (error) {
    // 401 means the token is no longer good; api.js has already cleared it.
    if (error.status === 401) {
      goToLogin('Your session has expired. Please sign in again.');
      return null;
    }
    throw error;
  }

  if (requireAdmin && user.role !== 'ADMIN') {
    // Deliberately not a redirect to login: the visitor IS signed in, they are just
    // not allowed. Sending them to a sign-in form would suggest the wrong fix.
    return { ...user, forbidden: true };
  }

  return user;
}

/** A one-shot message set by [goToLogin], shown once on the login page. */
export function takeAuthReason() {
  const reason = sessionStorage.getItem('tb.authReason');
  if (reason) sessionStorage.removeItem('tb.authReason');
  return reason;
}

export { isAdmin, isSignedIn };
