/**
 * Display formatting. No business logic and, deliberately, no arithmetic on money.
 */

/**
 * Formats a money string from the API for display: "1500.00" -> "₹1,500.00".
 *
 * Works on the STRING, never via parseFloat. The backend sends decimal strings
 * precisely so that no float rounding can happen in transit; converting to a number
 * here to add thousands separators would throw that away for no reason.
 */
export function formatMoney(amount) {
  if (amount === null || amount === undefined || amount === '') return '—';

  const text = String(amount);
  const negative = text.startsWith('-');
  const unsigned = negative ? text.slice(1) : text;
  const [whole, fraction = '00'] = unsigned.split('.');

  const grouped = whole.replace(/\B(?=(\d{3})+(?!\d))/g, ',');
  return `${negative ? '-' : ''}₹${grouped}.${fraction.padEnd(2, '0').slice(0, 2)}`;
}

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

/**
 * Adds decimal money strings exactly and returns a decimal string ("3750.00").
 *
 * Works in integer minor units (paise), never in floating point: `0.1 + 0.2` is
 * 0.30000000000000004, and a seat-selection total that is a paisa out would then
 * disagree with the amount the server charges. Integers up to 2^53 are exact in JS,
 * which is many orders of magnitude more than any booking total.
 *
 * The server's `totalAmount` remains the authority — this is only for the live total
 * while the visitor is still choosing.
 */
export function sumMoney(amounts) {
  let minorUnits = 0;
  for (const amount of amounts ?? []) {
    minorUnits += toMinorUnits(amount);
  }
  const whole = Math.trunc(minorUnits / 100);
  const fraction = Math.abs(minorUnits % 100);
  return `${whole}.${String(fraction).padStart(2, '0')}`;
}

/** "2250.00" -> 225000. Tolerates a missing or short fractional part. */
function toMinorUnits(amount) {
  const text = String(amount ?? '0').trim();
  if (!/^-?\d+(\.\d+)?$/.test(text)) return 0;

  const negative = text.startsWith('-');
  const [whole, fraction = ''] = (negative ? text.slice(1) : text).split('.');
  const paise = Number(whole) * 100 + Number(fraction.padEnd(2, '0').slice(0, 2));
  return negative ? -paise : paise;
}

/**
 * Formats an event time such as "2026-10-01T19:00:00" as "1 Oct 2026, 7:00 PM".
 *
 * Parsed literally, NOT through `new Date(...)`. Event times are zone-less in the
 * schema and mean wall-clock time at the venue; `new Date` would read them as the
 * viewer's local time and then re-render them in that zone, so a 7 PM show could
 * display as 12:30 AM for someone in another timezone. A ticket must show the time
 * printed on it.
 */
export function formatEventTime(iso) {
  const parts = parseNaiveDateTime(iso);
  if (!parts) return '—';
  const { year, month, day, hour, minute } = parts;
  return `${day} ${MONTHS[month - 1]} ${year}, ${formatClock(hour, minute)}`;
}

/** Just the date part: "1 Oct 2026". */
export function formatEventDate(iso) {
  const parts = parseNaiveDateTime(iso);
  if (!parts) return '—';
  return `${parts.day} ${MONTHS[parts.month - 1]} ${parts.year}`;
}

/**
 * Formats a true instant (one that carries an offset, like `expiresAt`) in the
 * viewer's local timezone — which is correct here, because a hold deadline is a
 * moment in time rather than a wall-clock label.
 */
export function formatInstant(iso) {
  if (!iso) return '—';
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return '—';
  return `${date.getDate()} ${MONTHS[date.getMonth()]} ${date.getFullYear()}, ${formatClock(
    date.getHours(),
    date.getMinutes(),
  )}`;
}

/**
 * "23h 12m left", or null once the instant has passed.
 * Used for hold countdowns; null is the caller's cue to render "expired".
 */
export function timeRemaining(iso, now = new Date()) {
  if (!iso) return null;
  const target = new Date(iso);
  if (Number.isNaN(target.getTime())) return null;

  const ms = target.getTime() - now.getTime();
  if (ms <= 0) return null;

  const minutes = Math.floor(ms / 60000);
  const hours = Math.floor(minutes / 60);
  if (hours >= 24) {
    const days = Math.floor(hours / 24);
    return `${days}d ${hours % 24}h left`;
  }
  if (hours > 0) return `${hours}h ${minutes % 60}m left`;
  if (minutes > 0) return `${minutes}m left`;
  return 'less than a minute left';
}

/** True once a zone-less event time has passed, compared in the viewer's clock. */
export function hasEventStarted(iso, now = new Date()) {
  const parts = parseNaiveDateTime(iso);
  if (!parts) return false;
  const asLocal = new Date(parts.year, parts.month - 1, parts.day, parts.hour, parts.minute);
  return asLocal.getTime() <= now.getTime();
}

/** Escapes text before it goes anywhere near innerHTML. */
export function escapeHtml(value) {
  return String(value ?? '')
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#39;');
}

function parseNaiveDateTime(iso) {
  if (!iso) return null;
  const match = /^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2})/.exec(String(iso));
  if (!match) return null;
  return {
    year: Number(match[1]),
    month: Number(match[2]),
    day: Number(match[3]),
    hour: Number(match[4]),
    minute: Number(match[5]),
  };
}

function formatClock(hour, minute) {
  const suffix = hour < 12 ? 'AM' : 'PM';
  const display = hour % 12 === 0 ? 12 : hour % 12;
  return `${display}:${String(minute).padStart(2, '0')} ${suffix}`;
}
