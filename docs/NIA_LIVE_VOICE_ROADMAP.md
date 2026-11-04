# Nia Live Voice Roadmap

## Stage 1 — Hands-free conversation mode (current draft PR)

- User explicitly starts and ends a voice session.
- Android speech recognition captures a turn and submits it to Nia.
- Nia speaks the completed answer with the existing system TTS engine.
- The listener automatically re-arms for the next turn.
- Recognition prefers Android's on-device recognizer when available. The system recognizer fallback may use a network service; offline-only behaviour is not yet guaranteed.
- No background or always-on microphone service.

## Stage 2 — Wake phrase and interruption

Reference projects:
- Jude Julius Jarvis: `VoiceInput.kt` and `AudioOut.kt` — streaming audio capture, keyword spotting, speech recognition, echo handling and interruption patterns.
- Bwarhness Jarvis Assistant: `WakeWordService.kt` — foreground microphone service lifecycle and wake event handoff.

Nia-specific work required:
- Use a wake model that actually recognises "Hey Nia" (do not ship a "Hey Jarvis" model under a different name).
- Evaluate sherpa-onnx versus openWakeWord for Android arm64, model size, licence and latency.
- Keep the wake detector local; do not upload ambient audio.
- Add acoustic echo cancellation / voice activity detection so Nia does not hear her own speech.
- Implement barge-in so Alan can interrupt Nia by speaking or saying "stop".
- Handle Android foreground microphone service restrictions, notification controls, permission revocation and battery use.

## Stage 3 — Natural voice

- Keep Android system TTS as the reliable no-cost fallback.
- Evaluate Kokoro or another locally runnable neural TTS voice with a British English voice.
- Offer optional cloud voices only with explicit provider/key configuration and clear cost/privacy disclosure.
- Measure Pixel 9 memory, storage, start-up time and playback latency before enabling a model by default.

## Stage 4 — Jarvis call interface

- Dedicated OLED-black voice screen.
- Animated Nia orb with separate listening, thinking, speaking and disconnected states.
- Live audio level indicator, mute and end-call controls.
- Return to the existing chat without losing the conversation or memory context.

## Verification gates

Do not mark a stage complete based only on compilation. Test on the Pixel 9:
- microphone permission granted/denied/revoked;
- repeated turn-taking without pressing the mic each time;
- TTS completion and manual interruption;
- no self-triggering from speaker echo;
- app background/foreground and screen locked/unlocked behaviour;
- battery drain during wake-word listening;
- no loss or alteration of Nia memory when a voice session ends.
