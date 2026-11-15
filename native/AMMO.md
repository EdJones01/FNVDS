# Ammo selection and reload

`WeaponCanUseAmmo` accepts the weapon's direct ammo form or an entry in its
ammo form list. Android dims incompatible ammo and places compatible ammo
first; the plugin independently checks compatibility and inventory ownership
again before dispatching an ammo equip request.

When an equip request changes ammo type, `PendingAmmoReload` records the ammo
and currently equipped weapon. The game-loop handler waits for that ammo to
become the process's selected ammo, then requests a native reload. Changing
weapons cancels the pending request; unsuccessful/waiting requests are bounded
to 30 attempts. Successful reload dispatch clears the pending request.

For hotkeyed weapons, the reload routine can restore the player's remembered
ammo preference. `UpdateHotkeyAmmoPreference` matches the weapon's aggregate
inventory entry and uses its first native hotkey index. It updates the saved
ammo preference both before selection and before reload dispatch.

| Binding | Use |
| --- | --- |
| `0x004BD7A0` | Read the aggregate entry's hotkey index |
| `0x004BFB30` | Read the player's saved hotkey ammo choice |
| `0x004BFB70` | Set that choice |
| Vtable entry `0x0108AE28` | Call the reload routine through xNVSE's hooked entry |

The plugin does not repeatedly force ammo after a reload or adjust ammo counts
to simulate a swap. Normal engine reload behaviour and its hook remain in use.
See [inventory notes](INVENTORY_COPIES.md) for copy tokens and action validation.
