#pragma once

namespace Activity {
bool fastTravel = false;
bool sawTravelRequest = false;
ULONGLONG travelStartedAt = 0;
float travelX = 0, travelY = 0;
TESObjectCELL* travelCell = nullptr;
bool cellChanging = false;
ULONGLONG cellChangeStartedAt = 0;
std::string destinationName;
std::string publishedPlace;

void DiscardCommands() {
    std::lock_guard<std::mutex> lock(g_pendingCommandMutex);
    g_pendingItemCommands.clear();
    g_pendingAmmoReload = {};
}
void Set(ActivityBlock reason, const std::string& place = {}) {
    {
        std::lock_guard<std::mutex> lock(g_snapshotMutex);
        publishedPlace = place;
        g_activityBlock.store(reason);
    }
    if (reason != ActivityBlock::None) DiscardCommands();
}
const char* Name(ActivityBlock reason) {
    switch (reason) {
        case ActivityBlock::Paused: return "PAUSED";
        case ActivityBlock::LoadingSave: return "LOADING_SAVE";
        case ActivityBlock::Dead: return "DEAD";
        case ActivityBlock::Bartering: return "BARTERING";
        case ActivityBlock::FastTravel: return "FAST_TRAVEL";
        case ActivityBlock::Loading: return "LOADING";
        case ActivityBlock::Entering: return "ENTERING";
        case ActivityBlock::LevelUp: return "LEVEL_UP";
        case ActivityBlock::Container: return "CONTAINER";
        case ActivityBlock::Crafting: return "CRAFTING";
        default: return "NONE";
    }
}
std::string Packet() {
    std::lock_guard<std::mutex> lock(g_snapshotMutex);
    ActivityBlock reason = g_activityBlock.load();
    std::string packet = std::string("STATE ACTIVITY ") + Name(reason);
    if (reason == ActivityBlock::Entering || reason == ActivityBlock::LevelUp)
        packet += " " + Base64Encode(publishedPlace);
    return packet;
}
std::string PlaceName(TESForm* form) {
    if (!form) return {};
    TESFullName* name = DYNAMIC_CAST(form, TESForm, TESFullName);
    return name && name->name.m_data ? GameTextToUtf8(name->name.m_data) : std::string();
}
bool MenuVisible(UInt32 id) { return reinterpret_cast<UInt8*>(0x11F308F)[id] != 0; }
void BeginFastTravel(PlayerCharacter* player) {
    fastTravel = true;
    cellChanging = false;
    sawTravelRequest = false;
    travelStartedAt = GetTickCount64();
    travelX = player->posX;
    travelY = player->posY;
    travelCell = player->parentCell;
    Set(ActivityBlock::FastTravel);
}
void Update(PlayerCharacter* player) {
    if (g_loadingSave.load()) { Set(ActivityBlock::LoadingSave); return; }
    if (!g_gameReady.load() || !player) {
        fastTravel = false;
        cellChanging = false;
        Set(ActivityBlock::None);
        return;
    }
    if (player->lifeState == 1 || player->lifeState == 2) { Set(ActivityBlock::Dead); return; }
    // PlayerCharacter's pending positioning request is at +0x1EC; +0x30
    // in that request contains the destination reference for vanilla fast travel.
    auto* request = *reinterpret_cast<UInt8**>(reinterpret_cast<UInt8*>(player) + 0x1EC);
    bool loading = MenuVisible(0x3EF);
    if (!fastTravel && request && *reinterpret_cast<void**>(request + 0x30)) BeginFastTravel(player);
    if (fastTravel) {
        if (request) sawTravelRequest = true;
        ULONGLONG elapsed = GetTickCount64() - travelStartedAt;
        bool moved = player->parentCell != travelCell || player->posX != travelX || player->posY != travelY;
        // A completed/cancelled request must not leave the companion locked.
        if (!request && !loading && elapsed >= 250
                && (sawTravelRequest || moved || elapsed >= 5000)) fastTravel = false;
        if (fastTravel) { Set(ActivityBlock::FastTravel); return; }
    }
    // Position requests store the destination worldspace at +0 and interior
    // cell at +4. Door transfers use these fields rather than the fast-travel reference.
    if (request && !cellChanging) {
        auto* world = *reinterpret_cast<TESWorldSpace**>(request);
        auto* cell = *reinterpret_cast<TESObjectCELL**>(request + 4);
        bool differentCell = cell && cell != player->parentCell;
        if (world && player->parentCell) {
            float targetX = *reinterpret_cast<float*>(request + 8);
            float targetY = *reinterpret_cast<float*>(request + 12);
            differentCell = differentCell || world != player->parentCell->worldSpace
                    || std::floor(targetX / 4096.0f) != std::floor(player->posX / 4096.0f)
                    || std::floor(targetY / 4096.0f) != std::floor(player->posY / 4096.0f);
        }
        if (differentCell) {
            cellChanging = true;
            cellChangeStartedAt = GetTickCount64();
            destinationName = PlaceName(cell);
            if (destinationName.empty()) destinationName = PlaceName(world);
        }
    }
    if (cellChanging) {
        if (!request && !loading && GetTickCount64() - cellChangeStartedAt >= 250) cellChanging = false;
        if (cellChanging) { Set(ActivityBlock::Entering, destinationName); return; }
    }
    if (loading) { Set(ActivityBlock::Loading); return; }
    if (MenuVisible(0x41D)) { Set(ActivityBlock::Bartering); return; }
    if (MenuVisible(0x3F5)) { Set(ActivityBlock::Paused); return; }
    if (MenuVisible(0x403)) {
        // Both skill allocation and perk selection are pages of LevelUpMenu.
        // Its own heading reads this native Actor::GetLevel query.
        const UInt16 level = ThisStdCall<UInt16>(0x0087F9F0, player);
        Set(ActivityBlock::LevelUp, std::to_string(level));
        return;
    }
    if (MenuVisible(0x3F0)) { Set(ActivityBlock::Container); return; }
    // Workbenches, reloading benches and campfires use RecipeMenu.
    if (MenuVisible(0x435)) { Set(ActivityBlock::Crafting); return; }
    if (MenuVisible(0x41B)) { Set(ActivityBlock::Paused); return; }
    if (*reinterpret_cast<void**>(0x11DAAC0)) { Set(ActivityBlock::Paused); return; }
    Set(ActivityBlock::None);
}
}
