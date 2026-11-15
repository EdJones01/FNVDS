# Status, attributes, effects and reputation

The game-loop handler in `plugin.cpp` reads player actor values for health,
AP, S.P.E.C.I.A.L., skills, survival needs and limb condition. `BuildStatusSnapshot`
adds maximum HP/AP, Hardcore mode, calendar fields and level/XP progression.
The app draws bars and formats these values; it does not write actor values
to imitate healing or XP gains.

`BuildExperienceSnapshot` uses the native level query, XP actor value,
required-XP function at `0x00648B50`, and the game's level-cap setting. The
companion displays progress within the current level without numeric XP totals.
Its bar is full at the configured cap.

S.P.E.C.I.A.L. modification indicators compare integer current and permanent
values, matching the native display logic. Displayed current attributes are
capped at 10 in Android. Effects come from native active-effect information
and relevant survival-stage spells; effect names/modifiers are distinct from
the raw RADS/Water/Food/Sleep counters.

Reputation queries use xNVSE's script interface and native reputation data to
determine visibility and standing. Names are resolved for the Pip-Boy display,
rather than exposing an internal faction editor name. Android applies the
karma/positive/negative colours; mixed standings have no extra highlight.

Quick-aid controls call the normal aid-use path. `AidNeedRelief` obtains
negative food/water/sleep modifiers from the game's evaluated item-card text;
it does not classify relief by item name. Inventory counts, current need and
Hardcore mode are checked again before consumption. Doctor's Bags use the
[limb-treatment path](LIMBCARE.md).
