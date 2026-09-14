/**
 * The only module that talks to the backend.
 *
 * Everything else imports named functions from here, so no page ever builds a URL
 * or reads the token itself. That is what keeps the auth header, the error shape and
 * the base URL in one place instead of scattered across six pages.
 */

/** Point this at your running backend. No build step, so it lives in the source. */
export const API_BASE_URL = 'http://localhost:8080/api';

/**
 * The JWT lives in sessionStorage, not localStorage (plan section 7).
 *
 * sessionStorage is scoped to the tab and cleared when it closes, so a token cannot
 * outlive the browsing session on a shared machine. Neither option survives XSS, but
 * the narrower lifetime is a real reduction in exposure for no extra work.
 */
const TOKEN_KEY = 'tb.token';
const USER_KEY = 'tb.user';

/**
 * A failed request, carrying the backend's error envelope
 * (`{ error, message, field, unavailableSeats }`) so callers can branch on a code
 * instead of matching on message text.
 */
export class ApiError extends Error {
  constructor(status, payload) {
    super(payload?.message || `Request failed with status ${status}`);
    this.name = 'ApiError';
    this.status = status;
    /** Backend error code, e.g. 'SEATS_UNAVAILABLE'. */
    this.code = payload?.error || 'UNKNOWN_ERROR';
    /** Present on validation errors: which field was rejected. */
    this.field = payload?.field ?? null;
    /** Present on SEATS_UNAVAILABLE: the eventSeatIds that were lost. */
    this.unavailableSeats = payload?.unavailableSeats ?? null;
  }

  get isAuthError() {
    return this.status === 401;
  }
}

// ---------------------------------------------------------------- session

export function getToken() {
  return sessionStorage.getItem(TOKEN_KEY);
}

export function isSignedIn() {
  return Boolean(getToken());
}

/** The signed-in user, from the cache written at login. Null when signed out. */
export function getCurrentUser() {
  const raw = sessionStorage.getItem(USER_KEY);
  if (!raw) return null;
  try {
    return JSON.parse(raw);
  } catch {
    // A corrupt entry should log the user out, not break every page that reads it.
    clearSession();
    return null;
  }
}

export function isAdmin() {
  return getCurrentUser()?.role === 'ADMIN';
}

function saveSession(token, user) {
  sessionStorage.setItem(TOKEN_KEY, token);
  sessionStorage.setItem(USER_KEY, JSON.stringify(user));
}

export function clearSession() {
  sessionStorage.removeItem(TOKEN_KEY);
  sessionStorage.removeItem(USER_KEY);
}

// ---------------------------------------------------------------- core

/**
 * Performs a request and unwraps the response.
 *
 * @param {string} method HTTP method.
 * @param {string} path Path below the API root, e.g. '/events'.
 * @param {object} [options]
 * @param {object} [options.body] JSON body.
 * @param {object} [options.query] Query parameters; null/undefined/'' are dropped.
 * @param {boolean} [options.auth] Attach the bearer token.
 * @throws {ApiError} on any non-2xx response.
 */
async function request(method, path, { body, query, auth = false } = {}) {
  const url = new URL(API_BASE_URL + path);
  if (query) {
    for (const [key, value] of Object.entries(query)) {
      // Blank values are omitted rather than sent empty: the backend treats an
      // empty filter as absent, but not sending it at all is unambiguous.
      if (value !== null && value !== undefined && value !== '') {
        url.searchParams.set(key, value);
      }
    }
  }

  const headers = {};
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (auth) {
    const token = getToken();
    if (!token) {
      // Fail here rather than sending an anonymous request and reporting the 401
      // as though the server rejected the credentials.
      throw new ApiError(401, { error: 'UNAUTHORIZED', message: 'Please sign in to continue' });
    }
    headers.Authorization = `Bearer ${token}`;
  }

  let response;
  try {
    response = await fetch(url, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch (cause) {
    // fetch only rejects on network-level failure, which almost always means the
    // API is not running or CORS blocked the call.
    throw new ApiError(0, {
      error: 'NETWORK_ERROR',
      message: `Could not reach the server at ${API_BASE_URL}. Is the backend running?`,
    });
  }

  if (response.status === 204) return null;

  const text = await response.text();
  let payload = null;
  if (text) {
    try {
      payload = JSON.parse(text);
    } catch {
      payload = null;
    }
  }

  if (!response.ok) {
    const error = new ApiError(response.status, payload);
    // An expired or revoked token must not leave a stale session behind, or every
    // subsequent page would render as signed-in and fail.
    if (error.status === 401 && auth) clearSession();
    throw error;
  }

  return payload;
}

// ---------------------------------------------------------------- auth

export async function register({ name, email, password }) {
  return request('POST', '/auth/register', { body: { name, email, password } });
}

/** Signs in and stores the session. */
export async function login({ email, password }) {
  const auth = await request('POST', '/auth/login', { body: { email, password } });
  saveSession(auth.token, auth.user);
  return auth;
}

/**
 * Re-reads the account from the server, which is how a page confirms a stored token
 * is still valid rather than trusting the cached user.
 */
export async function fetchMe() {
  const user = await request('GET', '/auth/me', { auth: true });
  sessionStorage.setItem(USER_KEY, JSON.stringify(user));
  return user;
}

/** Local only — the backend issues stateless JWTs, so there is nothing to revoke. */
export function logout() {
  clearSession();
}

// ---------------------------------------------------------------- events

export async function listEvents({ search, city, category, date, upcomingOnly } = {}) {
  return request('GET', '/events', {
    query: { search, city, category, date, upcomingOnly: upcomingOnly ? 'true' : '' },
  });
}

/** Distinct cities and categories, for the filter dropdowns. */
export async function listEventFilters() {
  return request('GET', '/events/filters');
}

export async function getEvent(eventId) {
  return request('GET', `/events/${encodeURIComponent(eventId)}`);
}

export async function getSeatMap(eventId) {
  return request('GET', `/events/${encodeURIComponent(eventId)}/seats`);
}

// ---------------------------------------------------------------- bookings

/** Holds seats for 24h, creating a PENDING booking. */
export async function holdSeats(eventId, eventSeatIds) {
  return request('POST', `/events/${encodeURIComponent(eventId)}/hold`, {
    body: { eventSeatIds },
    auth: true,
  });
}

export async function confirmBooking(bookingId) {
  return request('POST', `/bookings/${encodeURIComponent(bookingId)}/confirm`, { auth: true });
}

export async function listMyBookings() {
  return request('GET', '/bookings/me', { auth: true });
}

export async function getBooking(bookingId) {
  return request('GET', `/bookings/${encodeURIComponent(bookingId)}`, { auth: true });
}

export async function cancelBooking(bookingId) {
  return request('DELETE', `/bookings/${encodeURIComponent(bookingId)}`, { auth: true });
}

// ---------------------------------------------------------------- admin

export const admin = {
  listVenues: () => request('GET', '/admin/venues', { auth: true }),
  createVenue: (venue) => request('POST', '/admin/venues', { body: venue, auth: true }),
  getVenue: (id) => request('GET', `/admin/venues/${encodeURIComponent(id)}`, { auth: true }),
  createSeatLayout: (venueId, layout) =>
    request('POST', `/admin/venues/${encodeURIComponent(venueId)}/seats`, {
      body: layout,
      auth: true,
    }),

  listEvents: (filters = {}) => request('GET', '/admin/events', { query: filters, auth: true }),
  getEvent: (id) => request('GET', `/admin/events/${encodeURIComponent(id)}`, { auth: true }),
  createEvent: (event) => request('POST', '/admin/events', { body: event, auth: true }),
  updateEvent: (id, patch) =>
    request('PUT', `/admin/events/${encodeURIComponent(id)}`, { body: patch, auth: true }),
  cancelEvent: (id) => request('DELETE', `/admin/events/${encodeURIComponent(id)}`, { auth: true }),

  listBookings: (filters = {}) => request('GET', '/admin/bookings', { query: filters, auth: true }),
  getBooking: (id) => request('GET', `/admin/bookings/${encodeURIComponent(id)}`, { auth: true }),
  cancelBooking: (id) =>
    request('DELETE', `/admin/bookings/${encodeURIComponent(id)}`, { auth: true }),
};

export async function checkHealth() {
  return request('GET', '/health');
}
