// Included inside plugin.cpp's anonymous namespace. All queries run on the game thread.
std::string ItemDetailNumber(float number) {
    if (!std::isfinite(number)) return "—";
    char buffer[48]{};
    std::snprintf(buffer, sizeof(buffer), "%.1f", number);
    std::string text(buffer);
    if (text.ends_with(".0")) text.resize(text.size() - 2);
    return text;
}

std::string BuildItemDetailsSnapshot(PlayerCharacter* player) {
    if (g_inspectedItemForm == 0 || player == nullptr || !g_gameReady.load()) return {};
    const UInt32 formId = g_inspectedItemForm;
    const UInt32 token = g_inspectedItemToken;
    TESForm* form = FindInventoryForm(player, formId);
    ExtraDataList* extra = nullptr;
    if (form == nullptr) return {};
    const bool gear = form->typeID == kFormType_TESObjectWEAP
            || form->typeID == kFormType_TESObjectARMO || form->typeID == kFormType_TESObjectCLOT;
    if (gear) {
        auto copy = g_inventoryCopies.find(token);
        if (copy == g_inventoryCopies.end() || copy->second.formId != formId
                || !ResolveInventoryCopy(player, form, copy->second, extra)) return {};
    }

    // A borrowed single-stack view lets the read-only Pip-Boy queries inspect this copy.
    // Nothing is added to the player's inventory; neither entry owns the borrowed extra data.
    ExtraContainerChanges::ExtendDataList singleExtra;
    singleExtra.m_listHead.data = extra;
    singleExtra.m_listHead.next = nullptr;
    ExtraContainerChanges::EntryData entry{extra != nullptr ? &singleExtra : nullptr, 1, form};
    std::string stats;
    auto add = [&](const std::string& label, std::string value) {
        if (value.empty()) return;
        for (char& character : value) if (character == '\n' || character == '\r' || character == '\t') character = ' ';
        if (!stats.empty()) stats += '\n';
        stats += label + '\t' + value;
    };
    auto number = [&](const char* label, float value) { add(label, ItemDetailNumber(value)); };
    const bool hardcore = ThisStdCall<bool>(0x004D1360, player);
    const float value = reinterpret_cast<float (__fastcall *)(ExtraContainerChanges::EntryData*)>(
            0x004BD400)(&entry);
    float weight = reinterpret_cast<float (__cdecl *)(TESForm*, bool)>(0x0048EBC0)(form, hardcore);
    bool canMod = false;
    std::string modOptions;
    using ApplyPerk = void (__cdecl *)(UInt32, Actor*, ...);
    const auto applyPerk = reinterpret_cast<ApplyPerk>(0x005E58F0);

    if (form->typeID == kFormType_TESObjectWEAP) {
        auto* weapon = static_cast<TESObjectWEAP*>(form);
        const int flags = InventoryCopyModFlags(form, extra);
        auto hasModEffect = [&](UInt32 effect) {
            for (int slot = 0; slot < 3; ++slot)
                if ((flags & (1 << slot)) != 0 && weapon->effectMods[slot] == effect) return true;
            return false;
        };
        weight = ThisStdCall<float>(0x004BE380, weapon, hasModEffect(4));
        if (weight >= 10) {
            float multiplier = 1;
            applyPerk(0x49, player, &multiplier);
            weight *= multiplier;
        }
        const int condition = InventoryCopyCondition(form, extra);
        const float fraction = condition < 0 ? 1.0f : condition / 1000.0f;
        TESAmmo* ammo = weapon == GetEquippedWeapon(player) ? GetEquippedAmmo(player) : nullptr;
        using DamageQuery = float (__cdecl *)(TESObjectWEAP*, float,
                ExtraContainerChanges::EntryData*, TESAmmo*);
        using DpsQuery = float (__cdecl *)(ActorValueOwner*, TESObjectWEAP*, float, bool,
                ExtraContainerChanges::EntryData*, bool, bool, int, float, float, bool, bool, TESAmmo*);
        number("DAMAGE", fraction > 0 ? reinterpret_cast<DamageQuery>(0x006450F0)(
                weapon, fraction, &entry, ammo) : 0);
        number("DPS", reinterpret_cast<DpsQuery>(0x00645380)(
                &player->avOwner, weapon, fraction, true, &entry, false, false,
                -1, 0.0f, 0.0f, false, false, ammo));
        float reduction = 0;
        applyPerk(0x35, player, weapon, &reduction);
        number("STR REQUIRED", std::max<float>(0, weapon->strRequired - reduction));
        const char* skill = weapon->weaponSkill < 77
                ? reinterpret_cast<const char* (__cdecl *)(UInt32)>(0x0066EB00)(weapon->weaponSkill) : nullptr;
        add("SKILL REQUIRED", (skill != nullptr ? GameTextToUtf8(skill) + " " : std::string())
                + ItemDetailNumber(static_cast<float>(weapon->skillRequirement)));
        TESObjectIMOD* slots[] = {weapon->itemMod1, weapon->itemMod2, weapon->itemMod3};
        auto owned = BuildInventoryCounts(player);
        modOptions = BuildWeaponModOptions(weapon, token, extra, owned);
        std::string installed;
        for (int slot = 0; slot < 3; ++slot) {
            if (slots[slot] == nullptr) continue;
            if ((flags & (1 << slot)) != 0) {
                if (!installed.empty()) installed += ", ";
                installed += FormDisplayName(slots[slot]);
            } else {
                auto found = owned.find(slots[slot]);
                if (found != owned.end() && found->second > 0) canMod = true;
            }
        }
        add("MODS", installed);
    } else if (form->typeID == kFormType_TESObjectARMO) {
        auto* armor = static_cast<TESObjectARMO*>(form);
        const int condition = InventoryCopyCondition(form, extra);
        const float fraction = condition < 0 ? 0.0f : condition / 1000.0f;
        using ArmourQuery = float (__cdecl *)(UInt16, float);
        const auto protection = reinterpret_cast<ArmourQuery>(0x00646360);
        // The item card converts base DT/DR to whole ratings before applying condition.
        number("DT", protection(static_cast<UInt16>(armor->damageThreshold), fraction));
        number("DR", protection(static_cast<UInt16>(armor->armorRating / 100), fraction));
        std::string type = (armor->bipedModel.bipedFlags & TESBipedModelForm::eBipedFlag_HeavyArmor)
                ? "Heavy" : (armor->bipedModel.bipedFlags & TESBipedModelForm::eBipedFlag_MediumArmor)
                ? "Medium" : "Light";
        if (armor->bipedModel.IsPowerArmor()) type += " / Power armour";
        add("ARMOUR TYPE", type);
    } else if (form->typeID == kFormType_TESObjectCLOT) {
        add("ARMOUR TYPE", "Clothing");
    }
    number("WEIGHT", std::max<float>(0.0f, weight));
    number("VALUE", value);

    EffectItemList* effects = nullptr;
    auto* magic = DYNAMIC_CAST(form, TESForm, MagicItem);
    if (magic != nullptr) effects = &magic->list;
    if (effects == nullptr) {
        auto* enchantable = DYNAMIC_CAST(form, TESForm, TESEnchantableForm);
        if (enchantable != nullptr && enchantable->enchantItem != nullptr)
            effects = &enchantable->enchantItem->magicItem.list;
    }
    if (effects != nullptr && effects->list.m_listHead.data != nullptr) {
        char description[256]{};
        // The native formatter evaluates conditions (including hardcore mode), skills and perks.
        ThisStdCall<void>(0x00406620, effects, description, static_cast<UInt32>(sizeof(description)));
        description[sizeof(description) - 1] = 0;
        add("EFFECTS", GameTextToUtf8(description));
    }
    if (form->typeID == kFormType_TESAmmo) {
        char description[256]{};
        ThisStdCall<void>(0x00503A70, form, description, static_cast<UInt32>(sizeof(description)));
        description[sizeof(description) - 1] = 0;
        add("EFFECTS", GameTextToUtf8(description));
    }
    return "STATE ITEMDETAILS " + std::to_string(formId) + ' ' + std::to_string(token)
            + (canMod ? " 1 " : " 0 ") + (InventoryItemCanDrop(form) ? "1 " : "0 ")
            + Base64Encode(stats) + '\n' + modOptions;
}
