# Quest marker preview (0.7.2)

Long-pressing a quest opens a confirmation in the companion. `VIEWQUEST <questForm> <request>` queues a lookup on the main game loop. The request is restricted to a displayed, incomplete objective of a running, Pip-Boy-visible quest in the player's objective list.

The lookup calls TESQuest::GetTargetsAndObjectives at 0x0060F110 with a temporary target list and the player's collected objective list. The engine filters objective states and target conditions and builds door paths. It does not select the quest or replace the player's current target list. Only temporary list nodes are freed; targets remain game-owned. Existing world-map normalization and door-route resolution supply the first available endpoint on the current world map.

`STATE QUESTVIEW <request> <questForm> OK <x> <y>` returns the normalized point; `UNAVAILABLE` replaces the final three fields when there is no endpoint on that map. Request IDs prevent an older response from opening a later quest. The app cancels pending navigation after leaving Quests, changing world, or waiting for a save, and reports a timeout after eight seconds. The map applies the focus once when its bitmap and layout are ready, so later updates do not snap back after panning. A temporary filled quest icon makes an untracked quest's destination visible; it clears when leaving the world-map page.

Runtime lookup reference, inspected for addresses and behavior (no source copied):
- https://github.com/momusdigital/fnv_decomp/blob/main/src/fallout_shared/tesquest.cpp (0x0060F110)
- https://github.com/momusdigital/fnv_decomp/blob/main/src/fallout_shared/bgsquestobjective.cpp (0x005EC500)
- https://github.com/momusdigital/fnv_decomp/blob/main/src/fallout/ai/playercharacter.cpp (0x00952BA0)

Build checks do not establish GameNative runtime compatibility; verify both tracked and untracked quests on the Thor.
