# secrets/

Local secrets for the backend. **Nothing in this directory is committed** — the
whole folder is ignored by `.gitignore` (`secrets/`).

## Google Earth Engine service account

Drop the downloaded service-account key here and point `backend/.env` at it:

```
GEE_KEY_FILE=backend/secrets/<project>-<keyid>.json
GEE_ENABLED=true
```

The file is named `<project>-<keyid>.json` when Google exports it (e.g.
`agriconnect-512210-5edfa4162a04.json`). That name does **not** match the older
`service-account*.json` ignore rule, so `.gitignore` also carries a
`*-<12 hex chars>.json` pattern — a key is protected wherever it is dropped,
not only in this folder.

### Handling rules

- Never paste the contents into a chat, an issue, a commit or a screenshot.
  The `private_key` is the credential; publishing it is equivalent to handing
  over the Earth Engine project.
- Unlike an API key, a service-account key **cannot be rotated from a console
  toggle**. To revoke one, delete the key in
  Cloud Console → IAM & Admin → Service Accounts → (account) → Keys.
- On a server, use the platform's secret mechanism instead of this folder —
  on Render, a **Secret File** mounted at `/etc/secrets/`, with
  `GEE_KEY_FILE=/etc/secrets/<name>.json`.
- Production refuses to start when `GEE_ENABLED=true` but the key fails to
  load, rather than silently serving simulated data as if it were real. See
  `docs/07-EXTERNAL-CREDENTIALS.md` §2.1.
