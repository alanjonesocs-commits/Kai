# NIA15 Project Status

Last updated: 2026-11-04 (UTC)
Project: Kai-derived NIA15 build
Repository: https://github.com/alanjonesocs-commits/Kai
Active feature branch: `feature/nia15-command-centre`
Base branch: `build/nia15-full-duplex-wakeword`
Draft pull request: https://github.com/alanjonesocs-commits/Kai/pull/5

## Scope and boundaries

- This is the Kai-derived NIA15 build.
- NIA13 remains separate and must not be changed by this work.
- The independent ground-up A.L.A.N. build is a separate project.
- Alan is the owner and final decision-maker.
- Preserve provenance, timestamps, conflicting claims and user control. Do not silently overwrite memory.

## Confirmed work

- Existing chat and settings destinations remain in the app.
- The mobile start destination is the NIA command-centre landing screen; desktop still starts at Chat.
- The landing screen uses the existing HolographicBrain component.
- The command-centre branch has a GitHub Actions workflow for desktop tests, Android FOSS debug APK compilation and artifact upload.
- CI run #2 (run ID 41825657598) passed the common desktop test suite. At the last check, Android FOSS APK compilation was still in progress; artifact upload had not yet run.
- On 2026-11-04, the command-centre screen was extended with separate Projects & workspace, Skills & interactive tools, and Settings shortcuts.
- Skills opens the existing Settings Tools tab. Projects opens the existing Settings Sandbox tab. These reuse existing app surfaces; they are not new independent project/skills data engines.

## Latest implementation commits

- Command-centre shortcuts: `733dd669585be747eda40dad261769c97214107c`
- Settings initial-tab support: `46f2aa3455caf57a6faa6904954c4e3f075141c7`
- Navigation wiring: `255824fc4b8f213e993bf3ada606a0e33e9ed8ff`

## Reported device behaviour

Alan reported on Pixel 9 that voice starts after the audio-thread priority fix, but speech recognition still stutters, lags and catches only fragments. This remains REPORTED and requires repeatable device-side verification.

## Not yet verified / not complete

- Latest post-shortcut commits have not yet been shown to pass CI.
- Android APK build and artifact upload for the latest commits are not yet confirmed.
- No claim of successful installation or runtime testing on Pixel 9.
- Full-duplex interruption, wake-word reliability and long-session voice stability are not signed off.
- Memory capture, approval, storage, retrieval, restart persistence, corruption recovery and export/import still require independent tests.
- The brain visual remains a 2D Compose Canvas animation; its listening/thinking/speaking states are not connected to verified live engine state.
- Device telemetry is not implemented as a verified dashboard.
- The command centre is a staged navigation hub, not the finished NIA interface.

## Next engineering gates

1. Verify CI for the latest command-centre commits; fix failures before extending the branch.
2. Confirm Android FOSS APK artifact exists and record its checksum.
3. Install that exact APK on Alan's Pixel 9 and capture runtime evidence.
4. Exercise the Skills and Projects/Sandbox shortcuts on device; confirm back navigation and tab selection.
5. Continue voice diagnostics and separately test memory persistence/recovery. Do not conflate a successful compile with runtime or memory verification.

## Evidence labels

- CONFIRMED: supported by returned GitHub commit, workflow step or test output.
- REPORTED: user-reported device behaviour not independently observed here.
- NEEDS_VERIFICATION: code exists or behaviour is expected, but evidence is insufficient.
- UNKNOWN: no reliable evidence available.
