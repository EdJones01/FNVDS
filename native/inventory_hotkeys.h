// Included on the game-thread side of plugin.cpp, after the inventory-copy helpers.
bool AssignInventoryHotkey(PlayerCharacter* player, const PendingItemCommand& command) {
    if (command.hotkeySlot < 1 || command.hotkeySlot > 8 || command.hotkeySlot == 2) return false;
    TESForm* form = FindInventoryForm(player, command.formId);
    if (form == nullptr) return false;
    const bool gear = form->typeID == kFormType_TESObjectWEAP
            || form->typeID == kFormType_TESObjectARMO || form->typeID == kFormType_TESObjectCLOT;
    if (!gear && form->typeID != kFormType_AlchemyItem) return false;
    ExtraDataList* selected = nullptr;
    if (gear) {
        auto copy = g_inventoryCopies.find(command.instanceToken);
        if (copy == g_inventoryCopies.end() || copy->second.formId != form->refID
                || !ResolveInventoryCopy(player, form, copy->second, selected)) {
            WriteLog("FNVDS hotkey assignment rejected: inventory copy changed");
            return false;
        }
        if (!IsThrowableWeapon(form) && InventoryCopyCondition(form, selected) == 0) return false;
    }
    const int slot = static_cast<int>(command.hotkeySlot - 1);
    void* inventory = reinterpret_cast<void* (__cdecl *)(TESObjectREFR*)>(0x004BF220)(player);
    if (inventory == nullptr) return false;

    // The native Pip-Boy clears the destination slot, then assigns a selected extra list.
    // Let the engine create/merge default-copy extra data and maintain its hotkey cache.
    auto* changes = static_cast<ExtraContainerChanges*>(
            player->extraDataList.GetByType(kExtraData_ContainerChanges));
    auto* entries = changes != nullptr && changes->data != nullptr ? changes->data->objList : nullptr;
    TESForm* previousForm = nullptr;
    if (entries != nullptr) {
        for (auto it = entries->Begin(); !it.End(); ++it) {
            auto* entry = it.Get();
            if (entry == nullptr || entry->type == nullptr || entry->extendData == nullptr) continue;
            for (auto ex = entry->extendData->Begin(); !ex.End(); ++ex) {
                auto* extra = ex.Get();
                auto* key = extra != nullptr
                        ? static_cast<ExtraHotkey*>(extra->GetByType(kExtraData_Hotkey)) : nullptr;
                if (key != nullptr && key->index == slot) { previousForm = entry->type; break; }
            }
            if (previousForm != nullptr) break;
        }
    }
    if (previousForm != nullptr) {
        ExtraContainerChanges::EntryData previous{nullptr, 1, previousForm};
        ThisStdCall<void>(0x004BF330, inventory, &previous, slot);
    }
    auto counts = BuildInventoryCounts(player);
    const auto owned = counts.find(form);
    if (owned == counts.end() || owned->second <= 0) return false;
    ExtraContainerChanges::EntryData target{nullptr, owned->second, form};
    ThisStdCall<void>(0x004BF800, inventory, &target, selected, slot);
    player->MarkAsModified(0x20);
    char log[160]{};
    std::snprintf(log, sizeof(log), "FNVDS hotkey assigned form=%08X token=%u slot=%u",
            form->refID, command.instanceToken, command.hotkeySlot);
    WriteLog(log);
    return true;
}

std::string BuildHotkeySnapshot(PlayerCharacter* player) {
    std::string snapshot = "STATE HOTKEYS BEGIN\n";
    if (player != nullptr && g_gameReady.load()) {
        auto counts = BuildInventoryCounts(player);
        auto* changes = static_cast<ExtraContainerChanges*>(
                player->extraDataList.GetByType(kExtraData_ContainerChanges));
        auto* entries = changes != nullptr && changes->data != nullptr ? changes->data->objList : nullptr;
        bool seen[8]{};
        if (entries != nullptr) for (auto it = entries->Begin(); !it.End(); ++it) {
            auto* entry = it.Get();
            if (entry == nullptr || entry->type == nullptr || entry->extendData == nullptr) continue;
            auto owned = counts.find(entry->type);
            if (owned == counts.end() || owned->second <= 0) continue;
            for (auto ex = entry->extendData->Begin(); !ex.End(); ++ex) {
                auto* extra = ex.Get();
                auto* key = extra != nullptr
                        ? static_cast<ExtraHotkey*>(extra->GetByType(kExtraData_Hotkey)) : nullptr;
                if (key == nullptr || key->index >= 8 || key->index == 1 || seen[key->index]) continue;
                seen[key->index] = true;
                snapshot += "STATE HOTKEY " + std::to_string(key->index + 1) + ' '
                        + std::to_string(entry->type->refID) + ' ' + Base64Encode(FormDisplayName(entry->type)) + '\n';
            }
        }
    }
    return snapshot + "STATE HOTKEYS END\n";
}
