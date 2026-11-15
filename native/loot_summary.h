#pragma once

namespace LootSummary {
using Counts = std::unordered_map<TESForm*, SInt32>;
struct MenuLayout { UInt8 prefix[0x74]; TESObjectREFR* container; };
static_assert(offsetof(MenuLayout, container) == 0x74);
std::string published = "STATE LOOT CLOSED\n";
void* sessionMenu = nullptr;
UInt32 sessionContainer = 0;
Counts initialPlayer, initialContainer;

bool ReadTarget(void*& menuOut, TESObjectREFR*& refOut, char* name, size_t capacity) {
    __try {
        auto menu = *reinterpret_cast<MenuLayout**>(0x011D93F8);
        if (!menu || !BarterSummary::RegisteredForm(reinterpret_cast<TESForm*>(menu->container))) return false;
        if (!BarterSummary::CopyName(menu->container->baseForm, name, capacity)) return false;
        menuOut = menu; refOut = menu->container;
        return true;
    } __except (EXCEPTION_EXECUTE_HANDLER) { return false; }
}

Counts Inventory(TESObjectREFR* ref) {
    Counts counts;
    TESContainer* base = DYNAMIC_CAST(ref->baseForm, TESForm, TESContainer);
    if (base) {
        for (auto i = base->formCountList.Begin(); !i.End(); ++i) {
            auto row = i.Get();
            if (row && row->form) counts[row->form] += row->count;
        }
    }
    auto changes = static_cast<ExtraContainerChanges*>(ref->extraDataList.GetByType(kExtraData_ContainerChanges));
    auto entries = changes && changes->data ? changes->data->objList : nullptr;
    if (entries) for (auto i = entries->Begin(); !i.End(); ++i) {
        auto row = i.Get();
        if (!row || !row->type) continue;
        if (HasLeveledItemExtra(row)) counts[row->type] = row->countDelta;
        else counts[row->type] += row->countDelta;
    }
    return counts;
}

bool ItemInfo(TESForm* form, char* name, size_t capacity, float& value) {
    __try {
        if (!BarterSummary::CopyName(form, name, capacity)) return false;
        // A one-item entry without instance extras gives base (full-condition) value.
        ExtraContainerChanges::EntryData entry{nullptr, 1, form};
        value = reinterpret_cast<float (__fastcall*)(ExtraContainerChanges::EntryData*)>(0x004BD400)(&entry);
        return std::isfinite(value) && value >= 0;
    } __except (EXCEPTION_EXECUTE_HANDLER) { return false; }
}

SInt32 Count(const Counts& counts, TESForm* form) {
    auto found = counts.find(form);
    return found == counts.end() ? 0 : (std::max<SInt32>)(0, found->second);
}

void Update(PlayerCharacter* player) {
    static ULONGLONG lastAt = 0;
    const bool open = player && g_gameReady.load() && Activity::MenuVisible(0x3F0);
    std::string packet = "STATE LOOT CLOSED\n";
    if (!open) {
        sessionMenu = nullptr; sessionContainer = 0;
        initialPlayer.clear(); initialContainer.clear();
    } else {
        void* menu = nullptr;
        TESObjectREFR* container = nullptr;
        char title[512]{};
        packet = "STATE LOOT PENDING\n";
        if (ReadTarget(menu, container, title, sizeof(title))) {
            const bool newSession = menu != sessionMenu || container->refID != sessionContainer;
            const ULONGLONG now = GetTickCount64();
            if (!newSession && now - lastAt < 250) return;
            lastAt = now;
            Counts currentPlayer = Inventory(player), currentContainer = Inventory(container);
            if (newSession) {
                sessionMenu = menu; sessionContainer = container->refID;
                initialPlayer = currentPlayer; initialContainer = currentContainer;
            }
            std::unordered_set<TESForm*> forms;
            for (auto& pair : initialPlayer) forms.insert(pair.first);
            for (auto& pair : currentPlayer) forms.insert(pair.first);
            std::vector<std::string> rows;
            for (TESForm* form : forms) {
                const int64_t gain = int64_t(Count(currentPlayer, form)) - Count(initialPlayer, form);
                const int64_t loss = int64_t(Count(initialContainer, form)) - Count(currentContainer, form);
                if (!gain || !loss || (gain > 0) != (loss > 0)) continue;
                const int64_t amount = (std::min)(std::abs(gain), std::abs(loss));
                char name[512]{};
                float value = 0;
                if (!ItemInfo(form, name, sizeof(name), value)) continue;
                rows.push_back(std::string("STATE LOOTITEM ") + (gain > 0 ? "TAKE " : "DEPOSIT ")
                        + std::to_string(amount) + ' ' + std::to_string(double(value) * amount)
                        + ' ' + Base64Encode(GameTextToUtf8(name)) + '\n');
            }
            std::sort(rows.begin(), rows.end());
            packet = "STATE LOOT BEGIN " + Base64Encode(GameTextToUtf8(title)) + '\n';
            for (const auto& row : rows) packet += row;
            packet += "STATE LOOT END\n";
        }
    }
    std::lock_guard<std::mutex> lock(g_snapshotMutex);
    published = std::move(packet);
}
}
