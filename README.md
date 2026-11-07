# Bridge

Bridge lets Claude control your Android tablet using the **Claude app you already pay for**. No API key, no laptop.

What Bridge does (and nothing else):

1. **Tablet control:** Claude can read the screen, take screenshots, tap, swipe, type, go back or home, and open apps.
2. **Floating STOP button:** a red button you can drag anywhere. It stays where you leave it, even after a restart, and is always on top. One tap stops all control.
3. **No time limit:** control runs until you press STOP.
4. **Safety pause:** Bridge stops and shows **"YOUR TURN"** on password fields, OTP fields, payment screens and banking apps.
5. **Activity log:** a list of everything Bridge did, with timestamps.

---

## How the handoff works

```
Claude app (chat)  →  Anthropic's servers  →  your relay (free Cloudflare Worker)  ⇄  Bridge on your tablet
```

* Bridge is added to Claude as a **custom connector**, the official way to give Claude new tools.
* Claude's servers can't reach a tablet directly, so a tiny relay sits in between. It runs free in your own Cloudflare account. Bridge keeps one connection open to it while control is on.
* You chat with Claude normally ("open Settings and turn on dark mode"). Claude calls Bridge's tools (`screenshot`, `tap`, …) one step at a time.

**Why not let Bridge type into the Claude app and read its replies?** Anthropic's Consumer Terms forbid using the Claude apps "through automated or non-human means, whether through a bot, script, or otherwise". That approach could get your account suspended. It would also break whenever the Claude app's layout changes. The connector is a supported feature, so it avoids both problems.

### Limits and risks, in plain words

| Topic | What to expect |
|---|---|
| Cost | Free: Claude subscription you already have + Cloudflare free plan (100,000 requests/day; one tool call ≈ 1 request). |
| Claude plan | Custom connectors work on Free (reportedly 1 connector), Pro and Max. Add the connector on claude.ai in a browser; it then shows up in the Claude Android app. |
| Usage limits | Every screenshot uses part of your Claude usage allowance. A long task can hit your plan's message limit or fill the chat. If so, start a new chat and say "continue". |
| Speed | Each step takes a few seconds (screenshot → Claude thinks → action). |
| Seeing Claude while it works | Put the **Claude app and the target app side by side in split screen**. If the Claude app is in the background, Claude may not keep working. That has not been tested yet. |
| Privacy | Screenshots go to Anthropic (like any image you send Claude) and pass through your own Cloudflare relay. Bridge never sends screenshots of sensitive screens: it pauses first. |
| Connector URL | It contains a secret. Anyone who has it can control the tablet **while Bridge is started**. Don't share it. |
| Secure apps | Some apps (banking, Netflix, etc.) block screenshots. Claude will see an error or a black image there. |
| Safety detection | Bridge recognises these screens from words, field types and app names, so it can't recognise every bank or payment screen. It would rather pause too often than too rarely. |
| Signing key | The APK is signed with a key stored in this public repo, so each build installs as an update. Someone could use that key to build a fake "update". **Only install APKs from your own repo's Releases page.** (Optional: use your own key, see the end of this file.) |

---

## Step-by-step setup (tablet only)

You need: a GitHub account, a free Cloudflare account, the Claude app, and Chrome on the tablet.
Use Chrome in **desktop site** mode (⋮ → *Desktop site*) when a GitHub or Cloudflare page is hard to use.

### Part A: Build the APK

1. Open **github.com/vbkforge/BRIDGE-**. It is already public, so builds are free.
2. Tap **Actions**. If GitHub asks, tap **"I understand my workflows, go ahead and enable them"**.
3. Every push builds automatically. To build by hand: **Actions → Build Bridge APK → Run workflow → Run workflow**.
4. Wait for the green tick (about 4–6 minutes).
5. Open the repo's **Releases** page (right side of the main page, or `github.com/vbkforge/BRIDGE-/releases`).
6. In the newest **"Bridge build N"**, tap **`Bridge-N.apk`** to download it.
   (The same APK is also under *Actions → the run → Artifacts*, but that one is a zip.)

### Part B: Create the relay (one time)

1. Sign up at **dash.cloudflare.com** (free).
2. Open **Workers & Pages** once. If asked to pick a `workers.dev` subdomain, choose one (e.g. `yourname`).
3. **Account ID:** on the Workers & Pages page, find *Account ID* on the right (or under ⋮ / account menu). Copy it.
4. **API token:** profile icon → **My Profile → API Tokens → Create Token** → template **"Edit Cloudflare Workers"** → **Use template**. Under *Account Resources* pick your account and under *Zone Resources* pick *All zones*. Then tap **Continue to summary → Create Token** and copy the token (it is shown only once).
5. On GitHub: **repo → Settings → Secrets and variables → Actions → New repository secret**:
   * Name `CLOUDFLARE_API_TOKEN`, value = the token.
   * Name `CLOUDFLARE_ACCOUNT_ID`, value = the account ID.
6. **Actions → Deploy relay (Cloudflare) → Run workflow**. When it is green, open the run. The summary shows your **relay address**, e.g. `bridge-relay.yourname.workers.dev`.
7. Check it: open `https://bridge-relay.yourname.workers.dev` in Chrome. It should say **"Bridge relay is running."**

### Part C: Install Bridge and allow permissions

1. Open the downloaded `Bridge-N.apk`. If Android asks, allow **"Install unknown apps"** for Chrome (or your Files app), then tap **Install**.
2. Open **Bridge**.
3. **Accessibility:** tap **Open Accessibility settings** → find **Bridge** (may be under *Downloaded apps* / *Installed apps*) → turn it **On** → **Allow**.
   * **Switch greyed out / "Restricted setting"?** Go back to Bridge, tap **Open Bridge app info** → **⋮** (top right) → **Allow restricted settings** → confirm. Then turn on accessibility again.
4. **Overlay permission:** *not needed.* The STOP button is drawn by Bridge's accessibility service, which keeps it above every app. (This also fixes the unreliable stop button in your old version.)
5. **Relay address:** type the address from Part B step 6 → **Save address**.
6. **Recommended:** Android Settings → Apps → Bridge → Battery → **Unrestricted**.

### Part D: Connect Claude

1. In Bridge tap **Start**. The red **STOP** button appears and the status shows **Relay: Connected**.
   (Bridge must be started while you add the connector, so Claude can read its tool list.)
2. Tap **Copy connector URL**.
3. In Chrome open **claude.ai** → **Settings → Connectors → Add custom connector**.
   Name: `Bridge`. URL: paste. Leave Advanced settings empty. Tap **Add**.
4. Open the **Claude app**. In a chat, tap the tools/connectors button and make sure **Bridge** is enabled.
5. When Claude first uses a Bridge tool it asks for permission. Choose **Always allow** so it doesn't ask at every step.

---

## Build 1: what to test and what to expect

Status key: **PASS** / **FAIL** / **NOT TESTED**. Please tell me the result of each test, and paste the Activity log if anything fails (you can select and copy its text).

| # | Test | Expected result |
|---|---|---|
| 1 | Install APK, open Bridge | Main screen with Start/Stop, Activity log, Setup. Version at the bottom says `1.0.1` (or the build number). |
| 2 | Turn on accessibility | Step 1 shows **✅ Bridge accessibility service is ON**. Log: "Accessibility service is on". |
| 3 | Open `https://<relay>/mcp/<secret>` (the connector URL) in Chrome **before** Start | Page says `Tablet connected: NO`. |
| 4 | Tap **Start** | Red STOP button appears. Status: **CONTROL ON**. Relay line: **Connected — Claude can control the tablet**. Reload the page from test 3 → `Tablet connected: YES`. |
| 5 | Drag the STOP button somewhere else, then tap the Bridge **Stop** button, then **Start** again | The button comes back where you left it. |
| 6 | Restart the tablet, turn Bridge on if needed, tap Start | The STOP button is still where you left it. |
| 7 | Add the connector (Part D) | Claude lists Bridge with 8 tools: screenshot, read_screen, tap, swipe, type_text, go_back, go_home, open_app. |
| 8 | Split screen: Claude app + home screen. Ask Claude: *"Use Bridge: take a screenshot and tell me what you see."* | Claude describes your screen correctly. Log: "Claude: screenshot". |
| 9 | Ask: *"Use Bridge to open Settings, then go back home."* | Settings opens, then the home screen. Log shows "open app Settings" and "home". |
| 10 | Ask: *"Use Bridge to open Chrome, tap the address bar and search for weather."* | Chrome opens, text is typed, search runs. |
| 11 | While Claude is working, tap **STOP** once | Button disappears at once. Nothing else is tapped. Log: "STOPPED: You tapped the STOP button". Claude says Bridge is stopped/offline. |
| 12 | Start again. Ask Claude to open a website's login page (e.g. accounts.google.com in Chrome) | When a password field appears, the yellow **YOUR TURN** message shows. Claude is told to stop. Log: "SAFETY PAUSE: a password field in Chrome". |
| 13 | On that message tap **Resume** while still on the password page | Message says the screen is still sensitive. Leave the page, tap Resume → message closes, control continues. |
| 14 | Ask Claude to open your banking app (or Google Pay) | Bridge refuses, shows **YOUR TURN** ("Claude tried to open … banking or payment app"). |
| 15 | Let control run for 15+ minutes (screen on, Bridge started) | Screen stays on, connection stays "Connected". If Wi-Fi drops, it says "Retrying" and reconnects by itself. |
| 16 | Open **Activity log** | Timestamped list, newest first, of everything above. |

---

## How the old problems were fixed

| Old problem | Fix in Bridge |
|---|---|
| Stop button unreliable | The STOP button lives inside the accessibility service, the same component that performs actions, so STOP switches control off instantly. Claude's gestures are frozen while your finger is on the button, so a gesture can't cancel your tap. An interrupted tap also counts as STOP. STOP cuts the connection and cancels any queued action. Claude also can't tap the STOP button. |
| Claude couldn't connect | A stable relay address on Cloudflare (no changing tunnel URLs), automatic reconnect, a status page you can open in Chrome, and clear "stopped/offline" messages to Claude. |
| Depended on ChatGPT | Uses Claude's official custom connector feature (MCP). No ChatGPT, no API key. |

---

## Project layout

| Path | What it is |
|---|---|
| `app/` | Android app (Kotlin, no AndroidX; only dependency is OkHttp) |
| `app/.../BridgeService.kt` | Accessibility service: start/stop/pause, owns everything |
| `app/.../Actions.kt` | Screenshot, read screen, tap, swipe, type, back, home, open app |
| `app/.../McpHandler.kt` | The tools Claude sees |
| `app/.../SafetyGuard.kt` | Password / OTP / payment / banking detection |
| `app/.../OverlayController.kt` | STOP button and YOUR TURN message |
| `app/.../ActivityLog.kt`, `LogActivity.kt` | Activity log |
| `relay/` | Cloudflare Worker relay |
| `.github/workflows/build-apk.yml` | Builds the APK and publishes it on Releases |
| `.github/workflows/deploy-relay.yml` | Deploys the relay |

Requires Android 11 or newer (needed for accessibility screenshots). Targets Android 16 (API 36).

### Optional: your own signing key

Add the repository secrets `BRIDGE_KEYSTORE_BASE64` (base64 of a `.keystore` file), `BRIDGE_KEYSTORE_PASSWORD`, `BRIDGE_KEY_ALIAS` and `BRIDGE_KEY_PASSWORD`. Builds will then use your key. You must uninstall the old Bridge once and turn on accessibility again after switching keys.
