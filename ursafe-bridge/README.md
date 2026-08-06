# Ursafe encrypted device bridge

This branch is used only as a transport for Ursafe device jobs.

- `commands/<device-id>.json` contains an AES-GCM encrypted command envelope.
- `results/<device-id>/<job-id>.json` contains an AES-GCM encrypted result envelope.
- `artifacts/<device-id>/<job-id>/...` contains only files explicitly approved for public upload.

The pairing secret is generated on the Android device and must never be committed here.
