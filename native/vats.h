#pragma once

namespace Vats {
struct Limb {
    float x, y;
    NiPoint3 projected, world;
    SInt32 id;
    float weight, chance;
    UInt8 visible, crippled, selected, flag;
    Tile* tile;
};
struct Target { TESObjectREFR* ref; SInt32 kind; tList<Limb> limbs; };
struct Command {
    SInt32 action;
    bool success, flag5, flag6, flag7;
    UInt8 shots, extraShots;
    UInt16 flags;
    TESObjectREFR* target;
    SInt32 bodyValue;
    void* hitData;
    float armourDamage, healthDamage, cost;
    bool finalFlag;
    UInt8 padding[3];
};
static_assert(sizeof(Limb) == 0x34 && offsetof(Limb, tile) == 0x30);
static_assert(sizeof(Command) == 0x28 && offsetof(Command, cost) == 0x20);
struct Choice { Limb* limb; UInt32 token; };
struct Request { UInt32 session, action, token; };
std::atomic<bool> active{false};
std::mutex requestMutex;
std::deque<Request> requests;
std::string published = "STATE VATS CLOSED\n";
std::vector<Choice> choices;
UInt32 session = 0, nextToken = 0;
void* previousMenu = nullptr;
TESObjectREFR* previousTarget = nullptr;

template <class T> T& Field(void* object, size_t offset) {
    return *reinterpret_cast<T*>(static_cast<UInt8*>(object) + offset);
}
void* Core() { return reinterpret_cast<void*>(0x011F2250); }
int Mode() { return Field<int>(Core(), 8); }
TESObjectREFR* CurrentTarget() { return *reinterpret_cast<TESObjectREFR**>(0x011F21CC); }
int BodyValue(void* menu, Limb* limb) { return ThisStdCall<int>(0x007F3BF0, menu, limb->id); }
int Trait(const char* name) {
    return reinterpret_cast<int (__cdecl *)(const char*, int)>(0x00A00940)(name, -1);
}
float TileNumber(Tile* tile, UInt32 trait, float fallback = 0) {
    auto* value = Dialogue::Value(tile, trait);
    return value != nullptr && std::isfinite(value->num) ? value->num : fallback;
}
std::string TileText(Tile* tile, UInt32 trait) {
    auto* value = Dialogue::Value(tile, trait);
    return value != nullptr && value->str != nullptr ? GameTextToUtf8(value->str) : std::string();
}
Target* FindTarget(TESObjectREFR* ref) {
    auto* node = &reinterpret_cast<tList<Target>*>(0x011DB150)->m_listHead;
    for (unsigned n = 0; node != nullptr && n < 128; node = node->next, ++n)
        if (node->data != nullptr && node->data->ref == ref) return node->data;
    return nullptr;
}
std::vector<Command*> Queue() {
    std::vector<Command*> result;
    auto* node = &static_cast<tList<Command>*>(Core())->m_listHead;
    for (unsigned n = 0; node != nullptr && n < 128; node = node->next, ++n)
        if (node->data != nullptr) result.push_back(node->data);
    return result;
}
bool IsAttack(int action) {
    return reinterpret_cast<bool (__cdecl *)(int)>(0x0066DDE0)(action);
}
void Publish(std::string packet) {
    std::lock_guard<std::mutex> lock(g_snapshotMutex);
    published = std::move(packet);
}
void Reset() {
    active.store(false);
    previousMenu = nullptr;
    previousTarget = nullptr;
    choices.clear();
    { std::lock_guard<std::mutex> lock(requestMutex); requests.clear(); }
    Publish("STATE VATS CLOSED\n");
}
void Enqueue(Request request) {
    std::lock_guard<std::mutex> lock(requestMutex);
    if (requests.size() < 16) requests.push_back(request);
}
std::string RefName(TESObjectREFR* ref) {
    if (ref == nullptr) return {};
    const char* name = reinterpret_cast<const char* (__thiscall *)(TESObjectREFR*)>(0x0055D520)(ref);
    return name != nullptr ? GameTextToUtf8(name) : std::string();
}
int AttackType(TESObjectWEAP* weapon) {
    return reinterpret_cast<int (__cdecl *)(int)>(0x0066DBA0)(weapon != nullptr ? weapon->eWeaponType : 0);
}
UInt8 ShotCount(int type, int secondary) {
    return reinterpret_cast<UInt8 (__cdecl *)(int, int)>(0x007F5050)(type, secondary);
}
float AttackCost(PlayerCharacter* player, void* menu, TESObjectWEAP* weapon) {
    if (reinterpret_cast<bool (__cdecl *)()>(0x009526B0)()) return 0;
    float cost = reinterpret_cast<float (__cdecl *)(TESObjectWEAP*, int, int)>(0x0066DCE0)(weapon, 1, 0);
    if (weapon != nullptr && GetEquippedAmmo(player) != nullptr
            && ShotCount(AttackType(weapon), 0) >= Field<float>(menu, 0xF0)
            && ThisStdCall<SInt32>(0x004FE160, weapon, 0) > 1) {
        cost += reinterpret_cast<float (__cdecl *)(int, bool, float, int)>(0x0066DCA0)(9, true, 0.0f, 0);
    }
    return std::isfinite(cost) ? std::max<float>(0.0f, cost) : 0;
}
void FillAttack(Command& command, PlayerCharacter* player, void* menu, Limb* limb) {
    auto* weapon = GetEquippedWeapon(player);
    command.action = AttackType(weapon);
    command.target = CurrentTarget();
    command.bodyValue = BodyValue(menu, limb);
    command.shots = ShotCount(command.action, 0);
    const int total = ShotCount(command.action, 1);
    command.extraShots = static_cast<UInt8>(std::max<int>(0, total - command.shots));
    command.cost = AttackCost(player, menu, weapon);
}
float Damage(Command* command, PlayerCharacter* player) {
    if (command == nullptr || command->target == nullptr || !IsAttack(command->action)) return 0;
    auto* actor = DYNAMIC_CAST(command->target, TESObjectREFR, Actor);
    if (actor == nullptr) return 0;
    float amount = reinterpret_cast<float (__cdecl *)(Command*)>(0x009B4FE0)(command);
    amount = -ThisStdCall<float>(0x008808A0, actor, -amount, player);
    return std::isfinite(amount) ? std::max<float>(0.0f, amount) : 0;
}
bool Ready(void* menu) {
    return menu != nullptr && Mode() == 2 && Field<UInt8>(menu, 0xF8) != 0
            && Field<UInt8>(menu, 0xFB) == 0 && CurrentTarget() != nullptr;
}
void Process(PlayerCharacter* player, void* menu, const Request& request) {
    if (request.session != session || !Ready(menu)) return;
    char log[128]{};
    std::snprintf(log, sizeof(log), "FNVDS VATS command session=%u action=%u part=%u",
            request.session, request.action, request.token);
    WriteLog(log);
    if (request.action == 3 || request.action == 4) {
        // HandleInput's target-switch path is guarded by its gamepad dispatch flag.
        UInt8 saved = Field<UInt8>(menu, 0x109);
        Field<UInt8>(menu, 0x109) = 1;
        ThisStdCall<void>(0x007EECB0, menu, request.action == 3 ? 2 : 3, 0);
        Field<UInt8>(menu, 0x109) = saved;
        return;
    }
    auto queue = Queue();
    if (request.action == 5) {
        if (!queue.empty()) {
            ThisStdCall<void>(0x007EFA10, menu, static_cast<Command*>(nullptr), false, false);
            ThisStdCall<void>(0x007F0EA0, menu);
        }
        return;
    }
    if (request.action == 6) {
        if (!queue.empty()) reinterpret_cast<void (__cdecl *)()>(0x00705780)();
        return;
    }
    if (request.action == 7) {
        // Shutdown starts playback if the command list is nonempty. Free its
        // commands first so Exit takes the native empty-queue shutdown path.
        ThisStdCall<void>(0x009CA410, Core());
        if (!Queue().empty()) {
            WriteLog("FNVDS VATS exit canceled: native command queue is not empty");
            return;
        }
        { std::lock_guard<std::mutex> lock(requestMutex); requests.clear(); }
        reinterpret_cast<void (__cdecl *)()>(0x007EBD50)();
        return;
    }
    if (request.action != 1 && request.action != 2) return;
    auto choice = std::find_if(choices.begin(), choices.end(), [&](const Choice& c) { return c.token == request.token; });
    if (choice == choices.end()) return;
    Target* target = FindTarget(CurrentTarget());
    if (target == nullptr) return;
    bool owned = false;
    auto* node = &target->limbs.m_listHead;
    for (unsigned n = 0; node != nullptr && n < 64; node = node->next, ++n)
        if (node->data == choice->limb) { owned = true; break; }
    if (!owned || choice->limb->tile == nullptr) return;
    Limb* limb = choice->limb;
    Field<Limb*>(menu, 0x100) = limb;
    *reinterpret_cast<SInt32*>(0x011A59F0) = BodyValue(menu, limb);
    reinterpret_cast<void (__cdecl *)(bool)>(0x007EC3A0)(false);
    if (request.action == 1) return;
    const float chance = TileNumber(limb->tile, 0xFC4, limb->chance * 100.0f);
    // Display text includes the game's current hit-chance modifiers.
    std::string chanceText = TileText(limb->tile, 0xFC4);
    float effectiveChance = chanceText.empty() ? chance : std::strtof(chanceText.c_str(), nullptr);
    if (!std::isfinite(effectiveChance) || effectiveChance <= 0 || queue.size() >= 64) return;
    ThisStdCall<bool>(0x007F28D0, menu);
    auto* weapon = GetEquippedWeapon(player);
    const float cost = AttackCost(player, menu, weapon);
    if (cost > Field<float>(menu, 0xE0) + 0.001f) return;
    if (weapon != nullptr && GetEquippedAmmo(player) != nullptr
            && Field<float>(menu, 0xF0) + Field<float>(menu, 0xF4) <= 0) return;
    if (weapon != nullptr && (weapon->eWeaponType == 10 || weapon->eWeaponType == 13)) {
        auto owned = BuildInventoryCounts(player);
        auto count = owned.find(weapon);
        if (count == owned.end() || static_cast<int>(queue.size()) >= count->second) return;
    }
    auto* command = static_cast<Command*>(AllocateGameMemory(sizeof(Command)));
    if (command == nullptr) return;
    ThisStdCall<void>(0x009CA4A0, command);
    FillAttack(*command, player, menu, limb);
    command->success = reinterpret_cast<bool (__cdecl *)(float)>(0x004DFF00)(
            std::clamp(effectiveChance / 100.0f, 0.0f, 0.95f));
    ThisStdCall<void>(0x009C71A0, Core(), command);
    bool reload = ThisStdCall<bool>(0x007F28D0, menu);
    bool showReload = reload && weapon != nullptr && weapon->eWeaponType != 10 && weapon->eWeaponType != 13
            && ThisStdCall<SInt32>(0x004FE160, weapon, 0) != ThisStdCall<UInt8>(0x00524B60, weapon)
            && Field<float>(menu, 0xF0) + Field<float>(menu, 0xF4) > 0;
    ThisStdCall<void>(0x007EFA10, menu, command, showReload, false);
    ThisStdCall<void>(0x007F0EA0, menu);
}

void Update(PlayerCharacter* player) {
    static ULONGLONG lastReadAt = 0;
    const ULONGLONG now = GetTickCount64();
    const bool allowed = player != nullptr && g_gameReady.load() && !g_loadingSave.load()
            && g_activityBlock.load() == ActivityBlock::None;
    const int mode = allowed ? Mode() : 0;
    const bool targeting = mode >= 1 && mode <= 3;
    void* menu = targeting && Activity::MenuVisible(0x420) ? *reinterpret_cast<void**>(0x011DB0D4) : nullptr;
    // Ordinary cinematic kill shots also enter mode 4. Playback may extend an
    // observed targeting session, but cannot open the companion's V.A.T.S. panel.
    const bool playing = allowed && mode == 4 && active.load();
    bool opened = menu != nullptr || playing;
    bool wasActive = active.exchange(opened);
    if (!opened) {
        if (wasActive || previousMenu != nullptr) Reset();
        else { std::lock_guard<std::mutex> lock(requestMutex); requests.clear(); }
        return;
    }
    Activity::DiscardCommands();
    TESObjectREFR* ref = menu != nullptr ? CurrentTarget() : nullptr;
    if (!wasActive || previousMenu != menu || previousTarget != ref) {
        if (++session == 0) ++session;
        choices.clear();
        previousMenu = menu;
        previousTarget = ref;
    }
    Request pending{};
    { std::lock_guard<std::mutex> lock(requestMutex);
        if (!requests.empty()) { pending = requests.front(); requests.pop_front(); }
    }
    if (pending.session != 0) {
        Process(player, menu, pending);
        // Re-read on the next game-loop callback after native actions may close or switch the menu.
        lastReadAt = 0;
        return;
    }
    if (now - lastReadAt < 100) return;
    lastReadAt = now;
    Target* target = ref != nullptr ? FindTarget(ref) : nullptr;
    bool ready = Ready(menu) && target != nullptr;
    std::vector<Choice> nextChoices;
    std::string parts;
    auto queue = Queue();
    int attacks = 0;
    float queuedDamage = 0;
    for (Command* command : queue) if (IsAttack(command->action)) {
        ++attacks;
        if (command->target == ref) queuedDamage += Damage(command, player);
    }
    float selectedDamage = 0;
    if (target != nullptr && menu != nullptr) {
        std::unordered_map<int, size_t> bodyIndices;
        std::vector<Limb*> limbs;
        int selectedBody = -1000;
        auto* node = &target->limbs.m_listHead;
        const int nameTrait = Trait("_LimbName"), meterTrait = Trait("_MeterPercent");
        for (unsigned n = 0; node != nullptr && n < 64; node = node->next, ++n) {
            Limb* limb = node->data;
            if (limb == nullptr || limb->tile == nullptr) continue;
            int body = BodyValue(menu, limb);
            if (Field<Limb*>(menu, 0x100) == limb) selectedBody = body;
            std::string name = TileText(limb->tile, nameTrait);
            if (name.empty()) continue;
            auto duplicate = bodyIndices.find(body);
            if (duplicate != bodyIndices.end()) {
                Limb*& previous = limbs[duplicate->second];
                if (TileNumber(limb->tile, 0xFA3, 1) > TileNumber(previous->tile, 0xFA3, 1)) previous = limb;
            } else {
                bodyIndices.emplace(body, limbs.size());
                limbs.push_back(limb);
            }
        }
        std::stable_sort(limbs.begin(), limbs.end(), [&](Limb* a, Limb* b) {
            return BodyValue(menu, a) < BodyValue(menu, b);
        });
        for (Limb* limb : limbs) {
            const int body = BodyValue(menu, limb);
            const std::string name = TileText(limb->tile, nameTrait);
            auto old = std::find_if(choices.begin(), choices.end(), [&](const Choice& c) { return c.limb == limb; });
            UInt32 token = old != choices.end() ? old->token : ++nextToken;
            if (token == 0) token = ++nextToken;
            nextChoices.push_back({limb, token});
            bool selected = selectedBody == body;
            std::string text = TileText(limb->tile, 0xFC4);
            float chance = text.empty() ? limb->chance * 100.0f : std::strtof(text.c_str(), nullptr);
            if (!std::isfinite(chance)) chance = 0;
            float condition = TileNumber(limb->tile, meterTrait, limb->crippled ? 0.0f : 1.0f);
            int queued = 0;
            for (Command* command : queue)
                if (IsAttack(command->action) && command->target == ref && command->bodyValue == body) ++queued;
            if (selected) {
                Command sample{};
                ThisStdCall<void>(0x009CA4A0, &sample);
                FillAttack(sample, player, menu, limb);
                sample.success = true;
                selectedDamage = Damage(&sample, player);
                ThisStdCall<void>(0x009CA4C0, &sample);
            }
            parts += "STATE VATSPART " + std::to_string(token) + ' '
                    + std::to_string(static_cast<int>(std::clamp(chance, 0.0f, 95.0f))) + ' '
                    + std::to_string(static_cast<int>(std::clamp(condition, 0.0f, 1.0f) * 1000)) + ' '
                    + std::to_string(queued) + (selected ? " 1 " : " 0 ")
                    + (ready && chance > 0 ? "1 " : "0 ") + Base64Encode(name) + '\n';
        }
    }
    choices.swap(nextChoices);
    int targetCount = 0, targetIndex = 0;
    auto* targetNode = &reinterpret_cast<tList<Target>*>(0x011DB150)->m_listHead;
    for (unsigned n = 0; menu != nullptr && targetNode != nullptr && n < 128; targetNode = targetNode->next, ++n)
        if (targetNode->data != nullptr) { ++targetCount; if (targetNode->data->ref == ref) targetIndex = targetCount; }
    auto* actor = ref != nullptr ? DYNAMIC_CAST(ref, TESObjectREFR, Actor) : nullptr;
    float hp = actor != nullptr ? actor->avOwner.Fn_03(eActorVal_Health) : 0;
    float maximum = actor != nullptr ? actor->avOwner.Fn_08(eActorVal_Health) : 0;
    auto* weapon = GetEquippedWeapon(player);
    std::string targetName = ref != nullptr ? RefName(ref) : "V.A.T.S.";
    if (targetName.empty()) targetName = "TARGET";
    std::string weaponName = weapon != nullptr ? FormDisplayName(weapon) : "Unarmed";
    std::string ammo = menu != nullptr ? TileText(Field<Tile*>(menu, 0x28 + 13 * 4), 0xFC4) : std::string();
    if (ammo.empty()) ammo = "—";
    char header[320]{};
    std::snprintf(header, sizeof(header), "STATE VATS BEGIN %u %u %u %d %d %.1f %.1f %.1f %.1f %.1f %.1f %.1f %d ",
            session, ready ? 1u : 0u, ref != nullptr ? ref->refID : 0, targetIndex, targetCount,
            menu != nullptr ? Field<float>(menu, 0xE0) : 0, menu != nullptr ? Field<float>(menu, 0xE4) : 0,
            hp, maximum, selectedDamage, queuedDamage,
            menu != nullptr ? AttackCost(player, menu, weapon) : 0, attacks);
    Publish(std::string(header) + Base64Encode(targetName) + ' ' + Base64Encode(weaponName) + ' '
            + Base64Encode(ammo) + '\n' + parts + "STATE VATS END\n");
}
}
