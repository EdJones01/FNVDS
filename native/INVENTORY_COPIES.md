# Inventory copies, item details and actions

The game-loop code combines base-container counts with ExtraContainerChanges
deltas. Leveled-item entries use the replacement rule in `BuildInventoryCounts`.
Snapshot generation reads inventory; it does not create extra lists or items.

Non-throwable weapons and apparel have one row per copy. Each row's opaque
token identifies the form, extra-data pointer and ordinal. Tokens are resolved
only on the game thread after checking current ownership and list membership.
Plain copies use a null extra-list pointer only if a plain copy still exists.
Mutation/save transitions invalidate tokens without resetting their counter.

Throwable animation types grenade, mine, lunchbox mine and thrown stack by
form. Condition `-2` identifies a throwable stack; it has no displayed condition
bar. Its representative copy prefers an equipped, hotkeyed or other owned
extra list, then a plain copy. Other conditionless rows use `-1`.

## Equip, aid and details

Equip/unequip uses the engine's queued actor inventory operations with the
selected extra list. No stale copy is accepted just because it appeared in a
previous snapshot. Android follows an unambiguous newly issued matching token
for up to five seconds after equip so an expanded detail panel can stay open;
it does not guess between ambiguous duplicates.

`INSPECT <form> <token>` selects read-only details; `INSPECT 0 0` stops them.
`inventory_details.h` builds a borrowed single-copy EntryData for native damage,
DPS, weight, value, armour and effect queries. Borrowed list heads do not own
the game's extra data. Effects use the native evaluated item-card description,
including Hardcore-dependent conditions. Displayed weights are clamped to zero.

Details omit base AP cost and critical damage. Drop, Hotkey and conditional Mod
buttons are active gameplay actions, separate from the row's expansion tap.
Ordinary aid use runs the native cast at `0x008C1F80`; count checks request one
removal only if successful use did not already consume an item. Survival aid
revalidates the current need and evaluated relief. See [Stats](STATS.md),
[Limb treatment](LIMBCARE.md) and [Ammo](AMMO.md).

## Hotkeys

`HOTKEY <form> <token> <slot>` accepts slots 1 and 3-8. Slot 2 is reserved.
`inventory_hotkeys.h` validates the item/copy and uses native clear-slot
`0x004BF330` and assign-slot `0x004BF800` operations. The engine maintains
extra data and its hotkey cache; the player is marked inventory-modified.
`HOTKEYS BEGIN`, `HOTKEY`, and `HOTKEYS END` report occupied slots to Android's
seven-slot picker. Gear and usable aid are supported; invalid slots/copies
are rejected.

## Weapon mods

`inventory_mods.h` lists the selected weapon's three compatible mod slots,
owned counts and installed bits. `INSTALLMOD <weapon> <token> <mod>` rechecks
ownership, compatibility and an unfilled slot before using the native installer
at `0x00783AF0`. Its borrowed current-entry global is restored afterward.

Default-copy metadata uses zero countDelta rather than adding a weapon.
Stored absolute health is materialized when necessary before durability-mod
adjustment. Equipped copies are temporarily unequipped and re-equipped through
the existing native paths. Installation consumes one owned mod, checks the
count, invalidates weight/copy state and publishes MODRESULT. Android closes
the menu and logs errors without a success/error toast.

## Dropping

`DROPITEM <form> <token> <quantity>` defaults to one for the legacy two-argument
form. Stacked rows with multiple items open a quantity slider; individual
weapons and armour drop only the chosen copy. `inventory_drop.h` rechecks
ownership, quantity, quest-item restrictions, selected copy, activity, in-air
state, worn-item restrictions and a valid native drop point.

The player drop operation at `0x00954610` receives the chosen quantity once.
If quantity exceeds the selected extra-list stack, a null extra list lets the
engine choose the copies, matching native inventory handling. Success requires
the observed owned count to fall by exactly that quantity. Do not dereference
extra data after the call; the engine may transfer or free it. No RemoveItem
plus spawned-object substitute is used for dropping. DROPRESULT clears the
pending app action; failures are logged without a toast.

## Weight overview

ENCUMBRANCE reports the native InventoryWeight actor value and native maximum
carry weight, including its perk query. The Items readout opens an overview
sortable by name, total row weight or total row value. Stacks use totals;
individual gear retains separate rows and existing drop validation.

Runtime layout references include xNVSE 6.4.9 GameExtraData/GameForms headers
and the decompilation's inventory, item-card, actor and player routines. The
current code and native size/offset assertions define the implementation;
see [the native index](README.md) for research links.
