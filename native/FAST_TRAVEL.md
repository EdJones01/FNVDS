# Native fast-travel request

FNVDS submits the map marker through the game's positioning request rather than moving the player directly to its coordinates. This allows the engine to resolve a linked arrival reference, use its position and heading, and run the normal fast-travel processing.

The request is submitted on the game thread after checking marker availability and `PlayerCharacter::IsFastTravelAllowed`. An existing positioning request must not be replaced. The game owns and frees a request after submission; FNVDS must not retain or free it.

Runtime addresses for the supported New Vegas PC 1.4.0.525 executable:

| Address/offset | Purpose |
| --- | --- |
| `0x0093D660` | Player fast-travel eligibility check |
| Player `+0x1EC` | Pending positioning request |
| `0x00401000` | Game heap allocation |
| `0x00456540` | Initialize the `0x34`-byte positioning request |
| Request `+0x30` | Selected map marker reference |
| `0x0093BE30` | Submit the request; may process it synchronously |
| `0x0093BEA0` | Engine handler that resolves the marker's linked reference before fast travel |
| `0x00569B80` | Read the linked reference; used by FNVDS for destination diagnostics |

Research references (interfaces and layout facts, not incorporated implementation code):

- [MapMenu request submission](https://github.com/momusdigital/fnv_decomp/blob/91e1b6e8d8bb0f9a33e8cf88e77268757449e0a6/src/fallout/interface/menus/mapmenu.cpp): `MapMenu_StartFastTravel`, address `0x00798A00`.
- [Player request processing](https://github.com/momusdigital/fnv_decomp/blob/91e1b6e8d8bb0f9a33e8cf88e77268757449e0a6/src/fallout/ai/playercharacter.cpp): addresses `0x0093BE30`, `0x0093BEA0` and `0x0093CDF0`.
- [Request constructor and linked-reference lookup](https://github.com/momusdigital/fnv_decomp/blob/91e1b6e8d8bb0f9a33e8cf88e77268757449e0a6/src/fallout_shared/tesobjectrefr.cpp): addresses `0x00456540` and `0x00569B80`.
- [Address bindings](https://github.com/momusdigital/fnv_decomp/blob/91e1b6e8d8bb0f9a33e8cf88e77268757449e0a6/build/units.json).

Build-time assertions check the request size and map-marker offset. In-game arrival behavior must still be confirmed on GameNative/AYN Thor; a successful build alone does not verify it.
