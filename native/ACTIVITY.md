# Activity blocking

`activity.h` detects game states in which ordinary companion actions must not
run. `Activity::Set` publishes the reason and clears queued item actions and
pending ammo reloads whenever the reason is not `None`.

The detector covers save loading, player death, fast travel, cell positioning
requests, loading menus, barter, pause, level-up allocation, containers,
crafting/RecipeMenu and native text prompts. RecipeMenu covers workbenches,
reloading benches and campfires. LevelUpMenu covers both skills and perks.
The order of checks in `Activity::Update` determines precedence.

Fast travel and door changes inspect the player's pending positioning request
at `+0x1EC`; destination data can be supplied with an entering-state packet.
Level-up text reads the current level through the native actor query.

Android displays activity animations or text, or a read-only trade/container
summary. Dialogue and V.A.T.S. have their own controls. The active native text
entry session can send keyboard commands while ordinary item actions are
blocked, since naming and console input may coexist with a paused game.

The activity detector does not itself perform game transitions or change menu
visibility. Verify precedence and clearing behaviour on the Thor after edits.
