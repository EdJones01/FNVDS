# Collected notes and in-game audio

`GetPlayerPipBoyNotes` reads the player's acquired-note linked list at
`PlayerCharacter + 0x5E4`. The reader bounds traversal and deduplicates entries.
It does not scan all NOTE forms or use ordinary inventory as the note list.

Android requests note data with `NOTESON` and stops requesting it with
`NOTESOFF`. Snapshot generation processes up to 32 acquired notes per batch,
then refreshes the list after a three-second delay. Text type 1 reads the
native description's TNAM payload. Unsupported image notes are not streamed
as images. The read flag is shared with the game's note state.

The list uses `NOTES BEGIN`, `NOTE`, `NOTES END`, and `NOTESPROGRESS` packets.
Android reveals the first note before the full batch finishes. It displays a
loader only for an empty list; scan counts are no longer shown or retained by
the app.

`READNOTE`, `PLAYNOTE` and `STOPNOTE` queue game-thread actions. Audio plays
through the native game sound system, not Android. Sound handles and response
segments are managed in `plugin.cpp`; duration queries and loading callbacks
populate segment durations. The total includes already-finished segments.
The callback uses sound IDs and a mutex, not live note pointers.

`NOTEAUDIO` supplies playing identity/state, duration and elapsed milliseconds.
The Android ruler interpolates between received elapsed values and disappears
when playback ends. `NOTEAUDIOERROR` is logged without a toast. Save changes
clear playback, scan candidates and pending note actions before stale objects
can be used.
