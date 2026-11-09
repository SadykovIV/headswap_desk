# Notification shade and managed Activity launches

This change is local work for 0.6.0; runtime verification is recorded separately, not assumed from compilation.

**Current state, 9 November:** the HU notification shade is temporarily disabled at the user's request. `HuNotificationFeature` blocks model collection, overlays and open actions and removes notification capability from both component gateways, even when the saved preference is true. DriveDeck333 therefore does not intercept its native status area for the shade. Phone pairing notifications, the navigation HUD listener and media metadata remain independent. The sections below document the suspended implementation; earlier interaction PASS results do not mean the current build enables it. A separate runtime probe reports `notificationShadeMode=disabled`.

## Entry points

DriveDeck 0.6.9 code333 integrates the entry directly into its original `c.q2` Canvas sidebar. The pinned source calls `q2.s(Canvas,int,float,float,float,int)` for the cellular/Wi-Fi/Bluetooth status glyphs. The helper records those actual draw bounds with the Canvas transforms, adds a small notification indicator, and accepts taps inside that native status area. There is no invisible overlay hit box over DriveDeck or another app. Its existing app buttons and edit gestures remain routed to their original handlers. The control also exposes a labeled accessibility action; HeadSwap does not require an accessibility service.

The signature companion gateway adds capability bit6 and append-only transactions13–15: status/native-entry visibility, open shade, and open notification by key/package. Existing transaction numbers and old clients remain valid. A currently visible native entry suppresses the duplicate collapsed floating handle. Pausing/removing the native entry restores the fallback; a four-second visibility lease covers process/Activity loss.

The public component SDK adds an optional separate notification capability. Components can receive only count/readiness and request the host shade; they never receive notification text, PendingIntents or arbitrary display access. Existing components require a deliberate UI integration by their publisher to add their own native entry.

## Launch boundary

Notification content and Activity PendingIntents remain ephemeral in the active NLS session. The host accepts a PendingIntent only when it represents an Activity and the creator package, UID and Android user match the notification. Service/broadcast PendingIntents are not executed. The current record, phone lock and exact parent-session identity are checked before issuing and again before using an opaque one-time launch ticket.

The nonexported NotificationFullscreenActivity owns a normal fullscreen child lease. The PendingIntent is sent from the ordinary application UID with the child display in ActivityOptions, after geometry and input ownership are configured. The host tracks placement and provides Back/Home controls. Failure or cancellation returns to the desktop; there is no opaque phone-display or package-launch fallback. A third-party Activity may itself navigate onward according to its own implementation; the host does not claim to control arbitrary application-internal redirects.

Private notification text remains hidden while the phone is locked; new notification launches are rejected then. No notification text, URI, PendingIntent, or ticket is written to logs or files.

## Tests

The `drivedeck-notifications` disposable-emulator probe posts a real harmless same-package notification, uses the native control's exposed geometry for one actual DOWN/UP, taps its host-rendered card, verifies the target Activity is on a managed child, returns Home, and verifies a removed notification cannot be launched. Unit coverage includes Activity/service/broadcast identity gating and SDK capability negotiation. The DriveDeck exact-source patch remains reproducible and fail-closed when symbols do not match the pinned APK.

### Local build46 observation

HeadSwap main/QA compiled and the code333 companion replay/signature checks passed. The first notification runtime attempt correctly refused to expose a shade for an unproven transport: the new QA helper had generated only heartbeat packets, without the required first completed VIDEO_DATA write. This is a fixture error, not evidence of a production notification failure. The helper now follows the same initial-frame/heartbeat contract as the older shade probe; the corrected runtime result is recorded below. Failure logs remain in workspace `artifacts/runtime-qa/0.6.0-notifications/`.

### Android PendingIntent sender contract

The corrected real tap reached the fullscreen host on API37, but Android rejected the first PendingIntent send with BAL_BLOCK despite a visible HeadSwap Activity. The sender must opt in explicitly on Android14+. The implementation now uses `MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE` on API36+ and `MODE_BACKGROUND_ACTIVITY_START_ALLOWED` on API34–35, only after rechecking the live one-use user-click ticket, parent lease and resumed private fullscreen Activity. Android13 retains its platform path. No creator flags are changed and no unrestricted “allow always” mode is used.

Primary references checked 2026-11-09: [ActivityOptions sender mode](https://developer.android.com/reference/android/app/ActivityOptions#setPendingIntentBackgroundActivityStartMode(int)), [visible-only mode](https://developer.android.com/reference/android/app/ActivityOptions#MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE), [Activity security/BAL](https://developer.android.com/guide/components/activities/secure-bal). The local API37 SDK also records the sender method since API34 and visible-only constant since API36.


### Runtime evidence: 2026-11-09

Main build48 SHA-256 `5f6c66e192e0fc66c6af686a1359d9789b1f24b8631cab102cb4b470288b9b25`, QA52 SHA-256 `d531b47556e1d83975c758d847e3f6d7d15d1f0d4d24c70e0253e322d2571dad`, companion code333 SHA-256 `fcb5e530386a8d9bb40de5fec8906d633366bd0c103466d31c09e6b48eef6dec`.

* **Android17/API37: full PASS** (`api37-drivedeck-notifications-main48-qa52.txt`). The notification target first exists on the phone; its ordinary immutable Activity PendingIntent uses NEW_TASK|SINGLE_TOP, without MULTIPLE_TASK. One native status tap opens the shade; one card tap opens the target on an owned child. The composed parent buffer contains the actual green target view before any following action. The floating Home button restores the two DriveDeck tiles, and the real frame is saved. Removing the notification invalidates the old launch key. The normal phone launcher remains unchanged.
* **Android16/API36:** native entry, actual shade/card taps, existing Activity relocation, green target pixels and Home action confirmed with main48/QA51. The final screenshot predicate incorrectly expected Google Clock's black background; AOSP Clock is blue. This fixture predicate was corrected to inspect the native DriveDeck sidebar and Settings tile instead. This run is not recorded as an overall PASS.
* **Android13/API33:** main47 confirmed the native entry and shade. A card tap ran before the overlay reached InputDispatcher: live dumps show DRAW_PENDING followed by a visible committed input window. QA49 now waits for that exact expanded window region before a single tap; the production touch routing was not changed. The final notification result is recorded in the targeted-verification table below.

The screenshots `api37-main48-qa52-shade.png`, `...-activity.png`, and `...-home.png` have been opened and inspected. Earlier snapshots taken with fixed delays contained transition frames and are preserved as failed evidence, not used to claim success. All runtime artifacts are outside the source repository at workspace `artifacts/runtime-qa/0.6.0-notifications/`.

Main48 full validation: 1,247 app unit tests (0 failures, 27 existing skips), seven component-SDK tests (0 failures), application/test APK assembly and lint passed. Actual hardware Back and stale-owner checks were subsequently added; their results are listed below, separately from the earlier screen-Home result.

Companion packaging is reproducible: `artifacts/components-20261109/drivedeck069-code333-release/ROUNDTRIP_REPORT.json` records a standard-library patch restore byte-identical to the signed code333 APK. Neither this report nor the emulator tests constitute a GitHub publication or confirmation on every physical head unit.


### Final targeted verification

Production build55 changes only the settings permission summary/deep link relative to build53. Both full builds passed APK assembly, 1,247 app tests (0 failures, 27 existing skips), seven SDK tests and lint (0 errors; 156 warnings). Final production SHA-256: `5e98b5962acbda1c31b43a379c89733322a52688c96df09ccc6c09e47a00386a`. `build55-validation.json` records the exact artifacts and checks.

| Android / API | Notification interaction result | Evidence |
| --- | --- | --- |
| 13 / 33 | PASS, main55 + QA56 | `api33-drivedeck-notifications-build56.txt` |
| 14 / 34 | PASS, main55 + QA56 | `api34-drivedeck-notifications-main55-qa56.txt` |
| 15 / 35 | PASS, main55 + QA56 | `api35-drivedeck-notifications-main55-qa56.txt` |
| 16 / 36 | PASS, main53 + QA53 | `api36-drivedeck-notifications-build53.txt` |
| 17 / 37 | PASS, main53 + QA53 | `api37-drivedeck-notifications-build53.txt` |

Each PASS includes one real native status-area tap, one real notification-card tap, an ordinary Activity PendingIntent for a target already running on the phone, verified target pixels on the managed child, a hardware Back delivered to that child, rejection of captured Back after touch/Home, screen Home restoring the two tiles, and rejection of a removed notification key. Android14 exercises the Android14–15 sender opt-in branch; Android16/17 exercise the visible-only branch. The Android13 check exercises the path without that sender API.

Two fixture issues were distinguished from production behavior with actual frame/window evidence. First, the view tree becomes visible before the overlay's InputDispatcher window is committed; the fixture now waits for the exact visible input region before a single tap. Second, injected Back arrives through legacy key callbacks on Android13/14 and through the public predictive callback on newer images. A predictive-only test Activity finished normally under legacy Back, so its missing marker was not a lost event. QA56 records both public paths and preserves the target Activity for subsequent tests; API34 records `back display=9 via=legacy`; the final API33 run also records the actual legacy route and passes the remaining touch/Home/removal checks. No corresponding production routing workaround was added. Hardware routing itself is provided by the shared scoped fullscreen callback introduced in build53.

Settings structure passed on API33/34/36/37, including all seven sections, hierarchical Back, restored selected category, persisted options, component access and enlarged-font layout checks. The final pairing-channel status/deep-link check passed on API33 with build55: disabling only the pairing channel changes the status, and the settings row opens that exact channel; the original state was restored. The actual API37 index, Phone Screen and HUD pages were visually inspected; API34 and API37 notification shade/target/Home frames were also opened and inspected.

All project AVDs owned by this task (API37/5852 and API34/5854) were stopped explicitly after their runs. The shared ADB server and the user's Samsung were not changed by this agent. No artifacts were published to GitHub.

The follow-up 19-item audit added API35 Settings and notification interaction, both PASS on main55/QA56. The actual hardware Back route is `back display=5 via=legacy`; the native shade, target pixels, retained split and stale-action checks passed. Only the disposable API35 emulator5856 was stopped after testing; the Samsung and shared ADB server were unchanged.
