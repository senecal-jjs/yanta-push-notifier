# Plan: Push Notification Service (Kotlin + Ktor)

A small server that wakes Yantagram when something arrives for it on the internet
relay. It watches our strfry relay for events on registered tags and sends the
matching devices a **silent** push. The app then wakes, reconnects to the relay,
catches up, and shows its own local notifications.

The service never sees message contents, identities or contacts. It learns only what
onboarding already discloses: **this push token goes with these tags**.

This is the service described in Phase 5 of [nostr-relay-plan.md](nostr-relay-plan.md).
It lives in its own repository, next to the relay deployment, not in this repo. The
app isn't deployed, so the client and the service ship together with no
compatibility path.

## Decisions

Recommendations; change any before starting.

| Decision | Recommendation | Why |
|---|---|---|
| Push provider | **APNs and FCM directly**, not Expo's push service | Expo's service would be a third party seeing every token and push time. A `FIREBASE_PRIVATE_KEY` is already in the relay's environment. |
| Watching the relay | **One subscription to strfry for every event of our kind**, matched against tags in memory | A filter listing every registered tag would hit strfry's filter-size limits as users grow. Our relay only carries our kind anyway. |
| Push type | **Silent / background only**, with no payload | A push can't say anything useful without leaking metadata. The app decides what to show once it has decrypted the message. |
| Coalescing | **At most one push per token every 30 seconds** | A message arrives as many events (fragments, ACKs). One wake-up catches them all. |
| Storage | **SQLite on a volume**, with the in-memory index as the working copy | One instance and a small data set. It survives restarts without a database server. |
| Authentication | **None** on registration | Registering reveals nothing new, and there's no identity to authenticate. See *Risks* for what that allows. |
| Stack | Kotlin 2.x, JDK 21, Ktor 3 (server: Netty; client: CIO), kotlinx.serialization, Exposed + SQLite JDBC, Pushy (APNs), Google auth library + Ktor client (FCM HTTP v1), Gradle Kotlin DSL | Mature, small, and coroutine-native throughout |

## What it has to match

### The app ([relay-push-service.ts](services/relay-push-service.ts), [use-relay-push.ts](hooks/use-relay-push.ts))

- **When it registers:** while the relay is enabled, the app sends
  `PUT {pushUrl}/push/register` with its current tags. It registers again whenever
  its tag set changes (a new group, a new day), debounced by 2 seconds.
- **When it unregisters:** turning the relay off sends `DELETE /push/register` with
  the token.
- **What changes in the app:** today it sends an Expo token as `{token, tags}`. This
  plan changes it to the native token plus the platform (see *Client changes*).

### The relay ([nostr-relay-plan.md](nostr-relay-plan.md))

- **Events:** Nostr kind **2929** (`AppConfig.relay.eventKind`), each with exactly one
  `["y", <32 hex chars>]` routing tag and a NIP-40 `expiration` tag of at most 7
  days.
- **Tags change every UTC day** (`RELAY_WINDOW_MS`). The app subscribes to, and
  registers, the windows either side of today.
- **strfry write policy:** it already limits kind, tag shape, size and `created_at`,
  so anything the service sees from strfry is well formed.

## API

All bodies are JSON. Nothing that identifies a person appears in a path or query
string, since proxies log those.

### `PUT /push/register`

```json
{ "token": "<APNs device token hex | FCM registration token>",
  "platform": "ios" | "android",
  "tags": ["<32 hex>", "..."],
  "alerts": true,
  "self": "<64 lowercase hex>" }
```

- **`alerts`** (boolean, required): the user has notifications on. If false, every push
  to this token is silent.
- **`self`** (optional): the device's 32-byte sender marker key K. Replaced on every
  registration and deleted with it. Without it, the device is pushed for its own events.

- **Replaces** the token's whole tag set. Saving the same set again also refreshes the
  registration's `updated_at`.
- **Responses:**
  - `204` on success;
  - `400` for a malformed body, a bad token format, an unknown platform, a tag
    that isn't 32 lowercase hex characters, a missing or non-boolean `alerts`, or a
    `self` that isn't 64 lowercase hex characters;
  - `413` for more than 512 tags or a body over 64 KB;
  - `429` when rate-limited.
- **An empty `tags` list** means the same as `DELETE`.

### `DELETE /push/register`

`{ "token": "..." }` → always `204`, whether or not the token was registered. That
way the response doesn't reveal whether a token is registered.

### `GET /health`

`200` with `{ "relay": "connected" | "reconnecting", "registrations": <count> }`.
Used by the platform's health check. The count is the only aggregate it exposes.

## How it works

```
 app ──PUT /push/register──▶ ┌─────────────────────────────┐
                             │ Registry                    │
                             │  tag → {tokens}  (memory)   │◀── SQLite (load at start,
                             │  token → {tags}             │     write-through)
                             └──────────────┬──────────────┘
 strfry ──REQ kind 2929──▶ RelayListener ──▶ Matcher ──▶ Coalescer ──▶ ApnsSender / FcmSender
 (ws, internal network)    (dedup by id)    (y tag →     (≤ 1 per        │
                                             tokens)      token / 30 s)  └─▶ invalid token:
                                                                              remove from Registry
```

### Registry

- Two maps behind one `Mutex`: `tag → MutableSet<TokenKey>` and
  `token → Registration(platform, tags, updatedAt)`.
- **Changes** replace a token's old tags with its new ones in a single atomic step.
  SQLite (Exposed, one table `registrations(token PK, platform, tags TEXT,
  updated_at)`) is written through on every change, and loaded into memory at
  startup.
- **Expiry:** registrations not refreshed for **14 days** are removed by a daily job.
  By then their tags have long stopped matching anything, and keeping them only
  keeps data the service doesn't need.
- **Limits:** at most **200,000** registrations in total. Beyond that new tokens get
  `503` and an alert fires. It's a memory guard, not a capacity plan.

### RelayListener

- Opens a Ktor WebSocket to strfry over the private network (`STRFRY_URL`, e.g.
  `ws://yantagram-relay.internal:7777`) and sends
  `["REQ", "push", {"kinds": [2929], "since": <cursor>}]`.
- **Each `EVENT`:** pull the single `y` tag value and hand it to the Matcher. The
  signature isn't verified; strfry already did that.
- **Dedup:** an LRU of the last 50,000 event IDs, since reconnects replay an overlap.
- **Cursor:** the newest `created_at` seen, minus 60 seconds of overlap. It's kept in
  memory and written to SQLite every minute, so a restart doesn't replay the whole
  7 days. On first start it's `now`.
- **Reconnects** back off exponentially from 1 to 30 seconds, with jitter. `EOSE`
  only marks the end of the backlog; nothing special happens there.

### Matcher and Coalescer

- **Matcher:** `tag → tokens`, from the Registry. An event with no registered tag is
  dropped. Most events will be like that.
- **Coalescer:** for each token, track `lastSentAt` and a `pending` flag.
  - If the token hasn't been pushed in 30 seconds, push it now.
  - Otherwise mark it pending; a ticker sends pending pushes when their 30 seconds
    are up.
  - One wake-up is all a client needs, because it catches up everything from the
    relay itself.
- **Sends** go through a bounded `Channel` (capacity 10,000) to a small pool of
  sender coroutines. When the channel is full, the oldest pending wake-ups are
  dropped and a metric counts them. A missed wake-up costs only delay: the app still
  catches up when it's next opened.

### Sender marker and alerts

Every event the app publishes carries `["s", hex(HMAC-SHA256(K, utf8(content))[0..16])]`.
For each matched token with a K, the Matcher recomputes it and compares in constant
time; the device that sent the event gets no push at all. An event without `s` matches
no device.

Whether an event is a chat message comes from the packet's plaintext header
(`PacketKind`, a port of the app's `packet-kind.ts`, tested against its shared vectors):
a MESSAGE packet, or the first fragment of a fragmented message. A packet with the quiet
flag (`raw[11] & 0x02`) never is: the app sets it on a resend of a message the group was
already alerted for, so retries wake recipients silently instead of alerting again.

| Platform | alerts | Chat message | Push |
|---|---|---|---|
| iOS | true | yes | alert |
| iOS | any | no | silent |
| iOS | false | yes | silent |
| Android | any | any | data message |

**Coalescing:** if any event coalesced into a pending push was a chat message, the
pending push goes out as an alert, never as both. A pending alert goes out
`ALERT_COALESCE_SECONDS` (10) after the previous push rather than the full 30.

### Senders

**APNs** (Pushy, HTTP/2, token-based auth with the `.p8` key):

```
apns-push-type: background
apns-priority: 5
apns-topic: com.yantagram.yantagram
apns-expiration: <now + 1 hour>
apns-collapse-id: wake
{"aps": {"content-available": 1}}
```

- **Production vs sandbox:** both are supported, chosen by `APNS_ENV`. Development
  builds use sandbox tokens, so a dev deployment of the service needs sandbox.
**Alert** (chat messages, iOS with alerts on):

```
apns-push-type: alert
apns-priority: 10
apns-expiration: <now + 24 hours>
(no apns-collapse-id)
{"aps": {"alert": {"title": "Yantagram", "body": "New message"},
         "sound": "default", "mutable-content": 1, "content-available": 1}}
```

The app's Notification Service Extension replaces the title and body.

- **Removing tokens:** a `410 Unregistered` response, or `400 BadDeviceToken`,
  removes the token from the Registry.

**FCM** (HTTP v1 API, `POST /v1/projects/{id}/messages:send`, OAuth2 from the
service account with the Google auth library):

```json
{ "message": { "token": "...",
  "data": { "w": "1" },
  "android": { "priority": "HIGH", "ttl": "3600s", "collapse_key": "wake" } } }
```

- **Removing tokens:** a `404 UNREGISTERED` response, or `400 INVALID_ARGUMENT` about
  the token, removes it.
- **Retries:** `429` and `5xx` are retried with backoff, up to 3 times.

## Privacy rules

These are part of the spec. Code review checks them.

1. **Logs never contain** tokens, tags, sender keys, IP addresses or request bodies. Log events,
   not data: "registration replaced (n tags)" or "APNs 410, token removed".
2. **Metrics are counts only:**
   - registrations;
   - events received and matched;
   - pushes sent, by platform and type (alert or silent);
   - pushes dropped and failed (by platform and reason);
   - devices skipped because they sent the event (by platform);
   - relay reconnects.
3. **No access logs at the proxy** for `/push/*`. Fly's edge doesn't keep request
   bodies, but turn off any request logging that records client IPs.
4. **Nothing is ever shared** between registrations: no linking of tokens, no
   per-IP history beyond the rate limiter's short-lived counters.
5. **What a database snapshot holds:** tokens, platforms, tag sets, timestamps, the
   alerts setting and each token's sender key. With the sender keys, an operator with
   database access can tell which device sent which stored event; that's the cost of
   skipping self-pushes, and the app's relay disclosure says so. Expired rows are
   deleted, not archived.
6. **Apple and Google learn** whether a push is for a chat message (alert) or not
   (silent), and nothing about content, sender or group.

## Abuse limits

- **Rate limit:** Ktor's `RateLimit` plugin allows 30 registrations per minute per
  IP. The counter is kept in memory and expires.
- **Size limits:** request bodies are capped at 64 KB, and each token at 512 tags.
- **Strict validation:**
  - APNs tokens must be 64 hex characters;
  - FCM tokens must be 100–300 characters from `[A-Za-z0-9_:-]`;
  - tags must be exactly 32 lowercase hex characters.
- **Pushes per token** are capped by the coalescer at 2 a minute, whatever any
  publisher does.

## Configuration

| Variable | Meaning |
|---|---|
| `PORT` | HTTP port (default 8080) |
| `STRFRY_URL` | strfry WebSocket on the private network |
| `EVENT_KIND` | 2929 |
| `DB_PATH` | SQLite file on the mounted volume |
| `APNS_KEY_P8`, `APNS_KEY_ID`, `APNS_TEAM_ID` | APNs token auth |
| `APNS_TOPIC` | `com.yantagram.yantagram` |
| `APNS_ENV` | `production` or `sandbox` |
| `FCM_PROJECT_ID`, `FCM_CLIENT_EMAIL`, `FIREBASE_PRIVATE_KEY` | FCM service account |
| `COALESCE_SECONDS` | 30 |
| `ALERT_COALESCE_SECONDS` | 10: how soon a pending alert follows the previous push |
| `REGISTRATION_TTL_DAYS` | 14 |

Secrets come from the platform's secret store (Fly secrets, filled from 1Password),
never from files in the image.

## Project layout

```
push-service/
  build.gradle.kts, settings.gradle.kts, gradle/libs.versions.toml
  src/main/kotlin/com/yantagram/push/
    Application.kt          Ktor module: plugins, routes, lifecycle
    Config.kt               env → typed config, fails fast on missing values
    api/Routes.kt           /push/register, /health
    api/Validation.kt       token, tag and size checks
    registry/Registry.kt    in-memory index + write-through
    registry/Store.kt       Exposed/SQLite schema and queries
    relay/RelayListener.kt  strfry WebSocket, cursor, dedup
    relay/NostrMessages.kt  REQ/EVENT/EOSE/NOTICE parsing
    push/Coalescer.kt
    push/ApnsSender.kt
    push/FcmSender.kt
    push/PushSender.kt      interface; one result type for both providers
    jobs/Expiry.kt          daily registration expiry
  src/test/kotlin/...       unit tests + Testcontainers integration tests
  Dockerfile               (or Jib) — JRE 21 slim, non-root user
  fly.toml
```

## Phases

### Phase 1: skeleton and registration (~1 day)

- Set up the Gradle project, the Ktor server, config loading and `/health`.
- Build `PUT` and `DELETE /push/register` with validation, the Registry and the
  SQLite store, and load the store at startup.
- Add the rate limit and body-size limit.
- **Tests:**
  - replacing a token's tags updates both maps;
  - an empty tag list unregisters;
  - registrations survive a restart;
  - every validation case;
  - `DELETE` of an unknown token returns `204`.

### Phase 2: relay listener and matching (~1 day)

- Build the RelayListener (subscription, reconnects, dedup, cursor), the Matcher and
  the Coalescer, with a fake sender.
- **Tests:**
  - an event on a registered tag queues one wake-up;
  - ten events within 30 seconds send one push now and one when the 30 seconds are
    up;
  - an unregistered tag sends nothing;
  - events replayed after a reconnect are deduplicated;
  - the cursor resumes after a restart.
- **Integration (Testcontainers):** run strfry, publish kind 2929 events with
  `nostr-tools`-shaped JSON from the test, and assert on what the fake sender
  receives.

### Phase 3: APNs and FCM (~1–1.5 days)

- Build both senders, the token-removal rules, retries and metrics.
- **Tests:**
  - payloads and headers match the spec exactly;
  - `410` and `UNREGISTERED` remove the token;
  - `429` and `5xx` are retried, and give up after 3 attempts.
- **Manual:**
  - wake a dev build on a real iPhone (sandbox APNs) and a real Android phone (FCM);
  - confirm in the device logs that the app's background handler runs (needs the
    client changes below).

### Phase 4: deploy (~0.5 day)

- Run it as a Fly app in the same organisation as strfry, reaching strfry over
  `.internal` networking.
- One machine and a 1 GB volume for SQLite.
- Health checks on `/health`.
- Set the secrets.
- Set `RELAY_PUSH_URL` in the app's environment (already read by
  [app.config.ts](app.config.ts)).

### Phase 5: hardening (~0.5 day)

- Add the metrics endpoint on an internal port only.
- Alerts for:
  - the relay staying disconnected for more than 5 minutes;
  - the push failure rate going above 5%;
  - the registration cap being reached.
- Run a load test: 50,000 registrations at 20 tags each, plus 200 events a second.
  Check memory, and the latency from event to push.

**Total: about 4–5 days.**

## Client changes (this repo)

The service is useless without these. Today the app registers an Expo token and has
**no background handler**, so a silent push wouldn't do anything while the app is
suspended.

1. **Native tokens.** In [relay-push-service.ts](services/relay-push-service.ts):
   - use `Notifications.getDevicePushTokenAsync()` in place of
     `getExpoPushTokenAsync`;
   - send `{ token, platform: Platform.OS, tags }`;
   - re-register when the token changes, using
     `Notifications.addPushTokenListener`.
2. **Background wake-up handler.**
   - Add `expo-task-manager`.
   - Register a background notification task with `Notifications.registerTaskAsync`.
   - When the task runs, it must:
     1. connect the relay transport;
     2. fetch everything since the last cursor and process it through the normal
        packet pipeline;
     3. show local notifications for new messages (respecting `notificationContent`);
     4. disconnect.
   - It must finish within iOS's ~30-second background window.
   - It needs the database key and identity file to be readable while the phone is
     locked. They use `AFTER_FIRST_UNLOCK` today, which allows that.
3. **Register more future tags for push.**
   - Today the app registers today's tags plus one day either side. A phone whose app
     hasn't run for two days stops being woken, because its tags no longer match.
   - Register the next **7** daily windows with the push service; the relay
     subscription keeps its current windows.
   - Future tags reveal nothing the service can't already link (same token, same
     groups), and they keep pushes working through a week of the app not running.
4. **Onboarding copy:** the relay step already says the push token goes with your
   tag set. Add that pushes go through Apple or Google.

## Risks and limits

- **iOS throttles background pushes.**
  - Silent pushes (`apns-priority: 5`) are budgeted by iOS, roughly a few an hour
    per app, and dropped entirely while Low Power Mode is on or after the user
    force-quits the app.
  - So expect delayed notifications on iOS, not lost messages: everything still
    arrives when the app is opened.
  - If that isn't good enough, the next step is a **Notification Service Extension**
    that receives a visible push and fetches and decrypts from the relay itself. That
    needs native code, shared keychain access and an app group, so it's a separate
    project.
- **Android limits high-priority messages.** Android may lower the priority of
  high-priority FCM data messages that don't lead to a visible notification. If the
  wake-up finds nothing to show (for example, our own message echoing back), that
  will happen more often. Keep an eye on it.
- **Senders wake themselves.** The sender is subscribed to its own group tag, so its
  own message wakes it. It's harmless (one silent wake-up, coalesced), and avoiding
  it would mean telling the service which events are ours.
- **Unauthenticated registration.**
  - Anyone who learns a device's push token could register it to tags, which wakes
    that device at most twice a minute, or delete its registration, which delays its
    notifications.
  - Tokens are only ever sent to the service and to Apple or Google, so this needs a
    leak first.
  - If it becomes a problem: on first registration, send a silent push with a
    challenge the app must echo back.
- **One instance.** If the service is down, nothing is lost; notifications just wait
  until the app opens. Run more instances only if needed, with a shared database and
  each instance owning part of the tokens.
- **Apple and Google see push timing.** They learn when the device is woken, which is
  the same as for any messenger using push. No content or tags are in the payload.

## Open questions

1. **iOS reliability:** is a delayed notification acceptable at launch, or is the
   Notification Service Extension needed from day one?
2. **Repository:** should the service go in its own repo, or next to the relay's
   deployment config?
3. **Visible fallback:** after N hours of silent wake-ups that iOS hasn't delivered,
   should the service send one visible, generic "You may have new messages" alert?
   It's more reliable, but it shows a notification even when there's nothing new.
