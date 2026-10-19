# AgriConnect Privacy Policy

**Last updated:** [DATE — set before publishing]
**Applies to:** the AgriConnect Android application and the AgriConnect API service.

> **Draft.** This policy is an engineering draft pending legal review under the Botswana Data
> Protection Act, 2024. Do not publish until counsel has approved it.

---

## 1. Who we are

AgriConnect is operated by **[ORGANISATION NAME]**, [ADDRESS], Botswana ("we", "us"). For any privacy
question, contact **[privacy@agriconnect.bw]**.

We are the **data controller** for the personal data described below.

---

## 2. What we collect

We collect only what the app needs to work.

| What | Why | Required? |
|------|-----|-----------|
| Full name | To identify you to buyers/farm staff and on your account | Yes |
| Phone number | To sign you in and to send password/verification codes | Yes |
| Email address | Optional alternative sign-in and recovery | No |
| Password | To protect your account (stored only as a one-way hash) | Yes |
| Farm plots and GeoJSON boundaries | To map your farm and calculate satellite crop health | For farmers |
| Harvest, livestock, sales, expense records | To keep your farm records and summaries | For farmers/staff |
| Produce listings and orders | To run the marketplace | For farmers/buyers |
| Notification preferences, avatar | To personalise your experience | No |
| Approximate device/network state | To sync when you have a connection | Automatic |

We do **not** collect: biometric data, facial images, camera or motion-sensor data, advertising
identifiers, or your contacts.

---

## 3. How we use it

- To create and secure your account and sign you in.
- To record and sync your farm and marketplace data.
- To calculate vegetation indices from satellite imagery.
- To notify you about your orders and records.
- To keep the service secure (fraud/abuse prevention, audit logging).

We do **not** sell your data, and we do **not** use it for advertising.

---

## 4. Offline-first and your device

AgriConnect is offline-first: your records are saved on your device immediately and synced to our
servers when you have a connection. Signing out or deleting your account does not discard work that
is still waiting to sync — it is uploaded first, then handled according to your request.

---

## 5. Who we share data with

| Recipient | What | Why |
|-----------|------|-----|
| Google Earth Engine | Farm plot geometry (no name/phone) | To retrieve Sentinel-2 satellite imagery for NDVI |
| SMS/email provider | Your phone number / email | To deliver verification and recovery codes |
| Hosting provider | All data, encrypted in transit | To operate the service |

We may disclose data if required by law.

---

## 6. International transfers

Satellite processing and message delivery may involve servers outside Botswana. Where this happens
we rely on the provider's safeguards and transfer only the minimum data (for NDVI: geometry only,
with no personal identifiers).

---

## 7. How long we keep it

| Data | Retention |
|------|-----------|
| Account details (name, phone, email, avatar, preferences) | Until you delete your account, then anonymised |
| Farm plots, harvests, livestock, satellite history | Until you delete your account, then deleted |
| Sales, expenses, orders, listings | De-identified and kept for **7 years** for financial/tax record-keeping |
| Security audit logs | 24 months |
| Notifications | 12 months |
| Backups | 30 days rolling |

See [`03-DATA-RETENTION-AND-DELETION.md`](03-DATA-RETENTION-AND-DELETION.md) for the full schedule.

---

## 8. Your rights

Under the Botswana Data Protection Act you may:

- **Access** your data — use **Profile → Privacy & data → Export my data**, or contact us.
- **Correct** your data — edit it in the app.
- **Delete** your data — use **Profile → Privacy & data → Delete account**, or the web page at
  [`account-deletion.html`](account-deletion.html).
- **Object / restrict** processing, or **complain** to the Information and Data Protection
  Commission.

Deleting your account signs you out everywhere and permanently removes your farm records and
satellite history. Financial records are retained without your name for 7 years because we are
legally required to keep them.

---

## 9. Security

- Passwords are hashed with bcrypt and never stored in plain text.
- Sign-in tokens are stored only as irreversible hashes on our servers and in encrypted storage on
  your device.
- All traffic uses HTTPS.
- Access is role-based: the farmers who work the land cannot see financial records, and buyers
  cannot see farm operations.

No system is perfectly secure, but we take these measures seriously and review them.

---

## 10. Children

AgriConnect is for adults managing farms and buying produce. It is not directed at children, and we
do not knowingly collect data from children.

---

## 11. Changes

We will update this policy as the service evolves and will tell you about material changes in the
app before they take effect.

---

## 12. Contact

**[ORGANISATION NAME]**
[ADDRESS], Botswana
Email: **[privacy@agriconnect.bw]**
