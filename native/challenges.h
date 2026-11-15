#pragma once

namespace Challenges {
std::atomic<UInt32> pendingDetail{0};
std::unordered_map<UInt32, std::string> descriptions;
std::string detailPacket;

struct Entry {
    TESForm* form;
    std::string name;
    int progress, threshold;
    bool completed, recurring;
};

bool ReadState(TESForm* form, int& progress, int& threshold, bool& completed, bool& recurring) {
    __try {
        if (!form || form->typeID != kFormType_TESChallenge) return false;
        const auto bytes = reinterpret_cast<const UInt8*>(form);
        const UInt16 flags = *reinterpret_cast<const UInt16*>(bytes + 0x5C);
        const UInt32 state = *reinterpret_cast<const UInt32*>(bytes + 0x70);
        const int type = *reinterpret_cast<const int*>(bytes + 0x54);
        progress = *reinterpret_cast<const int*>(bytes + 0x6C);
        threshold = *reinterpret_cast<const int*>(bytes + 0x58);
        completed = (state & 2) != 0;
        recurring = (flags & 2) != 0;
        if ((flags & 1) && !(state & 1)) return false;
        if (progress <= 0 && !(flags & 4) && !(state & 4)) return false;
        // The Pip-Boy's three filters cover kills, hits/damage, and miscellaneous types.
        return type >= 0 && type < 14 && type != 7;
    } __except (EXCEPTION_EXECUTE_HANDLER) {
        return false;
    }
}

bool ReadDescription(TESForm* form, char* output, size_t size) {
    __try {
        auto description = reinterpret_cast<TESDescription*>(reinterpret_cast<UInt8*>(form) + 0x24);
        const char* text = description->Get(form, 0x43534544);
        strncpy_s(output, size, text ? text : "", _TRUNCATE);
        return true;
    } __except (EXCEPTION_EXECUTE_HANDLER) {
        return false;
    }
}

void Reset() {
    pendingDetail.store(0);
    detailPacket.clear();
    descriptions.clear();
}

std::string Snapshot(PlayerCharacter* player, DataHandler* data) {
    std::string packet = "STATE CHALLENGES BEGIN\n";
    if (!player || !data) {
        Reset();
        return packet + "STATE CHALLENGES END\n";
    }
    std::vector<Entry> entries;
    std::unordered_set<UInt32> seen;
    for (auto iter = data->challengeList.Begin(); !iter.End(); ++iter) {
        TESForm* form = reinterpret_cast<TESForm*>(iter.Get());
        Entry entry{};
        if (!ReadState(form, entry.progress, entry.threshold, entry.completed, entry.recurring)
                || !form->refID || !seen.insert(form->refID).second) continue;
        entry.form = form;
        entry.name = FormDisplayName(form);
        if (!entry.name.empty()) entries.push_back(std::move(entry));
    }
    std::stable_sort(entries.begin(), entries.end(), [](const Entry& a, const Entry& b) {
        if (a.completed != b.completed) return !a.completed;
        return a.name < b.name;
    });
    const UInt32 requested = pendingDetail.exchange(0);
    bool foundDetail = false;
    for (const Entry& entry : entries) {
        packet += "STATE CHALLENGE " + std::to_string(entry.form->refID) + ' '
                + std::to_string(entry.progress) + ' ' + std::to_string(entry.threshold) + ' '
                + (entry.completed ? "1 " : "0 ") + (entry.recurring ? "1 " : "0 ")
                + Base64Encode(entry.name) + '\n';
        if (entry.form->refID != requested) continue;
        foundDetail = true;
        auto cached = descriptions.find(requested);
        if (cached == descriptions.end()) {
            char text[16384]{};
            const bool read = ReadDescription(entry.form, text, sizeof(text));
            const std::string body = read ? GameTextToUtf8(text) : "Description unavailable.";
            cached = descriptions.emplace(requested, body).first;
        }
        const std::string encoded = Base64Encode(cached->second);
        detailPacket = "STATE CHALLENGEDETAIL " + std::to_string(requested)
                + " " + (encoded.empty() ? "-" : encoded) + '\n';
    }
    if (requested && !foundDetail)
        detailPacket = "STATE CHALLENGEDETAIL " + std::to_string(requested) + " -\n";
    return packet + "STATE CHALLENGES END\n" + detailPacket;
}
}
