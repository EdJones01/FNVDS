# Limb treatment (0.6.5)

The Status page receives `STATE LIMBCARE <bag count> <hardcore> <crippled mask>` with the regular snapshot. Mask bits follow the display order: head, torso, left arm, right arm, left leg, right leg. The native query uses unrounded condition values; a finite value at or below zero sets the bit. The Android renderer uses this mask for crippled warnings and treatment selection, rather than interpreting a rounded percentage as proof that a limb is crippled.

The companion sends `TREATLIMB <display index>`. The plugin queues this alongside other gameplay actions and rechecks activity blocking, current crippled limbs, Hardcore mode, the configured Doctor's Bag form, and owned inventory. In normal mode the requested index does not restrict healing; in Hardcore mode it must still identify a crippled limb. Nothing happens when no limbs are crippled, and the app shows a message if there is no bag.

Treatment uses the existing native aid cast at `0x008C1F80` and its existing count-based removal fallback. Before casting, the plugin records the current active-effect pointers. Immediately after casting on the same game thread, it identifies newly created effects from the bag's MagicItem using the engine RTTI for LimbConditionEffect. It sets that effect's actor-value selector at `+0x4C` to the selected limb in Hardcore mode, or `-1` for all limbs in normal mode. Effect magnitude and the native healing calculations remain untouched. No active-effect pointers are retained across frames or save loads.

`STATE LIMBRESULT <sequence> <result>` reports command processing/consumption status. An OK result means the native cast returned successfully, a new limb effect was targeted and the inventory count decreased. It does not mean that the following game update has already applied the healing. The app continues to display the native condition values and does not optimistically mark limbs as healed.

## Local source references

These layouts and call paths were inspected in the local FNV decompilation reference at revision `91e1b6e8d8bb0f9a33e8cf88e77268757449e0a6` and the local xNVSE 6.4.9 headers:

- `src/fallout/interface/menus/statsmenu.cpp`: the Doctor's Bag healing option uses default-object index `0x15`; selected body-part indices map to actor values.
- xNVSE `GameForms.h` and `GameForms.cpp`: the default-object manager pointer is at `0x011CA80C`, with DoctorsBag in its typed form structure.
- `src/fallout/ai/actor_magic.cpp`: native aid casting at `0x008C1F80`.
- `src/fallout/magic/magictarget.cpp`: creation and registration of native effects, including assigning the limb-condition selector from the Pip-Boy selection.
- `include/LimbConditionEffect.h` and `src/fallout/magic/limbconditioneffect.cpp`: the per-effect selector follows ValueModifierEffect at `+0x4C`; `-1` selects all limbs.
- `src/fallout/magic/activeeffect.cpp`: Finalize evaluates conditions with zero elapsed time; the first positive-time Update calls Start and applies the modifier.

The UI makes condition labels and bars red below 25%, pulses crippled labels and empty bar tracks, independently of the shutter page-transition preference. Runtime behavior still needs confirmation on GameNative/AYN Thor; compilation is not a gameplay test.

## Pulse rendering fix (0.6.7)

Crippled-limb redraws depend on view/window visibility rather than input focus, so they continue when New Vegas owns focus on the other display. Each frame invalidates the parent and limb view to repaint fading text and tracks. The empty red track has a stronger synchronized pulse. Callbacks stop when detached, hidden, or no limb is crippled. As of 0.7.1 the shutter setting has no effect on the pulse. Native treatment behavior is unchanged.

