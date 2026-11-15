# Challenges panel

DATA / CHALLENGES follows GENERAL. It shows the union of the Pip-Boy's kill, hit/damage and miscellaneous challenge lists, rather than restricting the companion to whichever filter is currently selected in-game. Completed entries are retained, dimmed and sorted below unfinished entries. Within each group, names are sorted alphabetically.

The game-loop snapshot traverses `DataHandler::challengeList`, the same source as `MapMenu::RefreshChallengeList` (`0x0079B600`). This is not a scan of every form in the game. For runtime 1.4.0.525, the challenge layout uses type at `+0x54`, threshold at `+0x58`, definition flags at `+0x5C`, progress at `+0x6C`, and saved state flags at `+0x70`.

Visibility follows the locked/unlocked and zero-progress checks in the local decompilation's `MapMenu::RefreshChallengeList` and `MapMenu_ChallengeFilterFunc` (`0x0079B720`). The three game filters cover types 0–6 and 8–13. A locked entry requires saved state bit 0; a zero-progress entry requires definition bit 2 or saved state bit 2. Completion is read from saved state bit 1, matching the getter at `0x005A6150` used to dim the Pip-Boy entry. Recurring status uses definition bit 1. No flags, progress, scripts or rewards are changed.

The local decompilation's `TESChallenge` layout, MapMenu challenge-text routine (`0x007991B0`), and `TESDescription::GetDescription` informed the field/component reads. Some recovered type and method names in that source are aliases of shared machine-code getters; the offsets and menu call sites are the relevant evidence.

Metadata is sent through the normal gameplay snapshot:

```
STATE CHALLENGES BEGIN
STATE CHALLENGE <form> <progress> <threshold> <completed> <recurring> <base64 name>
STATE CHALLENGES END
```

Tapping a row expands its description. Android sends `CHALLENGEDETAIL <form>`. The socket worker only records the request; the game-loop snapshot revalidates it against visible challenges before reading the `TESDescription` component at `+0x24`, using the DESC chunk (`0x43534544`). This reads one requested description rather than loading descriptions for the entire challenge collection. Text is copied immediately, converted to UTF-8 and cached by form ID; no description pointer is retained. The reply is `STATE CHALLENGEDETAIL <form> <base64 text>` (or `-` for empty content).

Save loading clears pending requests, descriptions and published detail state. Waiting snapshots publish an empty challenge list. Android compares metadata signatures before rebuilding the visible panel and keeps the selected challenge expanded as progress changes. The existing navigation, interface colors and scrolling apply to the new panel. Only xNVSE and FNVDS are required.
