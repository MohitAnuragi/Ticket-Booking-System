/**
 * Login and registration.
 *
 * One entry script for both pages. They differ only in which fields they collect and
 * which call they make, so a shared submit/validation/error path keeps them
 * consistent.
 *
 * The page identifies itself with `<form id="auth-form" data-mode="login|register">`
 * and fields are read through `form.elements`, so this module never hard-codes an id
 * that exists on only one of the two pages.
 */
import { login, register, isSignedIn } from './api.js';
import { mountShell, showStatus, describeError } from './nav.js';
import { nextFromQuery, takeAuthReason } from './session.js';

const form = document.getElementById('auth-form');
const mode = form?.dataset.mode === 'register' ? 'register' : 'login';

init();

function init() {
  mountShell(null);

  // Already signed in? There is nothing to do on these pages.
  if (isSignedIn()) {
    window.location.replace(nextFromQuery());
    return;
  }

  const reason = takeAuthReason();
  if (reason) showStatus('page-status', reason, 'info');

  form?.addEventListener('submit', onSubmit);
}

async function onSubmit(event) {
  event.preventDefault();
  clearErrors();
  showStatus('page-status', '');

  const values = readForm();
  if (!validate(values)) return;

  await submitting(mode === 'register' ? 'Creating account…' : 'Signing in…', async () => {
    if (mode === 'register') {
      await doRegister(values);
    } else {
      await doLogin(values);
    }
  });
}

async function doLogin({ email, password }) {
  try {
    await login({ email, password });
    window.location.replace(nextFromQuery());
  } catch (error) {
    handleError(error, { 401: 'That email and password do not match an account.' });
  }
}

async function doRegister({ name, email, password }) {
  try {
    await register({ name, email, password });
  } catch (error) {
    handleError(error, { 409: 'That email is already registered — sign in instead.' });
    return;
  }

  // Registration returns the account but no token, so sign in straight away rather
  // than making someone type the same credentials twice.
  try {
    await login({ email, password });
    window.location.replace(nextFromQuery());
  } catch {
    showStatus(
      'page-status',
      'Your account was created, but signing in automatically failed. Please sign in.',
      'success',
    );
    window.setTimeout(() => {
      window.location.href = 'login.html';
    }, 2500);
  }
}

// ---------------------------------------------------------------- validation

function readForm() {
  const fields = form.elements;
  return {
    name: fields.name ? fields.name.value.trim() : '',
    email: fields.email ? fields.email.value.trim() : '',
    // Passwords are NOT trimmed: spaces are legitimate characters, and stripping
    // them here would break a later sign-in.
    password: fields.password ? fields.password.value : '',
    confirm: fields.confirm ? fields.confirm.value : '',
  };
}

function validate({ name, email, password, confirm }) {
  if (mode === 'register' && !name) return fieldError('name', 'Enter your name');
  if (!email) return fieldError('email', 'Enter your email address');

  if (mode === 'login') {
    if (!password) return fieldError('password', 'Enter your password');
    return true;
  }

  // Mirrors the server's minimum so the visitor is not told only after a round trip.
  // The server remains the authority.
  if (password.length < 8) return fieldError('password', 'Use at least 8 characters');
  // Confirmation is a client-side courtesy; the API has no such field.
  if (password !== confirm) return fieldError('confirm', 'Those passwords do not match');
  return true;
}

// ---------------------------------------------------------------- helpers

/**
 * Runs an async submit with the button disabled, so a double-click cannot create two
 * accounts or fire two sign-ins.
 */
async function submitting(busyLabel, action) {
  const button = form.querySelector('button[type="submit"]');
  const original = button?.textContent;
  if (button) {
    button.disabled = true;
    button.textContent = busyLabel;
  }
  form.setAttribute('aria-busy', 'true');

  try {
    await action();
  } finally {
    // On success we are navigating away, but restoring state costs nothing and
    // avoids a permanently dead button on any error path.
    if (button) {
      button.disabled = false;
      button.textContent = original;
    }
    form.removeAttribute('aria-busy');
  }
}

/**
 * Puts a validation error next to the field the backend named, and anything else in
 * the page status region. `ApiError.field` uses the same names as the inputs
 * ('name' / 'email' / 'password'), so no mapping table is needed.
 */
function handleError(error, messagesByStatus = {}) {
  const friendly = messagesByStatus[error.status];

  if (error.field && form.elements[error.field]) {
    fieldError(error.field, friendly || error.message);
    return;
  }
  showStatus('page-status', friendly || describeError(error), 'error');
}

/** @returns {boolean} always false, so callers can `return fieldError(...)`. */
function fieldError(fieldName, message) {
  const input = form.elements[fieldName];
  const target = form.querySelector(`[data-error-for="${fieldName}"]`);
  if (target) target.textContent = message;
  if (input) {
    input.setAttribute('aria-invalid', 'true');
    input.focus();
  }
  return false;
}

function clearErrors() {
  for (const element of form.querySelectorAll('.field-error')) element.textContent = '';
  for (const input of form.querySelectorAll('[aria-invalid]')) {
    input.removeAttribute('aria-invalid');
  }
}
