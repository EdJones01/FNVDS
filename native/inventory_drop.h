// Included inside plugin.cpp's anonymous namespace; called on the game thread.
UInt32 g_inventoryDropSequence = 0;
std::string g_inventoryDropResult;

bool InventoryItemCanDrop(TESForm* form) {
    if (form == nullptr || form->IsQuestItem() || form->IsDeleted()
            || InventoryCategory(form) == nullptr) return false;
    // Currency and internal Pip-Boy devices are not ordinary droppable inventory.
    return form->refID != 0xF && form->refID != 0x00025B83 && form->refID != 0x00015038
            && form->refID != 0x000236D8 && form->refID != 0x0011BACB && form->refID != 0x0011BCF8;
}

void InventoryDropResult(bool success, const char* message) {
    g_inventoryDropResult = "STATE DROPRESULT " + std::to_string(++g_inventoryDropSequence)
            + (success ? " 1 " : " 0 ") + Base64Encode(message) + '\n';
    WriteLog(message);
}

void DropInventoryCopy(PlayerCharacter* player, const PendingItemCommand& command) {
    TESForm* form = FindInventoryForm(player, command.formId);
    if (!InventoryItemCanDrop(form)) {
        InventoryDropResult(false, form != nullptr && form->IsQuestItem()
                ? "Quest items cannot be dropped." : "That item cannot be dropped.");
        return;
    }
    ExtraDataList* extra = nullptr;
    const bool gear = form->typeID == kFormType_TESObjectWEAP
            || form->typeID == kFormType_TESObjectARMO || form->typeID == kFormType_TESObjectCLOT;
    const UInt32 quantity = command.quantity;
    if (quantity == 0 || quantity > 0x7FFFFFFFu || (gear && !IsThrowableWeapon(form) && quantity != 1)) {
        InventoryDropResult(false, "That item must be dropped one copy at a time.");
        return;
    }
    if (gear) {
        auto copy = g_inventoryCopies.find(command.instanceToken);
        if (copy == g_inventoryCopies.end() || copy->second.formId != form->refID
                || !ResolveInventoryCopy(player, form, copy->second, extra)) {
            InventoryDropResult(false, "Item copy changed. Open its details again.");
            return;
        }
    } else if (command.instanceToken != 0) {
        InventoryDropResult(false, "Item selection is no longer valid.");
        return;
    }
    auto counts = BuildInventoryCounts(player);
    auto owned = counts.find(form);
    if (owned == counts.end() || owned->second <= 0) {
        InventoryDropResult(false, "That item is no longer in your inventory.");
        return;
    }
    const int before = owned->second;
    if (quantity > static_cast<UInt32>(before)) {
        InventoryDropResult(false, "Inventory quantity changed. Select the amount again.");
        return;
    }
    // Match the Pip-Boy's temporary restrictions before invoking the player drop routine.
    if (ThisStdCall<bool>(0x009337B0, player)) {
        InventoryDropResult(false, "You cannot drop items while in the air.");
        return;
    }
    if (IsInventoryCopyEquipped(extra)
            && (ThisStdCall<SInt32>(0x008A7570, player) != -1
                || ThisStdCall<bool>(0x00418B10, extra))) {
        InventoryDropResult(false, "That equipped item cannot be dropped right now.");
        return;
    }
    NiPoint3 point{};
    if (!ThisStdCall<bool>(0x009614B0, player, form, &point, true, true)) {
        InventoryDropResult(false, "There is no room to drop that item here.");
        return;
    }
    using Drop = TESObjectREFR* (__thiscall *)(PlayerCharacter*, TESForm*, ExtraDataList*,
                                              SInt32, NiPoint3*, bool);
    // This is the player override of the virtual drop operation used by InventoryMenu.
    // InventoryMenu also clears the selected extra list when the requested
    // quantity spans more than that list's stack, letting the engine select it.
    ExtraDataList* dropExtra = extra;
    if (dropExtra && InventoryExtraCount(dropExtra) < static_cast<SInt32>(quantity)) dropExtra = nullptr;
    reinterpret_cast<Drop>(0x00954610)(player, form, dropExtra, static_cast<SInt32>(quantity), &point, false);
    // The engine owns removal, unequipping, extra-data transfer and creation of the world reference.
    // Do not access the selected extra list after the call: it may have moved or been freed.
    auto after = BuildInventoryCounts(player);
    auto remaining = after.find(form);
    const int afterCount = remaining != after.end() ? remaining->second : 0;
    if (afterCount != before) {
        g_inventoryCopies.clear();
        g_inventoryCopyTokens.clear();
        g_inspectedItemForm = g_inspectedItemToken = 0;
        player->MarkAsModified(0x20);
    }
    const bool confirmed = afterCount == before - static_cast<SInt32>(quantity);
    InventoryDropResult(confirmed, confirmed
            ? (quantity == 1 ? "Item dropped." : "Items dropped.")
            : "The game did not confirm the requested quantity was dropped.");
}
