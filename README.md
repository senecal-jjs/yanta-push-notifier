# Yantagram push service

Wakes Yantagram with a silent push when an event arrives for it on the strfry relay.
It never sees message contents or identities, only "this push token goes with these
tags". The design is in [push-notification-service.md](push-notification-service.md).

## Layout

| Path | What it does |
|---|---|
| `Application.kt` | Wiring, Ktor plugins (body limit, rate limit, status pages), lifecycle |
| `Config.kt` | Environment → typed config; fails fast listing every missing variable |
| `api/` | `PUT`/`DELETE /push/register`, `GET /health`, validation |
| `registry/` | In-memory tag ↔ token index, written through to SQLite; relay cursor |
| `relay/` | strfry subscription, reconnects with backoff, dedup, cursor |
| `push/` | Matcher, Coalescer (1 push per token per 30 s), bounded send queue, APNs and FCM senders |
| `jobs/Expiry.kt` | Deletes registrations not refreshed for 14 days |

## Running the tests

```bash
./gradlew test
```

The strfry integration test (`StrfryIntegrationTest`) starts `dockurr/strfry` with
Testcontainers and is skipped when Docker isn't reachable. With OrbStack, point
Testcontainers at its socket:

```bash
DOCKER_HOST=unix://$HOME/.orbstack/run/docker.sock ./gradlew test --rerun
```

## Configuration

See the table in the plan. Secrets (`APNS_KEY_P8`, `APNS_KEY_ID`, `APNS_TEAM_ID`,
`FCM_PROJECT_ID`, `FCM_CLIENT_EMAIL`, `FIREBASE_PRIVATE_KEY`) come from Fly secrets,
never from files in the image. PEM values may be multi-line or use literal `\n`.
Optional extras: `METRICS_PORT` (default 9091) and `MAX_REGISTRATIONS` (default 200000).

## Deploying

```bash
fly volumes create push_data --region iad --size 1
fly secrets set APNS_KEY_ID=… APNS_TEAM_ID=… FCM_PROJECT_ID=… FCM_CLIENT_EMAIL=… \
  APNS_KEY_P8="$(op read …)" FIREBASE_PRIVATE_KEY="$(op read …)"
fly deploy
```

Then set `RELAY_PUSH_URL` in the app's environment. Metrics are served on port 9091,
which has no public service; Fly scrapes it through the `[metrics]` section.

## Alerts

The service logs a line starting with `ALERT` when the registration cap is reached or
the relay has been disconnected for over 5 minutes. For dashboards, alert on
`push_relay_connected == 0`, `push_registration_cap_reached_total` increasing, and
`push_failed_total / (push_sent_total + push_failed_total) > 0.05`.
