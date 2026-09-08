# MockkHttp Agent Control — Definitive Implementation Plan
**Target version line:** 1.7.1 (correctness) → 1.8.0 → 1.9.0 → 1.10.0 → 1.11.0
**Repo root:** `/Users/sergiy/IdeaProjects/MockkNetworkInspector/MockkHttp`

---

## 0. THE ONE FACT THAT SHAPES EVERYTHING

Before any design: **the app honours the plugin's reply on only one of the two message types, and only in some modes.** Verified in `android-library/src/main/kotlin/com/sergiy/dev/mockkhttp/interceptor/MockkHttpInterceptor.kt` and mirrored in `flutter-package/lib/src/`:

| Message | When sent | Does the app read the reply? |
|---|---|---|
| `CHECK_MOCK` (pre-flight, every request) | always, before `chain.proceed()` | **YES**, synchronously, 5 000 ms budget (`CONNECTION_TIMEOUT_MS = 5000`, `:47`; Flutter `_connectTimeoutMs`, `mockk_http_client.dart:35`) |
| `FLOW` in **DEBUG / MOCKK_DEBUG** | after the real response | **YES**, blocking read, 60 000 ms (`READ_TIMEOUT_MS = 60000`, `:48–49`; Flutter `_readTimeoutMs = 60000`, `mockk_http_client.dart:36`) |
| `FLOW` in **RECORDING / MOCKK** | after the real response | **NO** — `sendToPluginAsync` writes and closes inside `socket.use { }` and never reads. The plugin's `ModifiedResponseData` is written into a stream nobody reads. |

Three consequences that are non-negotiable and that killed two of the four candidate designs:

1. **Response *substitution* (skip the network) is only possible from the `CHECK_MOCK` handler** — `GlobalOkHttpInterceptorServer.findMockForRequest` (`:543`). Every armed/ephemeral stub must be matched **there**, not in `OkHttpInterceptorServer.handleFlow`. A design that arms in `handleFlow` reports success, logs success, shows a substituted flow in the UI — and the app receives the original bytes and the real server takes the real side effect.
2. **Response *modification* of a real response only exists in DEBUG / MOCKK_DEBUG.** In RECORDING/MOCKK the reply is discarded, so `handleFlow`'s existing `Mode.MOCKK` branch (`findMatchingMockRule`/`matchesUrlPattern`, `:382/:398/:410`) is **dead code** and gets deleted.
3. **`CHECK_MOCK`'s reply carries the `mode` string that decides whether the app blocks.** That makes selective pausing free: return `"RECORDING"` for out-of-scope URLs and `"DEBUG"` only for the ones the policy targets. **No client release needed.**

The plan is built on this. The doctrine that follows from it — and that every tool description repeats — is:

> **Tier 1 — mock rules** (persistent, `MOCKK`): answered at `CHECK_MOCK`, zero agent latency, no pause. The default.
> **Tier 2 — armed stubs** (ephemeral, ordinal, TTL'd): also answered at `CHECK_MOCK`, zero agent latency, immune to the 60 s ceiling. **This is what automated tests are built on.**
> **Tier 3 — live intercept** (`DEBUG`/`MOCKK_DEBUG`): the agent sees the *real* response and rewrites it, inside a hard 45 s budget. Exploration only. The demo, not the product.

---

## 1. ARCHITECTURE

MockkHttp gains a **loopback HTTP/JSON control plane inside the IDE process** (`AgentControlServer`, `com.sun.net.httpserver`, zero new jars) that is the single source of truth for everything an automated caller can do. A **stdio MCP bridge** — a ~700-line self-contained JAR run by the JBR that Android Studio already ships, vendored to `~/.mockkhttp/bin/` by the plugin on every start — translates 15 `mockkhttp_*` tools into REST calls. The bridge discovers the port, the token and the project **at launch** by reading `~/.mockkhttp/instances/<instanceId>.json` and longest-prefix-matching the agent's working directory against each open project's `basePath`, so the committed `.mcp.json` contains no port, no token and no project id and survives IDE restarts, port walks and token rotation. Underneath, three things are pried out of Swing: the capture session (`CaptureSessionService`), the mock matcher (one implementation, in `MockkRulesStore`), and the Debug pause (`PendingInterceptRegistry` + pluggable `InterceptResolver`, with the existing `DebugInterceptDialog` demoted to one resolver among several). Armed stubs are matched in the `CHECK_MOCK` handler where substitution actually works; the pause policy is enforced by answering `CHECK_MOCK` with a per-request mode.

```
┌──────────────────────── developer's machine, one uid ─────────────────────────────────┐
│                                                                                        │
│  Android Studio terminal                     Android Studio / IntelliJ process         │
│  ┌────────────────────┐                      ┌─────────────────────────────────────┐   │
│  │ claude (CLI)       │                      │  MockkHttp plugin                   │   │
│  │   spawns ──────────┼── stdio MCP ────────▶│  (contains NOTHING MCP-shaped)      │   │
│  │  ┌──────────────┐  │  JSON-RPC 2.0        │                                     │   │
│  │  │mockkhttp-mcp │  │  dual-era            │  ┌───────────────────────────────┐  │   │
│  │  │   .jar       │──┼── HTTP/1.1 + JSON ──▶│  │ AgentControlServer  @APP      │  │   │
│  │  │  (bridge)    │  │  127.0.0.1:<ephem>   │  │ com.sun.net.httpserver        │  │   │
│  │  └──────┬───────┘  │  Bearer <token>      │  │ loopback only · /v1/**        │  │   │
│  └─────────┼──────────┘                      │  └───────────────┬───────────────┘  │   │
│            │ read once at launch             │                  │ ControlApi       │   │
│            ▼                                 │                  ▼ (UI-free façade) │   │
│  ~/.mockkhttp/instances/<id>.json ◀──written──┤  ┌──────────────────────────────┐   │   │
│    baseUrl · token · bridge.sha256           │  │ PROJECT services              │   │   │
│    projects[{projectId, basePath, …}]        │  │  CaptureSessionService        │   │   │
│    interceptor{port, bound, heldByPid}       │  │  ArmedStubStore   RunRegistry │   │   │
│                                              │  │  PendingInterceptRegistry     │   │   │
│                                              │  │  FlowStore    MockkRulesStore │   │   │
│                                              │  └────┬────────────────────┬─────┘   │   │
│                                              │       │                    │         │   │
│                                              │  ┌────▼─────────┐   ┌──────▼──────┐  │   │
│                                              │  │ Swing views  │   │ Global      │  │   │
│                                              │  │ Inspector    │   │ OkHttp      │  │   │
│                                              │  │ Mockk        │   │ Interceptor │  │   │
│                                              │  │ Agent (new)  │   │ Server      │  │   │
│                                              │  └──────────────┘   │  :9876      │  │   │
│                                              └─────────────────────┴──────▲──────┘──┘   │
└─────────────────────────────────────────────────────────────────────────  │ ───────────┘
                                        newline-delimited JSON over TCP     │
                                     (adb reverse / simctl / LAN for iOS)   │
                     ┌──────────────────────────────────────────────────────┴──────────┐
                     │  app under test (debug build)                                   │
                     │   MockkHttpInterceptor (OkHttp)  /  mockk_http (Dart)           │
                     │   ① CHECK_MOCK  ── 5 s ──▶  {hasMock, mode, status, headers,    │
                     │                              body, directives}                  │
                     │      hasMock ⇒ SKIP NETWORK  (rules + arms answer here)         │
                     │      mode    ⇒ block-or-not  (pause policy answers here)        │
                     │   ② FLOW      ── 60 s ──▶  {statusCode, headers, body}          │
                     │      read ONLY in DEBUG / MOCKK_DEBUG                           │
                     └────────────────────────────────────────────────────────────────┘
```

**Three independently versioned contracts.** (1) app↔plugin socket protocol on 9876, versioned by the AAR / pub.dev package; (2) plugin↔bridge REST `/v1`, versioned by `apiVersion` in `GET /v1/meta`; (3) bridge↔client MCP tool schema, versioned by the bridge JAR. MCP spec churn (the 2026-07-28 revision *deleted* the `initialize` handshake, sessions and the GET stream) costs a bridge rebuild, not a Marketplace review. Nothing MCP-shaped ships inside the plugin, so `com.intellij.mcpServer` — which does not exist at `sinceBuild="243"` and is a documented **Critical** verifier failure to depend on optionally on 243–251 — is never touched, and tool-name collision with the IDE's own bundled MCP server is impossible by construction.

---

## 2. DECISIONS FOR THE USER

Only four. Everything else in this document is settled.

### D1 — Bridge runtime and MCP transport
| Option | Cost | Consequence |
|---|---|---|
| **(A) JVM stdio bridge — RECOMMENDED** | `:mcp-bridge` Gradle subproject, fat JAR (~1.9 MB) vendored into plugin resources, launched by a generated shell/cmd launcher pinned to the IDE's own JBR | **Zero prerequisites** (every Android Studio ships a JBR ≥ 21). No npm, no supply chain, no `npx`. stdio means Claude Code imposes **no per-request idle timer** (it does on HTTP/SSE). Port + token read at launch → committed `.mcp.json` never goes stale. |
| (B) Node stdio bridge (npm `@mockkhttp/mcp`) | ~500 lines JS, npm publish with provenance, version pinning docs | Adds Node as a hard prerequisite for a plugin whose users are Android/Flutter devs; adds an npm supply-chain story you must then defend. |
| (C) Plugin serves MCP directly over HTTP | ~250 lines in-plugin, no second artifact | Two judges independently found this fatal: `.mcp.json` must bake in a walked port (9877→9878 when two IDEs open) and a rotating token; the file goes stale on every IDE restart and cannot be committed. Also puts MCP spec churn on the Marketplace release cycle. |

**Recommendation: A.** It is the only option with zero user-installed prerequisites *and* no stale-config failure mode.

### D2 — Ship client-library releases (android-library AAR + `mockk_http` on pub.dev) in this cycle?
| Option | Cost | Consequence |
|---|---|---|
| **(A) Yes, as a parallel track — RECOMMENDED** | Rebuild AAR, bump the version-pinned cache dir at `gradle-plugin/.../MockkHttpGradlePlugin.kt:122`, `dart pub publish`, users re-sync Gradle / `pub get` | Fixes four things that cannot be fixed plugin-side: the Flutter `await socket.first` framing bug (**silent data loss** on any body > ~1.4 KB), wire-negotiated `clientTimeoutMs`, wire control of the 500 ms request **deduplication window** that otherwise eats retries, and the optional pause heartbeat that could later lift the 60 s ceiling. |
| (B) Plugin-only v1 | none | Every feature still works, but: Flutter agent-authored bodies > 1.4 KB silently fall back to the original response; the retry in "500 on the second call" can be dropped app-side with only a warning to show for it; the pause budget stays hard-capped at 45 s forever. |

**Recommendation: A**, but every feature in this plan degrades gracefully without it and reports the degradation in `mockkhttp_status.client`. Publish order (per `VERSION_FILES.md`): **pub.dev first**, then Marketplace, because `HelpPanel.kt:38-39` renders the pubspec snippet.

### D3 — How far past the network boundary do we go?
| Option | Cost | Consequence |
|---|---|---|
| (A) Network only | — | Agent can mock and observe but a human must tap the app. That is exactly the babysitting the goal removes. |
| **(B) + app & device control — RECOMMENDED** | ~1.5 days. `AppManager.startApp/forceStopApp/restartApp` already exist, are headless, and have **zero call sites**. Expose them plus `adbPath` + `deviceSerial` in `status`. | Agent drives the loop itself: restart app cold, then `adb -s <serial> shell input tap …` / `am start -d <deeplink>` / `exec-out screencap` / `logcat` from plain Bash. Closes the loop for ~90 % of tests. |
| (C) + `:testkit` JUnit5 extension on Maven Central | +1 week, plus Central Portal namespace verification, GPG signing, and a **fifth** artifact on a release process `VERSION_FILES.md` already calls fully manual across four registries | The AI writes an ordinary Espresso/JUnit test that arms MockkHttp scenarios and runs in CI without an agent. Genuinely valuable, genuinely expensive. |

**Recommendation: B now (Milestone 4), C as a separate later decision.** Do not fold C's cost into this project's estimate.

### D4 — Write-authorization default
| Option | Consequence |
|---|---|
| **(A) Agent control ON by default, with visible audit + one-click revoke — RECOMMENDED** | Zero setup stumble. Loopback + 256-bit rotating token + browser lockout + `Agent` tab + status-bar indicator + first-connection balloon with a **Revoke** action. Blast radius is bounded by a closed verb set: no exec, no file read, no arbitrary path. |
| (B) OFF until the human clicks **Enable AI Control** | The only layer that actually stops a *headless* same-uid process (malicious npm postinstall) — it cannot click. Costs one click per machine and guarantees a "why does Claude say it's disabled" support thread. |

**Recommendation: A**, with `SettingsStore.agentControl = FULL | READ_ONLY | OFF` so B is one setting away. Rationale: the token file is same-uid-readable anyway, so the click only defends against automation that cannot click — a real but narrow win against a guaranteed friction cost. If the user prefers B, the only change is the default value and one extra `userSetupSteps` entry.

---

## 3. TOOL CATALOG

**Contract rules that apply to every entry.**

- **REST:** all paths under `/v1`. Every request carries `Authorization: Bearer <token>` and `X-MockkHttp-Client: <name>/<version>`. Every response carries `X-MockkHttp-Api-Version: 1.0`. Bodies are UTF-8 `application/json`.
- **Project addressing:** `{pid}` is `project.locationHash`. The bridge resolves it once per call from cwd; `project` is an optional override on every MCP tool.
- **Errors, REST:** `{"error":{"code":"SCREAMING_SNAKE","message":"…","hint":"the exact next call to make","details":{…}}}`.
- **Errors, MCP:** unknown tool or schema violation → JSON-RPC `-32602`. **Every** domain failure → a normal result with `isError:true` whose text is `message` + `"\n"` + `hint`. Models self-correct from `isError`; they cannot self-correct from `-32603`.
- **Clamping:** whenever the server reduces a requested value it returns `"clamped":{"<field>":{"requested":X,"applied":Y,"reason":"…"}}` naming the constraint. Never silently ignore a parameter.
- **Bodies:** default `max_body_chars = 20000`, hard max `262144`. Non-UTF-8 → `body_base64` + `body_encoding:"base64"`. Always report `body_truncated`.
- **Redaction:** `Authorization`, `Proxy-Authorization`, `Cookie`, `Set-Cookie`, `X-Api-Key`, `X-Auth-Token` → `"<redacted:32b>"` unless `include_secrets:true` **and** Settings → *Allow agent to reveal secrets* is on (default off) → otherwise `403 REVEAL_DISABLED`.

### Shared types

```jsonc
// Matcher — every present field must match (AND). Used by arm, pause_policy, flows, verify.
{
  "method": "POST",                       // optional, compared ignoreCase
  "host": "api.acme.com",                 // optional, exact, ignoreCase
  "path": "/v1/login",                    // optional, exact, case-SENSITIVE, trailing '/' normalised
  "path_regex": "^/v1/orders/\\d+$",      // optional; mutually exclusive with `path`; anchored (matches whole path)
  "url_contains": "login",                // optional, case-insensitive substring of the full URL
  "query": [ {"key":"id","value":"7","required":true,"match":"EXACT"} ],   // match: EXACT|WILDCARD|REGEX
  "header": [ {"key":"X-Env","value":"stage","match":"EXACT"} ],           // match: EXACT|REGEX
  "body_contains": "grant_type=refresh"   // optional, request body substring
}

// ResponseSpec
{ "status_code": 503,
  "headers": {"Retry-After":"30","Content-Type":"application/json"},
  "body": "{\"error\":\"boom\"}",
  "body_base64": null,
  "delay_ms": 0 }                          // server-side sleep before answering; capped at 4000 on the CHECK_MOCK path

// FlowSummary
{ "flow_id":"8f2c…", "seq":1287, "ts":1772460003123, "method":"POST",
  "url":"https://api.acme.com/v1/login", "host":"api.acme.com", "path":"/v1/login",
  "status":503, "duration_ms":412, "req_body_bytes":84, "res_body_bytes":31,
  "resolution":"STUBBED|MOCKED|PASSTHROUGH|MODIFIED|TIMEOUT|UNCONFIRMED",
  "source_id":"arm_7f3a|rule_9c1|null", "app_notified":true }
```

`resolution` is **honest**: `UNCONFIRMED` is used when the client build does not report `clientTimeoutMs` (i.e. pre-1.8 Flutter, where the framing bug can silently discard our answer). Never claim `STUBBED` when we cannot confirm the app consumed it.

---

### A. Session & devices

#### `mockkhttp_status` — `GET /v1/projects/{pid}/status`
Orientation. **The first call of every session.** Answers which IDE/project, whether a session is running and is *actually alive*, what the client build can do, and what is currently blocking progress.

```jsonc
// input
{ "project": "MyApp" }                    // optional; omit to auto-resolve from cwd

// output
{
  "api_version": "1.0",
  "resolution": {                          // WHY the bridge picked this project — lets the model self-correct
    "matched_by": "cwd-prefix",            // cwd-prefix | env-pin | explicit-arg | base-url-override
    "cwd": "/Users/sergiy/dev/MyApp",
    "instance_id": "ij-48213-a3f9c1",
    "ide": "Android Studio AI-243.26053.27",
    "plugin_version": "1.11.0",
    "project_id": "a1b2c3d4", "project_name": "MyApp", "base_path": "/Users/sergiy/dev/MyApp",
    "other_open_projects": [ {"project_id":"e5f6","name":"OtherApp","base_path":"/Users/sergiy/dev/Other"} ]
  },
  "agent_control": "full",                 // full | read_only | off
  "interceptor": { "port":9876, "bound":true, "owned_by_this_process":true, "held_by_pid":null },
  "session": {
    "running": true, "mode": "MOCKK",
    "device": {"serial":"emulator-5554","platform":"ANDROID","model":"Pixel 7","online":true,"api_level":34},
    "package_filter": "com.acme.myapp",
    "started_at": 1772459000000,
    "transport": {"adb_reverse":true, "verified_at":1772460000000},
    "app_last_seen_ms_ago": 812,           // liveness: last CHECK_MOCK/FLOW/PING from the filtered package
    "adb_path": "/Users/sergiy/Library/Android/sdk/platform-tools/adb"   // so the agent can shell out
  },
  "client": {                              // from the CHECK_MOCK handshake; nulls = pre-1.8 build
    "library": "android-okhttp", "version": "1.7.0",
    "read_timeout_ms": 60000,
    "dedup": {"enabled": true, "window_ms": 500, "controllable": false},
    "caps": []
  },
  "flows": {"count":184, "next_seq":1288},
  "mocks": {"collections":3, "rules":27, "rules_enabled":4},
  "arms": {"active":2, "expired_since_last_status":1},
  "intercepts": {"pending":0, "policy":{"scope":"matching","owner":"agent","deadline_ms":45000}},
  "run": {"run_id":"run_7f3a91","started_at":1772459900000,"expires_at":1772460800000} ,
  "warnings": [
    "This app build deduplicates identical requests within 500 ms and cannot be controlled over the wire. A fast retry may never reach MockkHttp. Upgrade the interceptor to >= 1.8.0, or set MockkHttpInterceptor.enableDeduplication = false in your debug Application.onCreate()."
  ]
}
```

#### `mockkhttp_devices` — `GET /v1/devices[?refresh=true]`
Enumerate targets. Fast by default; the multi-minute APK scan is opt-in.

```jsonc
// input
{ "project":"MyApp", "platform":"ANDROID|IOS_SIMULATOR|IOS_DEVICE|ALL", "refresh": false, "timeout_ms": 60000 }

// output
{
  "devices": [ {"serial":"emulator-5554","platform":"ANDROID","model":"Pixel 7 API 34",
                "online":true,"api_level":34,"architecture":"arm64-v8a"} ],
  "instrumented_packages": ["com.acme.myapp"],   // packages seen on the wire (see §5 GlobalOkHttpInterceptorServer)
  "tooling_errors": [],                          // iOS null-vs-empty preserved here; NEVER flattened to "no devices"
  "adb_path": "/Users/sergiy/Library/Android/sdk/platform-tools/adb",
  "hint": "instrumented_packages lists apps that have talked to MockkHttp at least once in this IDE session. It is not a completeness check — pass package_name explicitly if your app is not listed."
}
```

#### `mockkhttp_session` — `GET/POST /v1/projects/{pid}/session[/start|/stop|/mode|/app|/flows]`
Own the capture session headlessly. Idempotent.

```jsonc
// input
{ "project":"MyApp",
  "action":"get|start|stop|set_mode|set_app|clear_flows",
  "mode":"RECORDING|DEBUG|MOCKK|MOCKK_DEBUG",
  "device_serial":"emulator-5554",
  "package_name":"com.acme.myapp",         // REQUIRED for start (see below)
  "catch_all": false }                     // explicit opt-in to a null package filter

// output (start)
{ "state":"RUNNING", "changed":true, "mode":"MOCKK",
  "device":{"serial":"emulator-5554","platform":"ANDROID","model":"Pixel 7"},
  "package_filter":"com.acme.myapp",
  "transport":{"adb_reverse":true,"port":9876,"note":"adb reverse tcp:9876 tcp:9876 established"},
  "interceptor":{"bound":true},
  "warnings":[] }
```

Typed failures: `PACKAGE_REQUIRED` (a null filter makes this project a catch-all that steals every other open project's traffic — `GlobalOkHttpInterceptorServer.findTargetProject` falls back to a filterless registration), `DEVICE_NOT_FOUND`, `ADB_REVERSE_FAILED`, `INTERCEPTOR_PORT_IN_USE` (another IDE process owns 9876; `ensureStarted()` returned false — **never report `RUNNING` in this case**), `ALREADY_RUNNING` (returned as **success** with `changed:false`).

`set_mode` with `DEBUG` or `MOCKK_DEBUG` is **refused** with `PAUSE_POLICY_REQUIRED` unless a pause policy exists or was set to `scope:"all"` explicitly. Hint text: `"DEBUG pauses every request the app makes, including background polling, for up to 45 s each. Call mockkhttp_pause_policy first, e.g. {scope:'matching', match:[{url_contains:'/v1/login'}]}."`

#### `mockkhttp_app` — `POST /v1/projects/{pid}/app` *(Milestone 4, decision D3-B)*
Drive the app under test. Android only; iOS returns `501 NOT_SUPPORTED_ON_PLATFORM` with a hint.

```jsonc
// input
{ "project":"MyApp", "action":"launch|stop|restart|clear_data",
  "package_name":"com.acme.myapp",         // defaults to session.package_filter
  "wait_for_traffic_ms": 20000 }           // wait for the first CHECK_MOCK/FLOW from this package

// output
{ "action":"restart", "ok":true, "traffic_seen_after_ms":3140, "timed_out":false,
  "hint":"Use `adb -s emulator-5554 shell input tap X Y` and `adb -s … exec-out screencap -p > /tmp/s.png` from Bash to drive and inspect the UI. mockkhttp_status.session.adb_path has the resolved adb binary." }
```

---

### B. Flows & recording

#### `mockkhttp_flows` — `GET /v1/projects/{pid}/flows`, `GET …/flows/{flowId}`, `DELETE …/flows`
Summaries by default so a chatty app cannot blow the context window.

```jsonc
// input
{ "project":"MyApp",
  "action":"list|get|clear",
  "flow_id":"8f2c…",                       // action=get
  "method":"POST", "host":"api.acme.com", "url_contains":"/login", "path_regex":null,
  "status_min":500, "status_max":599,
  "since_seq":1200,                        // incremental cursor — never re-read everything
  "resolution":"STUBBED|MOCKED|PASSTHROUGH|MODIFIED|TIMEOUT|UNCONFIRMED",
  "limit":25,
  "include_body":"none|response|both",     // list only; get always includes both
  "max_body_chars":20000, "include_secrets":false }

// output (list)
{ "next_seq":1288, "returned":12, "total_matching":31,
  "flows":[ FlowSummary, … ],
  "note":"Bodies omitted. Call again with action:'get' and a flow_id, or include_body:'response'." }

// output (get)
{ ...FlowSummary,
  "request":{"headers":{"Authorization":"<redacted:32b>", …},"body":"…","body_encoding":"utf8","body_truncated":false},
  "response":{"headers":{…},"body":"…","body_encoding":"utf8","body_truncated":false},
  "redacted_headers":["authorization"],
  "stored_body_truncated_by_retention": false }
```

#### `mockkhttp_await_flow` — `GET /v1/projects/{pid}/flows/await`
**Deterministic waiting instead of `sleep`.** Blocks until `count` flows matching `match` have been recorded. Never throws on timeout.

```jsonc
// input
{ "project":"MyApp", "match": Matcher, "count":1, "since_seq":1287, "wait_ms":25000 }   // wait_ms clamped to 25000

// output — satisfied
{ "satisfied":true, "actual":1, "expected":1, "waited_ms":3210,
  "flows":[ FlowSummary ], "next_seq":1289 }

// output — not satisfied (NOT an error)
{ "satisfied":false, "actual":0, "expected":1, "waited_ms":25000, "next_seq":1287,
  "closest_observed":[ {"method":"GET","path":"/v1/user/profile","hits":3},
                       {"method":"POST","path":"/v1/login/refresh","hits":1} ],
  "hint":"Nothing matched in 25000 ms. The closest traffic was POST /v1/login/refresh — your matcher used path:'/v1/login' which is an exact, case-sensitive match. Try path_regex:'^/v1/login' or url_contains:'login'. Verify the session is running with mockkhttp_status." }
```

`closest_observed` is the single highest-value field in the whole catalog: it turns the most common agent failure (a slightly wrong matcher) from a blind 25 s timeout into a self-correcting round trip.

---

### C. Mock rules & collections

#### `mockkhttp_mocks` — `/v1/projects/{pid}/mocks…`
Persistent rules. Answered at `CHECK_MOCK` with **zero agent latency** — the mechanism 95 % of tests should use.

```jsonc
// input
{ "project":"MyApp",
  "action":"list|get|create|update|set_enabled|delete|list_collections|create_collection|delete_collection|export|import",
  "rule_id":"rule_01J8…", "collection_id":"col_01J8…",
  "name":"login 500",
  "method":"POST", "url":"https://api.acme.com/v1/login",
  "path_match":"EXACT|REGEX", "host_match":"EXACT|REGEX",     // explicit; default EXACT
  "query": [ {"key":"id","value":"7","required":true,"match":"EXACT"} ],
  "response": {"status_code":500,"headers":{…},"body":"…"},
  "from_flow_id":"8f2c…",                  // clone a captured flow into a rule — the fastest create path
  "loosen_query": true,                    // default true when from_flow_id is used
  "enabled": true,
  "exclusive": true,                       // disable other enabled rules on the same endpoint signature
  "new_collection": {"name":"Auth errors","package_name":"com.acme.myapp"},
  "json": "…",                             // action=import
  "strategy":"REPLACE|KEEP_BOTH|SKIP",
  "dry_run": false,
  "include_bodies": false, "limit": 100 }

// output (create / update)
{ "rule": {"rule_id":"rule_01J8…","name":"login 500","enabled":true,"method":"POST",
           "url":"https://api.acme.com/v1/login","collection_id":"col_01J8…",
           "response":{"status_code":500,"headers":{…},"body_bytes":31}},
  "will_match": {"method":"POST","host":"api.acme.com","path":"/v1/login",
                 "host_match":"EXACT","path_match":"EXACT","required_params":[]},
  "disabled_conflicting":["rule_01J7…"],
  "loosened_params":["ts","nonce"],
  "will_fire_in_modes":["MOCKK","MOCKK_DEBUG","DEBUG"],
  "current_mode":"RECORDING",
  "warnings":["Rules are inert in RECORDING mode. Call mockkhttp_session(action:'set_mode', mode:'MOCKK')."] }

// output (import, dry_run:true)  — pure analysis, zero writes
{ "dry_run":true, "collections":[{"name":"Checkout","new_rules":6,"changed_rules":2,"identical_rules":1}],
  "would_create":6, "would_replace":2, "would_skip":1 }
```

`loosen_query:true` marks volatile query params (`ts`, `timestamp`, `_`, `cb`, `nonce`, `sig`, `token`, `rnd`, `v`, and anything whose captured value is a pure integer ≥ 10 digits or a UUID) `required:false` and reports them in `loosened_params`. Without it, `StructuredUrl.fromUrl` marks **every** captured param `required:true` + `EXACT`, producing a rule that matches the captured call and nothing after it.

#### `mockkhttp_match_explain` — `POST /v1/projects/{pid}/mocks/explain`
Dry-run the **one** matcher against a hypothetical request. Removes the biggest source of agent confusion: writing a rule that silently never fires.

```jsonc
// input
{ "project":"MyApp", "method":"POST", "url":"https://api.acme.com/v1/login?ts=1772460003" }

// output
{ "mode":"MOCKK",
  "winner": {"kind":"arm","id":"arm_7f3a","label":"login 500 on 2nd call",
             "note":"occurrence 2 of 2; 0 remaining after this"},
  "candidates":[
    {"kind":"rule","id":"rule_01J8…","name":"login ok","enabled":true,
     "rejected_because":"query param 'ts' is required:true and EXACT ('1772459000' vs '1772460003') — set required:false or use match:'WILDCARD'"},
    {"kind":"rule","id":"rule_01J7…","name":"login 500","enabled":false,
     "rejected_because":"rule is disabled"},
    {"kind":"rule","id":"rule_01J6…","name":"login alt","enabled":true,
     "rejected_because":"collection 'Legacy' is disabled"}
  ],
  "would_skip_network": true }
```

---

### D. Debug intercepts

#### `mockkhttp_arm` — `POST/GET/DELETE /v1/projects/{pid}/arms`
**Tier 2, and the primitive automated tests are built on.** An ephemeral, ordinal, TTL'd stub matched **at `CHECK_MOCK`**. Zero agent latency, zero pause, immune to the 60 s ceiling. This is what makes *"500 on the second call"* a single call.

```jsonc
// input
{ "project":"MyApp",
  "action":"arm|list|disarm|disarm_all",
  "arm_id":"arm_7f3a",                     // disarm
  "label":"login 500 on 2nd call",
  "match": Matcher,
  "skip": 1,                               // let this many matching requests through untouched FIRST
  "times": 1,                              // then act this many times (0 = unlimited until ttl)
  "action_on_match":"respond|passthrough|delay",
  "respond": ResponseSpec,                 // when action_on_match = respond
  "responses": [ ResponseSpec, ResponseSpec ],   // OPTIONAL sequence: 1st match gets [0], 2nd gets [1], last repeats
  "ttl_ms": 300000,
  "run_id":"run_7f3a91",                   // optional: auto-disarmed when the run ends
  "activate": true }                       // if true and mode is RECORDING, escalate to MOCKK automatically

// output (arm)
{ "arm_id":"arm_7f3a", "label":"login 500 on 2nd call",
  "expires_at": 1772460303000, "skip":1, "times":1, "remaining":1, "fired":0,
  "mode_changed": {"from":"RECORDING","to":"MOCKK"},
  "warnings":["This app build deduplicates identical requests within 500 ms; if your retry fires faster than that it will never reach MockkHttp and this arm will not consume it. See mockkhttp_docs(topic:'troubleshooting')."] }

// output (list)
{ "arms":[ {"arm_id":"arm_7f3a","label":"…","match":{…},"skip":1,"times":1,
            "fired":1,"remaining":0,"expires_at":…,"consumed_flow_ids":["8f2c…"]} ],
  "expired_recently":[ {"arm_id":"arm_9b1","label":"…","expired_at":…,"fired":0} ] }
```

Arms are **readable and cancellable** — `fired`, `remaining`, `consumed_flow_ids` — so the agent can confirm its intervention landed and a crashed test cannot poison the next run (TTL + `disarm_all` + auto-disarm on `run end`).

**`action_on_match:"delay"`** answers `hasMock:false` but sleeps `delay_ms` first (capped at 4000, under the 5 s `CHECK_MOCK` budget), which is how the agent injects latency without a pause.

#### `mockkhttp_pause_policy` — `PUT /v1/projects/{pid}/pause-policy`
**The tool that makes agent-driven DEBUG usable at all.** Without it, DEBUG pauses *every* request, background polling included, and a chatty app hard-stalls (OkHttp `maxRequestsPerHost = 5`).

```jsonc
// input
{ "project":"MyApp",
  "scope":"all|matching|none",
  "match":[ Matcher, … ],
  "owner":"agent|human|both",              // who may answer; see §4
  "deadline_ms": 45000,
  "max_concurrent": 1,
  "on_overflow":"queue|passthrough|error",     // when max_concurrent is exhausted
  "on_deadline":"passthrough|error_504",
  "on_agent_lost":"passthrough|dialog|error"   // agent stopped polling; see §4
}

// output
{ "policy": { …as applied… },
  "clamped": {"deadline_ms":{"requested":120000,"applied":45000,
    "reason":"The app abandons the pause after 60000 ms (android-library 1.7.0 READ_TIMEOUT_MS). The plugin deadline is hard-capped at 55000 and defaults to 45000 so the plugin always loses the race."}},
  "warnings":["Session mode is MOCKK; no request will pause until mode is DEBUG or MOCKK_DEBUG."] }
```

#### `mockkhttp_await_intercept` — `GET /v1/projects/{pid}/intercepts/next`
Bounded long-poll. Returns `timed_out` rather than hanging or throwing, and **tells the agent what it missed**.

```jsonc
// input
{ "project":"MyApp", "match": Matcher, "since_seq": 41, "wait_ms": 20000 }   // clamped to 25000

// output — paused
{ "timed_out": false, "next_seq": 43,
  "intercept": {
    "flow_id":"8f2c…", "intercept_token":"itk_7Yq3…",
    "seq": 42, "arrived_at": 1772460003000,
    "deadline_at": 1772460048000, "remaining_ms": 41200,
    "on_deadline": "passthrough",
    "owner": "agent", "human_dialog_open": false,
    "request": {"method":"POST","url":"…","headers":{…},"body":"…","body_truncated":false},
    "upstream": {"status_code":200,"headers":{…},"body":"…","body_truncated":false},
    "matching_rules": [ {"rule_id":"rule_01J8…","name":"login 500","collection":"Auth errors"} ],
    "presets": ["400 bad_request","401 unauthorized","429 rate_limited","500 internal_error","503 service_unavailable","Empty","Malformed"]
  },
  "missed": [] }

// output — timed out
{ "timed_out": true, "next_seq": 43, "pending_now": 0, "current_mode":"MOCKK",
  "missed": [ {"flow_id":"7a1b…","seq":42,"method":"GET","url":"…",
               "resolved_by":"deadline","at":1772460040000} ],
  "hint":"No matching request paused in 20000 ms, but 1 intercept expired unanswered while you were away — increase pause_policy.deadline_ms or poll continuously. Current mode is MOCKK: nothing pauses. Call mockkhttp_session(action:'set_mode', mode:'MOCKK_DEBUG')." }
```

`missed` (driven by `since_seq`) is what makes "the app hasn't called yet" distinguishable from "the app called and I slept through it" — the classic blind-polling failure.

#### `mockkhttp_resolve_intercept` — `POST /v1/projects/{pid}/intercepts/{flowId}/resolve`
Answer a paused request. Capability-gated by `intercept_token`.

```jsonc
// input
{ "project":"MyApp",
  "action":"respond|passthrough|apply_rule|apply_preset|resolve_all",
  "flow_id":"8f2c…", "intercept_token":"itk_7Yq3…",
  "response": {"status_code":503,"headers":{"Retry-After":"5"},"body":"…"},
  "rule_id":"rule_01J8…", "preset_name":"503 service_unavailable",
  "save_as_rule": {"name":"login 503","collection_id":"col_01J8…"},
  "note":"verifying the offline banner",   // recorded on the flow and in the audit log
  "default_action":"passthrough" }         // action=resolve_all

// output
{ "resolved": true, "resolved_by":"agent", "flow_id":"8f2c…",
  "applied_status": 503, "latency_ms": 812, "remaining_ms": 38010,
  "app_notified": true,                    // ← writer.checkError() after the socket write. THE trust signal.
  "saved_rule_id":"rule_01J9…" }

// typed failures (isError:true, never silent)
{ "resolved": false, "reason":"ALREADY_RESOLVED", "resolved_by":"human",
  "human_decision": {"action":"modify","status_code":401},
  "hint":"A human answered this in the Debug dialog before you. Call mockkhttp_flows(action:'get', flow_id:'8f2c…') to see what was actually sent. To take exclusive control, call mockkhttp_pause_policy(owner:'agent')." }
// other reasons: INTERCEPT_EXPIRED {deadline_passed_ms, app_used:"ORIGINAL"} | INVALID_INTERCEPT_TOKEN
//                | HUMAN_HOLD | UNKNOWN_FLOW
```

`app_notified:false` means the socket was already closed when we wrote — the app had abandoned the pause. Today that failure is swallowed by `PrintWriter` and the developer is told nothing.

---

### E. Assertions & testing

#### `mockkhttp_run` — `POST /v1/projects/{pid}/runs`, `DELETE …/runs/{runId}`
Scopes a test: opens a fresh journal, remembers the previous mode, TTLs itself so a crashed agent cannot leave the IDE silently stubbing the developer's real traffic, and produces a CI-shaped report on close.

```jsonc
// input
{ "project":"MyApp", "action":"start|end|get",
  "run_id":"run_7f3a91",
  "name":"login-retry-banner",
  "clear_flows": true,
  "mode":"MOCKK",                          // entered on start; restored on end
  "ttl_ms": 900000,
  "include":["journal","unmatched","unused_arms"] }

// output (end) — RunReport
{ "run_id":"run_7f3a91", "name":"login-retry-banner", "duration_ms":48210,
  "seq_range":[1288,1301],
  "total_flows":14, "stubbed":2, "mocked":0, "passthrough":12, "modified":0, "timed_out":0,
  "unused_arms":[ {"arm_id":"arm_9b1","label":"orders 500","fired":0} ],
  "arm_hits": {"arm_7f3a":1},
  "unmatched":[ FlowSummary, … ],          // flows nothing stubbed — the gaps in the scenario
  "intercepts": {"resolved_by_agent":0,"resolved_by_human":0,"timed_out":0},
  "mode_restored":"RECORDING",
  "arms_disarmed":2 }
```

`unused_arms` is the assertion nobody writes by hand and it catches a dead scenario immediately.

#### `mockkhttp_verify` — `POST /v1/projects/{pid}/verify`
The assertion primitive. N named expectations in **one** round trip, each with a failure message written for a model to act on.

```jsonc
// input
{ "project":"MyApp", "run_id":"run_7f3a91", "since_seq":1288,
  "expectations":[
    {"name":"login called twice", "match":{"method":"POST","path":"/v1/login"},
     "count":{"exactly":2}},
    {"name":"second login got 503", "match":{"method":"POST","path":"/v1/login"},
     "nth":2, "expect_status":503},
    {"name":"error body says boom", "match":{"method":"POST","path":"/v1/login"}, "nth":2,
     "body_json_path":[{"path":"$.error","equals":"boom"}], "of":"response"},
    {"name":"no analytics during test", "match":{"host":"analytics.acme.com"},
     "count":{"never":true}}
  ],
  "ordered":["login called twice","second login got 503"] }

// output
{ "ok": false,
  "results":[
    {"name":"login called twice","ok":true,"expected":"exactly 2","actual":2,
     "matching_flow_ids":["8f2a…","8f2c…"]},
    {"name":"second login got 503","ok":false,"expected":"nth=2 status 503","actual":"status 200",
     "matching_flow_ids":["8f2c…"],
     "diagnosis":"The 2nd POST /v1/login returned 200, not 503. Arm arm_7f3a has fired:0 remaining:1 — it never matched. mockkhttp_match_explain says its matcher required query param 'ts' EXACT. Also note this client build deduplicates identical requests within 500 ms."},
    {"name":"error body says boom","ok":false,"expected":"$.error == 'boom'","actual":"path not present",
     "diagnosis":"Response body was 84 bytes of JSON with keys [id, token]."},
    {"name":"no analytics during test","ok":true,"expected":"never","actual":0}
  ],
  "order_ok": true }
```

`body_json_path` lets the agent assert on a body it is not permitted to read in full.

#### `mockkhttp_docs` — `GET /v1/docs?topic=…`
Serves the workflow guide on demand instead of inflating every tool description (the WireMock `look_up_documentation` pattern). Topics: `quickstart`, `automated_test`, `arms_and_ordering`, `mocking`, `debug_intercept`, `modes`, `matching`, `troubleshooting`, `limits`. Omit `topic` for an index.

```jsonc
{ "topic":"automated_test",
  "markdown":"## Writing an automated test with MockkHttp\n1. `mockkhttp_status` — confirm project, device, mode, and read `client.dedup`.\n2. `mockkhttp_run(action:'start', clear_flows:true, mode:'MOCKK')`.\n3. Arm the scenario in ONE call — ordinality lives here, not in a poll loop:\n   `mockkhttp_arm({match:{method:'POST',path:'/v1/login'}, skip:1, times:1, respond:{status_code:503}})`\n4. Drive the app. Run `./gradlew connectedAndroidTest` **as a BACKGROUND Bash job** — a foreground shell blocks you out of your own MCP tools — or `mockkhttp_app(action:'restart')` + `adb … input tap`.\n5. `mockkhttp_await_flow({match:{path:'/v1/login'}, count:2, wait_ms:25000})` — never `sleep`.\n6. `mockkhttp_verify({expectations:[…]})`.\n7. `mockkhttp_run(action:'end')` — read `unused_arms` and `unmatched`.\n\n**Never** use await_intercept/resolve_intercept for an automated test: the app abandons the pause after 60 s and your answer lands on a dead socket.",
  "related_tools":["mockkhttp_arm","mockkhttp_await_flow","mockkhttp_verify","mockkhttp_run"] }
```

---

## 4. THE DEBUG INTERCEPT PROTOCOL

### 4.1 What is broken today

`OkHttpInterceptorServer.showInterceptDialogAndWait` (`:270`) builds a `DebugInterceptDialog` inside a `SwingUtilities.invokeLater` lambda, never publishes the reference, and blocks the socket thread on a private `CountDownLatch` for **5 minutes** (`latch.await(5, TimeUnit.MINUTES)`, `:339`). Meanwhile both clients abandon the socket at **60 000 ms** (`MockkHttpInterceptor.READ_TIMEOUT_MS = 60000`, `:48`; Flutter `_readTimeoutMs = 60000`, `mockk_http_client.dart:36`), use the original response, and close. For four minutes the dialog looks live, the eventual answer is written into a dead socket, and `PrintWriter` (autoflush, never throws) swallows the `IOException`. The developer is told the edit was applied. **That is a silent-wrong-result bug and it must die before any automated caller touches this path.**

Additionally "a flow is paused" exists only as a stack frame plus a latch on an anonymous socket thread. There is nothing to name, nothing to enumerate, nothing to answer.

### 4.2 The pause becomes an addressable object

New project service `intercept/PendingInterceptRegistry.kt`:

```kotlin
data class PendingIntercept(
    val flowId: String,
    val seq: Long,                              // monotonic, for since_seq cursors
    val interceptToken: String,                 // "itk_" + 16 SecureRandom bytes, base64url
    val flow: HttpFlowData,                     // UNTRUNCATED — taken from the live AndroidFlowData,
                                                // never from FlowStore (trimForRetention truncates bodies)
    val arrivedAtMs: Long,
    val deadlineAtMs: Long,
    val future: CompletableFuture<InterceptDecision>,
    val context: InterceptContext,              // matching rules + presets, snapshotted ONCE for both resolvers
    @Volatile var humanHold: Boolean = false,
    @Volatile var dialog: DebugInterceptDialog? = null
)

@Service(Service.Level.PROJECT)
class PendingInterceptRegistry(private val project: Project) : Disposable {
    fun awaitDecision(flow: HttpFlowData, policy: PausePolicy): InterceptDecision   // blocks the socket thread
    fun awaitNext(match: Matcher, sinceSeq: Long, waitMs: Long): AwaitResult
    fun resolve(flowId: String, token: String?, d: InterceptDecision, by: ResolvedBy): ResolveOutcome
    fun listPending(): List<PendingIntercept>
    fun recentlyClosed(sinceSeq: Long): List<ClosedIntercept>   // bounded ring of 50, powers `missed`
}
```

`OkHttpInterceptorServer.showInterceptDialogAndWait` is **deleted**. `handleFlow` (`:161`) calls `registry.awaitDecision(flow, policy)`, which registers the entry, notifies every installed `InterceptResolver`, blocks on `future.get(remainingMs, MILLISECONDS)`, and **always** removes the entry in a `finally`. `CompletableFuture.complete` is the race arbiter — one winner, atomically, no locks, no lost updates.

The socket thread still blocks. That is correct and unavoidable: it is what holds the app's HTTP call open. What changes is that it now blocks on something the whole IDE — and the control plane — can see, enumerate and answer.

### 4.3 Selective pausing (plugin-only, no client release)

`PausePolicy.shouldPause(request)` is a **pure function of the request** (method, host, path, query, headers). It is evaluated in two places that therefore always agree:

1. **`GlobalOkHttpInterceptorServer.findMockForRequest`** (the `CHECK_MOCK` handler, `:543`) — the returned `mode` field becomes **per-request**: `"DEBUG"`/`"MOCKK_DEBUG"` for in-scope requests, `"RECORDING"`/`"MOCKK"` for everything else. The app reads that string to decide whether to block. So an out-of-scope background heartbeat **never blocks in the app at all** — no thread, no dispatcher slot, no latency.
2. **`OkHttpInterceptorServer.handleFlow`** — same predicate, so an out-of-scope flow takes the recording path and returns immediately without registering a pause.

`max_concurrent` is stateful, so a request can be told `DEBUG` at `CHECK_MOCK` and then find the slot taken by `FLOW` time. `on_overflow` handles it: `passthrough` (return `original()` immediately, app unblocks in milliseconds), `queue` (register and wait — default when `owner:"agent"`), `error` (return a 503 the app sees). Never a silent drop.

### 4.4 The timeout budget — the plugin always loses the race

| Stage | Budget | Source |
|---|---|---|
| App-side socket read timeout | **60 000 ms**, fixed client-side | `MockkHttpInterceptor.kt:48`, `mockk_http_client.dart:36` |
| Plugin pause deadline | **45 000 ms** default, **hard-capped at 55 000** regardless of setting | `SettingsStore.pauseDeadlineMs`, coerced `1000..55000` |
| `mockkhttp_await_intercept` `wait_ms` | ≤ **25 000 ms** (clamped) | leaves ≥ 20 s of the deadline for reading + deciding |
| Left for the agent to read, decide, and resolve | **≥ 20 000 ms** | reported per-intercept as `remaining_ms` |

`clientTimeoutMs` is negotiated over the wire when D2 ships: the `CHECK_MOCK` handshake carries `client.read_timeout_ms`, and the deadline becomes `min(clientTimeoutMs, configuredMax) − 5000`. **Absent ⇒ assume 60 000**, so every AAR and pub.dev release already in the wild keeps working with no client update on the critical path.

On expiry the registry: completes the future with `policy.onDeadline` (default `PASSTHROUGH`), closes any open dialog on the EDT, writes the flow back with `resolution:"TIMEOUT"` and `paused=false`, and appends to the `recentlyClosed` ring so the next `await_intercept` reports it in `missed`.

And the fix for the silent lie: `GlobalOkHttpInterceptorServer.handleClient` checks `socket.isClosed` **before** the write and `writer.checkError()` **after**, logs `⚠️ app already gave up — response discarded`, and reports the result back as `app_notified` on the resolve call.

### 4.5 Human and agent both attached

`PausePolicy.owner` is a tri-state, settable by the agent (`mockkhttp_pause_policy`) and by the human (an Inspector toolbar toggle):

- **`human`** — today's behaviour, byte for byte. The dialog opens; the agent's `resolve_intercept` returns `HUMAN_HOLD` with a hint. Default when no policy has ever been set.
- **`agent`** — **no dialog is ever constructed.** Mandatory for unattended runs: otherwise N concurrent pauses stack N modal dialogs on the EDT, each with its own clock, and the IDE is unusable. The Inspector instead shows a row `⏸ paused — awaiting agent (38 s)` with a **Take over** button that flips *that one flow* to `both` and opens the dialog. **A human can always seize control in one click.** This is the default when the mode change arrives over the control API.
- **`both`** — pair-programming mode. The dialog opens **and** `await_intercept` returns the same flow. First writer wins.

**How they cannot fight, concretely:**

1. One `CompletableFuture` per pending intercept. `resolve()` is `future.complete(d)`, which returns `true` for exactly one caller.
2. **The loser is always told, with data.** Agent loses → `{resolved:false, reason:"ALREADY_RESOLVED", resolved_by:"human", human_decision:{…}, hint:"…call mockkhttp_flows(action:'get', flow_id:…) to see what was sent."}`. Human loses → the registry calls `dialog.closeBecauseResolvedElsewhere(by, summary)`, which closes with `CANCEL_EXIT_CODE`, **suppresses the save-as-mock side effect**, and shows a one-line toast: `Resolved by agent: 503 — verifying the offline banner`. Nobody is left staring at a dialog for a request that already completed.
3. **The EDT ordering hole is closed.** `DebugInterceptDialog` is created inside a `SwingUtilities.invokeLater` lambda, and `showAndGet()` runs a **nested** EDT event loop. With N concurrent pauses, dialog #1's nested loop blocks the queue, so dialog #3's lambda has not run yet when the agent resolves flow #3. **The lambda's first statement is therefore `if (registry.isResolved(flowId)) return@invokeLater`** — no orphan dialog for an already-answered flow, and no stray save-as-mock write.
4. **Modality: keep `SwingUtilities.invokeLater`, do not "modernize" it.** `ApplicationManager.getApplication().invokeLater(r, ModalityState.nonModal())` is *defined* to defer until no modal dialog is on screen — so with the Settings dialog or `CreateMockDialog` open, the intercept dialog would never appear and the flow would silently ride the deadline to passthrough. Worse, `closeBecauseResolvedElsewhere` posted with `nonModal()` can **never** run, because the modal it is trying to close is exactly what blocks it. Raw `SwingUtilities.invokeLater` posts to the AWT `EventQueue` and **does** run inside a nested modal loop. Both open and close use it, with a code comment stating why. *(This is a correction to two of the four candidate designs, both of which had it backwards.)*
5. **`Hold`.** A new button on the dialog sets `humanHold = true`; the registry then rejects agent resolutions with `HUMAN_HOLD` and the agent is told, not silently ignored.

### 4.6 The agent dies mid-intercept

Three independent backstops, in order:

1. **The deadline is the guarantee.** It is ≤ 55 s and always below the client's 60 s, so the app is *never* left hanging longer than it would have been on its own. On expiry: `on_deadline` applies (`passthrough` by default), the dialog closes, the flow is marked `TIMEOUT`, and the app has already moved on gracefully.
2. **Agent-liveness fallback.** The registry stamps `lastAgentPollMs` on every `await_intercept`/`resolve_intercept` for the project. If `owner:"agent"` and no agent call has arrived for `agent_idle_ms` (15 000) **and** the intercept has less than 15 s left, `on_agent_lost` fires early: `passthrough` (default) resolves immediately; `dialog` promotes the intercept to `both` and opens the dialog so a human present at the IDE can rescue it; `error` returns a 504 to the app.
3. **Nothing leaks.** `awaitDecision` removes the registry entry in a `finally`. `PendingInterceptRegistry.dispose()` completes every outstanding future with `PASSTHROUGH`. `AgentControlServer.dispose()` drains long-polls before `HttpServer.stop(0)`. A killed `claude` process kills the stdio bridge, which holds no server-side state — HTTP is stateless and the pause is owned by the registry, not the connection.

A crashed agent therefore costs at most one request's `on_deadline` behaviour, visible in `RunReport.intercepts.timed_out` and in the next `await_intercept`'s `missed` array.

---

## 5. PLUGIN REFACTORS REQUIRED

All paths relative to `/Users/sergiy/IdeaProjects/MockkNetworkInspector/MockkHttp`.

### `src/main/kotlin/com/sergiy/dev/mockkhttp/proxy/GlobalOkHttpInterceptorServer.kt`
The correctness centre. Every item here is a real bug today.

1. **UTF-8 on both ends.** `:349` becomes `inputStream.reader(Charsets.UTF_8).buffered()` and `PrintWriter(OutputStreamWriter(outputStream, Charsets.UTF_8), true)`. Currently platform default while both clients encode UTF-8 — corrupts every non-ASCII body, which an automated writer produces constantly.
2. **Snapshot before iterating.** `routeFlow`, `findTargetProject` (`:482`), `findTargetProjectForMockCheck` iterate `registeredProjects.values` **outside** the `synchronizedMap` monitor from a socket thread while the EDT mutates it. Take `.values.toList()` once at the top of each. A live `ConcurrentModificationException` today; a control thread flipping filters widens it, and `handleClient`'s generic catch swallows it into a dropped reply and a 60 s app hang.
3. **`handleClient` always answers.** The generic catch must write a fallback `ModifiedResponseData.original()` line. Then `socket.isClosed` before the write and `writer.checkError()` after; log `⚠️ app already gave up — response discarded` and propagate the boolean to `PendingInterceptRegistry` as `appNotified`.
4. **Populate `knownMockkHttpPackages` from *any* message carrying a package name.** Today only `PING:<pkg>` registers a package, and `MockkHttpInterceptor.kt:335` sends a bare `"PING\n"` — so **no native Android app ever registers**, and any readiness signal built on that set is a permanent lie on the plugin's flagship platform. `AndroidFlowData.packageName` and `MockCheckRequest.packageName` already carry it: record from those too. Plugin-only, fixes it immediately; the AAR `PING:<pkg>` change (D2) is then only an optimisation.
5. **Arm evaluation moves here.** `findMockForRequest` (`:543`) consults `ArmedStubStore.take(request)` **before** `MockkRulesStore.findMatchingRuleObject`, and its early-return guard is widened from `MOCKK|MOCKK_DEBUG` to also answer in `DEBUG` (the app's DEBUG branch already honours `hasMock`). **This is the only place in the codebase where substitution works** (see §0).
6. **Per-request mode.** `MockCheckResponse.mode` is computed from `PausePolicy.shouldPause(request)`, not from the registration's flat mode.
7. **Handshake fields.** Parse optional `MockCheckRequest.client{library,version,readTimeoutMs,dedup{enabled,windowMs},caps[]}`; emit optional `MockCheckResponse.directives{dedup{enabled},pauseBudgetMs}`. Gson ignores unknown fields both ways → fully backward compatible.
8. **Lifecycle.** Implement `Disposable`: close the `ServerSocket`, interrupt the accept loop, join briefly. There is currently **no `Disposable` anywhere in `src/main`** — the daemon thread and socket leak on every dynamic plugin unload and the reload then cannot bind.
9. **Bind status is truth.** `ensureStarted()` already returns `false` on `BindException` but `registerProject` ignores it. Expose `isBound()`, `boundPort()`, `heldByPid()` (best-effort via `lsof`/`netstat`, nullable) so `status` and `session start` can report `INTERCEPTOR_PORT_IN_USE` instead of claiming `RUNNING`.
10. `addRegistrationListener` gains `removeRegistrationListener`; `updateProjectMode` becomes `internal`; skip registrations whose `project.isDisposed`.
11. Bind to `InetAddress.getLoopbackAddress()` **unless** `SettingsStore.allowLanInterceptor` is on (needed only for the documented physical-iPhone `MockkHttp.init(host:)` path). Today it binds the wildcard, which lets anyone on the LAN forge a `FLOW` into `FlowStore` that the agent then reads as ground truth.

### `src/main/kotlin/com/sergiy/dev/mockkhttp/proxy/OkHttpInterceptorServer.kt`
1. **Delete `showInterceptDialogAndWait` (`:270–346`).** `handleFlow` calls `PendingInterceptRegistry.awaitDecision(flow, policy)`.
2. **Delete `findMatchingMockRule` and `matchesUrlPattern` (`:382`, `:398`, `:410`)** — the second, regex-flavoured matcher, and dead code besides (RECORDING/MOCKK replies are discarded). One matcher survives, in `MockkRulesStore`.
3. **Delete the 5-minute latch.** Deadline comes from `PausePolicy`.
4. `saveMockRuleFromDialog(flowData, dialog, …)` (`:351`) → `saveMockRule(flow, ruleName, response, collectionId): Result<MockkRule>` — no Swing widget in the signature.
5. Add `fun getMode(): Mode`, `fun isRunning(): Boolean`, `fun addModeChangedListener(parent: Disposable, l: (Mode) -> Unit)`. Both fields are private `@Volatile` with no accessors today, so *"am I recording?"* is unanswerable and a programmatic `setMode` leaves the radio buttons lying.
6. `start()` returns `sealed class StartResult { STARTED; ALREADY_RUNNING; FAILED(reason) }` instead of a `Boolean` where `false` means "already running" and reads as failure.
7. `handleFlow` becomes `internal`; after a pause resolves, write the flow back with `paused = false` and the real `resolution`.

### `src/main/kotlin/com/sergiy/dev/mockkhttp/store/MockkRulesStore.kt`
1. **Thread safety first.** `rules` is a bare `mutableListOf` iterated **directly** by `findMatchingRuleObject` (`:312`) from a socket thread while the EDT adds/removes. Back it with a `LinkedHashMap<String, MockkRule>` (preserving the insertion order the matcher depends on) guarded by a `ReentrantReadWriteLock`, and iterate a snapshot in the matcher. **This lands before any programmatic write path.**
2. **UUID ids.** `addRule` `:118`, `addCollection` `:153`, `duplicateRule` `:242`, migration default `:87`. `"rule_" + System.currentTimeMillis()` **collides** whenever several rules are created in one millisecond — exactly what batch creation and any automated caller do. Migration: existing persisted ids are kept verbatim on load; only new ids are UUIDs.
3. **By-id API:** `getRule(id)`, `removeRule(id): Boolean`, `setRuleEnabled(id, Boolean): Boolean`, and `updateRule(ruleId, name?=null, method?=null, structuredUrl?=null, statusCode?=null, headers?=null, content?=null, pathMatch?=null, hostMatch?=null): MockkRule?` **preserving the id**. The in-place mutation already exists inside `applyMergeImport`'s REPLACE branch (`:791–796`) — lift it out.
4. **Explicit match semantics on the model:** `MockkRule.pathMatch: MatchMode = EXACT`, `hostMatch: MatchMode = EXACT`. Migration sets both to `EXACT` for every persisted rule and raises **one** notification listing rules whose `host`/`path` contains regex metacharacters (`. * + ? ^ $ [ ] ( ) | \`), with a "Convert to REGEX" action. This is the compatibility cost of deleting `matchesUrlPattern` and it must be paid visibly.
5. `findAllMatchingRules(method, host, path, query, headers)` returning per-candidate rejection reasons — powers `mockkhttp_match_explain`.
6. **Events:** `addRuleUpdatedListener`, `addCollectionUpdatedListener`, fired from `updateRule`, `setRuleEnabled`, `moveRule`, `updateCollection`, `removeAllCollections` (all silent today, leaving the tree showing phantoms). Wrap **every** listener invocation in `try/catch` as `FlowStore` already does.
7. **Invariants move down from the UI:** `getRuleSignature`, `areRulesIdentical`, `findConflictingRules`, `syncCollectionStateWithRules` move from `MockkRulesPanel` (`:702`, `:800`, `:814`, `:874`), plus a new `enableRuleExclusively(ruleId): List<MockkRule>` returning what it disabled. UI and control plane then share one invariant.
8. **Validation:** `addRule`/`updateRule` reject an unknown/empty `collectionId`, an empty `host` or `path`, and a `REGEX` query param that does not compile — return a typed failure instead of persisting a permanently unmatchable rule.
9. Delete the dead private `matchesStructured` (`:430`). Replace `clearAllListeners()` (`:505`) with `Disposable`-scoped subscriptions — `MockkRulesPanel.kt:117` calls it on **every** tool-window construction and would silently unsubscribe the control plane.

### `src/main/kotlin/com/sergiy/dev/mockkhttp/store/FlowStore.kt`
`getFlow(flowId)`, `findFlows(method?, host?, urlContains?, pathRegex?, statusMin?, statusMax?, resolution?, sinceSeq?, limit)`, a monotonic `seq` per flow (drives every cursor), `exportFlowsJson(): String` (lifted out of the private `InspectorPanel.exportFlows`, `:1278`). `totalFlowsReceived`/`pausedFlowsCount` become `AtomicInteger`; `addFlow`'s check-then-put and the eviction loop get synchronized. Replace `clearAllListeners()` (`:222`) with `Disposable`-scoped removal — `InspectorPanel.kt:347` calls it in its constructor. Clear `paused` when a pause resolves.

### `src/main/kotlin/com/sergiy/dev/mockkhttp/ui/InspectorPanel.kt`
**Demoted from state machine to view.** Lift the body of `start(mode)` (`:1135`: package-filter derivation, `ensureStarted`, `setupAdbReverse` ordering, mode mapping) and `stop()` (`:1238`) into `CaptureSessionService`. `getCurrentSelectedMode()` (`:1063`) stops being the source of truth — mode lives in the service; the radio/checkbox become setters and repaint from `onSessionStateChanged`. `selectedEmulator`, `selectedApp`, `lastSelectedSerial`, `lastSelectedPackage` move into the service; the `isRebuildingDeviceCombo`/`isRebuildingAppCombo` guards (`:85–89`) stay in the view — they are pure Swing-event artifacts. The auto-scan governor (`:63–125`: per-serial cooldown, exponential backoff, global budget, opt-out) moves to `DeviceScanCoordinator` so control-plane scans are rate-limited by the same brakes that exist to stop adbd flapping. Replace the start-failure `JOptionPane` (`:1222`) with a `Notification` — a modal popped from a background task with nobody to dismiss it stalls an unattended run. Add: the `⏸ paused — awaiting agent (38 s) [Take over]` row, the `🤖 Agent connected` chip, and an intercept-owner toggle. Delete `flowStore.clearAllListeners()` (`:347`).

### `src/main/kotlin/com/sergiy/dev/mockkhttp/ui/DebugInterceptDialog.kt`
Add `fun closeBecauseResolvedElsewhere(by: ResolvedBy, summary: String)` — closes on the EDT via `SwingUtilities.invokeLater` with `CANCEL_EXIT_CODE`, **suppresses the save-as-mock side effect**, shows a toast naming what was sent. Add a live countdown bound to `deadlineAtMs`, a `🤖 Claude is also watching this request` badge (constructor flag), and a **Hold** button setting `humanHold`. Publish the outcome as an `InterceptDecision` into the registry instead of being read via four getters after `showAndGet()`. Keep the raw body canonical and pretty-print only for display — today `formatJsonIfPossible` (`:765`) means pressing *Continue with Modified* without touching anything re-sends re-indented bytes under the original `Content-Length`.

### `src/main/kotlin/com/sergiy/dev/mockkhttp/ui/MockkRulesPanel.kt`
Delete the four invariant helpers (now in the store) and call the store's. Subscribe to `ruleUpdated`/`collectionUpdated`, wrapped in `invokeLater` like the existing four. Replace `clearAllListeners()` with `Disposable`-scoped registration.

### `src/main/kotlin/com/sergiy/dev/mockkhttp/ui/CreateMockDialog.kt` / `BatchCreateMockDialog.kt`
`CreateMockDialog.doOKAction` (`:722`) stops doing `removeRule(existing) + addRule(...)` (which regenerates the id) and calls `updateRule(id, …)`. `BatchCreateMockDialog`'s rule-building logic (name generation, `(method,host,path)` dedup, uniquification, `:187`) moves out of `doOKAction` into a plain function returning `BatchResult{created, skipped, failed}` so counts become data instead of a `Messages.showInfoMessage`.

### `src/main/kotlin/com/sergiy/dev/mockkhttp/store/SettingsStore.kt`
New keys: `agentControl: FULL|READ_ONLY|OFF` (default `FULL`, D4), `agentControlPreferredPort: Int = 0`, `pauseDeadlineMs: Int = 45000` (coerced `1000..55000`), `allowRevealSecrets: Boolean = false`, `allowLanInterceptor: Boolean = false`, `bridgeAutoVendor: Boolean = true`. Every setter returns `Boolean`/`Result` — `setInterceptorPort` currently rejects an out-of-range port by logging a warning and returning `Unit`, so an automated caller gets a success-shaped no-op. Either wire `getInterceptorPort()` into `ensureStarted()` or delete the field from `SettingsPanel`; it is editable today and completely ignored.

### `src/main/kotlin/com/sergiy/dev/mockkhttp/ui/SettingsPanel.kt`
New **Agent Control (AI / Claude Code)** section: the three-state toggle, read-only display of the bound control port + instance-file path + bridge SHA-256, buttons **Write `.mcp.json` into the project root**, **Copy `claude mcp add` command**, **Generate agent skill**, **Rotate token**, **Allow agent to reveal request/response secrets** (default off), the pause-deadline spinner, and a live *last agent activity* line.

### `src/main/kotlin/com/sergiy/dev/mockkhttp/ui/MockkHttpToolWindow.kt`
Register a fifth tab, **Agent**, rendering `AgentAuditLog`'s ring buffer (timestamp, client, action, target, outcome, the agent's `note`) with a **Revoke token** button. Non-negotiable: an agent rewriting your traffic must be visible somewhere a human looks.

### `src/main/kotlin/com/sergiy/dev/mockkhttp/ui/HelpPanel.kt`
New **AI / Claude Code** section with the live setup command and a worked prompt. **Do not touch** `GRADLE_PLUGIN_VERSION` / `FLUTTER_PACKAGE_VERSION` (`:38–39`) unless the corresponding packages actually ship (D2).

### `src/main/kotlin/com/sergiy/dev/mockkhttp/startup/MockkHttpStartupActivity.kt`
After the existing ADB check: `AgentControlServer.getInstance().ensureStarted()`, `InstanceRegistry.getInstance().refresh(project)`, `BridgeVendor.writeIfChanged()`. Non-fatal — a bind failure logs and notifies, never blocks the plugin. **This is what makes the plugin drivable without ever opening the tool window** (today `okHttpInterceptorServer.start()` has exactly one call site, inside the lazily-built Swing panel).

### `src/main/kotlin/com/sergiy/dev/mockkhttp/adb/EmulatorManager.kt`
Memoize the resolved ADB path in a `@Volatile` field invalidated on settings change — `getConfiguredOrDetectedAdbPath()` re-runs the full macOS search (`bash -l`, `zsh -l`, `mdfind -onlyin /`) on **every** `setupAdbReverse`. Fix all four `ProcessBuilder` sites (`:216–217`, `:230–231`, `:325–326`, `:472–474`) to `waitFor`-then-read: they currently `readText()` **before** `waitFor`, so a hung login shell blocks **forever** — unacceptable behind a tool call. Make `initialize()` `@Synchronized` with `@Volatile` fields. Add `removeDeviceChangeListener`. Return a typed `DeviceListResult.Ok | Failed(reason)` so `devices` can distinguish "ADB broke" from "no devices". Extract `ensureTransport(device): PrepareResult` (`ensureStarted` → `setupAdbReverse` → verify with `adb reverse --list`) so the ordering rule — a refused connection costs the app a 15 s backoff — lives in exactly one place.

### `src/main/kotlin/com/sergiy/dev/mockkhttp/adb/AppManager.kt`
Add a `quickScan` path that stops after the PING/marker/`run-as` probes (sub-second, no APK pull) and honours an explicit `budget: Duration`. Move the per-serial in-flight claim out of `InspectorPanel.scanningSerials` into the manager so a control-plane scan concurrent with the UI cannot oversubscribe the USB transport (the dispatcher caps at 3). Surface `ScanTally` (failures / oversized skips / budget skips) in the result instead of only the log.

### `src/main/resources/META-INF/plugin.xml`
Three additions, all public non-deprecated EPs, **no new `<depends>`** (so `sinceBuild=243` survives and `verifyPluginProjectConfiguration` stays clean):
```xml
<projectListeners>
  <listener class="com.sergiy.dev.mockkhttp.agent.InstanceFileProjectListener"
            topic="com.intellij.openapi.project.ProjectManagerListener"/>
</projectListeners>
<statusBarWidgetFactory id="MockkHttpAgentControl"
        implementation="com.sergiy.dev.mockkhttp.ui.AgentControlStatusBarWidgetFactory"/>
<notificationGroup id="MockkHttp Agent" displayType="BALLOON" toolWindowId="MockkHttp"/>
```
The listener implements **only `projectClosed`** — `ProjectManagerListener.projectOpened` and `canCloseProject` carry `Deprecated: true` in the shipped `app.jar` bytecode, and `CLAUDE.md` forbids deprecated APIs. The *open* half comes from the existing `<postStartupActivity>`. Note `statusBarWidgetFactory` requires the `id` attribute in 2024.3.

### `build.gradle.kts` / `settings.gradle.kts`
`include(":mcp-bridge")`. **No new plugin dependencies** — `com.sun.net.httpserver` comes from the JBR (`jdk.httpserver` is in its module list) and Gson 2.13.2 is already declared. Add a `vendorBridge` task copying `mcp-bridge`'s shadow jar into `src/main/resources/bridge/mockkhttp-mcp.jar` plus a `.sha256`, wired as a `processResources` dependency so the vendored copy can never drift. Version 1.7.0 → per-milestone, plus a `changeNotes` `<h3>` block each time.

### Client track (decision D2)
- `android-library/src/main/kotlin/.../MockkHttpInterceptor.kt`: send `PING:<packageName>` (`:335`); include `client{library,version,readTimeoutMs,dedup{enabled,windowMs},caps:["dedupControl","lineFramed"]}` in `CHECK_MOCK`; honour `directives.dedup.enabled` and `directives.pauseBudgetMs`. Bump `android-library/build.gradle.kts:13` **and** the cache-busting dir at `gradle-plugin/src/main/kotlin/.../MockkHttpGradlePlugin.kt:122` in the same commit — a stale cache dir silently ships the old AAR.
- `flutter-package/lib/src/mockk_http_client.dart`: **replace `await socket.first` at `:242` (`sendFlowAndWait`), `:212` (`checkForMock`) and `:167` (`ping`)** with `await utf8.decoder.bind(socket).transform(const LineSplitter()).first`. `socket.first` resolves on the **first TCP data event**, so any body over roughly 1.4 KB arrives split, `jsonDecode` throws into a bare `catch (_)`, and the app silently uses the **original** response. Agent-authored JSON exceeds that constantly and it fails invisibly. Same handshake/directive fields as Android. **Publish to pub.dev before the Marketplace release.**

---

## 6. NEW FILES

Package root: `com.sergiy.dev.mockkhttp`, under `src/main/kotlin/com/sergiy/dev/mockkhttp/`.

### `control/` — the HTTP control plane
| File | Purpose |
|---|---|
| `control/AgentControlServer.kt` | `@Service(APP)`, `Disposable`. Owns the `HttpServer` on `InetAddress.getLoopbackAddress()`, ephemeral port; **its own** daemon `ThreadPoolExecutor` (core 2, max 16, 60 s keepalive, named `MockkHttp-Control-N`) — **not** the shared app pool, which 25 s long-polls would starve. `dispose()` drains: flag shutdown → complete pending intercepts → wake long-polls → brief join → `httpServer.stop(0)` → shut executor. Never binds in the constructor. |
| `control/ControlRouter.kt` | Path/verb dispatch for `/v1/**`, the `/v1` version gate, the uniform error envelope, the 8 MB body cap, and a single `try/catch` turning any `Throwable` into a typed 500. |
| `control/ControlAuth.kt` | 32-byte `SecureRandom` token, base64url; `MessageDigest.isEqual` constant-time compare; loopback peer check; **browser lockout** (403 on any `Origin`/`Referer`/`Sec-Fetch-Site`, or `User-Agent` starting `Mozilla/`, or a non-loopback `Host`); mandatory `X-MockkHttp-Client` header; 600 req/min token bucket; `READ_ONLY` enforcement; a **long-poll semaphore of 2 per project** → `429 TOO_MANY_WAITERS`. |
| `control/ControlApi.kt` | **The façade.** Pure Kotlin, no HTTP types, no MCP types, no Swing. Every REST route and every future adapter calls these methods. This is the unit-testable core. |
| `control/dto/ApiDtos.kt` | Every wire DTO in one file, Gson, `@SerializedName` snake_case. The single definition of the REST contract; `docs/AGENT_API.md` is generated from it. |
| `control/handlers/MetaHandler.kt` | `GET /v1/meta` (apiVersion, pluginVersion, ide, capabilities, limits), `GET /v1/projects`, `GET /v1/docs`. |
| `control/handlers/SessionHandler.kt` | `session`, `devices`, `app` routes; translates `PrepareResult`/`StartResult` into typed error codes. |
| `control/handlers/FlowsHandler.kt` | `flows` list/get/clear + `flows/await`; redaction, base64 fallback, honest truncation flags. |
| `control/handlers/MocksHandler.kt` | Rule/collection CRUD, `from_flow_id`, exclusive enable, `explain`, export, dry-run import via `analyzeImport`. Calls `SaveAndSyncHandler.getInstance().scheduleProjectSave(project)` after mutations so a crash cannot lose the agent's work. |
| `control/handlers/InterceptsHandler.kt` | `arms` CRUD, `pause-policy`, `intercepts` snapshot / `next` (bounded long-poll) / `resolve` / `resolve_all`. |
| `control/handlers/RunHandler.kt` | `runs` start/end/get, `verify`. |

### `agent/` — discovery, audit, bridge
| File | Purpose |
|---|---|
| `agent/InstanceRegistry.kt` | `@Service(APP)`. Writes `~/.mockkhttp/instances/<instanceId>.json` atomically (tmp + `fsync` + `ATOMIC_MOVE`); prunes files whose pid is dead; refreshes on bind, project open/close, session change, token rotation; deletes on `dispose()`. **Permissions:** if `FileSystems.getDefault().supportedFileAttributeViews().contains("posix")` set `rwx------`/`rw-------` and refuse to start if that fails; otherwise apply an owner-only `AclFileAttributeView` and, if *that* fails, degrade to a **loud persistent warning** in the Agent tab plus a shortened token lifetime — never brick the feature on a roaming Windows profile. |
| `agent/InstanceFileProjectListener.kt` | `ProjectManagerListener`, **`projectClosed` only** (see §5 plugin.xml). Keeps `projects[]` honest so the bridge never resolves a disposed project. |
| `agent/AgentAuditLog.kt` | `@Service(APP)`. 500-entry ring of every mutating call, mirrored into `MockkHttpLogger` under a `🤖 AGENT` channel; `GET /v1/audit`; fires the first-connection balloon with a **Revoke** action. |
| `agent/BridgeVendor.kt` | Writes `/bridge/mockkhttp-mcp.jar` from plugin resources to `~/.mockkhttp/bin/mockkhttp-mcp.jar` when the SHA-256 differs, generates the OS-appropriate launcher (`mockkhttp-mcp` sh / `mockkhttp-mcp.cmd`) pinned to the IDE's own JBR `java`, chmod 700, records `bridge.sha256` in the instance file. |
| `agent/McpConfigWriter.kt` | On explicit user action only (a Settings button), **merges** `<project>/.mcp.json` — touching only `mcpServers.mockkhttp`, preserving all other formatting. Never runs automatically; never writes to `.gitignore`; never touches a file the IDE has open without going through `WriteAction` + `FileDocumentManager`. |

### `intercept/`
| File | Purpose |
|---|---|
| `intercept/PendingInterceptRegistry.kt` | `@Service(PROJECT)`, `Disposable`. The map, the futures, first-wins `resolve`, `awaitNext` long-poll, the `recentlyClosed` ring, the deadline sweeper on `AppExecutorUtil.getAppScheduledExecutorService()`, and a **32 MB global byte budget** on retained untruncated bodies. |
| `intercept/InterceptModels.kt` | `PendingIntercept`, `InterceptDecision` (`Passthrough` \| `Respond` \| `ApplyRule` \| `ApplyPreset`, each with optional `saveAsRule` + `note`), `ResolvedBy` (`HUMAN`\|`AGENT`\|`ARM`\|`DEADLINE`), `ResolveOutcome`, `InterceptContext`, `PausePolicy`. |
| `intercept/InterceptResolver.kt` | `interface InterceptResolver { fun onPaused(p); fun onResolved(p, by) }`. |
| `intercept/SwingDialogResolver.kt` | Today's dialog code, moved verbatim, plus the `isResolved` pre-check, the reference publication, and `SwingUtilities.invokeLater` with the explanatory comment. |
| `intercept/ControlPlaneResolver.kt` | Signals the long-poll condition; no UI. |
| `intercept/ArmedStubStore.kt` | `@Service(PROJECT)`. `CopyOnWriteArrayList<ArmedStub>` with `skip`/`times`/`responses[]`/`ttl`; `take(request)` atomically decrements and returns the first live match; TTL reaper; `run_id` association for auto-disarm. **Consulted from `findMockForRequest`.** |

### `session/`
| File | Purpose |
|---|---|
| `session/CaptureSessionService.kt` | `@Service(PROJECT)`, `Disposable`. `{targetDevice, targetApp, mode, running}` + `startSession(serial, packageName, mode): StartResult` (`ensureStarted` → `ensureTransport` → `server.start` → `setPackageNameFilter`), `stopSession`, `setMode`, `setPackageFilter`, `state()`, `addStateListener(Disposable, …)`. `packageName` is a **required** parameter. Unregisters on dispose (nothing does that today, so the global server keeps a hard `Project` reference for every closed project). |
| `session/DeviceScanCoordinator.kt` | `@Service(PROJECT)`. The per-serial claim, cooldown, exponential backoff and global scan budget, lifted out of `InspectorPanel`. |
| `session/TransportPreparer.kt` | `ensureTransport(device): PrepareResult` — platform switch: Android = `ensureStarted` + `adb reverse` + verify; iOS Simulator = no-op; iOS device = documented LAN precondition + reachability hint. |

### `match/` and `run/`
| File | Purpose |
|---|---|
| `match/RequestMatcher.kt` | The one `Matcher` evaluator, shared by arms, pause policy, flow queries and verify. Compiled regexes cached in a bounded `ConcurrentHashMap`. |
| `run/RunRegistry.kt` | `@Service(PROJECT)`. Run lifecycle, previous-mode restore, TTL reaper, `RunReport` assembly, auto-disarm of run-scoped arms. |
| `run/Journal.kt` | Per-run view over `FlowStore` by `seq` range, plus untruncated body retention for the run window under a byte budget. |
| `run/Verifier.kt` | Expectation evaluation (`count`, `nth`, `expect_status`, `body_json_path`, `ordered`) with actionable `diagnosis` strings that cite arms, matchers and the dedup warning. |

### UI
| File | Purpose |
|---|---|
| `ui/AgentAuditPanel.kt` | The **Agent** tab: live audit rendering, filters, Revoke. |
| `ui/AgentControlStatusBarWidgetFactory.kt` + `ui/AgentControlStatusBarWidget.kt` | `🤖 MockkHttp AI ●` when a client has called in the last 60 s; click → Agent tab / Revoke. |

### `mcp-bridge/` (new Gradle subproject, **must not** apply `intellijPlatform`)
```
mcp-bridge/build.gradle.kts                 // Kotlin JVM 21, only dep Gson, shadow-style fat jar
mcp-bridge/src/main/kotlin/com/sergiy/dev/mockkhttp/bridge/Main.kt        // stdio loop; never throws on startup
mcp-bridge/src/main/kotlin/.../McpProtocol.kt   // dual-era: legacy `initialize` + modern `server/discover`,
                                                // _meta protocolVersion, MCP-Protocol-Version / Mcp-Method /
                                                // Mcp-Name validation (-32020), -32022, resultType:"complete"
mcp-bridge/src/main/kotlin/.../Discovery.kt     // instance-file glob, pid liveness, longest-prefix cwd match,
                                                // MOCKKHTTP_PROJECT / MOCKKHTTP_BASE_URL+TOKEN overrides
mcp-bridge/src/main/kotlin/.../RestClient.kt    // java.net.http.HttpClient; bearer + X-MockkHttp-Client;
                                                // error envelope → isError translation
mcp-bridge/src/main/kotlin/.../Tools.kt         // the 15 tool schemas + descriptions — the ONLY place wording lives
mcp-bridge/src/main/kotlin/.../Format.kt        // summarisation, 20k-char truncation with an explicit note
```

### Docs & tests
```
docs/AGENT_API.md            // versioned REST contract: routes, DTOs, error codes, budget table, compat policy
docs/AI_AGENT_GUIDE.md       // what mockkhttp_docs serves verbatim; written for a model
docs/mcp/.mcp.json.template
docs/skill/SKILL.md.template // .claude/skills/mockkhttp/SKILL.md generator source
src/test/kotlin/.../match/RequestMatcherTest.kt
src/test/kotlin/.../store/MockkRulesStoreConcurrencyTest.kt
src/test/kotlin/.../control/ControlApiTest.kt              // binds an ephemeral port, drives java.net.http.HttpClient:
                                                           // auth rejection, browser 403, error envelope, clamping
src/test/kotlin/.../intercept/PendingInterceptRegistryTest.kt  // the race matrix: agent wins / human wins / arm wins /
                                                           // deadline wins / double-resolve / bad token / human hold /
                                                           // concurrent pauses / loser's dialog actually closes
src/test/kotlin/.../intercept/ArmedStubStoreTest.kt         // skip/times/sequence/ttl/exactly-once under concurrency
mcp-bridge/src/test/kotlin/.../McpProtocolTest.kt           // golden files for both eras
```
`src/test` is currently empty and was never in git. These are the project's first real tests and they are **deliverables, not nice-to-haves** — the intercept race and the MCP framing are exactly the code that cannot be validated by clicking.

---

## 7. SECURITY MODEL

**Threat model, stated honestly.** This channel can rewrite the HTTP responses a debug build receives and can read captured bodies containing live credentials. It cannot execute code, read arbitrary files, or reach the network. The realistic attackers are (a) a web page in the developer's browser, (b) another user on a shared machine, (c) a malicious package in the developer's own toolchain. **A same-uid process is explicitly out of scope** — it can already read `~/.ssh`, `~/.aws`, the Docker socket and the IDE's own `user.token`, and write `.git/hooks/pre-commit`. Pretending otherwise would be theatre. The goal is to add **no new remote surface**, keep the blast radius inside MockkHttp, and make every action visible and reversible.

1. **Loopback only, always.** `HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 64)`. Plus a defensive per-request check that `exchange.remoteAddress` is loopback. No setting changes this. **Also: the existing 9876 interceptor socket moves to loopback** unless `allowLanInterceptor` is explicitly enabled for the physical-iPhone path — it binds the wildcard today, which lets anyone on the LAN forge a `FLOW` that the agent then reads as ground truth.
2. **Anti-browser / anti-DNS-rebinding.** 403 — *before* auth — on any request carrying `Origin`, `Referer` or `Sec-Fetch-Site`, or whose `User-Agent` starts with `Mozilla/`, or whose `Host` is not `127.0.0.1[:port]` / `localhost[:port]` / `[::1][:port]`. Every request must also carry `X-MockkHttp-Client` — a non-simple header, so a form POST cannot reach us without a preflight, which we answer 403. A rebound `evil.com` resolving to 127.0.0.1 is stopped before authentication and never learns the port (ephemeral) or the token.
3. **Bearer token, 256-bit, rotated every IDE start.** `SecureRandom` → 32 bytes → base64url. Constant-time compare via `MessageDigest.isEqual`. **The token never enters VCS or an env var**: the bridge runs as the same user on the same machine and simply reads the instance file. `.mcp.json` contains no secret and is safe to commit.
4. **Filesystem permissions with a sane Windows path.** `~/.mockkhttp` `rwx------`, instance files `rw-------` on POSIX (refuse to start if that fails); owner-only ACL on Windows; if the ACL cannot be applied, a loud persistent warning + shortened token lifetime rather than a bricked feature.
5. **Capability scoping — the blast radius is a closed vocabulary.** No `exec`, no `read_file`, no `eval`, no arbitrary path parameter, no network verb. `body_file`-style references (if ever added) resolve only under `<project>/.mockkhttp/fixtures/` with normalisation + `startsWith` containment. `AGENT_CONTROL_READ_ONLY` 403s every mutating verb.
6. **Secrets redacted by default.** `Authorization`, `Proxy-Authorization`, `Cookie`, `Set-Cookie`, `X-Api-Key`, `X-Auth-Token` → `<redacted:32b>`. `include_secrets:true` requires the per-project Settings toggle (default off) and returns `403 REVEAL_DISABLED` otherwise. The toggle **auto-expires after 60 minutes** and logs when it does. `body_json_path` in `verify` lets the agent assert on a body it may not read.
7. **Rate limits and caps.** 600 req/min per token → 429; 8 MB request body → 413; `wait_ms` clamped to 25 000; long-poll concurrency capped at 2 per project → `429 TOO_MANY_WAITERS`; bounded executor.
8. **Intercept resolution is capability-gated.** Answering requires the `interceptToken` handed out with that specific pending flow. Guessing a `flowId` is not enough; stale answers from a previous run are rejected; answers after the deadline are rejected.
9. **Visibility and reversibility — the real security story on a single-user box.** `AgentAuditLog` ring buffer → Logs tab (`🤖` prefix) + the **Agent** tab. Status-bar widget lit whenever a client has called within 60 s. A balloon on the first connection from an unseen `X-MockkHttp-Client` with a **Revoke** action that regenerates the token and rewrites the instance file, instantly breaking every connected bridge. `SettingsStore.agentControl = OFF` closes the socket and deletes the instance file.
10. **Supply chain.** The default documented path executes only the **plugin-vendored** bridge jar, whose SHA-256 the plugin knows and reports in `status`; the jar is produced by the same Gradle build and Marketplace-signed with the plugin. No npm, no registry, no `npx`, nothing to compromise.
11. **Deliberately not done.** No OAuth, no mTLS, no per-tool permission prompts, no peer-process authentication. These add ceremony without changing the threat model on a single-user dev box, and every prompt an automated test must click through defeats the feature.

---

## 8. USER SETUP

### Steps

1. **Update MockkHttp** in Android Studio (Settings → Plugins) and restart. On startup the plugin binds the loopback control server, writes `~/.mockkhttp/instances/<id>.json` (mode 0600), and vendors the bridge + launcher to `~/.mockkhttp/bin/`. Nothing else happens until an agent connects. *No Node, no npm, no CLI, no PATH change — the bridge runs on the JBR that Android Studio already ships.*
2. **Open the app project in Android Studio** — the same directory you will run `claude` from. This is what makes project auto-resolution work; there is no ID to copy anywhere.
3. **MockkHttp tool window → Settings → Agent Control (AI / Claude Code).** Confirm it reads **Full** and shows a bound port. Click **Write `.mcp.json` into the project root**. *(If your team is mixed macOS/Windows, click **Copy `claude mcp add` command** instead and gitignore `.mcp.json` — see the note below.)*
4. **Inspector tab:** pick your device and app, press **Start**. *(From Milestone 4 the agent can do this itself with `mockkhttp_session(action:"start")`; doing it once by hand is the fastest way to confirm the plumbing.)*
5. **Open the Android Studio terminal** (⌥F12 / Alt+F12), `cd` to the repo root, run `claude`. Approve the project-scoped MCP server at the trust prompt. `/mcp` should show `mockkhttp: connected · 15 tools`.
6. **Verify:** *"use mockkhttp_status"*. Expect it to name your IDE, project, resolved path, device and mode. If it says the working directory matches no open project, you are running `claude` from a subdirectory of a different repo — `cd` to the project root, or add `"MOCKKHTTP_PROJECT": "MyApp"` to the env block.
7. **First real loop:** *"Open the login screen. Tell me what calls it made, then make POST /v1/login return 503 on the second attempt and check the retry banner."* Claude calls `mockkhttp_run(start)` → `mockkhttp_arm({match:{method:'POST',path:'/v1/login'}, skip:1, times:1, respond:{status_code:503}})` → drives the app → `mockkhttp_await_flow` → `mockkhttp_verify` → `mockkhttp_run(end)`.
8. **Optional, recommended:** Settings → **Generate agent skill** writes `.claude/skills/mockkhttp/SKILL.md`. The skill teaches the workflow (arm first, live intercept only when you must, always `status` first); the MCP tools stay the primitives. This is what Proxyman and WireMock both ship and it is the difference between a model that guesses and one that knows.
9. **To stop:** Settings → Agent Control → **Off**, or the status-bar **Revoke token**. The socket closes, the instance file is deleted, every bridge instantly loses access.

### `<project>/.mcp.json` — exact contents (macOS / Linux)

```json
{
  "mcpServers": {
    "mockkhttp": {
      "type": "stdio",
      "command": "${HOME}/.mockkhttp/bin/mockkhttp-mcp",
      "env": {
        "MOCKKHTTP_PROJECT_DIR": "${CLAUDE_PROJECT_DIR}"
      }
    }
  }
}
```

Windows variant (written automatically when the IDE runs on Windows):

```json
{
  "mcpServers": {
    "mockkhttp": {
      "type": "stdio",
      "command": "${USERPROFILE}\\.mockkhttp\\bin\\mockkhttp-mcp.cmd",
      "env": {
        "MOCKKHTTP_PROJECT_DIR": "${CLAUDE_PROJECT_DIR}"
      }
    }
  }
}
```

**No port. No token. No project id.** All three are discovered at bridge launch from `~/.mockkhttp/instances/*.json`, so the file survives IDE restarts, port walks and token rotation, and is safe to commit and share — every teammate's IDE writes their own instance file and their own launcher. **Mixed-OS teams:** commit nothing and have each developer click **Write `.mcp.json`**, or commit both entries under different server names.

### `~/.mockkhttp/instances/<instanceId>.json` — written by the plugin (0600)

```json
{
  "schema": 1,
  "apiVersion": "1.0",
  "instanceId": "ij-48213-a3f9c1",
  "pid": 48213,
  "startedAt": "2026-09-01T10:22:31Z",
  "ide": { "name": "Android Studio", "build": "AI-243.26053.27", "pluginVersion": "1.11.0" },
  "control": { "baseUrl": "http://127.0.0.1:53411/v1", "token": "b7Qm9…43chars", "scheme": "Bearer" },
  "bridge": { "path": "/Users/sergiy/.mockkhttp/bin/mockkhttp-mcp.jar", "sha256": "9f2c…" },
  "interceptor": { "port": 9876, "bound": true, "ownedByThisProcess": true, "heldByPid": null },
  "agentControl": "full",
  "projects": [
    { "projectId": "a1b2c3d4", "name": "MyApp", "basePath": "/Users/sergiy/dev/MyApp",
      "sessionRunning": true, "mode": "MOCKK", "packageFilter": "com.acme.myapp",
      "device": { "serial": "emulator-5554", "platform": "ANDROID" } }
  ]
}
```

### Escape hatches (documented, never needed on the happy path)

```bash
# running claude outside the project root, or from another MCP client
claude mcp add --transport stdio mockkhttp -- "$HOME/.mockkhttp/bin/mockkhttp-mcp"

# pin a project explicitly (env block in .mcp.json)
"MOCKKHTTP_PROJECT": "MyApp"                       # projectId, name, or instanceId:projectId

# bypass discovery entirely (CI, devcontainer, remote-dev, SSH port-forward)
"MOCKKHTTP_BASE_URL": "http://127.0.0.1:53411/v1"
"MOCKKHTTP_TOKEN":    "b7Qm9…"
```

---

## 9. BUILD ORDER

Honest estimate: **7–9 weeks solo**, not 3. The judges were unanimous that the winning proposal's phase sizing was ~2× optimistic, in a 13 k-LOC codebase with zero existing tests and a four-registry manual release process. Each milestone is independently shippable.

---

### M0 — Correctness only, no features (1.7.1) · **1.5 weeks**
Every item is a real bug that harms humans today; the changelog mentions no AI.

- UTF-8 pinning on both socket ends; snapshot iteration in `routeFlow`/`findTargetProject`/`findTargetProjectForMockCheck`; `socket.isClosed` + `writer.checkError()` + guaranteed fallback reply.
- `knownMockkHttpPackages` populated from any packageName-bearing message (fixes native Android detection permanently).
- UUID rule/collection ids with a load-preserving migration; `ReentrantReadWriteLock` + snapshot in `MockkRulesStore`; `Disposable`-scoped listeners replacing both `clearAllListeners()`.
- **Matcher unification:** delete `matchesUrlPattern` + `findMatchingMockRule` + dead `matchesStructured`; add `pathMatch`/`hostMatch` with migration to `EXACT` and the one-time regex-metacharacter notification.
- ADB path memoization + `waitFor`-then-read on all four `ProcessBuilder` sites + `@Synchronized initialize()`.
- `GlobalOkHttpInterceptorServer` becomes `Disposable`; 9876 binds loopback unless opted out.
- Flutter `LineSplitter` framing fix → **pub.dev release** (D2; ship this regardless of D2's other items — it is silent data loss today).
- First tests: `RequestMatcherTest`, `MockkRulesStoreConcurrencyTest`.

**Done =** existing UI behaviour byte-for-byte identical; `./gradlew verifyPluginProjectConfiguration && ./gradlew verifyPlugin` clean; the two test classes pass; a mocked 5 KB JSON body reaches a Flutter app intact; a native Android app appears in `getKnownMockkHttpPackages()`.

---

### M1 — Control plane + read/mock/mode: **the vertical slice** (1.8.0) · **2 weeks**
Delivers the actual loop the user asked for while touching **none** of the risky code — no `handleFlow` change, no pause registry, no `InspectorPanel` extraction, no Swing, no wire-protocol change. Pure additive read + store-write over services that are already headless.

- `AgentControlServer`, `ControlAuth`, `ControlRouter`, `ControlApi`, `ApiDtos`, `InstanceRegistry`, `AgentAuditLog`, `BridgeVendor`, `McpConfigWriter`.
- Endpoints: `GET /v1/meta`, `/v1/projects`, `/v1/projects/{pid}/status`, `/flows` (+`/{flowId}`, DELETE, `/await`), `/mocks` (full CRUD + `explain` + dry-run import), `POST /session/mode`.
- `FlowStore.getFlow/findFlows/seq`; `MockkRulesStore.getRule/updateRule/enableRuleExclusively/findAllMatchingRules`.
- `:mcp-bridge` subproject + `vendorBridge` task; MCP tools: `status`, `flows`, `await_flow`, `mocks`, `match_explain`, `session(get|set_mode|clear_flows)`, `docs`.
- Settings section, Agent tab, status-bar widget, `docs/AGENT_API.md`, `AI_AGENT_GUIDE.md`.
- Tests: `ControlApiTest`, `McpProtocolTest`.

**Done =** from the Android Studio terminal, with the human having pressed Start once: *"tell me what the app called, then make GET /v1/user return 500"* works end to end, the app receives the 500 with no network call, and `mockkhttp_status.resolution` names the right project. Proves the two things most likely to be wrong — cwd project resolution and the bearer/instance-file handshake — before any intercept plumbing exists.

---

### M2 — Arms, runs, verify: **the automated-test substrate** (1.9.0) · **1.5 weeks**
- `ArmedStubStore` wired into `GlobalOkHttpInterceptorServer.findMockForRequest`, with the DEBUG widening. `skip` / `times` / `responses[]` / `passthrough` / `delay`, TTL reaper, run association.
- `RunRegistry`, `Journal`, `Verifier`; endpoints `/arms`, `/runs`, `/verify`.
- Tools: `arm`, `run`, `verify`. `mockkhttp_docs` gains `automated_test` and `arms_and_ordering`.
- Client-capability plumbing: `client{…}` in `CHECK_MOCK`, dedup warnings surfaced in `status`, `arm` and `verify.diagnosis`.
- Test: `ArmedStubStoreTest` — exactly-once `skip`/`times` under concurrent `CHECK_MOCK`.

**Done =** *"500 on the second call"* is **one** `mockkhttp_arm` call with `skip:1, times:1`, zero round trips of polling, and `mockkhttp_verify` asserts it with `nth:2`. `RunReport.unused_arms` catches a dead scenario. **This is the milestone that makes the user's stated goal real.**

---

### M3 — The pause (1.10.0) · **1.5 weeks**
- `PendingInterceptRegistry`, `InterceptModels`, `InterceptResolver`, `SwingDialogResolver`, `ControlPlaneResolver`, `PausePolicy`.
- `handleFlow` rewired; `findMockForRequest` returns the per-request mode; the 45 s deadline + `clientTimeoutMs` negotiation; the 5-minute latch deleted.
- `DebugInterceptDialog`: `closeBecauseResolvedElsewhere`, the `isResolved` pre-check, countdown, agent badge, **Hold**. `InspectorPanel`: the `⏸ awaiting agent [Take over]` row and owner toggle.
- Endpoints `/pause-policy`, `/intercepts{,/next,/{id}/resolve,/resolve_all}`; tools `pause_policy`, `await_intercept`, `resolve_intercept`.
- **Land the registry with `SwingDialogResolver` as the only resolver first** and verify Debug mode is byte-for-byte unchanged before wiring the control plane.
- Test: `PendingInterceptRegistryTest`, full race matrix including "the loser's dialog actually closes".

**Done =** a human and an agent are both attached; whoever answers first wins; the loser is told, with data; the dialog closes itself; a killed agent costs one `on_deadline`; `app_notified` is truthful; `owner:"agent"` never constructs a dialog; a background-polling app in DEBUG with a `matching` policy shows **zero** added latency on out-of-scope calls.

---

### M4 — Headless session + drive (1.11.0) · **1 week** (D3-B)
`CaptureSessionService`, `DeviceScanCoordinator`, `TransportPreparer` extracted from `InspectorPanel`; `DevicesHandler`; `session start/stop/set_app`; `mockkhttp_app` (launch/stop/restart/clear_data); `adb_path` + `serial` in `status`; project-close lifecycle cleanup. Highest regression risk of any milestone, which is exactly why it is last.

**Done =** from a cold IDE with nothing but the project open, the agent runs the full loop — start session, arm, restart app, drive with `adb input tap`, await, verify, end run — with **zero human clicks** after M1's one-time setup.

---

### M5 — optional, D3-C · `:testkit` JUnit5 extension + Maven Central. Separate decision, separate estimate.

**Parallel client track (D2-A):** `android-library` 1.8.0 (`PING:<pkg>`, `client{}` handshake, `directives` hono