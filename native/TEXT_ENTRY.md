# Native text entry and console

`text_entry.h` publishes the native TextEditMenu (ID `0x41B`) independently of
ordinary save-ready page snapshots. The menu singleton is at `0x011DAEC4`;
raw input begins at `+0x34` and the cursor is read at `+0x44`. Native editor
and acceptance checks remain in charge of the submitted text.

`STATE TEXTENTRY <session> <acceptEnabled> <base64 prompt> <base64 text>` opens
the Android keyboard; `STATE TEXTENTRY CLOSED` hides it. The displayed text
contains a caret. Commands are `TEXTKEY <session> <code>`:

| Code | Action |
| --- | --- |
| 32-126 | Printable ASCII |
| 1 | Backspace |
| 2 / 3 | Cursor left / right |
| 4 | Clear via native End and Backspace input |
| 5 | Accept text, or submit a console command |
| 6 | Forward delete |
| 7 | Close console; ignored for ordinary text prompts |
| 8 | Recall older console history; ignored for ordinary text prompts |

TextEditMenu's keyboard handler at `0x007E6620` receives full 32-bit codes,
including extended editing keys. Insertion is bounded to fewer than 255
existing bytes for native text prompts. A mutex-protected queue holds at most
128 session-tagged keys. The game-thread update discards stale requests.
No player-name/string buffer is overwritten directly.

## Console

`CONSOLEOPEN` requests opening, revalidated against loaded-game, activity,
text-prompt and V.A.T.S. state. Complete native open/close entry points are
`0x00703E10` / `0x00703DA0`; visibility alone is insufficient because the
console keyboard handler also requires menu mode. Input uses `0x0071B210`.

The console publishes through TEXTENTRY with prompt `CONSOLE`. Its input
preview is read from native DebugText; command output stays in the game.
Code 8 maps to native history-up `0x80000003`, which stops at the oldest
command. Printable console insertion is bounded by the current preview size.

The companion's active-session keyboard commands may pass its paused-action
guard. Other ordinary gameplay commands remain blocked. Save/new-game/exit
reset sessions, and disconnect clears pending keys. The overlay uses touch
without Android's IME or OS keyboard injection; arbitrary custom mod editors
that bypass TextEditMenu are outside its scope.
