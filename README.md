# Namazu Cloud Client

Open-source Elements element that maintains an outbound, mutually-authenticated `wss` connection
to the Namazu Cloud control plane and mints `Elements`-`SessionSecret`s for cloud users on demand.

## What it does

The Namazu Cloud control plane never dials into customer infrastructure. Instead, every deployed
cloud instance runs this element, which keeps a persistent outbound WebSocket open to a well-known
control-plane endpoint. The control plane can then exchange session secrets through the open
channel, letting support and automation act *as* a cloud user without holding the user's password.

Mutual authentication is established per connection with a HMAC-SHA256 challenge/response handshake
keyed by a shared secret `S` (derived key `K = SHA-256(S)`):

1. Server → client: `challenge { nonce, expiresAt }`
2. Client → server: `response { clientId, challengeNonce, nonceClient, mac }`
   — `mac = HMAC(K, "client-auth.v1" + "." + clientId + "." + nonce)` (constant-time verified)
3. Server → client: `server-auth { mac }`
   — `mac = HMAC(K, "server-auth.v1" + "." + clientId + "." + nonce + "." + nonceClient)`; the client
   verifies it before serving any request

The connection uses TLS (`wss`) in every real deployment; the local dev harness allows `ws`.

## Modules

| Module | Contents |
|--------|----------|
| `api` | The Jackson-free wire protocol (`ControlFrame`, `ControlWire`, `ControlMac`) and configuration keys (`CloudClientConfig`) shared by this repo and the closed-source control plane |
| `element` | The deployed Elements element: connection manager, handshake state machine, controls the `CloudClientService` lifecycle |
| `mock` | A mock control-plane element (`@ServerEndpoint`) for local loopback testing |
| `debug` | Local SDK runtime harness that loads `element` + `mock` (never deployed) |

## Building

```bash
mvn install
```

Requires JDK 21 and Maven 3.9+. Build artifacts are the classifiers

- `com.namazustudios.cloud:element:elm:<version>` — the element archive
- `com.namazustudios.cloud:mock:elm:<version>` — the mock control plane
- `com.namazustudios.cloud:api:<version>` — the plain jar consumed by the closed-source control plane

CI builds with the git short SHA appended to the Maven version so every artifact is traceable to its
commit:

```bash
mvn -B "-Drevision=0.1.0-SNAPSHOT-$(git rev-parse --short HEAD)" install
```

## Local development

```bash
# Start MongoDB (only required to boot the local SDK runtime):
docker compose -f services-dev/docker-compose.yml up -d

# Install the api/element/mock artifacts, then boot the harness (from the repo root):
mvn install
mvn -pl debug exec:java
```

The harness loads the client element together with the mock control-plane element. The mock serves
at `ws://localhost:8080/ws/cloud-control/control` and prints `[mock] LOOPBACK OK` when a full
handshake + `request_session` round-trip completes. Overrides (e.g. non-default secret) go in
`debug/local.properties` (gitignored):

```properties
com.namazustudios.cloud.client.secret=dev-secret
com.namazustudios.cloud.client.url=ws://localhost:8080/ws/cloud-control
```

## Configuration (element attributes)

| Attribute | Env override | Default | Meaning |
|-----------|--------------|---------|---------|
| `com.namazustudios.cloud.client.url` | `com_namazustudios_cloud_client_url` | `cloud.namazustudios.com` | Control-plane base; the client appends `/control` |
| `com.namazustudios.cloud.client.id` | `com_namazustudios_cloud_client_id` | — | Unique client identity (`<org>.<region>.<instance>`), echoed in the control plane's audit trail |
| `com.namazustudios.cloud.client.secret` | `com_namazustudios_cloud_client_secret` | — | Shared secret `S`; blank → service idles with a WARN |
| `com.namazustudios.cloud.client.retry.seconds` | `com_namazustudios_cloud_client_retry_seconds` | `5` | Reconnect delay on failure/close |
| `com.namazustudios.cloud.client.session.ttl.minutes` | `com_namazustudios_cloud_client_session_ttl_minutes` | `60` | Lifetime of minted session secrets |

## Control frames exchanged

Serve/receive of controls is exercised by the mock loopback; the API of `ControlFrame`:

| Frame | Direction | Purpose |
|-------|-----------|---------|
| `challenge` | S → C | `{ nonce, expiresAt }` handshake opener |
| `response` | C → S | `{ clientId, challengeNonce, nonceClient, mac }` mutual auth |
| `server-auth` | S → C | server proof; client verifies before serving requests |
| `request_session` | S → C | ask the connected client for an `Elements-SessionSecret` |
| `session` | C → S | `{ sessionSecret, expiresAt, userId, userName }` |
| `ack` | either | confirming a completed operation |
| `error` | either | `{ code }` (e.g. `session_mint_failed`, `protocol_error`, `bad_auth`) |
| `bye` | either | clean close |

## License

Apache 2.0. See [LICENSE](LICENSE). The server-side counterpart lives in the closed-source
`namazu-cloud-element` repository and consumes exactly the `api` artifact from this repo.