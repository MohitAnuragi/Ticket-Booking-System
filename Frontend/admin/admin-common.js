/**
 * Shared plumbing for the admin pages.
 *
 * These pages live in `Frontend/admin/`, one level down, so every import here uses
 * `../js/`. `nav.js` and `session.js` already handle that depth: the nav rewrites its
 * links, and `?next=` keeps the `admin/` prefix.
 */
import { mountShell, showStatus, describeError } from '../js/nav.js';
import { requireSignIn } from '../js/session.js';

/**
 * Guards an admin page.
 *
 * A signed-in non-admin gets a plain refusal panel rather than a redirect to the
 * sign-in form: they are already signed in, so offering a login would suggest the
 * wrong fix. Anonymous visitors are redirected by [requireSignIn] itself.
 *
 * @param {string} containerId element to replace with the refusal, if refused.
 * @returns {Promise<object|null>} the admin user, or null if the page must not render.
 */
export async function requireAdminPage(containerId) {
  mountShell('admin');

  const user = await requireSignIn({ requireAdmin: true });
  if (!user) return null; // A redirect is already under way.

  if (user.forbidden) {
    const container = document.getElementById(containerId);
    if (container) {
      container.innerHTML = `
        <div class="empty">
          <h2>Administrators only</h2>
          <p>
            You are signed in as ${escapeText(user.name)}, which is not an
            administrator account.
          </p>
          <p><a class="btn btn-primary" href="../index.html">Back to events</a></p>
        </div>`;
    }
    return null;
  }

  return user;
}

/**
 * Converts a `datetime-local` input value to what the API expects.
 *
 * A browser gives "2026-10-01T19:00" (no seconds); the API parses ISO-8601 local
 * date-times and wants "2026-10-01T19:00:00".
 */
export function toApiDateTime(value) {
  if (!value) return '';
  return value.length === 16 ? `${value}:00` : value;
}

/**
 * Runs a form submit with the button disabled.
 * @returns {Promise<boolean>} whether the action completed without throwing.
 */
export async function submitWith(button, busyLabel, action) {
  const original = button?.textContent;
  if (button) {
    button.disabled = true;
    button.textContent = busyLabel;
  }
  try {
    await action();
    return true;
  } catch (error) {
    throw error;
  } finally {
    if (button) {
      button.disabled = false;
      button.textContent = original;
    }
  }
}

/**
 * Reports an API failure, translating the codes the admin endpoints raise into
 * sentences that say what to do next.
 */
export function reportError(error, statusElementId = 'page-status') {
  const friendly = {
    SEATS_ALREADY_EXIST:
      'Some of those seat labels already exist at this venue. Use different rows, or a different seat count.',
    VENUE_HAS_NO_SEATS:
      'That venue has no seat layout yet, so an event there would have nothing to sell. Generate seats for it first.',
    EVENT_ALREADY_STARTED: 'That event has already started.',
    HOLD_EXPIRED: 'That hold had already lapsed, so there was nothing to cancel.',
  }[error?.code];

  showStatus(statusElementId, friendly || describeError(error), 'error');
}

/** Escapes text for insertion into markup. Mirrors format.js, kept local to avoid
 *  importing the whole module into every admin page. */
function escapeText(value) {
  return String(value ?? '')
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;');
}

export { showStatus, describeError };
