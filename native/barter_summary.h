#pragma once

namespace BarterSummary {
std::string published = "STATE BARTER CLOSED\n";
struct Node { UInt8* entry; Node* next; };
struct MenuLayout {
    UInt8 prefix[0x80];
    TESObjectREFR* merchant;
    float balance;
    int unknown88, playerCaps, merchantCaps;
    UInt8 inventoryLists[0x78];
    Node buying, selling;
};
static_assert(offsetof(MenuLayout, buying) == 0x10C);
static_assert(offsetof(MenuLayout, selling) == 0x114);
static_assert(offsetof(MenuLayout, playerCaps) == 0x8C);
struct Item { char name[512]; int count; bool selling; };
struct Capture {
    char merchant[512];
    int playerCaps, merchantCaps;
    float balance;
    unsigned size;
    Item items[2048];
};

bool RegisteredForm(TESForm* form) {
    return form && form->refID
            && reinterpret_cast<TESForm* (__cdecl*)(UInt32)>(0x004839C0)(form->refID) == form;
}

bool CopyName(TESForm* form, char* output, size_t size) {
    if (!RegisteredForm(form)) return false;
    TESFullName* fullName = DYNAMIC_CAST(form, TESForm, TESFullName);
    const char* text = fullName ? fullName->name.m_data : nullptr;
    strncpy_s(output, size, text && *text ? text : "Item", _TRUNCATE);
    return true;
}

bool Read(Capture& out) {
    __try {
        auto menu = *reinterpret_cast<MenuLayout**>(0x011D8FA4);
        if (!menu) return false;
        if (!RegisteredForm(reinterpret_cast<TESForm*>(menu->merchant))
                || !CopyName(menu->merchant->baseForm, out.merchant, sizeof(out.merchant))) return false;
        out.balance = menu->balance;
        out.playerCaps = menu->playerCaps;
        out.merchantCaps = menu->merchantCaps;
        out.size = 0;
        if (!std::isfinite(out.balance) || out.playerCaps < 0 || out.merchantCaps < 0) return false;
        for (unsigned side = 0; side < 2; ++side) {
            auto node = side == 0 ? &menu->selling : &menu->buying;
            unsigned visited = 0;
            while (node) {
                if (++visited > 2048) return false;
                auto entry = node->entry;
                if (entry) {
                    const int count = *reinterpret_cast<int*>(entry + 4);
                    TESForm* form = *reinterpret_cast<TESForm**>(entry + 8);
                    if (form && count > 0) {
                        if (out.size >= 2048) return false;
                        Item& item = out.items[out.size];
                        if (!CopyName(form, item.name, sizeof(item.name))) return false;
                        item.count = count;
                        item.selling = side == 0;
                        ++out.size;
                    }
                }
                node = node->next;
            }
        }
        return true;
    } __except (EXCEPTION_EXECUTE_HANDLER) {
        return false;
    }
}

void Update() {
    static ULONGLONG lastAt = 0;
    const ULONGLONG now = GetTickCount64();
    const bool active = g_gameReady.load() && g_activityBlock.load() == ActivityBlock::Bartering;
    if (active && now - lastAt < 250) return;
    lastAt = now;
    std::string packet = "STATE BARTER CLOSED\n";
    if (active) {
        // Reused only on the game thread; keep the bounded text copy off its stack.
        static Capture capture{};
        packet = "STATE BARTER PENDING\n";
        if (Read(capture) && capture.balance > -2147483000.0f && capture.balance < 2147483000.0f) {
            const std::string name = GameTextToUtf8(capture.merchant);
            packet = "STATE BARTER BEGIN " + Base64Encode(name.empty() ? "TRADER" : name)
                    + ' ' + std::to_string(capture.playerCaps) + ' ' + std::to_string(capture.merchantCaps)
                    + ' ' + std::to_string(static_cast<int>(std::floor(capture.balance))) + '\n';
            for (unsigned i = 0; i < capture.size; ++i) {
                const Item& item = capture.items[i];
                std::string label = GameTextToUtf8(item.name);
                if (label.empty()) label = "Item";
                packet += std::string("STATE BARTERITEM ") + (item.selling ? "SELL " : "BUY ")
                        + std::to_string(item.count) + ' ' + Base64Encode(label) + '\n';
            }
            packet += "STATE BARTER END\n";
        }
    }
    std::lock_guard<std::mutex> lock(g_snapshotMutex);
    published = std::move(packet);
}
}
