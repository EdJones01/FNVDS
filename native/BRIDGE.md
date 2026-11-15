# Plugin lifecycle and local bridge

`plugin.cpp` implements the xNVSE entry points, game-loop handler and socket
worker. The companion connects to the loopback TCP service at `127.0.0.1:47888`.
Handshake validation precedes normal command traffic. The worker publishes the
compiled plugin version independently of loaded-save snapshots once a second.

## Thread boundaries

- The game-loop handler reads player/menu state and executes queued gameplay
  commands after checking loaded-game and activity state.
- The socket worker parses bounded requests and sends copied snapshots. It does
  not resolve arbitrary addresses supplied by the app.
- Snapshot strings are protected by `g_snapshotMutex`; pending item commands
  have a separate mutex. Socket writes occur after releasing the snapshot lock.
- Note audio loading callbacks synchronize duration data separately; they do
  not retain live note pointers across callbacks.

Most gameplay page snapshots are assembled every 250 ms when a save is ready.
Dialogue and V.A.T.S. have their own throttles; local-map rendering/transfer has
its own scheduling. Changed-state comparisons avoid resending many unchanged
lists. These are scheduling intervals, not a guaranteed network frame rate.

Save-loading/new-game/exit notifications clear transient state and queued
actions. Copy tokens are invalidated without resetting the token counter, so
a delayed request cannot intentionally reuse a token from an earlier save.

## Data transport

State and commands are newline-delimited text. Human-readable strings are
Base64-encoded UTF-8 where the packet format requires it. Map bytes travel in
`STATE MAPDATA BEGIN`, `CHUNK`, and `END` records; lengths and decoded dimensions
are checked before Android accepts a map. Some BSA map payloads carry compressed
bytes, which the companion inflates before decoding the DDS.

This is a local companion protocol, not a public network API. Commands are
revalidated on the game thread; availability in a previous snapshot is not
permission to act on a stale item, marker, limb or menu.
