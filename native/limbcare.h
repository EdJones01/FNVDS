#pragma once

constexpr UInt32 kLimbCareActorValues[6] = {
    eActorVal_Head, eActorVal_Torso, eActorVal_LeftArm,
    eActorVal_RightArm, eActorVal_LeftLeg, eActorVal_RightLeg
};
UInt32 g_limbCareSequence = 0;
std::string g_limbCareResult;

TESForm* DoctorsBagForm() {
    auto* defaults = *reinterpret_cast<BGSDefaultObjectManager**>(0x011CA80C);
    return defaults ? defaults->defaultObjects.asStruct.DoctorsBag : nullptr;
}
UInt32 CrippledLimbMask(PlayerCharacter* player) {
    UInt32 mask = 0;
    if (player) for (unsigned i = 0; i < 6; ++i) {
        float condition = player->avOwner.Fn_03(kLimbCareActorValues[i]);
        if (std::isfinite(condition) && condition <= 0) mask |= 1u << i;
    }
    return mask;
}
void FinishLimbCare(const char* result) {
    g_limbCareResult = "STATE LIMBRESULT " + std::to_string(++g_limbCareSequence) + " " + result + "\n";
}
std::string BuildLimbCareSnapshot(PlayerCharacter* player) {
    SInt32 count = 0;
    if (player) {
        auto counts = BuildInventoryCounts(player);
        auto found = counts.find(DoctorsBagForm());
        if (found != counts.end()) count = std::max<SInt32>(0, found->second);
    }
    bool hardcore = player && ThisStdCall<bool>(0x004D1360, player);
    return "STATE LIMBCARE " + std::to_string(count) + " " + (hardcore ? "1 " : "0 ")
            + std::to_string(CrippledLimbMask(player)) + "\n" + g_limbCareResult;
}

std::unordered_set<ActiveEffect*> CurrentLimbCareEffects(PlayerCharacter* player) {
    std::unordered_set<ActiveEffect*> before;
    auto* list = player->magicTarget.GetEffectList();
    unsigned visited = 0;
    if (list) for (auto it = list->Begin(); !it.End() && visited++ < 8192; ++it)
        if (it.Get()) before.insert(it.Get());
    return before;
}

unsigned TargetNewLimbCareEffects(PlayerCharacter* player, TESForm* form,
        const std::unordered_set<ActiveEffect*>& before, SInt32 actorValue) {
    MagicItem* source = DYNAMIC_CAST(form, TESForm, MagicItem);
    auto* list = player->magicTarget.GetEffectList();
    unsigned targeted = 0, visited = 0;
    if (source && list) for (auto it = list->Begin(); !it.End() && visited++ < 8192; ++it) {
        ActiveEffect* effect = it.Get();
        if (!effect || before.contains(effect) || effect->magicItem != source) continue;
        void* limb = Fallout_DynamicCast(effect, 0, RTTI_ActiveEffect, RTTI_LimbConditionEffect, 0);
        if (!limb) continue;
        // The native cast creates the effect; its first positive-time update applies healing.
        // Set the same per-effect actor-value field used by the Pip-Boy before that update.
        *reinterpret_cast<SInt32*>(static_cast<UInt8*>(limb) + 0x4C) = actorValue;
        ++targeted;
    }
    return targeted;
}
