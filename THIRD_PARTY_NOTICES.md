# Third-party notices

The Nia live-voice design was informed by these open-source Android projects:

- **Jarvis by Jude Julius** — https://github.com/judejulius/jarvis — MIT License. Reference material: offline wake-word and speech-recognition pipeline, conversation turn-taking, interruption handling, and local/cloud voice fallback.
- **Jarvis Assistant by Bwarhness** — https://github.com/Bwarhness/jarvis-assistant — Apache License 2.0. Reference material: Android speech-recognition lifecycle, voice-session state handling, and foreground wake-word service design.

The initial Nia conversation-mode integration uses Nia's existing Android speech-recognition and text-to-speech dependencies. No third-party source files are copied verbatim in this initial integration. Any later direct source-code reuse must retain the applicable upstream copyright and licence notices.
