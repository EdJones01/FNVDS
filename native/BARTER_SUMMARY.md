# Read-only barter summary

The lower display replaces the barter animation with a trade monitor. It shows the merchant name, current player/trader caps, offered items and quantities in independently scrollable columns, the cap settlement, and projected player caps. It does not add trade-selection or confirmation commands. Normal companion actions remain blocked while bartering; only the summary's scrolling receives touches.

For New Vegas 1.4.0.525, the game-loop callback reads the active BarterMenu pointer at `0x011D8FA4` only while the existing activity detector reports BARTERING. Snapshot cadence is 250ms; the worker publishes only changed packets. Menu reads use bounded list traversal and a guarded copy. No pending entry, tile, inventory, multiplier, barter calculation or cap balance is modified.

Runtime fields, traced through the local decompilation's `bartermenu.cpp`, `BarterMenu.h`, and `AddOrUpdateEntryCount` in `staging/blk_00720000_od.cpp`:

- `+0x80`: merchant reference.
- `+0x84`: floating net cap balance. Positive values pay the player; negative values charge the player.
- `+0x8C`, `+0x90`: player and trader caps from `UpdateCapsLabels` (`0x0072DB60`).
- `+0x10C`: pending items the player receives; `+0x114`: pending items the player gives. Each list node has entry/next pointers; the pending entry has quantity at `+4` and form at `+8`. Compile-time assertions check these offsets.

Recovered member names in the decompilation are sometimes misleading. The list directions follow the active-list routing in `AddOrUpdateEntryCount` (`0x0072FAA0`) and transfers in `CompleteBarterTransaction` (`0x0072FD10`), rather than those names.

The cap settlement uses the game's pending net balance, floored to an integer as in its transaction routine. Player receipts are capped by trader caps. An unaffordable purchase displays NOT ENOUGH CAPS and no projected after-trade amount. A trader shortfall displays the capped payout plus a warning. Per-item prices are not recomputed or presented as base values; the summary uses the game's net trade calculation.

Protocol:

```
STATE BARTER BEGIN <base64 merchant> <player caps> <trader caps> <net balance>
STATE BARTERITEM SELL|BUY <quantity> <base64 name>
STATE BARTER END
```

`STATE BARTER PENDING` clears stale values while a menu snapshot is unavailable. `STATE BARTER CLOSED` clears the summary after leaving barter or when the game is no longer ready. The existing activity packet controls visibility, so pause, death and save-loading overlays continue to take priority. The entire snapshot is copied under the existing mutex before the socket worker sends it; no game pointers are shared with Android or retained by the worker.

The corrected reader checks form identities against the game's form registry and copies names inside the guarded capture. Packet construction uses those copied character buffers rather than dereferencing captured game pointers. Its fixed-size capture buffer is reused on the game thread, outside the stack.

Android applies only changed summaries and restores both offer scroll positions after updates. The panel follows the companion display color and leaves gameplay input focus unchanged. Acceptance/cancellation and item selection stay on the game's upper display.
