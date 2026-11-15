# V.A.T.S. bridge

`vats.h` observes native targeting menu `0x420` and VATS core `0x011F2250`.
Only an observed targeting menu in modes 1-3 opens the companion panel. Mode 4
may retain an existing session during playback, but cannot open a panel by
itself: ordinary cinematic kill shots also enter that mode.

## State and requests

`STATE VATS BEGIN` supplies the session, readiness, target, AP, HP, damage
prediction, cost and labels. `VATSPART` rows contain opaque limb tokens,
chance, condition, queued count, selection/enabled state and name. `END`
commits the view and `CLOSED` restores the ordinary companion page.

`VATSCMD <session> <action> <part token>` uses these action numbers:

| Action | Meaning |
| --- | --- |
| 1 / 2 | Select part / queue an attack on it |
| 3 / 4 | Previous / next target |
| 5 | Undo the last native queued action |
| 6 | Execute queued actions |
| 7 | Discard queued actions and leave V.A.T.S. |

Requests run one at a time on the game thread. The session, ready mode 2,
target membership, limb token, hit chance, AP and relevant ammo limits are
checked before queuing. Engine-owned commands receive native ownership after
insertion. Temporary damage-query commands use the native destructor.

## Native bindings

| Binding | Use |
| --- | --- |
| `0x011DB0D4`, `0x011F21CC`, `0x011DB150` | Menu, current target, target list |
| `0x007F3BF0` | Map limb ID to body actor value |
| `0x007EECB0` | Native target-selection input |
| `0x009CA4A0` / `0x009CA4C0` | Command construction/destruction |
| `0x009C71A0` | Add an engine command |
| `0x007F28D0`, `0x007EFA10`, `0x007F0EA0` | Ammo, queue/AP and chance display updates |
| `0x00705780` | Accept and execute the queue |
| `0x009CA410` | Destroy/remove queued commands |
| `0x007EBD50` | Close targeting; starts playback if commands remain |
| `0x009B4FE0`, `0x008808A0` | Predict damage and apply the target's difficulty adjustment |

Exit first calls command cleanup, checks the list is empty and discards pending
companion requests, then closes targeting. Calling shutdown with a nonempty
queue would execute it. Undo and Execute retain their separate native paths.
Save transitions reset pending requests and published state.

## Android view

`VatsView.java` uses a two-column body-part grid. An unselected part tap selects
it; tapping the selected part again queues an attack. Queue indicators are
separate centred `X` marks, one per attack. HP damage and AP cost previews
pulse smoothly; queue marks flash. These animations are independent of the
page-shutter preference. Playback retains the panel with controls disabled.

Predictions are estimates, not guaranteed damage. Specialized native melee
special-attack buttons are not exposed by this screen. Runtime validation on
the Thor is still needed after code changes; build success is not a gameplay
check. ABI research used the xNVSE SDK and the decompilation reference linked
from [the native index](README.md).
