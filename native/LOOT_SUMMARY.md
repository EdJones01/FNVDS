# Container-session summary

The container activity screen shows net items DEPOSITED and TAKEN during the current open-container session. Taking an item and returning it cancels that quantity, as requested. Separate columns scroll independently and show quantity, line base value and a total base value per side, plus the net value difference. Actual transfers remain in the game; no container command or transfer hook is added.

The ContainerMenu pointer is `0x011D93F8` on runtime 1.4.0.525. Its container reference is at `+0x74`, confirmed by the local decompilation's `ContainerMenu.h` offset assertions and `containermenu.cpp` call sites. The local layout carries its own compile-time assertion. Reference identity and its copied base-form name are checked inside the guarded target read.

On the first visible game-loop frame for a new menu/container, FNVDS records both inventories. Counts combine each reference's TESContainer base counts with ExtraContainerChanges deltas, including the existing leveled-item replacement rule. Every 250ms it compares current counts with those baselines. A row requires opposite matching changes in both inventories: player gain with container loss means TAKEN; player loss with container gain means DEPOSITED. The smaller matching quantity is used. This avoids counting an unrelated player-only inventory change as a container transfer.

Closing the container, losing loaded-game readiness, or switching menu/container identity clears the baselines. Reopening starts a new session. Activity visibility remains owned by the existing blocker, so higher-priority loading/death/pause screens retain control.

Values use the existing native inventory-value getter at `0x004BD400` on a borrowed one-item entry without instance extras. These are explicitly labeled BASE VALUE: they do not include individual copy condition or merchant barter pricing. The amount shown per row is quantity multiplied by that value; column totals and the net difference use those same numbers. No prices or contents are changed.

Protocol:

```
STATE LOOT BEGIN <base64 container name>
STATE LOOTITEM TAKE|DEPOSIT <quantity> <line base value> <base64 item name>
STATE LOOT END
```

`STATE LOOT CLOSED` clears the summary; `STATE LOOT PENDING` clears displayed values while a target read is unavailable. The socket worker receives copied packet strings under the existing mutex. Android applies changed summaries only, sorts names, preserves column scroll positions, and uses the same colored summary view as bartering. The original barter mode retains its cap-settlement behavior.
