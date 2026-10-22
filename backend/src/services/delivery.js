import { query } from '../db/index.js';
import { config } from '../config/index.js';
import { maskIdentifier } from '../utils/crypto.js';

/**
 * Delivery abstraction for verification codes and recovery links.
 *
 * IMPORTANT — READ docs/07-EXTERNAL-CREDENTIALS.md.
 * No email/SMS provider is configured in this build. With
 * DELIVERY_PROVIDER=NONE|OUTBOX every message is written to the `message_outbox`
 * table (development-only) and NEVER actually sent. This is deliberate: the
 * brief forbids a fake recovery flow that pretends to send a message. The token
 * lifecycle is real; the transport is not yet wired.
 */

/** Persist every outbound message for audit, regardless of provider. */
async function record({ channel, destination, subject, body, provider, delivered, providerRef, error }) {
  await query(
    `INSERT INTO message_outbox (channel, destination, subject, body, delivered, provider, provider_ref, error)
     VALUES ($1,$2,$3,$4,$5,$6,$7,$8)`,
    [channel, destination, subject ?? null, body, !!delivered, provider, providerRef ?? null, error ?? null],
  );
}

async function sendViaSmtp(_msg) {
  // Deliberately not implemented: enabling requires a real provider + credentials.
  throw new Error('SMTP provider not configured');
}

/**
 * Send an SMS through the Twilio REST API.
 *
 * A plain HTTPS POST rather than the `twilio` SDK: the SDK is a large dependency
 * for one call, and this keeps the credentials handling visible. Twilio
 * authenticates with HTTP Basic (account SID : auth token), and returns 201 with
 * the message SID on success.
 *
 * Every failure is thrown so `deliver()` records it in `message_outbox` with the
 * reason. A code that was never sent must never look sent: the caller (password
 * reset / verification) relies on `delivered` being honest.
 */
async function sendViaTwilio({ destination, body }) {
  const { accountSid, authToken, from } = config.delivery.twilio;
  if (!accountSid || !authToken || !from) {
    throw new Error('Twilio credentials are incomplete (need TWILIO_ACCOUNT_SID, TWILIO_AUTH_TOKEN, TWILIO_FROM)');
  }

  const url = `https://api.twilio.com/2010-04-01/Accounts/${encodeURIComponent(accountSid)}/Messages.json`;
  const form = new URLSearchParams({ To: destination, From: from, Body: body });
  const auth = Buffer.from(`${accountSid}:${authToken}`).toString('base64');

  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 15_000);
  let res;
  try {
    res = await fetch(url, {
      method: 'POST',
      headers: {
        Authorization: `Basic ${auth}`,
        'Content-Type': 'application/x-www-form-urlencoded',
      },
      body: form.toString(),
      signal: controller.signal,
    });
  } catch (err) {
    if (err.name === 'AbortError') throw new Error('Twilio request timed out after 15s');
    throw new Error(`Twilio request failed: ${err.message}`);
  } finally {
    clearTimeout(timer);
  }

  const text = await res.text();
  let parsed = null;
  try {
    parsed = JSON.parse(text);
  } catch {
    // Non-JSON body (an HTML error page, say). Reported below with the status.
  }

  if (!res.ok) {
    // Twilio's own error text is the most useful thing to log; it says whether
    // the number is unverified, the sender is invalid, or the account is out of
    // credit — all problems the operator must see rather than guess at.
    const detail = parsed?.message || text.slice(0, 300) || 'no response body';
    throw new Error(`Twilio rejected the message (HTTP ${res.status}): ${detail}`);
  }

  return { sid: parsed?.sid ?? null, status: parsed?.status ?? null };
}

/**
 * Send a message. Returns { delivered, provider, devCode? }.
 * `devCode` is only populated when EXPOSE_DEV_CODES is enabled in development,
 * so the recovery journey is testable end-to-end without a provider.
 */
export async function deliver({ channel, destination, subject, body, devCode }) {
  const provider = config.delivery.provider;
  const result = { delivered: false, provider };

  try {
    if (provider === 'SMTP' && channel === 'EMAIL') {
      await sendViaSmtp({ destination, subject, body });
      result.delivered = true;
    } else if (provider === 'TWILIO' && channel === 'SMS') {
      const sent = await sendViaTwilio({ destination, body });
      result.delivered = true;
      // Record Twilio's message SID so a sent message can be traced in the
      // Twilio console from its outbox row.
      result.providerRef = sent?.sid ?? null;
    } else {
      // NONE / OUTBOX — persisted only. Explicitly not delivered.
      result.provider = 'OUTBOX';
      result.delivered = false;
    }
  } catch (err) {
    result.error = err.message;
  }

  await record({
    channel,
    destination,
    subject,
    body,
    provider: result.provider,
    delivered: result.delivered,
    providerRef: result.providerRef,
    error: result.error,
  });

  if (config.delivery.exposeDevCodes && devCode) {
    result.devCode = devCode;
  }
  result.to = maskIdentifier(destination);
  return result;
}

export function sendPasswordResetCode({ to, code, expiresMinutes }) {
  const subject = 'AgriConnect password reset code';
  const body = `Your AgriConnect password reset code is ${code}. It expires in ${expiresMinutes} minutes. If you did not request this, ignore this message.`;
  return deliver({ channel: to.includes('@') ? 'EMAIL' : 'SMS', destination: to, subject, body, devCode: code });
}

export function sendVerificationCode({ to, code, expiresMinutes }) {
  const subject = 'AgriConnect verification code';
  const body = `Your AgriConnect verification code is ${code}. It expires in ${expiresMinutes} minutes.`;
  return deliver({ channel: to.includes('@') ? 'EMAIL' : 'SMS', destination: to, subject, body, devCode: code });
}

export default { deliver, sendPasswordResetCode, sendVerificationCode };
