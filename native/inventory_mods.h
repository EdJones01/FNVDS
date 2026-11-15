// Included inside plugin.cpp's anonymous namespace; inventory mutations run on the game thread.
UInt32 g_weaponModResultSequence = 0;
std::string g_weaponModResult;

void WeaponModResult(bool success, const char* message) {
    ++g_weaponModResultSequence;
    g_weaponModResult = "STATE MODRESULT " + std::to_string(g_weaponModResultSequence)
            + (success ? " 1 " : " 0 ") + Base64Encode(message) + '\n';
    WriteLog(message);
}

std::string BuildWeaponModOptions(TESObjectWEAP* weapon, UInt32 token, ExtraDataList* extra,
                                  const std::unordered_map<TESForm*, SInt32>& owned) {
    const std::string key = std::to_string(weapon->refID) + ' ' + std::to_string(token);
    std::string snapshot = "STATE WEAPONMODS BEGIN " + key + '\n';
    TESObjectIMOD* slots[] = {weapon->itemMod1, weapon->itemMod2, weapon->itemMod3};
    const int flags = InventoryCopyModFlags(weapon, extra);
    for (int slot = 0; slot < 3; ++slot) {
        TESObjectIMOD* mod = slots[slot];
        if (mod == nullptr) continue;
        auto found = owned.find(mod);
        const int count = found != owned.end() ? std::max<SInt32>(0, found->second) : 0;
        snapshot += "STATE WEAPONMOD " + key + ' ' + std::to_string(mod->refID) + ' '
                + std::to_string(count) + ((flags & (1 << slot)) != 0 ? " 1 " : " 0 ")
                + Base64Encode(FormDisplayName(mod)) + '\n';
    }
    return snapshot + "STATE WEAPONMODS END " + key + '\n';
}

template <class T> tList<T>* NewInventoryList() {
    auto* list = static_cast<tList<T>*>(AllocateGameMemory(sizeof(tList<T>)));
    if (list != nullptr) {
        list->m_listHead.data = nullptr;
        list->m_listHead.next = nullptr;
    }
    return list;
}

// Native AddHead takes the address of the stored pointer, not the pointed-to object.
template <class T> void InventoryListPrepend(tList<T>* list, T* value) {
    ThisStdCall<void>(0x005AE3D0, list, &value);
}

bool InstallInventoryWeaponMod(PlayerCharacter* player, const PendingItemCommand& command) {
    auto fail = [](const char* message) { WeaponModResult(false, message); return false; };
    if (g_consoleInterface == nullptr) return fail("Weapon mod could not be installed.");
    TESForm* form = FindInventoryForm(player, command.formId);
    auto copy = g_inventoryCopies.find(command.instanceToken);
    ExtraDataList* selected = nullptr;
    if (form == nullptr || form->typeID != kFormType_TESObjectWEAP
            || copy == g_inventoryCopies.end() || copy->second.formId != form->refID
            || !ResolveInventoryCopy(player, form, copy->second, selected))
        return fail("Weapon copy changed. Open its details again.");
    auto* weapon = static_cast<TESObjectWEAP*>(form);
    TESObjectIMOD* slots[] = {weapon->itemMod1, weapon->itemMod2, weapon->itemMod3};
    TESObjectIMOD* mod = nullptr;
    int bit = 0;
    for (int slot = 0; slot < 3; ++slot) {
        if (slots[slot] != nullptr && slots[slot]->refID == command.modFormId
                && (InventoryCopyModFlags(form, selected) & (1 << slot)) == 0) {
            mod = slots[slot]; bit = 1 << slot; break;
        }
    }
    auto owned = BuildInventoryCounts(player);
    auto count = mod != nullptr ? owned.find(mod) : owned.end();
    if (mod == nullptr || count == owned.end() || count->second <= 0)
        return fail("That mod is already installed or is no longer in your inventory.");
    const int modCount = count->second;
    void* inventory = reinterpret_cast<void* (__cdecl *)(TESObjectREFR*)>(0x004BF220)(player);
    if (inventory == nullptr) return fail("Player inventory is unavailable.");
    auto* changes = static_cast<ExtraContainerChanges*>(player->extraDataList.GetByType(kExtraData_ContainerChanges));
    if (changes == nullptr || changes->data == nullptr) return fail("Player inventory is unavailable.");

    // Default copies need owned extra data before the native mod-menu installer can use them.
    // A zero countDelta entry records metadata for an existing copy without adding an item.
    if (selected == nullptr) {
        auto* data = changes->data;
        if (data->objList == nullptr) data->objList = static_cast<ExtraContainerChanges::EntryDataList*>(
                static_cast<void*>(NewInventoryList<ExtraContainerChanges::EntryData>()));
        if (data->objList == nullptr) return fail("Could not prepare weapon metadata.");
        ExtraContainerChanges::EntryData* owner = nullptr;
        for (auto it = data->objList->Begin(); !it.End(); ++it)
            if (it.Get() != nullptr && it.Get()->type == form) { owner = it.Get(); break; }
        if (owner == nullptr) {
            owner = static_cast<ExtraContainerChanges::EntryData*>(AllocateGameMemory(sizeof(*owner)));
            if (owner == nullptr) return fail("Could not prepare weapon metadata.");
            *owner = {nullptr, 0, form};
            InventoryListPrepend<ExtraContainerChanges::EntryData>(data->objList, owner);
        }
        if (owner->extendData == nullptr) owner->extendData = static_cast<ExtraContainerChanges::ExtendDataList*>(
                static_cast<void*>(NewInventoryList<ExtraDataList>()));
        if (owner->extendData == nullptr) return fail("Could not prepare weapon metadata.");
        selected = static_cast<ExtraDataList*>(AllocateGameMemory(sizeof(ExtraDataList)));
        if (selected == nullptr) return fail("Could not prepare weapon metadata.");
        ThisStdCall<void>(0x00410360, selected);
        InventoryListPrepend(owner->extendData, selected);
    }
    ExtraContainerChanges::ExtendDataList oneExtra;
    oneExtra.m_listHead.data = selected;
    oneExtra.m_listHead.next = nullptr;
    ExtraContainerChanges::EntryData target{&oneExtra, 1, form};
    ExtraContainerChanges::EntryData modEntry{nullptr, 1, mod};
    auto* extraCount = static_cast<ExtraCount*>(selected->GetByType(kExtraData_Count));
    const bool splitStack = extraCount != nullptr && extraCount->count > 1;
    ThisStdCall<void>(0x004BDB40, &target);
    const bool wasEquipped = IsInventoryCopyEquipped(selected);
    using Unequip = bool (__thiscall *)(Actor*, TESForm*, int, ExtraDataList*, bool, bool, bool);
    if (wasEquipped) reinterpret_cast<Unequip>(0x0088D7D0)(player, form, 1, selected, true, true, false);
    if (IsInventoryCopyEquipped(selected)) {
        ThisStdCall<void>(0x004BDC70, &target);
        return fail("Weapon could not be unequipped for modding.");
    }
    // The engine adds durability bonuses to stored absolute health. Materialize full health
    // when it was represented by the absent/-1 sentinel before installing a health mod.
    bool addsHealth = false;
    for (int slot = 0; slot < 3; ++slot)
        if (bit == (1 << slot) && weapon->effectMods[slot] == 10) addsHealth = true;
    auto* health = static_cast<ExtraHealth*>(selected->GetByType(kExtraData_Health));
    if (addsHealth && (health == nullptr || health->health == -1.0f)) {
        float maximum = static_cast<float>(weapon->health.health);
        if (InventoryCopyModFlags(form, selected) != 0) {
            for (int slot = 0; slot < 3; ++slot)
                if ((InventoryCopyModFlags(form, selected) & (1 << slot)) != 0
                        && weapon->effectMods[slot] == 10) {
                    maximum += weapon->value1Mod[slot]; break;
                }
        }
        ThisStdCall<void>(0x00419970, selected, maximum);
    }
    struct CurrentModEntry {
        ExtraContainerChanges::EntryData** address = reinterpret_cast<ExtraContainerChanges::EntryData**>(0x011D9F58);
        ExtraContainerChanges::EntryData* previous = *address;
        explicit CurrentModEntry(ExtraContainerChanges::EntryData* entry) { *address = entry; }
        ~CurrentModEntry() { *address = previous; }
    };
    bool installed = false;
    {
        CurrentModEntry scope(&target);
        // ItemModItem uses the current-entry global and does not access its menu receiver.
        char unusedMenu = 0;
        installed = ThisStdCall<bool>(0x00783AF0, &unusedMenu, &modEntry);
    }
    ExtraDataList* result = oneExtra.m_listHead.data;
    installed = installed && result != nullptr && (InventoryCopyModFlags(form, result) & bit) != 0;
    ThisStdCall<void>(0x004BDC70, &target);
    if (splitStack && selected != result) {
        ExtraContainerChanges::ExtendDataList originalExtra;
        originalExtra.m_listHead.data = selected;
        originalExtra.m_listHead.next = nullptr;
        ExtraContainerChanges::EntryData original{&originalExtra, 1, form};
        ThisStdCall<void>(0x004BDC70, &original);
    }
    if (wasEquipped && result != nullptr) {
        using Equip = void (__thiscall *)(Actor*, TESForm*, UInt32, ExtraDataList*, UInt32, bool, UInt32);
        reinterpret_cast<Equip>(0x0088C650)(player, form, 1, result, 1, false, 1);
    }
    while (oneExtra.m_listHead.data != nullptr || oneExtra.m_listHead.next != nullptr)
        ThisStdCall<void>(0x0063F7B0, &oneExtra);
    changes->data->unk3 = changes->data->unk2;
    changes->data->unk2 = -1.0f;
    player->MarkAsModified(0x20);
    // Mutations can split or merge stacks; old copy tokens must not select a different copy.
    g_inventoryCopyTokens.clear();
    g_inventoryCopies.clear();
    g_inspectedItemForm = 0;
    g_inspectedItemToken = 0;
    if (!installed) return fail("The game could not install that weapon mod.");
    char line[96]{};
    std::snprintf(line, sizeof(line), "RemoveItem %08X 1 1", mod->refID);
    g_consoleInterface->RunScriptLine2(line, player, true);
    const auto after = BuildInventoryCounts(player);
    auto remaining = after.find(mod);
    const int remainingCount = remaining != after.end() ? remaining->second : 0;
    if (remainingCount != modCount - 1)
        return fail("Mod installed, but its inventory count could not be confirmed. Check the in-game inventory.");
    WeaponModResult(true, "Weapon mod installed.");
    return true;
}
