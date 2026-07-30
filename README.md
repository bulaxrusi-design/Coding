# TTC Bridge v3

TTC Bridge v3 is a foreground-only Android test companion that lets this ChatGPT conversation exchange screenshots and a tightly restricted command set through a dedicated GitHub repository. It does **not** embed ChatGPT credentials or use another AI model.

## What changed from v2

- Every app start creates a new `session_id`; stale commands from an older session cannot replay.
- Failed commands are acknowledged once instead of retrying forever.
- Screenshots are redacted whenever the foreground app is not allowlisted.
- Payment, wallet, banking, billing, and Play Store surfaces are blocked even if accidentally allowlisted.
- TTC tracking now supports run start/stop, pause/resume, level start/complete, generic markers, wall time, and active time.
- `state/latest-ttc-report.json` contains the current or last completed report.
- A foreground notification and STOP action remain visible for the entire session.

## Runtime repository files

- `control/command.json` — command written by ChatGPT.
- `state/current.jpg` — latest allowlisted screenshot, or a privacy placeholder.
- `state/status.json` — session ID, acknowledgement, foreground package, screen dimensions, and TTC state.
- `state/latest-ttc-report.json` — structured TTC report.

Use a separate public repository containing no unrelated data. The Android PAT should be fine-grained, limited to that repository, and have only `Contents: Read and write`.

## Command envelope

Every command must include the current `session_id` from `state/status.json` and a monotonically increasing `seq`:

```json
{
  "seq": 1,
  "session_id": "copy-from-state/status.json",
  "action": "observe",
  "expires_at": "2026-07-30T12:00:00Z"
}
```

`expires_at` is optional. Supported actions are:

- `observe`
- `launch`
- `tap`
- `swipe`
- `back`
- `wait`
- `batch`
- `start_run`
- `pause_run`
- `resume_run`
- `mark_level_start`
- `mark_level_complete`
- `mark_event`
- `stop_run`
- `panic`

### TTC example

```json
{"seq":2,"session_id":"...","action":"start_run","label":"Number Match baseline"}
```

```json
{"seq":3,"session_id":"...","action":"mark_level_start","level":1}
```

```json
{"seq":4,"session_id":"...","action":"mark_level_complete","level":1,"outcome":"completed","metadata":{"attempts":1}}
```

The report records both `wall_ttc_ms` and `active_ttc_ms`. Use `pause_run`/`resume_run` only for interruptions that should be excluded from active TTC.

## Safety scope

This project is intended only for the operator's own device, authorized test accounts, and explicitly in-scope apps. It has no hidden startup, no shell/file access, no credential collection, and no background activation of ChatGPT.
