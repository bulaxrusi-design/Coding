# ChatGPT Device Lab v2

Foreground-only Android companion that embeds the official ChatGPT web interface and adds an explicit, operator-controlled device bridge for an allowlisted game.

The bridge requires visible MediaProjection consent and an enabled Accessibility service. It can capture the screen and perform only tap, swipe, back, wait, and launch actions while the exact allowlisted package is in the foreground. Every run creates a new session ID; stale commands are rejected. A permanent notification and STOP action remain visible.

It deliberately does not request SMS, contacts, call logs, location, clipboard, unrestricted storage, shell, package installation, device-admin, VPN, or boot-start permissions.