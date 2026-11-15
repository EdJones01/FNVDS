# Dialogue choices and response skipping

`dialogue.h` observes the native DialogMenu and its existing choice tiles on
the game thread. Reads normally have a 100 ms throttle, bypassed for pending
selection/skip requests. Only a ready dialogue menu exposes actionable choices.

The snapshot contains the speaker, readiness, encoded choice text and opaque
choice/speech tokens. `DIALOGUESELECT <token>` is checked against current
choices; `DIALOGUESKIP <token>` is accepted only for the current spoken response.
Tokens change when dialogue identity/state changes, rejecting old taps.

Both actions use DialogMenu's native selection handler at `0x007624F0`.
The game owns conditions, skill checks, scripts, barter, goodbye and response
playback. Skipping uses its spoken-response handler path; the app does not
stop arbitrary game sounds.

Android parses supported skill/S.P.E.C.I.A.L. check text for faint pass/fail
backgrounds and dims choices while the speaker responds. Those highlights do
not change what the native dialogue handler accepts. Menu/tile pointers are
never received from the companion.
