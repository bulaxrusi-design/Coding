# Xuci Agent Pro v3 — source build

This branch contains a clean Android source project packed as `xuci-agent-pro-v3-source.tar.gz.b64` so GitHub Actions can compile it with the official Android toolchain. It does not patch or reuse DEX bytecode from the earlier APKs.

The workflow decodes the source, builds `:app:assembleDebug`, computes SHA-256, and uploads both the APK and source archive.

Core design:

- Material 3 native Android UI
- Gemini 3.5 Flash function calling
- AES-GCM API-key storage backed by Android Keystore
- official Termux `RUN_COMMAND` service with PendingIntent result callback
- confirmation-first tool execution
- command policy blocking destructive/root/system actions
- APK audit/decompile, source search/read, Python, HTTP and scoped Termux tools
- worker-boundary error handling and persisted crash report
