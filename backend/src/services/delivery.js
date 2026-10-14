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

async function sendViaTwilio(_msg) {
  throw new Error('Twilio provider not configured');
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
      await sendViaTwilio({ destination, body });
      result.delivered = true;
    } else {
      // NONE / OUTBOX — persisted only. Explicitly not delivered.
      result.provider = 'OUTBOX';
      result.delivered = false;
    }
  } catch (err) {
    result.error = err.message;
  }

  await record({ channel, destination, subject, body, provider: result.provider, delivered: result.delivered, error: result.error });

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
