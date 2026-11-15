#include "nvse/PluginAPI.h"
#include "nvse/GameAPI.h"
#include "nvse/GameExtraData.h"
#include "nvse/GameData.h"
#include "nvse/GameObjects.h"
#include "nvse/GameEffects.h"
#include "nvse/GameTiles.h"
#include "nvse/GameUI.h"
#include "nvse/GameRTTI_1_4_0_525.inc"

#include <Windows.h>
#include <WinSock2.h>
#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
#include <cctype>
#include <cstdint>
#include <cstdlib>
#include <cstdio>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <deque>
#include <string>
#include <tuple>
#include <thread>
#include <unordered_map>
#include <unordered_set>
#include <vector>
#include <mutex>
#include <cstddef>
#include <new>

namespace {

HMODULE g_pluginModule = nullptr;
constexpr unsigned short kBridgePort = 47888;
constexpr char kPluginVersion[] = "1.0.0";
std::atomic<int> g_healthTenths{-1};
std::atomic<int> g_actionPointsTenths{-1};
std::atomic<int> g_specialTenths[7] = {-1, -1, -1, -1, -1, -1, -1};
std::atomic<int> g_skillTenths[14] = {-1, -1, -1, -1, -1, -1, -1,
                                      -1, -1, -1, -1, -1, -1, -1};
std::atomic<int> g_effectTenths[4] = {-1, -1, -1, -1};
std::atomic<int> g_limbHealthTenths[6] = {-1, -1, -1, -1, -1, -1};
std::atomic<bool> g_gameReady{false};
std::atomic<bool> g_newGamePending{false};
enum class ActivityBlock { None, Paused, LoadingSave, Dead, Bartering, FastTravel, Loading, Entering, LevelUp, Container, Crafting };
std::atomic<ActivityBlock> g_activityBlock{ActivityBlock::None};
std::atomic<bool> g_loadingSave{false};
std::mutex g_snapshotMutex;
// The game-loop handler publishes these strings; BridgeWorker copies them under this mutex.
std::string g_readOnlySnapshot;
std::string g_readOnlyNoteSnapshot;
NVSEConsoleInterface* g_consoleInterface = nullptr;
NVSEScriptInterface* g_scriptInterface = nullptr;
std::mutex g_pendingCommandMutex;
std::mutex g_logMutex;
bool IsPlayingRadioStation(UInt32 formId);
void WriteLog(const char* message);
void MarkNoteRead(BGSNote* note, PlayerCharacter* player);

enum class ItemCommandType {
    Equip,
    UseAid,
    UseNeedAid,
    TreatLimb,
    TrackQuest,
    ViewQuest,
    FastTravel,
    TuneRadio,
    ReadNote,
    PlayNote,
    StopNote,
    InspectItem,
    AssignHotkey,
    InstallWeaponMod,
    DropItem,
    PlaceWorldMarker,
    PlaceLocalMarker,
    RemoveCustomMarker
};

std::array<std::string, 3> AidNeedRelief(PlayerCharacter* player, TESForm* form);

struct PendingItemCommand {
    UInt32 formId;
    ItemCommandType type;
    bool wasEquipped;
    UInt32 instanceToken = 0;
    UInt32 hotkeySlot = 0;
    UInt32 modFormId = 0;
    UInt32 quantity = 1;
    float markerX = 0, markerY = 0;
};
void PlaceCustomMapMarker(PlayerCharacter* player, const PendingItemCommand& command);
bool RemoveCustomMapMarker(PlayerCharacter* player);
UInt32 g_viewQuestForm = 0;
UInt32 g_viewQuestRequest = 0;
bool g_viewQuestPending = false;
std::string g_viewQuestResult;
struct InventoryCopy {
    UInt32 formId;
    ExtraDataList* extra;
    SInt32 ordinal;
    bool operator==(const InventoryCopy&) const = default;
};
struct InventoryCopyHash {
    size_t operator()(const InventoryCopy& copy) const {
        return copy.formId ^ (reinterpret_cast<uintptr_t>(copy.extra) >> 2)
                ^ (static_cast<size_t>(copy.ordinal) * 2654435761u);
    }
};
// Only the game thread resolves these tokens. The bridge never accepts game addresses.
std::unordered_map<InventoryCopy, UInt32, InventoryCopyHash> g_inventoryCopyTokens;
std::unordered_map<UInt32, InventoryCopy> g_inventoryCopies;
UInt32 g_nextInventoryCopyToken = 1;
UInt32 g_inspectedItemForm = 0;
UInt32 g_inspectedItemToken = 0;
struct PendingAmmoReload {
    UInt32 ammoFormId = 0;
    UInt32 weaponFormId = 0;
    UInt8 attempts = 0;
};
std::deque<PendingItemCommand> g_pendingItemCommands;
PendingAmmoReload g_pendingAmmoReload;
std::unordered_map<UInt32, TESObjectREFR*> g_fastTravelMarkers;
std::unordered_map<UInt32, TESObjectREFR*> g_pipboyRadioStations;
std::vector<BGSNote*> g_noteScanCandidates;
size_t g_noteScanIndex = 0;
bool g_noteScanActive = false;
ULONGLONG g_nextNoteSnapshotAt = 0;
ULONGLONG g_notesReadyAt = 0;
std::atomic<bool> g_notesRequested{false};
UInt32 g_playingNoteFormId = 0;
ULONGLONG g_notePlaybackStartedAt = 0;
ULONGLONG g_notePlaybackDurationMs = 0;
std::atomic<bool> g_notePlaybackError{false};
struct FnvdsSoundHandle {
    UInt32 soundId;
    bool assumeSuccess;
    UInt32 state;
};
static_assert(sizeof(FnvdsSoundHandle) == 0x0C);

struct FnvdsString {
    const char* data;
    UInt16 length;
    UInt16 capacity;
};

struct FnvdsDialogueResponse {
    UInt8 reserved[0x10];
    FnvdsString voicePath;
};

struct FnvdsDialogueItem {
    UInt8 reserved[0x0C];
    void* topicInfo;
};

struct FnvdsConversation {
    UInt8 storage[0x0C];
};

// Opaque engine-owned request storage; the size and marker offset are checked below.
struct FnvdsFastTravelRequest {
    UInt8 storage[0x30];
    TESObjectREFR* mapMarker;
};
static_assert(sizeof(FnvdsFastTravelRequest) == 0x34);
static_assert(offsetof(FnvdsFastTravelRequest, mapMarker) == 0x30);

struct FnvdsOpaqueGameObject {
    virtual ~FnvdsOpaqueGameObject() = default;
};

void* AllocateGameMemory(UInt32 size) {
    return ThisStdCall<void*>(0xAA3E40, reinterpret_cast<void*>(0x11F6238), size);
}

void FreeGameMemory(void* memory) {
    if (memory != nullptr) {
        ThisStdCall<void>(0xAA4060, reinterpret_cast<void*>(0x11F6238), memory);
    }
}

std::deque<FnvdsSoundHandle> g_noteAudioQueue;
FnvdsSoundHandle g_noteCurrentSound{0xFFFFFFFF, false, 0};
ULONGLONG g_noteSoundStartedAt = 0;
ULONGLONG g_noteSoundGraceUntil = 0;
bool g_noteAudioEnteredDialogue = false;
std::mutex g_noteDurationMutex;
std::unordered_map<UInt32, UInt32> g_noteSegmentDurations;
ULONGLONG g_nextNoteDurationQueryAt = 0;

// The audio thread supplies milliseconds after the sound has loaded. Never call
// game functions here, or retain pointers into note/save data across callbacks.
void __cdecl OnNoteSoundLoaded(void* context, UInt32 durationMs) {
    const UInt32 soundId = static_cast<UInt32>(reinterpret_cast<uintptr_t>(context));
    std::lock_guard<std::mutex> lock(g_noteDurationMutex);
    auto entry = g_noteSegmentDurations.find(soundId);
    if (entry != g_noteSegmentDurations.end() && durationMs > 0) {
        entry->second = durationMs;
    }
}

ULONGLONG GetNativeSoundDurationMs(FnvdsSoundHandle& sound) {
    if (sound.soundId == 0xFFFFFFFF) return 0;
    const int durationMs = ThisStdCall<int>(0xAD8B30, &sound);
    return durationMs > 0 ? static_cast<ULONGLONG>(durationMs) : 0;
}

void UpdateNoteDuration(ULONGLONG now) {
    if (now < g_nextNoteDurationQueryAt) return;
    g_nextNoteDurationQueryAt = now + 100;
    std::vector<UInt32> pending;
    {
        std::lock_guard<std::mutex> lock(g_noteDurationMutex);
        for (const auto& entry : g_noteSegmentDurations) {
            if (entry.second == 0) pending.push_back(entry.first);
        }
    }
    // Queued segments may finish loading before their Play callback runs.
    for (UInt32 id : pending) {
        FnvdsSoundHandle handle{id, false, 0};
        const auto duration = GetNativeSoundDurationMs(handle);
        if (duration > 0) OnNoteSoundLoaded(reinterpret_cast<void*>(uintptr_t(id)),
                static_cast<UInt32>(duration));
    }
    // Retain each segment's length so the total includes segments that have already finished.
    std::lock_guard<std::mutex> lock(g_noteDurationMutex);
    ULONGLONG total = 0;
    for (const auto& entry : g_noteSegmentDurations) {
        if (entry.second == 0) return; // Do not publish a partial note length.
        total += entry.second;
    }
    if (total > 0) g_notePlaybackDurationMs = total;
}

void StopNativeNoteAudio() {
    {
        // A later callback for a removed sound ID is ignored by OnNoteSoundLoaded.
        std::lock_guard<std::mutex> lock(g_noteDurationMutex);
        g_noteSegmentDurations.clear();
    }
    g_nextNoteDurationQueryAt = 0;
    if (g_noteCurrentSound.soundId != 0xFFFFFFFF) {
        ThisStdCall<bool>(0xAD88F0, &g_noteCurrentSound);
    }
    g_noteCurrentSound = {0xFFFFFFFF, false, 0};
    g_noteAudioQueue.clear();
    if (g_noteAudioEnteredDialogue) {
        void* audio = *reinterpret_cast<void**>(0x11F6D98);
        if (audio != nullptr) ThisStdCall<void>(0xAD8650, audio);
        *reinterpret_cast<volatile UInt8*>(0x11DCFA4) = 0;
        g_noteAudioEnteredDialogue = false;
    }
    g_noteSoundStartedAt = 0;
    g_noteSoundGraceUntil = 0;
    g_playingNoteFormId = 0;
    g_notePlaybackStartedAt = 0;
    g_notePlaybackDurationMs = 0;
}

bool QueueVoiceNoteAudio(BGSNote* note, PlayerCharacter* player) {
    if (note == nullptr || player == nullptr || note->noteText == nullptr
            || note->unk070[0] == 0) return false;
    Character* character = nullptr;
    FnvdsConversation* conversation = nullptr;
    bool queuedAny = false;
    try {
        character = static_cast<Character*>(AllocateGameMemory(sizeof(Character)));
        if (character == nullptr) return false;
        ThisStdCall<void>(0x8D1F40, character, false);
        ThisStdCall<void>(0x484490, static_cast<TESForm*>(character));
        ThisStdCall<void>(0x575690, character,
                reinterpret_cast<void*>(static_cast<uintptr_t>(note->unk070[0])));

        conversation = static_cast<FnvdsConversation*>(
                AllocateGameMemory(sizeof(FnvdsConversation)));
        if (conversation == nullptr) {
            delete reinterpret_cast<FnvdsOpaqueGameObject*>(character);
            return false;
        }
        void* topic = note->noteText;
        ThisStdCall<void>(0x83B850, conversation, character, player, topic);
        const UInt32 audioFlags = *reinterpret_cast<volatile UInt32*>(0x7974CA);
        const bool hasItem = ThisStdCall<bool>(0x83B9A0, conversation);
        if (hasItem) {
            auto* item = static_cast<FnvdsDialogueItem*>(
                    ThisStdCall<void*>(0x83C820, conversation));
            if (item != nullptr && ThisStdCall<bool>(0x83C7B0, item)) {
                do {
                    item = static_cast<FnvdsDialogueItem*>(
                            ThisStdCall<void*>(0x83C820, conversation));
                    if (item == nullptr) break;
                    auto* response = static_cast<FnvdsDialogueResponse*>(
                            ThisStdCall<void*>(0x83C820, item));
                    if (response == nullptr) break;
                    if (item->topicInfo != nullptr) {
                        ThisStdCall<void>(0x61F170, item->topicInfo, 0, character);
                    }
                    const char* path = response->voicePath.data;
                    if (path != nullptr && path[0] != '\0') {
                        void* audio = *reinterpret_cast<void**>(0x11F6D98);
                        if (audio != nullptr) {
                            FnvdsSoundHandle sound = ThisStdCall<FnvdsSoundHandle>(
                                    0xAD7480, audio, path, audioFlags, nullptr);
                            if (sound.soundId != 0xFFFFFFFF) {
                                ThisStdCall<bool>(0xAD89E0, &sound, 0.9f);
                                g_noteAudioQueue.push_back(sound);
                                queuedAny = true;
                            }
                        }
                    }
                    if (item->topicInfo != nullptr) {
                        ThisStdCall<void>(0x61F170, item->topicInfo, 1, character);
                    }
                } while (ThisStdCall<bool>(0x83C7E0, item));
            }
        }
    } catch (...) {
        g_noteAudioQueue.clear();
        queuedAny = false;
    }
    if (conversation != nullptr) {
        ThisStdCall<void>(0x83B8D0, conversation);
        FreeGameMemory(conversation);
    }
    if (character != nullptr) {
        delete reinterpret_cast<FnvdsOpaqueGameObject*>(character);
    }
    return queuedAny;
}

bool StartNativeNoteAudio(BGSNote* note, PlayerCharacter* player) {
    if (note == nullptr || player == nullptr) return false;
    StopNativeNoteAudio();
    void* audio = *reinterpret_cast<void**>(0x11F6D98);
    if (audio == nullptr) return false;

    const UInt8 noteType = note->unk07C;
    if (noteType == 0) {
        const TESForm* soundForm = reinterpret_cast<const TESForm*>(note->noteText);
        if (soundForm == nullptr || soundForm->refID == 0) return false;
        const UInt32 flags = 0x00000001 | 0x00000100; // 2D and one-shot
        const FnvdsSoundHandle sound = ThisStdCall<FnvdsSoundHandle>(
                0xAD73B0, audio, soundForm->refID, flags);
        if (sound.soundId == 0xFFFFFFFF) return false;
        g_noteAudioQueue.push_back(sound);
    } else if (noteType == 3) {
        if (!QueueVoiceNoteAudio(note, player)) return false;
    } else {
        return false;
    }

    // Prepare all plugin and note state before Play() starts the asynchronous
    // game audio callback. Avoid script compilation or timer/API calls after
    // playback begins.
    const ULONGLONG startedAt = GetTickCount64();
    g_noteCurrentSound = g_noteAudioQueue.front();
    g_noteAudioQueue.pop_front();
    {
        std::lock_guard<std::mutex> lock(g_noteDurationMutex);
        g_noteSegmentDurations.emplace(g_noteCurrentSound.soundId, 0);
        for (const auto& sound : g_noteAudioQueue) {
            g_noteSegmentDurations.emplace(sound.soundId, 0);
        }
    }
    auto registerDuration = [](FnvdsSoundHandle& sound) {
        ThisStdCall<void>(0xAD8EC0, &sound, &OnNoteSoundLoaded,
                reinterpret_cast<void*>(uintptr_t(sound.soundId)));
    };
    registerDuration(g_noteCurrentSound);
    for (auto& sound : g_noteAudioQueue) {
        registerDuration(sound);
    }
    g_noteSoundStartedAt = startedAt;
    g_noteSoundGraceUntil = g_noteSoundStartedAt + 750;
    g_playingNoteFormId = note->refID;
    g_notePlaybackStartedAt = g_noteSoundStartedAt;
    const UInt8 wasRead = note->read;
    note->read = 1;
    if (noteType == 3) {
        ThisStdCall<void>(0xAD85A0, audio);
        *reinterpret_cast<volatile UInt8*>(0x11DCFA4) = 1;
        g_noteAudioEnteredDialogue = true;
    }
    const bool started = ThisStdCall<bool>(0xAD8830, &g_noteCurrentSound, false);
    if (!started) {
        note->read = wasRead;
        StopNativeNoteAudio();
        return false;
    }
    return true;
}

void UpdateNativeNoteAudio() {
    if (g_playingNoteFormId == 0 || g_noteCurrentSound.soundId == 0xFFFFFFFF) return;
    const ULONGLONG now = GetTickCount64();
    UpdateNoteDuration(now);
    const bool playing = ThisStdCall<bool>(0xAD8930, &g_noteCurrentSound);
    if (playing) return;
    if (now < g_noteSoundGraceUntil) return;
    g_noteCurrentSound = {0xFFFFFFFF, false, 0};
    if (g_noteAudioQueue.empty()) {
        StopNativeNoteAudio();
        return;
    }
    g_noteCurrentSound = g_noteAudioQueue.front();
    g_noteAudioQueue.pop_front();
    if (!ThisStdCall<bool>(0xAD8830, &g_noteCurrentSound, false)) {
        StopNativeNoteAudio();
        g_notePlaybackError.store(true, std::memory_order_relaxed);
        WriteLog("FNVDS audio note playback: next audio segment failed to start.");
        return;
    }
    g_noteSoundStartedAt = now;
    g_noteSoundGraceUntil = now + 750;
}

void WriteLog(const char* message) {
    std::lock_guard<std::mutex> logLock(g_logMutex);
    char modulePath[MAX_PATH]{};
    const DWORD length = GetModuleFileNameA(g_pluginModule, modulePath, MAX_PATH);
    if (length == 0 || length >= MAX_PATH) {
        return;
    }

    std::filesystem::path logPath(modulePath);
    logPath = logPath.parent_path() / "FNVDS.log";
    std::ofstream log(logPath, std::ios::app);
    if (log) {
        log << message << "\n";
    }
}

bool SendBytes(SOCKET socket, const std::string& message) {
    // A successful send can consume only part of the buffer; advance until all bytes are sent.
    size_t sent = 0;
    while (sent < message.size()) {
        const int result = send(socket, message.data() + sent,
                                static_cast<int>(message.size() - sent), 0);
        if (result <= 0) {
            return false;
        }
        sent += static_cast<size_t>(result);
    }
    return true;
}

bool SendLine(SOCKET socket, const std::string& line) {
    return SendBytes(socket, line + "\n");
}

std::string Base64Encode(const std::string& input);

struct MapTexturePayload {
    std::vector<unsigned char> bytes;
    std::string sourceName;
    uint32_t decodedSize = 0;
    bool zlibCompressed = false;
};

bool ReadU32(std::istream& input, uint32_t& value) {
    input.read(reinterpret_cast<char*>(&value), sizeof(value));
    return input.good();
}

std::string LowerAscii(std::string value) {
    std::transform(value.begin(), value.end(), value.begin(), [](unsigned char character) {
        return static_cast<char>(std::tolower(character));
    });
    std::replace(value.begin(), value.end(), '/', '\\');
    return value;
}

std::vector<std::string> MapTextureNames(const std::string& worldKey) {
    std::string stem;
    if (worldKey == "MOJAVE") stem = "nv";
    else if (worldKey == "DEAD_MONEY") stem = "villa";
    else if (worldKey == "HONEST_HEARTS") stem = "zion";
    else if (worldKey == "OLD_WORLD_BLUES") stem = "bigmt";
    else if (worldKey == "LONESOME_ROAD") stem = "divide";
    else return {};

    std::vector<std::string> names;
    if (worldKey == "MOJAVE") {
        names.emplace_back("wasteland_nv_1024_no_map.dds");
        names.emplace_back("wasteland_nv_1024.dds");
    } else {
        names.emplace_back("wasteland_" + stem + "_1024_no_map.dds");
        names.emplace_back("wasteland_" + stem + "_1024.dds");
    }
    return names;
}

std::filesystem::path GameDataDirectory() {
    char modulePath[MAX_PATH]{};
    if (g_pluginModule == nullptr
            || GetModuleFileNameA(g_pluginModule, modulePath, MAX_PATH) == 0) return {};
    return std::filesystem::path(modulePath).parent_path().parent_path().parent_path();
}

bool ReadLooseMapTexture(const std::filesystem::path& dataDirectory,
                         const std::vector<std::string>& names,
                         MapTexturePayload& payload) {
    const auto mapDirectory = dataDirectory / "Textures" / "Interface" / "WorldMap";
    for (const std::string& name : names) {
        std::ifstream input(mapDirectory / name, std::ios::binary);
        if (!input) continue;
        input.seekg(0, std::ios::end);
        const std::streamoff size = input.tellg();
        if (size <= 0 || size > 16 * 1024 * 1024) continue;
        input.seekg(0, std::ios::beg);
        payload.bytes.resize(static_cast<size_t>(size));
        input.read(reinterpret_cast<char*>(payload.bytes.data()), size);
        if (!input.good() && !input.eof()) continue;
        payload.sourceName = name;
        payload.decodedSize = static_cast<uint32_t>(payload.bytes.size());
        payload.zlibCompressed = false;
        return true;
    }
    return false;
}

bool ReadMapTextureFromBsa(const std::filesystem::path& archivePath,
                           const std::vector<std::string>& names,
                           MapTexturePayload& payload) {
    std::ifstream input(archivePath, std::ios::binary);
    if (!input) return false;
    input.seekg(0, std::ios::end);
    const std::streamoff archiveSize = input.tellg();
    input.seekg(0, std::ios::beg);
    uint32_t signature = 0, version = 0, folderRecordOffset = 0, archiveFlags = 0;
    uint32_t folderCount = 0, fileCount = 0, folderNameLength = 0, fileNameLength = 0;
    uint32_t fileFlags = 0;
    if (!ReadU32(input, signature) || !ReadU32(input, version)
            || !ReadU32(input, folderRecordOffset) || !ReadU32(input, archiveFlags)
            || !ReadU32(input, folderCount) || !ReadU32(input, fileCount)
            || !ReadU32(input, folderNameLength) || !ReadU32(input, fileNameLength)
            || !ReadU32(input, fileFlags)) return false;
    constexpr uint32_t kBsaSignature = 0x00415342;
    constexpr uint32_t kBsaPathNames = 0x1;
    constexpr uint32_t kBsaFileNames = 0x2;
    constexpr uint32_t kBsaDefaultCompression = 0x4;
    constexpr uint32_t kBsaEmbeddedFileNames = 0x100;
    constexpr uint32_t kFileCompressionToggle = 0x40000000;
    constexpr uint32_t kFileSizeMask = 0x3FFFFFFF;
    if (signature != kBsaSignature || version != 104
            || (archiveFlags & (kBsaPathNames | kBsaFileNames))
                    != (kBsaPathNames | kBsaFileNames)
            || folderCount > 100000 || fileCount > 2000000
            || archiveSize <= 36) return false;

    const uint64_t namesOffset = static_cast<uint64_t>(folderRecordOffset)
            + folderNameLength + static_cast<uint64_t>(folderCount) * 17
            + static_cast<uint64_t>(fileCount) * 16;
    if (namesOffset + fileNameLength > static_cast<uint64_t>(archiveSize)) return false;
    input.seekg(static_cast<std::streamoff>(namesOffset), std::ios::beg);
    std::vector<char> filenameTable(fileNameLength);
    if (fileNameLength > 0) {
        input.read(filenameTable.data(), static_cast<std::streamsize>(fileNameLength));
        if (!input) return false;
    }
    std::vector<std::string> fileNames;
    fileNames.reserve(fileCount);
    size_t nameOffset = 0;
    for (uint32_t index = 0; index < fileCount; ++index) {
        if (nameOffset >= filenameTable.size()) return false;
        const char* start = filenameTable.data() + nameOffset;
        const void* end = std::memchr(start, '\0', filenameTable.size() - nameOffset);
        if (end == nullptr) return false;
        const size_t length = static_cast<const char*>(end) - start;
        fileNames.emplace_back(start, length);
        nameOffset += length + 1;
    }

    input.seekg(folderRecordOffset, std::ios::beg);
    std::vector<uint32_t> folderFiles(folderCount);
    for (uint32_t index = 0; index < folderCount; ++index) {
        uint64_t hash = 0;
        uint32_t offset = 0;
        input.read(reinterpret_cast<char*>(&hash), sizeof(hash));
        if (!ReadU32(input, folderFiles[index]) || !ReadU32(input, offset)) return false;
    }
    uint32_t nameIndex = 0;
    input.seekg(static_cast<std::streamoff>(folderRecordOffset)
                    + static_cast<std::streamoff>(folderCount) * 16,
                std::ios::beg);
    for (uint32_t folderIndex = 0; folderIndex < folderCount; ++folderIndex) {
        unsigned char folderNameLengthByte = 0;
        input.read(reinterpret_cast<char*>(&folderNameLengthByte), 1);
        if (!input || folderNameLengthByte == 0 || folderNameLengthByte > 255) return false;
        std::string folderName(folderNameLengthByte, '\0');
        input.read(folderName.data(), folderNameLengthByte);
        if (!input) return false;
        if (!folderName.empty() && folderName.back() == '\0') folderName.pop_back();
        folderName = LowerAscii(folderName);

        for (uint32_t fileIndex = 0; fileIndex < folderFiles[folderIndex]; ++fileIndex) {
            uint64_t fileHash = 0;
            uint32_t sizeFlags = 0, dataOffset = 0;
            input.read(reinterpret_cast<char*>(&fileHash), sizeof(fileHash));
            if (!ReadU32(input, sizeFlags) || !ReadU32(input, dataOffset)
                    || nameIndex >= fileNames.size()) return false;
            const std::string path = folderName + "\\" + LowerAscii(fileNames[nameIndex++]);
            const std::string mapFolder = "textures\\interface\\worldmap\\";
            if (path.compare(0, mapFolder.size(), mapFolder) != 0) continue;
            const std::string baseName = path.substr(mapFolder.size());
            if (std::find(names.begin(), names.end(), baseName) == names.end()) continue;

            uint32_t storedSize = sizeFlags & kFileSizeMask;
            // The per-file bit toggles the archive's default compression setting.
            const bool compressed = ((sizeFlags & kFileCompressionToggle) != 0)
                    != ((archiveFlags & kBsaDefaultCompression) != 0);
            const bool embeddedName = (archiveFlags & kBsaEmbeddedFileNames) != 0;
            uint64_t contentOffset = dataOffset;
            if (embeddedName) {
                input.seekg(dataOffset, std::ios::beg);
                unsigned char embeddedLength = 0;
                input.read(reinterpret_cast<char*>(&embeddedLength), 1);
                if (!input || storedSize < static_cast<uint32_t>(embeddedLength) + 1) return false;
                contentOffset += static_cast<uint64_t>(embeddedLength) + 1;
                storedSize -= static_cast<uint32_t>(embeddedLength) + 1;
            }
            if (storedSize == 0 || storedSize > 16 * 1024 * 1024
                    || contentOffset + storedSize > static_cast<uint64_t>(archiveSize)) return false;
            payload.bytes.resize(storedSize);
            input.seekg(static_cast<std::streamoff>(contentOffset), std::ios::beg);
            input.read(reinterpret_cast<char*>(payload.bytes.data()), storedSize);
            if (!input) return false;
            payload.sourceName = baseName;
            payload.zlibCompressed = compressed;
            if (compressed) {
                if (storedSize < 4) return false;
                std::memcpy(&payload.decodedSize, payload.bytes.data(), sizeof(payload.decodedSize));
                if (payload.decodedSize == 0 || payload.decodedSize > 16 * 1024 * 1024) return false;
            } else {
                payload.decodedSize = storedSize;
            }
            return true;
        }
    }
    return false;
}

bool LoadGameMapTexture(const std::string& worldKey, MapTexturePayload& payload) {
    const std::vector<std::string> names = MapTextureNames(worldKey);
    if (names.empty()) return false;
    const std::filesystem::path dataDirectory = GameDataDirectory();
    if (dataDirectory.empty()) return false;
    if (ReadLooseMapTexture(dataDirectory, names, payload)) return true;
    // Search each candidate name across all archives before falling back to
    // the next name. ReadMapTextureFromBsa returns its first matching entry,
    // which otherwise makes archive file order override names' priority.
    std::vector<std::filesystem::path> archives;
    std::error_code error;
    for (const auto& entry : std::filesystem::directory_iterator(dataDirectory, error)) {
        if (error) break;
        if (entry.is_regular_file(error)
                && LowerAscii(entry.path().extension().string()) == ".bsa") {
            archives.push_back(entry.path());
        }
    }
    std::sort(archives.begin(), archives.end());
    for (const std::string& name : names) {
        const std::vector<std::string> preferredName{name};
        for (const auto& archive : archives) {
            if (ReadMapTextureFromBsa(archive, preferredName, payload)) return true;
        }
    }
    return false;
}

bool SendGameMapTexture(SOCKET socket, const std::string& worldKey) {
    MapTexturePayload payload;
    if (!LoadGameMapTexture(worldKey, payload)) {
        WriteLog(("FNVDS map transfer: map texture not found for " + worldKey).c_str());
        return SendLine(socket, "STATE MAPDATA ERROR " + worldKey + " NOT_FOUND");
    }
    WriteLog(("FNVDS map texture selected world=" + worldKey
            + " file=" + payload.sourceName
            + " bytes=" + std::to_string(payload.bytes.size())
            + " decoded=" + std::to_string(payload.decodedSize)
            + " compressed=" + (payload.zlibCompressed ? "1" : "0")).c_str());
    constexpr size_t kChunkBytes = 1200;
    char header[160]{};
    std::snprintf(header, sizeof(header), "STATE MAPDATA BEGIN %s %u %u %u",
                  worldKey.c_str(), static_cast<unsigned int>(payload.bytes.size()),
                  payload.decodedSize, payload.zlibCompressed ? 1u : 0u);
    if (!SendLine(socket, header)) return false;
    for (size_t offset = 0; offset < payload.bytes.size(); offset += kChunkBytes) {
        const size_t length = (std::min)(kChunkBytes, payload.bytes.size() - offset);
        const std::string chunk = Base64Encode(std::string(
                reinterpret_cast<const char*>(payload.bytes.data() + offset), length));
        if (!SendLine(socket, "STATE MAPDATA CHUNK " + worldKey + " " + chunk)) return false;
    }
    return SendLine(socket, "STATE MAPDATA END " + worldKey);
}

std::string Base64Encode(const std::string& input) {
    static constexpr char alphabet[] =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    std::string encoded;
    encoded.reserve(((input.size() + 2) / 3) * 4);
    for (size_t i = 0; i < input.size(); i += 3) {
        const uint32_t a = static_cast<unsigned char>(input[i]);
        const uint32_t b = i + 1 < input.size()
                ? static_cast<unsigned char>(input[i + 1]) : 0;
        const uint32_t c = i + 2 < input.size()
                ? static_cast<unsigned char>(input[i + 2]) : 0;
        const uint32_t triple = (a << 16) | (b << 8) | c;
        encoded.push_back(alphabet[(triple >> 18) & 0x3F]);
        encoded.push_back(alphabet[(triple >> 12) & 0x3F]);
        encoded.push_back(i + 1 < input.size() ? alphabet[(triple >> 6) & 0x3F] : '=');
        encoded.push_back(i + 2 < input.size() ? alphabet[triple & 0x3F] : '=');
    }
    return encoded;
}

#include "localmap.h"

std::string GameTextToUtf8(const char* text) {
    if (text == nullptr || *text == '\0') {
        return {};
    }
    const int sourceLength = static_cast<int>(std::strlen(text));
    const int wideLength = MultiByteToWideChar(CP_ACP, 0, text, sourceLength, nullptr, 0);
    if (wideLength <= 0) {
        return std::string(text, sourceLength);
    }
    std::wstring wide(static_cast<size_t>(wideLength), L'\0');
    MultiByteToWideChar(CP_ACP, 0, text, sourceLength, wide.data(), wideLength);
    const int utf8Length = WideCharToMultiByte(CP_UTF8, 0, wide.data(), wideLength,
                                                nullptr, 0, nullptr, nullptr);
    if (utf8Length <= 0) {
        return std::string(text, sourceLength);
    }
    std::string utf8(static_cast<size_t>(utf8Length), '\0');
    WideCharToMultiByte(CP_UTF8, 0, wide.data(), wideLength, utf8.data(), utf8Length,
                        nullptr, nullptr);
    return utf8;
}

bool StartsWithAsciiInsensitive(const std::string& text, const char* prefix) {
    size_t index = 0;
    for (; prefix[index] != '\0'; ++index) {
        if (index >= text.size()) return false;
        char value = text[index];
        char expected = prefix[index];
        if (value >= 'A' && value <= 'Z') value = static_cast<char>(value - 'A' + 'a');
        if (expected >= 'A' && expected <= 'Z') {
            expected = static_cast<char>(expected - 'A' + 'a');
        }
        if (value != expected) return false;
    }
    return true;
}

bool StripOptionalObjectivePrefix(std::string& objective) {
    const size_t firstCharacter = objective.find_first_not_of(" \t\r\n");
    if (firstCharacter == std::string::npos) return false;
    if (firstCharacter > 0) objective.erase(0, firstCharacter);

    size_t prefixLength = 0;
    if (StartsWithAsciiInsensitive(objective, "Optional:")) prefixLength = 9;
    else if (StartsWithAsciiInsensitive(objective, "(Optional)")) prefixLength = 10;
    else if (StartsWithAsciiInsensitive(objective, "[Optional]")) prefixLength = 10;
    if (prefixLength == 0) return false;

    objective.erase(0, prefixLength);
    const size_t textStart = objective.find_first_not_of(" \t\r\n:-");
    objective = textStart == std::string::npos ? std::string() : objective.substr(textStart);
    return true;
}

std::string FormDisplayName(TESForm* form) {
    if (form == nullptr) {
        return {};
    }
    TESFullName* fullName = DYNAMIC_CAST(form, TESForm, TESFullName);
    if (fullName != nullptr && fullName->name.m_data != nullptr
            && fullName->name.m_data[0] != '\0') {
        return GameTextToUtf8(fullName->name.m_data);
    }
    const char* editorName = form->GetName();
    return GameTextToUtf8(editorName == nullptr ? "Unknown" : editorName);
}

const char* InventoryCategory(TESForm* form) {
    if (form == nullptr) return nullptr;
    switch (form->typeID) {
        case kFormType_TESObjectWEAP: return "WEAPONS";
        case kFormType_TESObjectARMO:
        case kFormType_TESObjectCLOT: return "APPAREL";
        case kFormType_AlchemyItem:
        case kFormType_IngredientItem: return "AID";
        case kFormType_TESAmmo: return "AMMO";
        case kFormType_TESObjectMISC:
        case kFormType_TESObjectBOOK:
        case kFormType_TESKey:
        case kFormType_TESObjectIMOD:
        case kFormType_TESCasinoChips:
        case kFormType_TESCaravanMoney:
        case kFormType_TESCaravanCard:
        case kFormType_BGSNote: return "MISC";
        default: return nullptr;
    }
}

bool IsThrowableWeapon(TESForm* form) {
    if (!form || form->typeID != kFormType_TESObjectWEAP) return false;
    UInt8 type = static_cast<TESObjectWEAP*>(form)->eWeaponType;
    return type == TESObjectWEAP::kWeapType_OneHandGrenade
            || type == TESObjectWEAP::kWeapType_OneHandMine
            || type == TESObjectWEAP::kWeapType_OneHandLunchboxMine
            || type == TESObjectWEAP::kWeapType_OneHandThrown;
}

const char* InventoryGroup(TESForm* form) {
    if (form == nullptr) return "OTHER";
    if (form->typeID == kFormType_TESObjectWEAP) {
        switch (static_cast<TESObjectWEAP*>(form)->weaponSkill) {
            case eActorVal_Guns: return "GUNS";
            case eActorVal_EnergyWeapons: return "ENERGY";
            case eActorVal_Explosives: return "EXPLOSIVES";
            case eActorVal_MeleeWeapons: return "MELEE";
            case eActorVal_Unarmed: return "UNARMED";
            default: return "OTHER";
        }
    }
    if (form->typeID == kFormType_TESObjectARMO || form->typeID == kFormType_TESObjectCLOT) {
        auto* biped = form->typeID == kFormType_TESObjectARMO
                ? &static_cast<TESObjectARMO*>(form)->bipedModel
                : &static_cast<TESObjectCLOT*>(form)->bipedModel;
        UInt32 equipType = form->typeID == kFormType_TESObjectARMO
                ? static_cast<TESObjectARMO*>(form)->equipType.equipType
                : static_cast<TESObjectCLOT*>(form)->equipType.equipType;
        // Body suits take precedence over head slots used by a full-body outfit.
        if ((biped->partMask & TESBipedModelForm::eSlot_UpperBody) != 0 || equipType == 7) {
            if (biped->IsPowerArmor()) return "POWER";
            if ((biped->bipedFlags & TESBipedModelForm::eBipedFlag_HeavyArmor) != 0) return "HEAVY";
            if ((biped->bipedFlags & TESBipedModelForm::eBipedFlag_MediumArmor) != 0) return "MEDIUM";
            return "LIGHT";
        }
        constexpr UInt32 headSlots = TESBipedModelForm::eSlot_Head | TESBipedModelForm::eSlot_Hair
                | TESBipedModelForm::eSlot_Headband | TESBipedModelForm::eSlot_Hat
                | TESBipedModelForm::eSlot_Mask;
        if ((biped->partMask & headSlots) != 0 || equipType == 8) return "HEADWEAR";
        return "ACCESSORIES";
    }
    if (form->typeID == kFormType_AlchemyItem) {
        auto* aid = static_cast<AlchemyItem*>(form);
        switch (aid->equipType.equipType) {
            case 11: return "STIMPAKS";
            case 10: return "CHEMS";
            case 12: return "FOOD";
            case 13: return "ALCOHOL";
            default: break;
        }
        if ((aid->alchFlags & 0x02) != 0) return "FOOD";
        if ((aid->alchFlags & 0x04) != 0) return "CHEMS";
        return "OTHER";
    }
    switch (form->typeID) {
        case kFormType_TESObjectIMOD: return "MODS";
        case kFormType_TESKey: return "KEYS";
        case kFormType_TESObjectBOOK: return "BOOKS";
        case kFormType_TESCasinoChips:
        case kFormType_TESCaravanMoney: return "CURRENCY";
        case kFormType_TESCaravanCard: return "CARDS";
        default: return form->refID == 0x0000000F ? "CURRENCY" : "OTHER";
    }
}

bool HasLeveledItemExtra(const ExtraContainerChanges::EntryData* entry) {
    if (entry == nullptr || entry->extendData == nullptr) return false;
    const UInt8 type = kExtraData_LeveledItem;
    const UInt8 mask = static_cast<UInt8>(1u << (type & 7));
    for (auto extraIter = entry->extendData->Begin(); !extraIter.End(); ++extraIter) {
        ExtraDataList* extraList = extraIter.Get();
        if (extraList != nullptr
                && (extraList->m_presenceBitfield[type >> 3] & mask) != 0) {
            return true;
        }
    }
    return false;
}

bool HasEquippedExtra(const ExtraContainerChanges::EntryData* entry) {
    if (entry == nullptr || entry->extendData == nullptr) return false;
    const UInt8 types[] = {kExtraData_Worn, kExtraData_WornLeft};
    for (UInt8 type : types) {
        const UInt8 mask = static_cast<UInt8>(1u << (type & 7));
        for (auto extraIter = entry->extendData->Begin(); !extraIter.End(); ++extraIter) {
            ExtraDataList* extraList = extraIter.Get();
            if (extraList != nullptr
                    && (extraList->m_presenceBitfield[type >> 3] & mask) != 0) {
                return true;
            }
        }
    }
    return false;
}

std::unordered_map<TESForm*, SInt32> BuildInventoryCounts(PlayerCharacter* player) {
    // Start with base-container counts, then merge the instance's count deltas below.
    std::unordered_map<TESForm*, SInt32> counts;
    if (player == nullptr || player->baseForm == nullptr) return counts;
    auto* actorBase = static_cast<TESActorBase*>(player->baseForm);
    for (auto formIter = actorBase->container.formCountList.Begin();
         !formIter.End(); ++formIter) {
        auto* formCount = formIter.Get();
        if (formCount != nullptr && formCount->form != nullptr) {
            counts[formCount->form] += formCount->count;
        }
    }
    auto* changes = static_cast<ExtraContainerChanges*>(
            player->extraDataList.GetByType(kExtraData_ContainerChanges));
    auto* entries = changes != nullptr && changes->data != nullptr
            ? changes->data->objList : nullptr;
    if (entries != nullptr) {
        for (auto entryIter = entries->Begin(); !entryIter.End(); ++entryIter) {
            auto* entry = entryIter.Get();
            if (entry == nullptr || entry->type == nullptr) continue;
            auto found = counts.find(entry->type);
            if (found == counts.end()) {
                if (entry->countDelta > 0) counts.emplace(entry->type, entry->countDelta);
            } else if (HasLeveledItemExtra(entry)) {
                // For these entries, replace the base count instead of adding to it.
                found->second = entry->countDelta;
            } else {
                found->second += entry->countDelta;
            }
        }
    }
    return counts;
}

bool IsInventoryCopyEquipped(ExtraDataList* extra) {
    return extra != nullptr && (extra->GetByType(kExtraData_Worn) != nullptr
            || extra->GetByType(kExtraData_WornLeft) != nullptr);
}

SInt32 InventoryExtraCount(ExtraDataList* extra) {
    if (extra == nullptr) return 0;
    auto* count = static_cast<ExtraCount*>(extra->GetByType(kExtraData_Count));
    return count != nullptr ? std::max<SInt32>(0, count->count) : 1;
}

int InventoryCopyModFlags(TESForm* form, ExtraDataList* extra) {
    if (form->typeID != kFormType_TESObjectWEAP || extra == nullptr) return 0;
    auto* mods = static_cast<ExtraWeaponModFlags*>(extra->GetByType(kExtraData_WeaponModFlags));
    return mods != nullptr ? mods->flags & 7 : 0;
}

int InventoryCopyCondition(TESForm* form, ExtraDataList* extra) {
    float maximum = 0;
    if (form->typeID == kFormType_TESObjectWEAP) {
        auto* weapon = static_cast<TESObjectWEAP*>(form);
        maximum = static_cast<float>(weapon->health.health);
        const int mods = InventoryCopyModFlags(form, extra);
        bool healthModInstalled = false;
        for (int slot = 0; slot < 3; ++slot) {
            if ((mods & (1 << slot)) != 0 && weapon->effectMods[slot] == 10)
                healthModInstalled = true;
        }
        if (healthModInstalled) {
            // Match GetModdedValue: use the first effect-10 value and truncate to whole health.
            for (int slot = 0; slot < 3; ++slot) {
                if (weapon->effectMods[slot] == 10) {
                    maximum = static_cast<float>(static_cast<int>(
                            static_cast<double>(weapon->health.health) + weapon->value1Mod[slot]));
                    break;
                }
            }
        }
    } else if (form->typeID == kFormType_TESObjectARMO) {
        maximum = static_cast<float>(static_cast<TESObjectARMO*>(form)->health.health);
    }
    if (!std::isfinite(maximum) || maximum <= 0) return -1;
    auto* health = extra != nullptr
            ? static_cast<ExtraHealth*>(extra->GetByType(kExtraData_Health)) : nullptr;
    // ExtraHealth is absolute health, not a normalized fraction; absence means full condition.
    const float current = health != nullptr && health->health != -1.0f ? health->health : maximum;
    if (!std::isfinite(current)) return -1;
    return static_cast<int>(std::round(std::clamp(current / maximum, 0.0f, 1.0f) * 1000));
}

bool ResolveInventoryCopy(PlayerCharacter* player, TESForm* form,
                          const InventoryCopy& copy, ExtraDataList*& resolved) {
    auto counts = BuildInventoryCounts(player);
    auto owned = counts.find(form);
    if (owned == counts.end() || owned->second <= 0) return false;
    auto* changes = static_cast<ExtraContainerChanges*>(
            player->extraDataList.GetByType(kExtraData_ContainerChanges));
    auto* entries = changes != nullptr && changes->data != nullptr
            ? changes->data->objList : nullptr;
    SInt32 extendedCount = 0;
    if (entries != nullptr) {
        for (auto it = entries->Begin(); !it.End(); ++it) {
            auto* entry = it.Get();
            if (entry == nullptr || entry->type != form || entry->extendData == nullptr) continue;
            for (auto ex = entry->extendData->Begin(); !ex.End(); ++ex) {
                ExtraDataList* extra = ex.Get();
                const SInt32 count = InventoryExtraCount(extra);
                if (copy.extra != nullptr && extra == copy.extra && copy.ordinal < count) {
                    resolved = extra;
                    return true;
                }
                extendedCount += count;
            }
        }
    }
    // Plain copies have no extra list. Validate that one still remains before passing null to EquipItem.
    resolved = nullptr;
    return copy.extra == nullptr && copy.ordinal >= 0
            && copy.ordinal < owned->second - extendedCount;
}

void ResetInventoryCopies() {
    g_inventoryCopyTokens.clear();
    g_inventoryCopies.clear();
    g_inspectedItemForm = 0;
    g_inspectedItemToken = 0;
    // Do not reset the token counter: a delayed command from an old save must stay invalid.
    std::lock_guard<std::mutex> lock(g_pendingCommandMutex);
    std::erase_if(g_pendingItemCommands, [](const PendingItemCommand& command) {
        return command.type == ItemCommandType::Equip || command.type == ItemCommandType::InspectItem
                || command.type == ItemCommandType::AssignHotkey
                || command.type == ItemCommandType::InstallWeaponMod
                || command.type == ItemCommandType::DropItem;
    });
}

TESObjectWEAP* GetEquippedWeapon(PlayerCharacter* player) {
    if (player == nullptr || player->baseProcess == nullptr) return nullptr;
    ExtraContainerChanges::EntryData* weaponInfo = player->baseProcess->GetWeaponInfo();
    if (weaponInfo == nullptr || weaponInfo->type == nullptr
            || weaponInfo->type->typeID != kFormType_TESObjectWEAP) {
        return nullptr;
    }
    return static_cast<TESObjectWEAP*>(weaponInfo->type);
}

TESAmmo* GetEquippedAmmo(PlayerCharacter* player) {
    if (player == nullptr || player->baseProcess == nullptr) return nullptr;
    BaseProcess::AmmoInfo* ammoInfo = player->baseProcess->GetAmmoInfo();
    return ammoInfo != nullptr ? ammoInfo->ammo : nullptr;
}

bool UpdateHotkeyAmmoPreference(PlayerCharacter* player, TESObjectWEAP* weapon,
        TESAmmo* ammo) {
    if (player == nullptr || weapon == nullptr || ammo == nullptr) return false;
    auto* changes = static_cast<ExtraContainerChanges*>(
            player->extraDataList.GetByType(kExtraData_ContainerChanges));
    auto* entries = changes != nullptr && changes->data != nullptr ? changes->data->objList : nullptr;
    if (entries == nullptr) return true;
    for (auto it = entries->Begin(); !it.End(); ++it) {
        auto* entry = it.Get();
        if (entry == nullptr || entry->type != weapon) continue;
        // Reload reads the first hotkey on the weapon's aggregate inventory entry.
        const int slot = ThisStdCall<int>(0x004BD7A0, entry);
        if (slot < 0) return true;
        if (slot >= 8) return false;
        auto* previous = ThisStdCall<TESAmmo*>(0x004BFB30, player, slot);
        if (previous == ammo) return true;
        // Keep the player's saved hotkey ammo choice in step with the process ammo.
        ThisStdCall<void>(0x004BFB70, player, static_cast<UInt32>(slot), ammo);
        const bool updated = ThisStdCall<TESAmmo*>(0x004BFB30, player, slot) == ammo;
        char logMessage[176]{};
        std::snprintf(logMessage, sizeof(logMessage),
                "FNVDS hotkey ammo preference weapon=%08X slot=%d ammo=%08X result=%u",
                weapon->refID, slot + 1, ammo->refID, updated ? 1u : 0u);
        WriteLog(logMessage);
        return updated;
    }
    return true;
}

bool TriggerPlayerReload(PlayerCharacter* player, TESObjectWEAP* weapon) {
    if (player == nullptr || weapon == nullptr) return false;
    using PlayerReloadFn = bool (__thiscall *)(PlayerCharacter*, TESObjectWEAP*, int,
            UInt8, UInt8);
    // xNVSE hooks this PlayerCharacter vtable entry as its reload routine.
    constexpr uintptr_t kPlayerReloadVtableEntry = 0x0108AE28;
    const auto reload = *reinterpret_cast<PlayerReloadFn*>(kPlayerReloadVtableEntry);
    return reload != nullptr && reload(player, weapon, 1, 0, 0);
}

void ProcessPendingAmmoReload(PlayerCharacter* player) {
    // Wait for the requested ammo to be selected; cancel if the equipped weapon changes.
    PendingAmmoReload& pending = g_pendingAmmoReload;
    if (pending.ammoFormId == 0 || pending.weaponFormId == 0) return;

    TESObjectWEAP* weapon = GetEquippedWeapon(player);
    if (weapon == nullptr || weapon->refID != pending.weaponFormId) {
        char logMessage[128]{};
        std::snprintf(logMessage, sizeof(logMessage),
                      "FNVDS ammo reload canceled form=%08X expectedWeapon=%08X currentWeapon=%08X",
                      pending.ammoFormId, pending.weaponFormId,
                      weapon != nullptr ? weapon->refID : 0);
        WriteLog(logMessage);
        pending = {};
        return;
    }

    TESAmmo* selectedAmmo = GetEquippedAmmo(player);
    if (selectedAmmo == nullptr || selectedAmmo->refID != pending.ammoFormId) {
        if (++pending.attempts >= 30) {
            char logMessage[128]{};
            std::snprintf(logMessage, sizeof(logMessage),
                          "FNVDS ammo reload expired form=%08X selected=%08X",
                          pending.ammoFormId,
                          selectedAmmo != nullptr ? selectedAmmo->refID : 0);
            WriteLog(logMessage);
            pending = {};
        }
        return;
    }

    if (!UpdateHotkeyAmmoPreference(player, weapon, selectedAmmo)) {
        WriteLog("FNVDS ammo reload canceled: could not update hotkey ammo preference");
        pending = {};
        return;
    }

    const UInt8 attempt = static_cast<UInt8>(pending.attempts + 1);
    if (TriggerPlayerReload(player, weapon)) {
        char logMessage[128]{};
        std::snprintf(logMessage, sizeof(logMessage),
                      "FNVDS ammo reload form=%08X weapon=%08X result=1 attempt=%u",
                      pending.ammoFormId, pending.weaponFormId, attempt);
        WriteLog(logMessage);
        pending = {};
        return;
    }

    if (++pending.attempts >= 30) {
        char logMessage[128]{};
        std::snprintf(logMessage, sizeof(logMessage),
                      "FNVDS ammo reload form=%08X weapon=%08X result=0 attempts=%u",
                      pending.ammoFormId, pending.weaponFormId, pending.attempts);
        WriteLog(logMessage);
        pending = {};
    }
}

bool WeaponCanUseAmmo(const TESObjectWEAP* weapon, const TESAmmo* ammo) {
    if (weapon == nullptr || ammo == nullptr || weapon->ammo.ammo == nullptr) return false;
    TESForm* acceptedAmmo = weapon->ammo.ammo;
    if (acceptedAmmo == ammo) return true;
    if (acceptedAmmo->typeID == kFormType_BGSListForm) {
        auto* ammoList = static_cast<BGSListForm*>(acceptedAmmo);
        return ammoList->GetIndexOf(ammo) != eListInvalid;
    }
    return false;
}

TESForm* FindInventoryForm(PlayerCharacter* player, UInt32 formId) {
    for (const auto& [form, count] : BuildInventoryCounts(player)) {
        if (form != nullptr && count > 0 && form->refID == formId) return form;
    }
    return nullptr;
}

// New Vegas stores the Pip-Boy's acquired-note list as an inline singly linked
// list at PlayerCharacter + 0x5E4. The first node is embedded in PlayerCharacter;
// subsequent nodes are separately allocated. This is the same list consumed by
// MapMenu::RefreshNotesList, so it naturally contains only notes in the Pip-Boy.
struct PlayerNoteListNode {
    BGSNote* note;
    PlayerNoteListNode* next;
};
static_assert(sizeof(PlayerNoteListNode) == 8,
        "New Vegas x86 Pip-Boy note list node must be two pointers");

std::vector<BGSNote*> GetPlayerPipBoyNotes(PlayerCharacter* player) {
    std::vector<BGSNote*> notes;
    if (player == nullptr) return notes;

    auto* node = reinterpret_cast<PlayerNoteListNode*>(&player->note);
    std::unordered_set<PlayerNoteListNode*> visited;
    constexpr size_t kMaximumNotes = 1024;
    while (node != nullptr && node->note != nullptr && notes.size() < kMaximumNotes
            && visited.insert(node).second) {
        notes.push_back(node->note);
        node = node->next;
    }
    return notes;
}

BGSNote* FindPlayerPipBoyNote(PlayerCharacter* player, UInt32 formId) {
    for (BGSNote* note : GetPlayerPipBoyNotes(player)) {
        if (note != nullptr && note->refID == formId) return note;
    }
    return nullptr;
}

void MarkNoteRead(BGSNote* note, PlayerCharacter* player) {
    if (note == nullptr || player == nullptr) return;
    note->read = 1;
    if (g_scriptInterface == nullptr || g_scriptInterface->CompileScript == nullptr
            || g_scriptInterface->CallFunctionAlt == nullptr) return;
    char source[128]{};
    std::snprintf(source, sizeof(source),
                  "Begin Function {}\nSetNoteRead %08X 1 1\nEnd", note->refID);
    Script* script = g_scriptInterface->CompileScript(source);
    if (script != nullptr) g_scriptInterface->CallFunctionAlt(script, player, 0);
}

#include "activity.h"
#include "dialogue.h"
#include "text_entry.h"
#include "barter_summary.h"
#include "loot_summary.h"
#include "vats.h"
#include "inventory_hotkeys.h"
#include "inventory_mods.h"
#include "inventory_drop.h"
#include "limbcare.h"

void ProcessItemCommands(PlayerCharacter* player) {
    if (player == nullptr || !g_gameReady.load(std::memory_order_relaxed)) return;
    UpdateNativeNoteAudio();
    if (g_activityBlock.load() != ActivityBlock::None || Vats::active.load()) {
        Activity::DiscardCommands();
        return;
    }
    ProcessPendingAmmoReload(player);
    std::deque<PendingItemCommand> commands;
    {
        std::lock_guard<std::mutex> lock(g_pendingCommandMutex);
        // Release the queue lock before executing commands against game objects.
        commands.swap(g_pendingItemCommands);
    }
    for (const PendingItemCommand& command : commands) {
        Activity::Update(player);
        if (g_activityBlock.load() != ActivityBlock::None) break;
        if (command.type == ItemCommandType::RemoveCustomMarker) {
            WriteLog(RemoveCustomMapMarker(player) ? "FNVDS custom marker removed"
                    : "FNVDS custom marker removal: no marker or native call failed");
            continue;
        }
        if (command.type == ItemCommandType::PlaceWorldMarker
                || command.type == ItemCommandType::PlaceLocalMarker) {
            PlaceCustomMapMarker(player, command);
            continue;
        }
        UInt32 formId = command.formId;
        const bool treatingLimb = command.type == ItemCommandType::TreatLimb;
        const bool hardcoreTreatment = treatingLimb && ThisStdCall<bool>(0x004D1360, player);
        if (treatingLimb) {
            UInt32 mask = CrippledLimbMask(player);
            if (!mask) { FinishLimbCare("NO_CRIPPLED"); continue; }
            if (command.hotkeySlot >= 6 || (hardcoreTreatment && !(mask & (1u << command.hotkeySlot)))) {
                FinishLimbCare("LIMB_CHANGED"); continue;
            }
            TESForm* bag = DoctorsBagForm();
            if (!bag || bag->typeID != kFormType_AlchemyItem) { FinishLimbCare("NO_BAG"); continue; }
            formId = bag->refID;
        }
        char logMessage[192]{};
        if (command.type == ItemCommandType::DropItem) {
            DropInventoryCopy(player, command);
            continue;
        }
        if (command.type == ItemCommandType::InstallWeaponMod) {
            InstallInventoryWeaponMod(player, command);
            continue;
        }
        if (command.type == ItemCommandType::AssignHotkey) {
            AssignInventoryHotkey(player, command);
            continue;
        }
        if (command.type == ItemCommandType::InspectItem) {
            g_inspectedItemForm = formId;
            g_inspectedItemToken = command.instanceToken;
            continue;
        }
        if (command.type == ItemCommandType::ReadNote) {
            BGSNote* note = FindPlayerPipBoyNote(player, formId);
            if (note != nullptr) {
                // This is the same runtime flag the Pip-Boy checks to dim a note.
                // The game writes it to the save when the player reads the note.
                MarkNoteRead(note, player);
            }
            continue;
        }

        if (command.type == ItemCommandType::PlayNote) {
            BGSNote* note = FindPlayerPipBoyNote(player, formId);
            if (note == nullptr) continue;
            bool played = false;
            try {
                played = StartNativeNoteAudio(note, player);
            } catch (...) {
                StopNativeNoteAudio();
                WriteLog("FNVDS built-in note audio playback threw an internal exception.");
            }
            g_notePlaybackError.store(!played, std::memory_order_relaxed);
            if (!played) WriteLog("FNVDS built-in note audio playback could not start.");
            continue;
        }

        if (command.type == ItemCommandType::StopNote) {
            StopNativeNoteAudio();
            continue;
        }

        if (command.type == ItemCommandType::UseAid || command.type == ItemCommandType::UseNeedAid || treatingLimb) {
            std::snprintf(logMessage, sizeof(logMessage),
                          "FNVDS aid command start form=%08X", formId);
            WriteLog(logMessage);

            TESForm* form = nullptr;
            SInt32 inventoryCountBefore = 0;
            const auto inventoryCounts = BuildInventoryCounts(player);
            for (const auto& [inventoryForm, count] : inventoryCounts) {
                if (inventoryForm != nullptr && count > 0 && inventoryForm->refID == formId) {
                    form = inventoryForm;
                    inventoryCountBefore = count;
                    break;
                }
            }
            std::snprintf(logMessage, sizeof(logMessage),
                          "FNVDS aid inventory lookup form=%08X result=%s",
                          formId, form != nullptr ? "found" : "null");
            WriteLog(logMessage);
            if (form == nullptr || form->typeID != kFormType_AlchemyItem) {
                if (treatingLimb) FinishLimbCare("NO_BAG");
                std::snprintf(logMessage, sizeof(logMessage),
                              "FNVDS aid command rejected form=%08X reason=not-alchemy", formId);
                WriteLog(logMessage);
                continue;
            }

            if (command.type == ItemCommandType::UseNeedAid) {
                const UInt32 need = command.hotkeySlot;
                if (need < 1 || need > 3 || !ThisStdCall<bool>(0x004D1360, player)) continue;
                const float current = player->avOwner.Fn_03(need == 1 ? eActorVal_Dehydration
                        : need == 2 ? eActorVal_Hunger : eActorVal_Sleepdeprevation);
                auto relief = AidNeedRelief(player, form);
                if (!std::isfinite(current) || current <= 0 || relief[need == 1 ? 1 : need == 2 ? 0 : 2].empty()) {
                    WriteLog("FNVDS survival aid rejected: need resolved or item no longer reduces it");
                    continue;
                }
            }
            using UseAlchemyItemFn = bool (__thiscall *)(void*, void*, void*, bool);
            std::snprintf(logMessage, sizeof(logMessage),
                          "FNVDS aid native use enter form=%08X", formId);
            WriteLog(logMessage);
            const auto previousEffects = treatingLimb ? CurrentLimbCareEffects(player) : std::unordered_set<ActiveEffect*>{};
            const bool used = reinterpret_cast<UseAlchemyItemFn>(0x008C1F80)(
                    player, form, nullptr, true);
            unsigned targeted = 0;
            if (used && treatingLimb) {
                targeted = TargetNewLimbCareEffects(player, form, previousEffects,
                        hardcoreTreatment ? static_cast<SInt32>(kLimbCareActorValues[command.hotkeySlot]) : -1);
            }
            if (used) {
                const auto countsAfterUse = BuildInventoryCounts(player);
                const auto countAfterUse = countsAfterUse.find(form);
                const SInt32 inventoryCountAfter = countAfterUse == countsAfterUse.end()
                        ? 0 : countAfterUse->second;
                // Request one removal only if successful use did not reduce the observed count.
                if (inventoryCountBefore > 0
                        && inventoryCountAfter >= inventoryCountBefore) {
                    if (g_consoleInterface != nullptr) {
                        char removeLine[96]{};
                        std::snprintf(removeLine, sizeof(removeLine),
                                      "RemoveItem %08X 1 1", formId);
                        const bool removeExecuted = g_consoleInterface->RunScriptLine2(
                                removeLine, player, true);
                        const auto countsAfterRemoval = BuildInventoryCounts(player);
                        const auto countAfterRemoval = countsAfterRemoval.find(form);
                        const SInt32 remainingCount = countAfterRemoval == countsAfterRemoval.end()
                                ? 0 : countAfterRemoval->second;
                        std::snprintf(logMessage, sizeof(logMessage),
                                      "FNVDS aid removal form=%08X before=%d afterUse=%d script=%u remaining=%d",
                                      formId, inventoryCountBefore, inventoryCountAfter,
                                      removeExecuted ? 1u : 0u, remainingCount);
                        WriteLog(logMessage);
                    } else {
                        std::snprintf(logMessage, sizeof(logMessage),
                                      "FNVDS aid removal skipped form=%08X reason=no-script-runner",
                                      formId);
                        WriteLog(logMessage);
                    }
                }
            }
            std::snprintf(logMessage, sizeof(logMessage),
                          "FNVDS aid command return form=%08X used=%u",
                          formId, used ? 1u : 0u);
            WriteLog(logMessage);
            if (treatingLimb) {
                const auto remaining = BuildInventoryCounts(player);
                auto found = remaining.find(form);
                bool consumed = found == remaining.end() || found->second < inventoryCountBefore;
                FinishLimbCare(used && targeted > 0 && consumed ? "OK" : "FAILED");
                std::snprintf(logMessage, sizeof(logMessage),
                        "FNVDS limb care form=%08X limb=%u hardcore=%u effects=%u consumed=%u",
                        formId, command.hotkeySlot, hardcoreTreatment ? 1u : 0u, targeted, consumed ? 1u : 0u);
                WriteLog(logMessage);
            }
            continue;
        }

        if (command.type == ItemCommandType::ViewQuest) {
            g_viewQuestForm = formId;
            g_viewQuestRequest = command.instanceToken;
            g_viewQuestPending = true;
            continue;
        }
        if (command.type == ItemCommandType::TrackQuest) {
            if (g_consoleInterface == nullptr) continue;
            char scriptLine[64]{};
            std::snprintf(scriptLine, sizeof(scriptLine), "SetCurrentQuest %08X", formId);
            std::snprintf(logMessage, sizeof(logMessage),
                          "FNVDS quest track start form=%08X", formId);
            WriteLog(logMessage);
            const bool executed = g_consoleInterface->RunScriptLine2(scriptLine, player, true);
            std::snprintf(logMessage, sizeof(logMessage),
                          "FNVDS quest track return form=%08X result=%u",
                          formId, executed ? 1u : 0u);
            WriteLog(logMessage);
            continue;
        }

        if (command.type == ItemCommandType::FastTravel) {
            auto markerIter = g_fastTravelMarkers.find(formId);
            TESObjectREFR* marker = markerIter == g_fastTravelMarkers.end()
                    ? nullptr : markerIter->second;
            auto* markerData = marker == nullptr ? nullptr : DYNAMIC_CAST(
                    marker->extraDataList.GetByType(kExtraData_MapMarker),
                    BSExtraData, ExtraMapMarker);
            if (marker == nullptr
                    || marker->baseForm == nullptr || marker->baseForm->refID != 0x10
                    || markerData == nullptr || markerData->data == nullptr
                    || (markerData->data->flags & ExtraMapMarker::kFlag_CanTravel) == 0
                    || (markerData->data->flags & ExtraMapMarker::kFlag_Hidden) != 0) {
                std::snprintf(logMessage, sizeof(logMessage),
                              "FNVDS fast travel rejected marker=%08X reason=unavailable", formId);
                WriteLog(logMessage);
                continue;
            }
            // Apply the Pip-Boy's eligibility gate for map and favourite requests.
            using IsFastTravelAllowedFn = bool (__thiscall *)(PlayerCharacter*);
            if (!reinterpret_cast<IsFastTravelAllowedFn>(0x0093D660)(player)) {
                std::snprintf(logMessage, sizeof(logMessage),
                              "FNVDS fast travel rejected marker=%08X reason=player-restricted", formId);
                WriteLog(logMessage);
                continue;
            }
            auto* pendingRequest = *reinterpret_cast<void**>(
                    reinterpret_cast<UInt8*>(player) + 0x1EC);
            if (pendingRequest != nullptr) {
                std::snprintf(logMessage, sizeof(logMessage),
                              "FNVDS fast travel rejected marker=%08X reason=movement-pending", formId);
                WriteLog(logMessage);
                continue;
            }

            using AllocateRequestFn = void* (__cdecl *)(UInt32);
            auto* request = static_cast<FnvdsFastTravelRequest*>(
                    reinterpret_cast<AllocateRequestFn>(0x00401000)(sizeof(FnvdsFastTravelRequest)));
            if (request == nullptr) {
                std::snprintf(logMessage, sizeof(logMessage),
                              "FNVDS fast travel rejected marker=%08X reason=allocation-failed", formId);
                WriteLog(logMessage);
                continue;
            }
            // These are the constructor and request setter used by MapMenu.
            // The engine owns the submitted request and resolves the linked landing reference.
            ThisStdCall<void>(0x00456540, request);
            request->mapMarker = marker;
            auto* linkedLanding = ThisStdCall<TESObjectREFR*>(0x00569B80, marker);
            TESObjectREFR* landing = linkedLanding != nullptr ? linkedLanding : marker;
            std::snprintf(logMessage, sizeof(logMessage),
                          "FNVDS fast travel native request marker=%08X landing=%08X linked=%u point=%.1f,%.1f,%.1f heading=%.4f",
                          formId, landing->refID, linkedLanding != nullptr ? 1u : 0u,
                          landing->posX, landing->posY, landing->posZ, landing->rotZ);
            WriteLog(logMessage);
            Activity::BeginFastTravel(player);
            ThisStdCall<void>(0x0093BE30, player, request);
            std::snprintf(logMessage, sizeof(logMessage),
                          "FNVDS fast travel request submitted marker=%08X", formId);
            WriteLog(logMessage);
            continue;
        }

        if (command.type == ItemCommandType::TuneRadio) {
            auto stationIter = g_pipboyRadioStations.find(formId);
            if (g_consoleInterface == nullptr || stationIter == g_pipboyRadioStations.end()
                    || stationIter->second == nullptr) {
                std::snprintf(logMessage, sizeof(logMessage),
                              "FNVDS radio tune rejected station=%08X reason=not-available", formId);
                WriteLog(logMessage);
                continue;
            }
            char scriptLine[96]{};
            const bool turnOff = IsPlayingRadioStation(formId);
            if (turnOff) {
                std::snprintf(scriptLine, sizeof(scriptLine), "PipboyRadio 0");
            } else {
                std::snprintf(scriptLine, sizeof(scriptLine), "PipboyRadio 1 %08X", formId);
            }
            std::snprintf(logMessage, sizeof(logMessage),
                          "FNVDS radio tune start station=%08X turnOff=%u", formId, turnOff ? 1u : 0u);
            WriteLog(logMessage);
            const bool executed = g_consoleInterface->RunScriptLine2(scriptLine, player, true);
            std::snprintf(logMessage, sizeof(logMessage),
                          "FNVDS radio tune return station=%08X result=%u",
                          formId, executed ? 1u : 0u);
            WriteLog(logMessage);
            continue;
        }

        if (command.type == ItemCommandType::Equip) {
            TESForm* item = FindInventoryForm(player, formId);
            if (item == nullptr) {
                std::snprintf(logMessage, sizeof(logMessage),
                              "FNVDS item command rejected form=%08X reason=not-in-inventory",
                              formId);
                WriteLog(logMessage);
                continue;
            }
            ExtraDataList* copyExtra = nullptr;
            bool unequip = command.wasEquipped;
            const bool individualGear = item->typeID == kFormType_TESObjectWEAP
                    || item->typeID == kFormType_TESObjectARMO
                    || item->typeID == kFormType_TESObjectCLOT;
            if (individualGear) {
                const auto copy = g_inventoryCopies.find(command.instanceToken);
                if (copy == g_inventoryCopies.end() || copy->second.formId != formId
                        || !ResolveInventoryCopy(player, item, copy->second, copyExtra)
                        || IsInventoryCopyEquipped(copyExtra) != command.wasEquipped) {
                    WriteLog("FNVDS item copy rejected: inventory or equipped state changed");
                    continue;
                }
                unequip = IsInventoryCopyEquipped(copyExtra);
                g_inventoryCopyTokens.erase(copy->second);
                g_inventoryCopies.erase(copy);
            }
            if (item->typeID == kFormType_TESAmmo
                    && !WeaponCanUseAmmo(GetEquippedWeapon(player),
                            static_cast<TESAmmo*>(item))) {
                std::snprintf(logMessage, sizeof(logMessage),
                              "FNVDS ammo equip rejected form=%08X reason=incompatible-with-equipped-weapon",
                              formId);
                WriteLog(logMessage);
                continue;
            }
            auto* ammo = item->typeID == kFormType_TESAmmo
                    ? static_cast<TESAmmo*>(item) : nullptr;
            TESAmmo* previousAmmo = ammo != nullptr ? GetEquippedAmmo(player) : nullptr;
            TESObjectWEAP* equippedWeapon = ammo != nullptr
                    ? GetEquippedWeapon(player) : nullptr;
            const bool changedAmmoType = ammo != nullptr && previousAmmo != ammo;
            if (ammo != nullptr && !unequip
                    && !UpdateHotkeyAmmoPreference(player, equippedWeapon, ammo)) {
                WriteLog("FNVDS ammo equip rejected: could not update hotkey ammo preference");
                continue;
            }


            std::snprintf(logMessage, sizeof(logMessage),
                          "FNVDS item native command start form=%08X wasEquipped=%u",
                          formId, command.wasEquipped ? 1u : 0u);
            WriteLog(logMessage);
            using ActorItemFn = void (__thiscall *)(Actor*, TESForm*, UInt32,
                    ExtraDataList*, UInt32, bool, UInt32);
            constexpr uintptr_t kActorEquipItemAddress = 0x0088C650;
            constexpr uintptr_t kActorUnequipItemAddress = 0x0088C790;
            const uintptr_t itemFunction = unequip
                    ? kActorUnequipItemAddress : kActorEquipItemAddress;
            reinterpret_cast<ActorItemFn>(itemFunction)(
                    player, item, 1, copyExtra, 1, false, 1);
            std::snprintf(logMessage, sizeof(logMessage),
                          "FNVDS item native command dispatched form=%08X wasEquipped=%u",
                          formId, command.wasEquipped ? 1u : 0u);
            WriteLog(logMessage);
            if (changedAmmoType && !command.wasEquipped && equippedWeapon != nullptr) {
                g_pendingAmmoReload = {ammo->refID, equippedWeapon->refID, 0};
                std::snprintf(logMessage, sizeof(logMessage),
                              "FNVDS ammo reload queued form=%08X weapon=%08X",
                              formId, equippedWeapon->refID);
                WriteLog(logMessage);
            }
            continue;
        }
    }
}

std::string BuildExperienceSnapshot(PlayerCharacter* player) {
    const char* unavailable = "STATE EXPERIENCE 0 -1 -1 0\n";
    if (!player || !g_gameReady.load()) return unavailable;
    const UInt16 level = ThisStdCall<UInt16>(0x0087F9F0, player);
    if (level == 0 || level > 1000) return unavailable;
    // StatsMenu checks the current iMaxCharacterLevel setting at +4 of this Setting.
    const int cap = *reinterpret_cast<int*>(0x011D0C64);
    const bool capped = cap > 0 && level >= cap;
    double progress = 1;
    int span = 1;
    if (!capped) {
        const float xp = player->avOwner.Fn_03(eActorVal_XP);
        const auto threshold = reinterpret_cast<int (__cdecl*)(int)>(0x00648B50);
        const int start = threshold(level), next = threshold(level + 1);
        if (!std::isfinite(xp) || xp < 0 || start < 0 || next <= start) return unavailable;
        span = next - start;
        progress = std::clamp<double>(static_cast<double>(xp) - start, 0, span);
    }
    char packet[128]{};
    std::snprintf(packet, sizeof(packet), "STATE EXPERIENCE %u %.3f %d %d\n",
            static_cast<unsigned>(level), progress, span, capped ? 1 : 0);
    return packet;
}

std::string BuildStatusSnapshot(PlayerCharacter* player) {
    if (!player) return std::string("STATE STATUS -1 -1 0 -\n") + BuildExperienceSnapshot(nullptr);
    auto tenths = [](float value) {
        return std::isfinite(value) && value >= 0 && value < 10000000
                ? static_cast<int>(std::lround(value * 10)) : -1;
    };
    // StatsMenu uses the integer permanent actor-value query for the HP/AP denominators.
    const int maxHealth = tenths(static_cast<float>(player->avOwner.Fn_07(eActorVal_Health)));
    const int maxAp = tenths(static_cast<float>(player->avOwner.Fn_07(eActorVal_ActionPoints)));
    const bool hardcore = ThisStdCall<bool>(0x004D1360, player);
    // Calendar's first four fields are the year, zero-based month, day and hour globals.
    auto** calendar = reinterpret_cast<TESGlobal**>(0x011DE7B8);
    std::string date;
    if (calendar[0] && calendar[1] && calendar[2] && calendar[3]) {
        float year = calendar[0]->data, month = calendar[1]->data;
        float day = calendar[2]->data, hour = calendar[3]->data;
        if (std::isfinite(year) && year >= 0 && year < 10000
                && std::isfinite(month) && month >= 0 && month < 12
                && std::isfinite(day) && day >= 1 && day < 32
                && std::isfinite(hour) && hour >= 0 && hour < 24) {
            const int minutes = static_cast<int>(hour * 60);
            char text[64]{};
            std::snprintf(text, sizeof(text), "%02d.%02d.%04d %02d:%02d",
                    static_cast<int>(month) + 1, static_cast<int>(day), static_cast<int>(year),
                    minutes / 60, minutes % 60);
            date = text;
        }
    }
    return "STATE STATUS " + std::to_string(maxHealth) + " " + std::to_string(maxAp)
            + " " + (hardcore ? "1 " : "0 ") + (date.empty() ? "-" : Base64Encode(date)) + "\n"
            + BuildExperienceSnapshot(player);
}

// Return the evaluated negative hunger/thirst/sleep modifiers from the native item card.
// Matching the game's own AV abbreviations keeps this independent of item names.
std::array<std::string, 3> AidNeedRelief(PlayerCharacter* player, TESForm* form) {
    std::array<std::string, 3> relief;
    if (!player || !form || form->typeID != kFormType_AlchemyItem
            || !ThisStdCall<bool>(0x004D1360, player)) return relief;
    auto* magic = DYNAMIC_CAST(form, TESForm, MagicItem);
    if (!magic || !magic->list.list.m_listHead.data) return relief;
    char description[2048]{};
    ThisStdCall<void>(0x00406620, &magic->list, description,
            static_cast<UInt32>(sizeof(description)));
    description[sizeof(description) - 1] = 0;
    std::string text(description);
    const UInt32 values[] = {eActorVal_Hunger, eActorVal_Dehydration, eActorVal_Sleepdeprevation};
    for (size_t index = 0; index < relief.size(); ++index) {
        const char* abbreviation = reinterpret_cast<const char* (__cdecl*)(UInt32)>(0x0066EB00)(values[index]);
        if (!abbreviation || !*abbreviation) continue;
        std::string prefix = std::string(abbreviation) + ' ';
        size_t position = 0;
        while ((position = text.find(prefix, position)) != std::string::npos) {
            const size_t begin = position;
            position += prefix.size();
            if (begin && text[begin - 1] != ' ' && text[begin - 1] != '\n'
                    && text[begin - 1] != ',') continue;
            char* end = nullptr;
            long amount = std::strtol(text.c_str() + position, &end, 10);
            if (end == text.c_str() + position || amount >= 0) continue;
            size_t finish = text.find_first_of(",\r\n", position);
            std::string modifier = text.substr(position, finish == std::string::npos
                    ? std::string::npos : finish - position);
            while (!modifier.empty() && std::isspace(static_cast<unsigned char>(modifier.back()))) modifier.pop_back();
            if (!relief[index].empty()) relief[index] += ", ";
            relief[index] += GameTextToUtf8(modifier.c_str());
        }
    }
    return relief;
}

std::string InventoryOverviewFields(PlayerCharacter* player, TESForm* form, ExtraDataList* extra) {
    ExtraContainerChanges::ExtendDataList singleExtra;
    singleExtra.m_listHead.data = extra;
    singleExtra.m_listHead.next = nullptr;
    ExtraContainerChanges::EntryData entry{extra ? &singleExtra : nullptr, 1, form};
    const bool hardcore = ThisStdCall<bool>(0x004D1360, player);
    float weight = reinterpret_cast<float (__cdecl *)(TESForm*, bool)>(0x0048EBC0)(form, hardcore);
    const float value = reinterpret_cast<float (__fastcall *)(ExtraContainerChanges::EntryData*)>(0x004BD400)(&entry);
    if (form->typeID == kFormType_TESObjectWEAP) {
        auto* weapon = static_cast<TESObjectWEAP*>(form);
        const int flags = InventoryCopyModFlags(form, extra);
        bool lighter = false;
        for (int slot = 0; slot < 3; ++slot)
            if ((flags & (1 << slot)) && weapon->effectMods[slot] == 4) lighter = true;
        weight = ThisStdCall<float>(0x004BE380, weapon, lighter);
        if (weight >= 10) {
            float multiplier = 1;
            reinterpret_cast<void (__cdecl *)(UInt32, Actor*, ...)>(0x005E58F0)(0x49, player, &multiplier);
            weight *= multiplier;
        }
    }
    auto* defaults = *reinterpret_cast<BGSDefaultObjectManager**>(0x011CA80C);
    int medicine = defaults && form == defaults->defaultObjects.asStruct.Stimpak ? 1
            : defaults && form == defaults->defaultObjects.asStruct.SuperStimpak ? 2
            : defaults && form == defaults->defaultObjects.asStruct.RadAway ? 3 : 0;
    char fields[128]{};
    std::snprintf(fields, sizeof(fields), "%.3f %.3f %d %d ",
            std::isfinite(weight) ? std::max<float>(0.0f, weight) : 0.0f,
            std::isfinite(value) ? std::max<float>(0.0f, value) : 0.0f,
            InventoryItemCanDrop(form) ? 1 : 0, medicine);
    auto relief = AidNeedRelief(player, form);
    return std::string(fields) + (relief[0].empty() ? "-" : Base64Encode(relief[0])) + ' '
            + (relief[1].empty() ? "-" : Base64Encode(relief[1])) + ' '
            + (relief[2].empty() ? "-" : Base64Encode(relief[2])) + ' ';
}

std::string BuildInventorySnapshot(PlayerCharacter* player) {
    std::string snapshot = "STATE INVENTORY BEGIN\n";
    std::unordered_map<InventoryCopy, UInt32, InventoryCopyHash> nextTokens;
    std::unordered_map<UInt32, InventoryCopy> nextCopies;
    if (player != nullptr && player->baseForm != nullptr) {
        auto* changes = static_cast<ExtraContainerChanges*>(
                player->extraDataList.GetByType(kExtraData_ContainerChanges));
        auto* entries = changes != nullptr && changes->data != nullptr
                ? changes->data->objList : nullptr;

        std::unordered_map<TESForm*, SInt32> counts = BuildInventoryCounts(player);
        std::unordered_map<UInt32, TESForm*> formsById;
        for (const auto& [form, count] : counts) if (form) formsById.emplace(form->refID, form);
        TESObjectWEAP* equippedWeapon = GetEquippedWeapon(player);
        TESAmmo* equippedAmmo = GetEquippedAmmo(player);
        std::unordered_set<TESForm*> equipped;
        std::unordered_map<TESForm*, std::vector<ExtraDataList*>> extrasByForm;
        if (entries != nullptr) {
            for (auto entryIter = entries->Begin(); !entryIter.End(); ++entryIter) {
                auto* entry = entryIter.Get();
                if (entry != nullptr && entry->type != nullptr && HasEquippedExtra(entry)) {
                    equipped.insert(entry->type);
                }
                if (entry != nullptr && entry->type != nullptr && entry->extendData != nullptr) {
                    for (auto extra = entry->extendData->Begin(); !extra.End(); ++extra) {
                        if (extra.Get() != nullptr) extrasByForm[entry->type].push_back(extra.Get());
                    }
                }
            }
        }
        std::vector<std::tuple<std::string, std::string, SInt32, UInt32,
                               bool, bool, bool, std::string, UInt32, int, int, int>> items;
        items.reserve(counts.size());
        for (const auto& [form, count] : counts) {
            const char* category = InventoryCategory(form);
            // Internal wearable devices are intentionally absent from the
            // vanilla Pip-Boy inventory. Keep them out of the companion too.
            const bool internalPipboy = form->refID == 0x00025B83
                    || form->refID == 0x00015038 || form->refID == 0x000236D8
                    || form->refID == 0x0011BACB || form->refID == 0x0011BCF8;
            if (category != nullptr && count > 0 && !internalPipboy) {
                const bool individual = std::strcmp(category, "WEAPONS") == 0
                        || std::strcmp(category, "APPAREL") == 0;
                if (individual) {
                    const bool throwable = IsThrowableWeapon(form);
                    auto addCopy = [&](ExtraDataList* extra, SInt32 ordinal) {
                        InventoryCopy copy{form->refID, extra, ordinal};
                        auto previous = g_inventoryCopyTokens.find(copy);
                        UInt32 token = previous != g_inventoryCopyTokens.end() ? previous->second : 0;
                        if (token == 0) {
                            token = g_nextInventoryCopyToken++;
                            if (token == 0) token = g_nextInventoryCopyToken++;
                        }
                        nextTokens.emplace(copy, token);
                        nextCopies.emplace(token, copy);
                        auto* hotkey = extra != nullptr
                                ? static_cast<ExtraHotkey*>(extra->GetByType(kExtraData_Hotkey)) : nullptr;
                        items.emplace_back(category, FormDisplayName(form), throwable ? count : 1, form->refID,
                                IsInventoryCopyEquipped(extra), false, true, InventoryGroup(form), token,
                                throwable ? -2 : InventoryCopyCondition(form, extra), InventoryCopyModFlags(form, extra),
                                hotkey != nullptr && hotkey->index < 8 ? hotkey->index + 1 : 0);
                    };
                    if (throwable) {
                        ExtraDataList* representative = nullptr;
                        auto extraEntry = extrasByForm.find(form);
                        if (extraEntry != extrasByForm.end()) for (ExtraDataList* extra : extraEntry->second) {
                            if (InventoryExtraCount(extra) <= 0) continue;
                            if (IsInventoryCopyEquipped(extra)) { representative = extra; break; }
                            if (!representative || extra->GetByType(kExtraData_Hotkey)) representative = extra;
                        }
                        addCopy(representative, 0);
                        continue;
                    }
                    SInt32 remaining = count;
                    auto extraEntry = extrasByForm.find(form);
                    if (extraEntry != extrasByForm.end()) {
                        for (ExtraDataList* extra : extraEntry->second) {
                            const SInt32 stackCount = std::min<SInt32>(remaining, InventoryExtraCount(extra));
                            for (SInt32 index = 0; index < stackCount; ++index) addCopy(extra, index);
                            remaining -= stackCount;
                            if (remaining == 0) break;
                        }
                    }
                    for (SInt32 index = 0; index < remaining; ++index) addCopy(nullptr, index);
                    continue;
                }
                items.emplace_back(category, FormDisplayName(form), count,
                                   form->refID, equipped.contains(form) || form == equippedAmmo,
                                   form->typeID == kFormType_AlchemyItem,
                                   form->typeID != kFormType_TESAmmo
                                           || WeaponCanUseAmmo(equippedWeapon,
                                                   static_cast<TESAmmo*>(form)),
                                   InventoryGroup(form), 0, -1, 0, 0);
            }
        }
        std::sort(items.begin(), items.end(), [](const auto& left, const auto& right) {
            if (std::get<0>(left) != std::get<0>(right))
                return std::get<0>(left) < std::get<0>(right);
            if (std::get<1>(left) != std::get<1>(right))
                return std::get<1>(left) < std::get<1>(right);
            if (std::get<3>(left) != std::get<3>(right))
                return std::get<3>(left) < std::get<3>(right);
            return std::get<8>(left) < std::get<8>(right);
        });
        for (const auto& [category, name, count, formId, isEquipped,
                          isUsableAid, ammoApplicable, group, token, condition, mods, hotkey] : items) {
            snapshot += "STATE ITEM ";
            snapshot += category;
            snapshot += ' ';
            snapshot += std::to_string(count);
            snapshot += ' ';
            snapshot += std::to_string(formId);
            snapshot += ' ';
            snapshot += isEquipped ? "1" : "0";
            snapshot += ' ';
            snapshot += isUsableAid ? "1" : "0";
            snapshot += ' ';
            snapshot += ammoApplicable ? "1" : "0";
            snapshot += ' ';
            snapshot += group;
            snapshot += ' ';
            snapshot += std::to_string(token) + ' ' + std::to_string(condition) + ' '
                    + std::to_string(mods) + ' ' + std::to_string(hotkey) + ' ';
            auto copy = nextCopies.find(token);
            snapshot += InventoryOverviewFields(player, formsById.at(formId),
                    copy != nextCopies.end() ? copy->second.extra : nullptr);
            snapshot += Base64Encode(name);
            snapshot += '\n';
        }
    }
    g_inventoryCopyTokens.swap(nextTokens);
    g_inventoryCopies.swap(nextCopies);
    snapshot += "STATE INVENTORY END\n";
    snapshot += BuildHotkeySnapshot(player);
    if (player != nullptr && g_gameReady.load()) {
        const float carried = player->avOwner.Fn_03(eActorVal_InventoryWeight);
        // The native capacity query includes the Get Max Carry Weight perk entry point.
        const float capacity = ThisStdCall<float>(0x008A0C20, player);
        if (std::isfinite(carried) && std::isfinite(capacity)) {
            char weight[96]{};
            std::snprintf(weight, sizeof(weight), "STATE ENCUMBRANCE %.3f %.3f\n", carried, capacity);
            snapshot += weight;
        } else snapshot += "STATE ENCUMBRANCE UNAVAILABLE\n";
    } else snapshot += "STATE ENCUMBRANCE UNAVAILABLE\n";
    return snapshot;
}

#include "inventory_details.h"

std::string BuildQuestSnapshot(PlayerCharacter* player) {
    std::string snapshot = "STATE QUESTS BEGIN\n";
    if (player != nullptr) {
        struct QuestRow {
            TESQuest* quest;
            std::string questName;
            std::string objectives;
            std::string optionalObjectives;
            std::unordered_set<std::string> objectiveTexts;
        };
        std::vector<QuestRow> questRows;
        std::unordered_map<TESQuest*, size_t> questRowByForm;
        for (auto objectiveIter = player->questObjectiveList.Begin();
             !objectiveIter.End(); ++objectiveIter) {
            BGSQuestObjective* objective = objectiveIter.Get();
            if (objective == nullptr || (objective->status & BGSQuestObjective::eQObjStatus_displayed) == 0
                    || (objective->status & BGSQuestObjective::eQObjStatus_completed) != 0
                    || objective->quest == nullptr || (objective->quest->flags & 1) == 0
                    // GetQuestFlag flag 5 controls whether the quest is shown in the Pip-Boy.
                    // Background/hidden quests can have live displayed objectives without this bit.
                    || (objective->quest->flags & (1 << 5)) == 0
                    // Some background quests retain an incomplete objective after the quest
                    // itself has completed. The Pip-Boy treats the completed state as final.
                    || (objective->quest->flags & 0x03) == 0x03) {
                continue;
            }
            // Aggregate objectives under one row per quest, deduplicating their display text.
            auto [rowIter, inserted] = questRowByForm.emplace(
                    objective->quest, questRows.size());
            if (inserted) {
                const char* questName = objective->quest->fullName.name.m_data;
                questRows.push_back({objective->quest,
                        GameTextToUtf8(questName == nullptr ? "Active Quest" : questName),
                        {}, {}, {}});
            }
            QuestRow& row = questRows[rowIter->second];
            const char* objectiveName = objective->displayText.m_data;
            std::string translatedObjective = GameTextToUtf8(
                    objectiveName == nullptr ? "" : objectiveName);
            if (translatedObjective.empty()) continue;
            if (!row.objectiveTexts.insert(translatedObjective).second) continue;
            const bool isOptional = StripOptionalObjectivePrefix(translatedObjective);
            std::string& destination = isOptional ? row.optionalObjectives : row.objectives;
            if (!destination.empty()) destination += '\n';
            destination += translatedObjective;
        }
        for (const QuestRow& row : questRows) {
            if (row.objectives.empty() && row.optionalObjectives.empty()) continue;
            snapshot += "STATE QUEST ";
            snapshot += std::to_string(row.quest->refID);
            snapshot += ' ';
            snapshot += row.quest == player->quest
                    ? "1" : "0";
            snapshot += ' ';
            snapshot += Base64Encode(row.questName);
            snapshot += ' ';
            snapshot += Base64Encode(row.objectives);
            snapshot += ' ';
            snapshot += Base64Encode(row.optionalObjectives);
            snapshot += '\n';
        }
    }
    snapshot += "STATE QUESTS END\n";
    return snapshot;
}

#include "challenges.h"

bool ReadGeneralStat(unsigned index, char* label, size_t labelSize, int& value) {
    if (index >= 43) return false;
    __try {
        auto entries = reinterpret_cast<void**>(0x011C6D50);
        if (!entries[index]) return false;
        value = reinterpret_cast<int (__cdecl*)(int)>(0x005A3370)(index);
        const char* name = reinterpret_cast<const char* (__cdecl*)(int)>(0x0078A260)(index);
        if (!name || !*name) return false;
        strncpy_s(label, labelSize, name, _TRUNCATE);
        return true;
    } __except (EXCEPTION_EXECUTE_HANDLER) {
        return false;
    }
}

std::string BuildGeneralStatSnapshot(PlayerCharacter* player) {
    std::string snapshot = "STATE GENERALSTATS BEGIN\n";
    if (player) {
        // StatsMenu populates every ID from 0 through 42 in this order.
        for (unsigned index = 0; index < 43; ++index) {
            char name[512]{};
            int value = 0;
            if (!ReadGeneralStat(index, name, sizeof(name), value)) continue;
            snapshot += "STATE GENERALSTAT " + std::to_string(index) + ' '
                    + std::to_string(value) + ' ' + Base64Encode(name) + '\n';
        }
    }
    snapshot += "STATE GENERALSTATS END\n";
    return snapshot;
}

std::atomic<UInt32> g_pendingPerkDetail{0};
std::unordered_map<UInt32, std::string> g_perkDescriptions;
std::string g_perkDetailPacket;

void ResetPerkDetails() {
    g_pendingPerkDetail.store(0);
    g_perkDescriptions.clear();
    g_perkDetailPacket.clear();
}

bool ReadPerkDescription(BGSPerk* perk, char* output, size_t size) {
    __try {
        const char* text = perk->description.Get(nullptr, 0x43534544);
        strncpy_s(output, size, text ? text : "", _TRUNCATE);
        return true;
    } __except(EXCEPTION_EXECUTE_HANDLER) { return false; }
}

std::string BuildPerkSnapshot(PlayerCharacter* player) {
    std::string snapshot = "STATE PERKS BEGIN\n";
    auto** dataHandlerPointer = reinterpret_cast<DataHandler**>(0x011C3F2C);
    DataHandler* dataHandler = dataHandlerPointer == nullptr ? nullptr : *dataHandlerPointer;
    if (!player) ResetPerkDetails();
    UInt32 requested = g_pendingPerkDetail.exchange(0);
    bool foundDetail = false;
    if (player != nullptr && dataHandler != nullptr) {
        for (auto perkIter = dataHandler->perkList.Begin(); !perkIter.End(); ++perkIter) {
            BGSPerk* perk = perkIter.Get();
            if (perk == nullptr) continue;
            const UInt8 rank = player->GetPerkRank(perk, false);
            if (rank == 0) continue;
            snapshot += "STATE PERK " + std::to_string(perk->refID) + ' ';
            snapshot += std::to_string(static_cast<unsigned int>(rank));
            snapshot += ' ';
            snapshot += Base64Encode(FormDisplayName(perk));
            snapshot += '\n';
            if (requested && perk->refID == requested) {
                foundDetail = true;
                auto cached = g_perkDescriptions.find(requested);
                if (cached == g_perkDescriptions.end()) {
                    char text[16384]{};
                    bool read = ReadPerkDescription(perk, text, sizeof(text));
                    cached = g_perkDescriptions.emplace(requested,
                            read ? GameTextToUtf8(text) : "Description unavailable.").first;
                }
                std::string encoded = Base64Encode(cached->second);
                g_perkDetailPacket = "STATE PERKDETAIL " + std::to_string(requested)
                        + " " + (encoded.empty() ? "-" : encoded) + '\n';
            }
        }
    }
    snapshot += "STATE PERKS END\n";
    if (requested && !foundDetail)
        g_perkDetailPacket = "STATE PERKDETAIL " + std::to_string(requested) + " -\n";
    return snapshot + g_perkDetailPacket;
}

void ResetNoteScan() {
    g_noteScanCandidates.clear();
    g_noteScanIndex = 0;
    g_noteScanActive = false;
    g_nextNoteSnapshotAt = 0;
}

std::string BuildNoteSnapshot(PlayerCharacter* player) {
    std::string snapshot;
    const ULONGLONG now = GetTickCount64();
    const bool requested = g_notesRequested.load(std::memory_order_relaxed);
    if (!requested || player == nullptr) {
        if (g_noteScanActive || !g_noteScanCandidates.empty()) {
            ResetNoteScan();
        }
        snapshot += "STATE NOTES BEGIN\nSTATE NOTES END\nSTATE NOTESPROGRESS 0 0 0\n";
    } else {
        bool scanStarted = false;
        if (now >= g_notesReadyAt && !g_noteScanActive && now >= g_nextNoteSnapshotAt) {
            g_noteScanCandidates.clear();
            // Read the exact acquired-note list used by the Pip-Boy Misc tab.
            // Notes are tracked separately from ExtraContainerChanges inventory.
            g_noteScanCandidates = GetPlayerPipBoyNotes(player);
            char noteCandidateLog[128]{};
            std::snprintf(noteCandidateLog, sizeof(noteCandidateLog),
                    "FNVDS Pip-Boy player note list count=%u",
                    static_cast<unsigned int>(g_noteScanCandidates.size()));
            WriteLog(noteCandidateLog);
            g_noteScanIndex = 0;
            g_noteScanActive = !g_noteScanCandidates.empty();
            scanStarted = true;
            if (!g_noteScanActive) g_nextNoteSnapshotAt = now + 3000;
        }
        if (scanStarted) snapshot += "STATE NOTES BEGIN\n";

        // Read more notes per snapshot to reduce initial-load time. The app can
        // show each batch while later entries are still being prepared.
        constexpr size_t kNotesPerGameLoop = 32;
        size_t processedThisLoop = 0;
        while (g_noteScanActive && g_noteScanIndex < g_noteScanCandidates.size()
                && processedThisLoop < kNotesPerGameLoop) {
            BGSNote* note = g_noteScanCandidates[g_noteScanIndex++];
            ++processedThisLoop;
            if (note == nullptr) continue;
            const unsigned int noteType = static_cast<unsigned int>(note->unk07C);
            const bool audioNote = noteType == 0 || noteType == 3;
            const char* noteText = nullptr;
            if (!audioNote && note->noteText != nullptr) {
                if (noteType == 1) noteText = note->noteText->Get(note, 'MANT');
            }
            const std::string content = GameTextToUtf8(noteText == nullptr ? "" : noteText);
            if (content.empty()) {
                static std::unordered_set<UInt32> loggedEmptyNoteBodies;
                if (loggedEmptyNoteBodies.insert(note->refID).second) {
                    char noteTextLog[128]{};
                    std::snprintf(noteTextLog, sizeof(noteTextLog),
                            "FNVDS note body empty form=%08X type=%u component=%u",
                            note->refID, noteType, note->noteText == nullptr ? 0u : 1u);
                    WriteLog(noteTextLog);
                }
            }
            snapshot += "STATE NOTE ";
            snapshot += std::to_string(note->refID);
            snapshot += ' ';
            snapshot += note->read ? "1" : "0";
            snapshot += ' ';
            snapshot += std::to_string(noteType);
            snapshot += ' ';
            snapshot += Base64Encode(FormDisplayName(note));
            snapshot += ' ';
            snapshot += Base64Encode(content);
            snapshot += '\n';
        }

        bool scanCompleted = false;
        if (g_noteScanActive && g_noteScanIndex >= g_noteScanCandidates.size()) {
            g_noteScanActive = false;
            g_nextNoteSnapshotAt = now + 3000;
            scanCompleted = true;
        }
        if (scanCompleted || (scanStarted && !g_noteScanActive)) {
            snapshot += "STATE NOTES END\n";
        }
        snapshot += "STATE NOTESPROGRESS ";
        snapshot += std::to_string(g_noteScanIndex);
        snapshot += ' ';
        snapshot += std::to_string(g_noteScanCandidates.size());
        snapshot += g_noteScanActive ? " 1\n" : " 0\n";
    }
    snapshot += "STATE NOTEAUDIO ";
    snapshot += std::to_string(g_playingNoteFormId);
    snapshot += g_playingNoteFormId == 0 ? " 0 0 0\n" : " 1 ";
    if (g_playingNoteFormId != 0) {
        snapshot += std::to_string(g_notePlaybackDurationMs);
        snapshot += ' ';
        snapshot += std::to_string(now >= g_notePlaybackStartedAt
                ? now - g_notePlaybackStartedAt : 0);
        snapshot += '\n';
    }
    snapshot += g_notePlaybackError.exchange(false, std::memory_order_relaxed)
            ? "STATE NOTEAUDIOERROR 1\n" : "STATE NOTEAUDIOERROR 0\n";
    return snapshot;
}

struct ReputationQuery {
    Script* famePresent = nullptr;
    Script* infamyPresent = nullptr;
    Script* standingRanks[3][6]{};
    Script* mixedFameFavored = nullptr;
};

std::unordered_map<UInt32, ReputationQuery> g_reputationQueries;

Script* CompileReputationExpression(UInt32 reputationFormId, const char* expression,
                                    unsigned int axis, unsigned int threshold = 0) {
    if (g_scriptInterface == nullptr || g_scriptInterface->CompileExpression == nullptr) {
        return nullptr;
    }
    char source[128]{};
    if (threshold == 0) {
        std::snprintf(source, sizeof(source), "%s %08X %u > 0",
                      expression, reputationFormId, axis);
    } else {
        std::snprintf(source, sizeof(source), "%s %08X %u == %u",
                      expression, reputationFormId, axis, threshold);
    }
    return g_scriptInterface->CompileExpression(source);
}

bool EvaluateReputationCondition(Script* expression, PlayerCharacter* player) {
    if (expression == nullptr || player == nullptr || g_scriptInterface == nullptr
            || g_scriptInterface->CallFunction == nullptr) {
        return false;
    }
    alignas(NVSEArrayVarInterface::Element)
            unsigned char resultStorage[sizeof(NVSEArrayVarInterface::Element)]{};
    auto* result = new (resultStorage) NVSEArrayVarInterface::Element();
    // Reputation condition expressions return numeric booleans. Avoid the
    // SDK Element destructor here because it links FormHeap_Free into plugins.
    return g_scriptInterface->CallFunction(expression, player, nullptr, result, 0)
            && result->Bool();
}

ReputationQuery& GetReputationQuery(UInt32 reputationFormId) {
    auto [queryIter, inserted] = g_reputationQueries.try_emplace(reputationFormId);
    if (inserted) {
        ReputationQuery& query = queryIter->second;
        query.famePresent = CompileReputationExpression(
                reputationFormId, "GetReputation", 1);
        query.infamyPresent = CompileReputationExpression(
                reputationFormId, "GetReputation", 0);
        if (query.famePresent == nullptr || query.infamyPresent == nullptr) {
            char message[128]{};
            std::snprintf(message, sizeof(message),
                          "FNVDS could not compile reputation queries for %08X.",
                          reputationFormId);
            WriteLog(message);
        }
    }
    return queryIter->second;
}

int GetReputationRank(ReputationQuery& query, UInt32 reputationFormId,
                      PlayerCharacter* player, unsigned int axis) {
    if (axis > 2) return 1;
    Script** rankQueries = query.standingRanks[axis];
    if (rankQueries[0] == nullptr) {
        for (unsigned int rank = 1; rank <= 6; ++rank) {
            rankQueries[rank - 1] = CompileReputationExpression(
                    reputationFormId, "GetReputationThreshold", axis, rank);
        }
    }
    for (int rank = 6; rank >= 2; --rank) {
        if (EvaluateReputationCondition(rankQueries[rank - 1], player)) return rank;
    }
    return 1;
}

bool DoesFameOutweighInfamy(ReputationQuery& query, UInt32 reputationFormId,
                            PlayerCharacter* player) {
    if (query.mixedFameFavored == nullptr && g_scriptInterface != nullptr
            && g_scriptInterface->CompileExpression != nullptr) {
        char expression[128]{};
        std::snprintf(expression, sizeof(expression),
                      "GetReputation %08X 1 > GetReputation %08X 0",
                      reputationFormId, reputationFormId);
        query.mixedFameFavored = g_scriptInterface->CompileExpression(expression);
    }
    return EvaluateReputationCondition(query.mixedFameFavored, player);
}

int GetCombinedReputationTitle(ReputationQuery& query, UInt32 reputationFormId,
                               PlayerCharacter* player, bool hasFame, bool hasInfamy) {
    if (!hasFame && !hasInfamy) return 0; // Neutral
    const int mixedRank = GetReputationRank(query, reputationFormId, player, 0);
    if (mixedRank >= 5) return 15; // Wild Child
    if (mixedRank == 4) return 10; // Unpredictable
    if (mixedRank == 3) return 5;  // Mixed
    if (mixedRank == 2) {
        return DoesFameOutweighInfamy(query, reputationFormId, player)
                ? 11 : 14; // Dark Hero / Soft-Hearted Devil
    }
    const int fameRank = GetReputationRank(query, reputationFormId, player, 1);
    if (fameRank >= 6) return 3; // Idolized
    if (fameRank == 5) return 2; // Liked
    if (fameRank == 4) return 1; // Accepted
    if (fameRank == 3) return 7; // Good-Natured Rascal
    if (fameRank == 2) return 6; // Smiling Troublemaker
    const int infamyRank = GetReputationRank(query, reputationFormId, player, 2);
    if (infamyRank >= 6) return 12; // Vilified
    if (infamyRank == 5) return 8;  // Hated
    if (infamyRank == 4) return 4;  // Shunned
    if (infamyRank == 3) return 13; // Merciful Thug
    if (infamyRank == 2) return 9;  // Sneering Punk
    return 0;
}

int GetKarmaLevel(float karma) {
    if (!std::isfinite(karma)) return -1;
    if (karma >= 750.0f) return 4; // Very Good
    if (karma >= 250.0f) return 3; // Good
    if (karma > -250.0f) return 2; // Neutral
    if (karma > -750.0f) return 1; // Evil
    return 0; // Very Evil
}

std::string BuildReputationSnapshot(PlayerCharacter* player, DataHandler* dataHandler) {
    int karmaLevel = player == nullptr ? -1
            : GetKarmaLevel(player->avOwner.Fn_03(eActorVal_Karma));
    std::string snapshot = "STATE KARMA " + std::to_string(karmaLevel) + "\n";
    snapshot += "STATE REPUTATIONS BEGIN\n";
    if (player != nullptr && dataHandler != nullptr && g_scriptInterface != nullptr) {
        std::unordered_set<UInt32> emittedReputations;
        for (auto iter = dataHandler->reputationList.Begin(); !iter.End(); ++iter) {
            TESReputation* reputation = iter.Get();
            if (reputation == nullptr || reputation->refID == 0
                    || !emittedReputations.insert(reputation->refID).second) {
                continue;
            }
            TESFullName* fullName = DYNAMIC_CAST(reputation, TESForm, TESFullName);
            const std::string factionName = fullName && fullName->name.m_data
                    ? GameTextToUtf8(fullName->name.m_data) : std::string();
            if (factionName.empty()) continue;
            ReputationQuery& query = GetReputationQuery(reputation->refID);
            const bool hasFame = EvaluateReputationCondition(query.famePresent, player);
            const bool hasInfamy = EvaluateReputationCondition(query.infamyPresent, player);
            // The Pip-Boy only reveals a faction after the player has earned
            // at least one point of fame or infamy with it.
            if (!hasFame && !hasInfamy) continue;
            const int title = GetCombinedReputationTitle(query,
                    reputation->refID, player, hasFame, hasInfamy);
            snapshot += "STATE REPUTATION ";
            snapshot += Base64Encode(factionName);
            snapshot += ' ';
            snapshot += std::to_string(title);
            snapshot += '\n';
        }
    }
    snapshot += "STATE REPUTATIONS END\n";
    return snapshot;
}

template <typename T>
bool ReadGameMemory(uintptr_t address, T& value) {
    SIZE_T bytesRead = 0;
    return ReadProcessMemory(GetCurrentProcess(), reinterpret_cast<const void*>(address),
                             &value, sizeof(T), &bytesRead) != FALSE
            && bytesRead == sizeof(T);
}

bool RefreshPipboyRadioListIfNeeded(PlayerCharacter* player, uintptr_t mapMenu) {
    if (player == nullptr || player->parentCell == nullptr || mapMenu == 0) return false;

    bool hasAvailableStation = false;
    unsigned int mapStationCount = 0;
    uintptr_t node = mapMenu + 0x194; // MapMenu::m_kMapMarkerListBox head
    for (unsigned int count = 0; count < 256 && node != 0; ++count) {
        uintptr_t listItem = 0;
        uintptr_t next = 0;
        if (!ReadGameMemory<uintptr_t>(node, listItem)
                || !ReadGameMemory<uintptr_t>(node + 4, next)) break;
        if (listItem != 0) {
            ++mapStationCount;
            unsigned char filtered = 1;
            uintptr_t stationRef = 0;
            if (ReadGameMemory<unsigned char>(listItem + 8, filtered)
                    && ReadGameMemory<uintptr_t>(listItem + 4, stationRef)
                    && !filtered && stationRef != 0) {
                hasAvailableStation = true;
                break;
            }
        }
        if (next == node) break;
        node = next;
    }

    // FalloutRadio_GetDiscoveredStationsList returns BSSimpleList at
    // 0x011DD59C. Its embedded tList head begins four bytes into the object.
    unsigned int discoveredStationCount = 0;
    bool discoveredListReadable = false;
    node = 0x011DD5A0;
    for (unsigned int count = 0; count < 256 && node != 0; ++count) {
        uintptr_t stationEntry = 0;
        uintptr_t next = 0;
        if (!ReadGameMemory<uintptr_t>(node, stationEntry)
                || !ReadGameMemory<uintptr_t>(node + 4, next)) break;
        discoveredListReadable = true;
        if (stationEntry == 0) break;
        ++discoveredStationCount;
        if (next == node) break;
        node = next;
    }
    if (hasAvailableStation && (!discoveredListReadable
            || mapStationCount == discoveredStationCount)) return false;

    // FalloutNV.exe 1.4.0.525 MapMenu::RefreshRadioList. The game's own
    // routine rebuilds the Pip-Boy list from its discovered-station registry
    // and applies its native radio filter; do not scan world references here.
    static uintptr_t lastMapMenu = 0;
    static ULONGLONG lastRefreshAt = 0;
    const ULONGLONG now = GetTickCount64();
    if (mapMenu == lastMapMenu && now - lastRefreshAt < 2000) return false;
    using RefreshRadioList = void(__thiscall*)(void*);
    reinterpret_cast<RefreshRadioList>(0x0079BEA0)(reinterpret_cast<void*>(mapMenu));
    lastMapMenu = mapMenu;
    lastRefreshAt = now;
    return true;
}

std::string BuildActiveEffectsSnapshot(PlayerCharacter* player) {
    // Match StatsMenu::UpdatePrimaryActorValueTile: compare integer current
    // and permanent values, rather than inferring changes from effect names.
    std::string snapshot="STATE SPECIALMOD";
    for (UInt32 index=0;index<7;++index) {
        int direction=0;
        if(player && g_gameReady.load()) {
            UInt32 av=eActorVal_Strength+index;
            float current=player->avOwner.Fn_03(av), permanent=player->avOwner.Fn_08(av);
            if(std::isfinite(current) && std::isfinite(permanent)) {
                int now=static_cast<int>(current), baseline=static_cast<int>(permanent);
                direction=now>baseline?1:now<baseline?-1:0;
            }
        }
        snapshot+=" "+std::to_string(direction);
    }
    snapshot+="\nSTATE ACTIVEEFFECTS BEGIN\n";
    if(!player || !g_gameReady.load()) return snapshot+"STATE ACTIVEEFFECTS END\n";
    struct Group {
        std::string name;
        std::vector<std::pair<UInt32,int>> modifiers;
        std::vector<std::string> named;
    };
    std::unordered_set<TESForm*> survivalSpells;
    const uintptr_t stageFunctions[4]={0x00592220,0x004018B0,0x00402030,0x00402760};
    const UInt32 stageValues[4]={eActorVal_RadLevel,eActorVal_Dehydration,eActorVal_Hunger,eActorVal_Sleepdeprevation};
    for(unsigned i=0;i<4;++i) {
        float level=player->avOwner.Fn_03(stageValues[i]);
        if(!std::isfinite(level)) continue;
        auto* stage=reinterpret_cast<TESForm* (__cdecl *)(float)>(stageFunctions[i])(level);
        TESForm* spell=nullptr;
        // All four stage records store their penalty spell at +0x1C.
        if(stage && ReadGameMemory<TESForm*>(reinterpret_cast<uintptr_t>(stage)+0x1C,spell) && spell)
            survivalSpells.insert(spell);
    }
    std::unordered_map<MagicItem*,Group> groups;
    EffectNode* effects=player->magicTarget.GetEffectList();
    unsigned visited=0;
    if(effects) for(auto it=effects->Begin();!it.End() && visited++<8192;++it) {
        ActiveEffect* active=it.Get();
        if(!active || !active->bApplied || !active->effectItem || !active->magicItem) continue;
        if(*(reinterpret_cast<const UInt8*>(active)+0x13)) continue;
        EffectItem* item=active->effectItem;EffectSetting* setting=item->setting;
        if(!setting || !std::isfinite(active->magnitude)) continue;
        using ItemPredicate=bool (__thiscall *)(EffectItem*);
        if(reinterpret_cast<ItemPredicate>(0x00404730)(item)) continue; // hidden usage-monitor effect
        bool named=(setting->effectFlags&0x2000)!=0;
        if(setting->archtype==EffectSetting::kArchType_Script && !named) continue;
        TESForm* source=DYNAMIC_CAST(active->magicItem,MagicItem,TESForm);
        bool stage=source && survivalSpells.contains(source);
        bool usesAV=reinterpret_cast<ItemPredicate>(0x00403EE0)(item);
        UInt32 av=usesAV?reinterpret_cast<UInt32 (__thiscall *)(EffectItem*)>(0x00403EA0)(item):UInt32(-1);
        const char* abbreviation=av<77?reinterpret_cast<const char* (__cdecl *)(UInt32)>(0x0066EB00)(av):nullptr;
        bool persistentType=active->spellType==1 || active->spellType==6 || active->spellType==10
                || (active->spellType==4 && (active->unk18&0x80));
        bool numeric=usesAV && abbreviation && *abbreviation
                && (stage || active->duration!=0 || persistentType);
        if(!numeric && !named) continue;
        Group& group=groups[active->magicItem];
        if(group.name.empty()) {
            group.name=source?FormDisplayName(source):std::string();
            if(group.name.empty() && active->magicItem->name.m_data)
                group.name=GameTextToUtf8(active->magicItem->name.m_data);
            if(group.name.empty()) group.name=FormDisplayName(setting);
        }
        if(named) {
            std::string text=FormDisplayName(setting);
            if(!text.empty() && std::find(group.named.begin(),group.named.end(),text)==group.named.end())
                group.named.push_back(std::move(text));
        } else if(numeric) {
            float magnitude=active->magnitude;
            if((setting->effectFlags&4) && magnitude>0) magnitude=-magnitude;
            if(std::abs(magnitude)>100000000) continue;
            int value=static_cast<int>(magnitude);
            auto found=std::find_if(group.modifiers.begin(),group.modifiers.end(),[&](const auto& entry){return entry.first==av;});
            if(found==group.modifiers.end())group.modifiers.emplace_back(av,value);
            else found->second+=value;
        }
    }
    std::vector<std::pair<std::string,std::string>> rows;
    for(auto& [source,group]:groups) {
        std::string detail;
        auto append=[&](const std::string& value){if(!detail.empty())detail+=", ";detail+=value;};
        for(const auto& value:group.named)append(value);
        for(const auto& [av,value]:group.modifiers) if(value) {
            const char* name=reinterpret_cast<const char* (__cdecl *)(UInt32)>(0x0066EB00)(av);
            if(name && *name) {char text[128]{};std::snprintf(text,sizeof(text),"%s %+d",name,value);append(GameTextToUtf8(text));}
        }
        if(!group.name.empty() && !detail.empty())rows.emplace_back(group.name,std::move(detail));
    }
    std::sort(rows.begin(),rows.end());
    for(const auto& [name,detail]:rows)
        snapshot+="STATE ACTIVEEFFECT "+Base64Encode(name)+" "+Base64Encode(detail)+"\n";
    snapshot+="STATE ACTIVEEFFECTS END\n";
    static size_t lastCount=static_cast<size_t>(-1);
    if(lastCount!=rows.size()) {lastCount=rows.size();WriteLog(("FNVDS active effects groups="+std::to_string(lastCount)).c_str());}
    return snapshot;
}

bool IsPlayingRadioStation(UInt32 formId) {
    using IsRadioEnabledFn = bool (__cdecl *)();
    if (!reinterpret_cast<IsRadioEnabledFn>(0x00798440)()) return false;
    uintptr_t entry = 0;
    uintptr_t station = 0;
    UInt32 currentId = 0;
    return ReadGameMemory<uintptr_t>(0x011DD42C, entry) && entry != 0
            && ReadGameMemory<uintptr_t>(entry, station) && station != 0
            && ReadGameMemory<UInt32>(station + 0x0C, currentId)
            && currentId == formId;
}

std::string BuildRadioSnapshot(PlayerCharacter* player) {
    std::string snapshot;
    g_pipboyRadioStations.clear();
    snapshot += "STATE RADIO NONE\n";
    snapshot += "STATE RADIOSTATIONS BEGIN\n";

    // FalloutNV.exe 1.4.0.525 stores its active Pip-Boy radio state here.
    // RadioStationState begins with the station reference pointer.
    uintptr_t radioEntry = 0;
    uintptr_t currentRadioRef = 0;
    uintptr_t currentBaseForm = 0;
    using IsRadioEnabledFn = bool (__cdecl *)();
    if (reinterpret_cast<IsRadioEnabledFn>(0x00798440)()
            && ReadGameMemory<uintptr_t>(0x011DD42C, radioEntry) && radioEntry != 0
            && ReadGameMemory<uintptr_t>(radioEntry, currentRadioRef) && currentRadioRef != 0
            && ReadGameMemory<uintptr_t>(currentRadioRef + 0x20, currentBaseForm)
            && currentBaseForm != 0) {
        std::string name = FormDisplayName(reinterpret_cast<TESForm*>(currentBaseForm));
        if (!name.empty()) snapshot = "STATE RADIO " + Base64Encode(name) + "\n";
    }

    // Use only the live Pip-Boy list. It applies the game's discovered and
    // in-range filtering; scanning cell references directly includes stations
    // the Pip-Boy does not currently list.
    std::unordered_set<std::string> seenStationNames;
    uintptr_t mapMenu = 0;
    if (ReadGameMemory<uintptr_t>(0x011DA368, mapMenu) && mapMenu != 0) {
        RefreshPipboyRadioListIfNeeded(player, mapMenu);
        uintptr_t node = mapMenu + 0x194; // MapMenu + radioRefList + tList head
        for (unsigned int count = 0; count < 256 && node != 0; ++count) {
            uintptr_t listItem = 0;
            uintptr_t next = 0;
            if (!ReadGameMemory<uintptr_t>(node, listItem)
                    || !ReadGameMemory<uintptr_t>(node + 4, next)) break;
            if (listItem != 0) {
                uintptr_t stationRef = 0;
                unsigned char filtered = 1;
                if (ReadGameMemory<uintptr_t>(listItem + 4, stationRef)
                        && ReadGameMemory<unsigned char>(listItem + 8, filtered)
                        && !filtered && stationRef != 0) {
                    uintptr_t baseForm = 0;
                    if (ReadGameMemory<uintptr_t>(stationRef + 0x20, baseForm)
                            && baseForm != 0) {
                        const std::string name = FormDisplayName(
                                reinterpret_cast<TESForm*>(baseForm));
                        if (!name.empty() && seenStationNames.insert(name).second) {
                            auto* stationReference = reinterpret_cast<TESObjectREFR*>(stationRef);
                            const UInt32 stationRefId = stationReference->refID;
                            if (stationRefId != 0) {
                                g_pipboyRadioStations[stationRefId] = stationReference;
                            }
                            snapshot += "STATE RADIOSTATION ";
                            snapshot += std::to_string(stationRefId);
                            snapshot += ' ';
                            snapshot += Base64Encode(name);
                            snapshot += '\n';
                        }
                    }
                }
            }
            if (next == node) break;
            node = next;
        }
    }
    snapshot += "STATE RADIOSTATIONS END\n";
    return snapshot;
}

bool NormalizeWorldMapPoint(const TESWorldSpace* world, float worldX, float worldY,
                            float& normalizedX, float& normalizedY) {
    if (world == nullptr || !std::isfinite(worldX) || !std::isfinite(worldY)) return false;
    float west = static_cast<float>(world->mapData.cellNWCoordinates.X);
    float north = static_cast<float>(world->mapData.cellNWCoordinates.Y) + 1.0f;
    float east = static_cast<float>(world->mapData.cellSECoordinates.X) + 1.0f;
    float south = static_cast<float>(world->mapData.cellSECoordinates.Y);
    if (east <= west || north <= south) {
        west = world->min.X / 4096.0f;
        north = world->max.Y / 4096.0f;
        east = world->max.X / 4096.0f;
        south = world->min.Y / 4096.0f;
    }
    const float width = east - west;
    const float height = north - south;
    if (width <= 0.0f || height <= 0.0f) return false;
    const double mapInset = *reinterpret_cast<const double*>(0x01075008);
    const double mapScale = *reinterpret_cast<const double*>(0x01075010);
    normalizedX = static_cast<float>(((worldX / 4096.0f - west) / width)
                                    * mapScale + mapInset);
    normalizedY = static_cast<float>(((north - worldY / 4096.0f) / height)
                                    * mapScale + mapInset);
    return std::isfinite(normalizedX) && std::isfinite(normalizedY);
}

TESWorldSpace* MapReferenceWorld(TESObjectREFR* reference) {
    if (reference == nullptr) return nullptr;
    // The Pip-Boy uses GetParentWorldSpace, which consults the persistent
    // reference cell before falling back to the ordinary parent cell.
    using GetWorldFn = TESWorldSpace* (__thiscall *)(TESObjectREFR*);
    return reinterpret_cast<GetWorldFn>(0x00575D70)(reference);
}

TESWorldSpace* DisplayMapWorld(TESWorldSpace* world) {
    std::array<TESWorldSpace*, 32> visited{};
    for (size_t depth = 0; world && depth < visited.size(); ++depth) {
        if (std::find(visited.begin(), visited.begin() + depth, world) != visited.begin() + depth) return nullptr;
        visited[depth] = world;
        // Parent-use bit 2 is the world-map inheritance flag (TESWorldSpace::GetWorldMapData).
        if (!world->parent || !(world->parentFlags & (1u << 2))) return world;
        world = world->parent;
    }
    return nullptr;
}

bool NormalizeWorldMapReference(TESWorldSpace* displayWorld, TESObjectREFR* reference,
                                float& x, float& y) {
    TESWorldSpace* sourceWorld = MapReferenceWorld(reference);
    if (!displayWorld || !sourceWorld || DisplayMapWorld(sourceWorld) != displayWorld) return false;
    NiPoint3 point;
    point.x = reference->posX; point.y = reference->posY; point.z = reference->posZ;
    if (sourceWorld != displayWorld) {
        // The Pip-Boy applies this native forward transform before projecting a child-world reference.
        ThisStdCall<void>(0x00588E40, sourceWorld, &point, false);
    }
    return NormalizeWorldMapPoint(displayWorld, point.x, point.y, x, y);
}

bool HasCustomMapMarker(PlayerCharacter* player) {
    if (!player) return false;
    __try {
        using GetFn = TESObjectREFR* (__thiscall *)(PlayerCharacter*);
        return reinterpret_cast<GetFn>(0x00798400)(player) != nullptr;
    } __except(EXCEPTION_EXECUTE_HANDLER) { return false; }
}

bool RemoveCustomMapMarker(PlayerCharacter* player) {
    if (!HasCustomMapMarker(player)) return false;
    __try {
        using ClearFn = void (__thiscall *)(PlayerCharacter*);
        reinterpret_cast<ClearFn>(0x00952F90)(player);
        return true;
    } __except(EXCEPTION_EXECUTE_HANDLER) { return false; }
}

bool InvokePlaceCustomMarker(PlayerCharacter* player, NiPoint3 point, TESForm* space) {
    __try {
        using PlaceFn = void (__thiscall *)(PlayerCharacter*, NiPoint3, TESForm*);
        reinterpret_cast<PlaceFn>(0x00952E60)(player, point, space);
        return true;
    } __except(EXCEPTION_EXECUTE_HANDLER) { return false; }
}

void PlaceCustomMapMarker(PlayerCharacter* player, const PendingItemCommand& command) {
    if (!player || !player->parentCell || !std::isfinite(command.markerX)
            || !std::isfinite(command.markerY) || command.markerX < 0 || command.markerX > 1
            || command.markerY < 0 || command.markerY > 1) return;
    TESObjectCELL* cell = player->parentCell;
    if (cell->cellState != TESObjectCELL::kCellLoadState_Attached) return;
    NiPoint3 point{};
    TESForm* space = nullptr;
    if (command.type == ItemCommandType::PlaceLocalMarker) {
        bool inside = cell->IsInterior();
        UInt32 area = inside ? cell->refID : cell->worldSpace ? cell->worldSpace->refID : 0;
        if (!area || area != command.formId) return;
        float west, north;
        {
            std::lock_guard<std::mutex> lock(LocalMaps::mutex);
            const auto& shown = LocalMaps::published;
            const auto& current = LocalMaps::building;
            if (!LocalMaps::requested.load() || !shown.generation || shown.cellId != area
                    || current.cellId != area || shown.centerX != static_cast<SInt32>(command.hotkeySlot)
                    || shown.centerY != static_cast<SInt32>(command.modFormId)
                    || current.centerX != shown.centerX || current.centerY != shown.centerY) return;
            west = LocalMaps::west; north = LocalMaps::north;
        }
        float px = west + command.markerX * LocalMaps::kWorldSpan;
        float py = north - command.markerY * LocalMaps::kWorldSpan;
        float angle = inside ? reinterpret_cast<float (__thiscall *)(TESObjectCELL*)>(0x00555AD0)(cell) : 0;
        point.x = px * std::cos(angle) - py * std::sin(angle);
        point.y = px * std::sin(angle) + py * std::cos(angle);
        point.z = player->posZ;
        space = inside ? static_cast<TESForm*>(cell) : static_cast<TESForm*>(cell->worldSpace);
    } else {
        TESWorldSpace* source = !cell->IsInterior() ? cell->worldSpace : nullptr;
        if (cell->IsInterior()) {
            TESObjectREFR* entrance = nullptr;
            if (ReadGameMemory<TESObjectREFR*>(reinterpret_cast<uintptr_t>(player) + 0x604, entrance)
                    && entrance && entrance->parentCell && !entrance->parentCell->IsInterior())
                source = MapReferenceWorld(entrance);
        }
        TESWorldSpace* world = DisplayMapWorld(source);
        if (!world || world->refID != command.formId) return;
        float x0, y0, x1, y1;
        if (!NormalizeWorldMapPoint(world, 0, 0, x0, y0)
                || !NormalizeWorldMapPoint(world, 4096, 4096, x1, y1)
                || std::abs(x1-x0) < 1e-8f || std::abs(y1-y0) < 1e-8f) return;
        // Invert the same world-map projection used to publish marker coordinates.
        point.x = (command.markerX - x0) * 4096 / (x1 - x0);
        point.y = (command.markerY - y0) * 4096 / (y1 - y0);
        point.z = 0;
        space = world;
    }
    if (!space || !std::isfinite(point.x) || !std::isfinite(point.y)) return;
    bool placed = InvokePlaceCustomMarker(player, point, space);
    WriteLog(placed ? "FNVDS custom marker placed" : "FNVDS custom marker native call failed");
}

std::string BuildLocalMarkerSnapshot(PlayerCharacter* player) {
    if(!LocalMaps::requested.load() || !g_gameReady.load() || !player || !player->parentCell
            || player->parentCell->cellState!=TESObjectCELL::kCellLoadState_Attached) return {};
    UInt32 area;int cx,cy;float west,north;
    {
        std::lock_guard<std::mutex> lock(LocalMaps::mutex);
        area=LocalMaps::building.cellId;cx=LocalMaps::building.centerX;cy=LocalMaps::building.centerY;
        west=LocalMaps::west;north=LocalMaps::north;
    }
    TESObjectCELL* currentCell=player->parentCell;
    bool interior=currentCell->IsInterior();
    UInt32 expected=interior?currentCell->refID:currentCell->worldSpace->refID;
    if(!area || area!=expected) return {};
    float angle=interior?reinterpret_cast<float (__thiscall *)(TESObjectCELL*)>(0x00555AD0)(currentCell):0;
    auto referenceCell=[](TESObjectREFR* reference)->TESObjectCELL* {
        return reference?reinterpret_cast<TESObjectCELL* (__thiscall *)(TESObjectREFR*)>(0x008D6F30)(reference):nullptr;
    };
    auto onMap=[&](TESObjectREFR* reference) {
        if(!reference || reference==player || (reference->flags&(0x20|0x800))) return false;
        return interior?referenceCell(reference)==currentCell:MapReferenceWorld(reference)==currentCell->worldSpace;
    };
    auto project=[&](TESObjectREFR* reference,float& x,float& y) {
        float px=reference->posX,py=reference->posY;
        if(interior) {float rx=px*std::cos(angle)+py*std::sin(angle);py=py*std::cos(angle)-px*std::sin(angle);px=rx;}
        x=(px-west)/LocalMaps::kWorldSpan;y=(north-py)/LocalMaps::kWorldSpan;
        return std::isfinite(x)&&std::isfinite(y);
    };
    std::string snapshot="STATE LOCALMARKERS BEGIN "+std::to_string(area)+" "+std::to_string(cx)+" "+std::to_string(cy)+"\n";
    auto emit=[&](char kind,TESObjectREFR* ref,const std::string& label) {
        float x=0,y=0;if(!onMap(ref)||!project(ref,x,y)) return false;
        if(kind=='D' && (x<0 || x>1 || y<0 || y>1)) return false;
        char line[144]{};std::snprintf(line,sizeof(line),"STATE LOCALMARKER %c %.6f %.6f %u ",kind,x,y,ref->refID);
        snapshot+=line;snapshot+=Base64Encode(label);snapshot+='\n';return true;
    };
    using CurrentTargets=QuestObjectiveTargets* (__thiscall *)(PlayerCharacter*);
    QuestObjectiveTargets* targets=reinterpret_cast<CurrentTargets>(0x00952BA0)(player);
    std::unordered_set<UInt32> emittedQuestRefs;
    std::string questName=player->quest?FormDisplayName(player->quest):"Quest objective";
    if(targets) for(auto it=targets->Begin();!it.End();++it) {
        auto* objective=it.Get();if(!objective)continue;
        TESObjectREFR* chosen=nullptr;
        if(onMap(objective->target)) chosen=objective->target;
        else if(objective->teleportLinks.data) {
            // Prefer the route's first transition in the displayed cell/world.
            for(UInt32 i=0;i<(std::min)(objective->teleportLinks.size,UInt32(256));++i) {
                auto* door=objective->teleportLinks.data[i].door;
                if(onMap(door)) {chosen=door;break;}
            }
        }
        if(chosen && emittedQuestRefs.insert(chosen->refID).second) emit('Q',chosen,questName);
    }
    using PlacedMarker=TESObjectREFR* (__thiscall *)(PlayerCharacter*);
    TESObjectREFR* custom=reinterpret_cast<PlacedMarker>(0x00798400)(player);
    emit('C',custom,"Custom marker");

    // Cache serialized door points, not live reference pointers, between scans.
    static std::string doors;
    static UInt32 previousArea=0;static int previousX=0,previousY=0;
    static ULONGLONG lastScan=0;
    ULONGLONG now=GetTickCount64();
    if(area!=previousArea || cx!=previousX || cy!=previousY || now-lastScan>=1000) {
        previousArea=area;previousX=cx;previousY=cy;lastScan=now;
        size_t start=snapshot.size();std::unordered_set<UInt32> seen;
        auto scan=[&](TESObjectCELL* cell) {
            if(!cell || (cell->cellState!=TESObjectCELL::kCellLoadState_Attached
                    && cell->cellState!=TESObjectCELL::kCellLoadState_Loaded)) return;
            for(auto it=cell->objectList.Begin();!it.End();++it) {
                TESObjectREFR* door=it.Get();
                if(!door || !door->baseForm || door->baseForm->typeID!=kFormType_TESObjectDOOR
                        || !seen.insert(door->refID).second || !onMap(door)) continue;
                auto* teleport=static_cast<ExtraTeleport*>(door->extraDataList.GetByType(kExtraData_Teleport));
                if(!teleport || !teleport->data || !teleport->data->linkedDoor) continue;
                TESObjectCELL* destination=referenceCell(teleport->data->linkedDoor);
                if(destination==referenceCell(door)) continue;
                std::string name=destination?FormDisplayName(destination):std::string();
                if(name.empty())name=FormDisplayName(door->baseForm);
                if(name.empty())name="Door";
                emit('D',door,name);
            }
        };
        if(interior)scan(currentCell);
        else {
            using GetCell=TESObjectCELL* (__thiscall *)(TESWorldSpace*,int,int);
            for(int y=cy-LocalMaps::kRadius;y<=cy+LocalMaps::kRadius;++y)
                for(int x=cx-LocalMaps::kRadius;x<=cx+LocalMaps::kRadius;++x)
                scan(reinterpret_cast<GetCell>(0x005875A0)(currentCell->worldSpace,x,y));
            scan(currentCell->worldSpace->cell);
        }
        doors=snapshot.substr(start);
    } else snapshot+=doors;
    snapshot+="STATE LOCALMARKERS END\n";
    return snapshot;
}

struct QuestMapRoute {
    UInt32 endpointRefId;
    std::vector<std::pair<float, float>> points;
};

std::vector<QuestMapRoute> FindQuestMapRoutes(PlayerCharacter* player, TESWorldSpace* world,
                                             bool& targetsAvailable,
                                             QuestObjectiveTargets* suppliedTargets = nullptr) {
    std::vector<QuestMapRoute> routes;
    targetsAvailable = false;
    if (!g_gameReady.load(std::memory_order_relaxed)) {
        targetsAvailable = true;
        return routes;
    }
    if (player == nullptr || world == nullptr) return routes;
    if (player->quest == nullptr && suppliedTargets == nullptr) {
        targetsAvailable = true;
        return routes;
    }
    using GetCurrentQuestTargetsFn = QuestObjectiveTargets* (__thiscall *)(PlayerCharacter*);
    auto getTargets = reinterpret_cast<GetCurrentQuestTargetsFn>(0x00952BA0);
    QuestObjectiveTargets* targets = suppliedTargets ? suppliedTargets : getTargets(player);
    if (targets == nullptr) return routes;
    targetsAvailable = true;
    std::unordered_set<UInt32> emittedEndpoints;
    for (auto iter = targets->Begin(); !iter.End(); ++iter) {
        BGSQuestObjective::Target* objectiveTarget = iter.Get();
        if (objectiveTarget == nullptr) continue;
        std::vector<std::pair<float, float>> candidate;
        UInt32 candidateEndpoint = 0;
        auto& links = objectiveTarget->teleportLinks;
        for (UInt32 i = 0; links.data != nullptr && i < links.size; ++i) {
            TESObjectREFR* door = links.data[i].door;
            float x = 0.0f;
            float y = 0.0f;
            if (door != nullptr && door != player
                    && NormalizeWorldMapReference(world, door, x, y)) {
                candidate.emplace_back(x, y);
                candidateEndpoint = door->refID;
            }
        }
        TESObjectREFR* target = objectiveTarget->target;
        TESWorldSpace* targetWorld = MapReferenceWorld(target);
        char detail[192]{};
        std::snprintf(detail, sizeof(detail), " quest=%08X target=%08X world=%08X directCell=%08X links=%u",
                      player->quest ? player->quest->refID : 0,
                      target ? target->refID : 0, targetWorld ? targetWorld->refID : 0,
                      target && target->parentCell ? target->parentCell->refID : 0, links.size);
        static std::unordered_map<UInt32, std::string> lastQuestDiagnostics;
        std::string& previous = lastQuestDiagnostics[target ? target->refID : 0];
        if (previous != detail) {
            WriteLog((std::string("FNVDS destination lookup") + detail).c_str());
            previous = detail;
        }
        float targetX = 0.0f;
        float targetY = 0.0f;
        if (target != nullptr && target != player
                && NormalizeWorldMapReference(world, target, targetX, targetY)) {
            candidate.emplace_back(targetX, targetY);
            candidateEndpoint = target->refID;
        }
        // The engine supplies teleport doors in route order and the objective
        // reference last. Filter that chain to the active world map.
        if (!candidate.empty() && emittedEndpoints.insert(candidateEndpoint).second) {
            routes.push_back({candidateEndpoint, std::move(candidate)});
        }
    }
    return routes;
}

std::string BuildQuestViewResult(PlayerCharacter* player, TESWorldSpace* world) {
    if (!g_viewQuestPending) return g_viewQuestResult;
    g_viewQuestPending = false;
    g_viewQuestResult = "STATE QUESTVIEW " + std::to_string(g_viewQuestRequest)
            + " " + std::to_string(g_viewQuestForm) + " UNAVAILABLE\n";
    if (!player || !world) return g_viewQuestResult;
    TESQuest* quest = nullptr;
    for (auto iter = player->questObjectiveList.Begin(); !iter.End(); ++iter) {
        auto* objective = iter.Get();
        if (objective && objective->quest && objective->quest->refID == g_viewQuestForm
                && (objective->status & 3) == 1 && (objective->quest->flags & 0x23) == 0x21) {
            quest = objective->quest;
            break;
        }
    }
    if (!quest) return g_viewQuestResult;
    struct TemporaryTargets {
        QuestObjectiveTargets list{};
        ~TemporaryTargets() {
            auto* node = list.Head()->next;
            while (node) {
                auto* next = node->next;
                reinterpret_cast<void (__cdecl*)(void*)>(0x00401030)(node);
                node = next;
            }
        }
    } targets;
    // TESQuest's native lookup applies objective states and target conditions, and
    // updates door paths without changing PlayerCharacter's tracked quest/list.
    ThisStdCall<void>(0x0060F110, quest, &targets.list, &player->questObjectiveList);
    bool available = false;
    auto routes = FindQuestMapRoutes(player, world, available, &targets.list);
    if (!routes.empty()) {
        const auto& point = routes.front().points.back();
        char packet[192]{};
        std::snprintf(packet, sizeof(packet), "STATE QUESTVIEW %u %u OK %.6f %.6f\n",
                g_viewQuestRequest, g_viewQuestForm, point.first, point.second);
        g_viewQuestResult = packet;
    }
    return g_viewQuestResult;
}

bool FindCustomMapPoint(PlayerCharacter* player, TESWorldSpace* world,
                        float& normalizedX, float& normalizedY) {
    if (player == nullptr || world == nullptr) return false;
    // Runtime 1.4.0.525 PlayerCharacter::QPlayerMapMarker, also used by
    // MapMenu to display the player's placed pin. No optional command needed.
    using GetPlacedMarkerFn = TESObjectREFR* (__thiscall *)(PlayerCharacter*);
    auto getPlacedMarker = reinterpret_cast<GetPlacedMarkerFn>(0x00798400);
    TESObjectREFR* marker = getPlacedMarker(player);
    TESWorldSpace* markerWorld = MapReferenceWorld(marker);
    char detail[192]{};
    std::snprintf(detail, sizeof(detail),
                  "FNVDS custom lookup ref=%08X world=%08X directCell=%08X activeWorld=%08X",
                  marker ? marker->refID : 0, markerWorld ? markerWorld->refID : 0,
                  marker && marker->parentCell ? marker->parentCell->refID : 0, world->refID);
    static std::string lastCustomDiagnostic;
    if (lastCustomDiagnostic != detail) {
        WriteLog(detail);
        lastCustomDiagnostic = detail;
    }
    return NormalizeWorldMapReference(world, marker, normalizedX, normalizedY);
}

std::string BuildMapMarkerSnapshot(DataHandler* dataHandler, TESWorldSpace* world) {
    std::string snapshot = "STATE MARKERS BEGIN\n";
    g_fastTravelMarkers.clear();
    if (world == nullptr) {
        snapshot += "STATE MARKERS END\n";
        return snapshot;
    }

    std::unordered_set<UInt32> seenReferences;
    std::unordered_set<TESObjectCELL*> seenCells;
    UInt32 scannedCells = 0;
    UInt32 mapMarkerReferences = 0;
    UInt32 emittedMarkers = 0;
    auto scanCell = [&](TESObjectCELL* cell) {
        if (cell == nullptr || !seenCells.insert(cell).second) return;
        ++scannedCells;
        for (auto refIter = cell->objectList.Begin(); !refIter.End(); ++refIter) {
            TESObjectREFR* reference = refIter.Get();
            if (reference == nullptr || reference->baseForm == nullptr
                    || reference->baseForm->refID != 0x10
                    || !seenReferences.insert(reference->refID).second) {
                continue;
            }
            ++mapMarkerReferences;
            auto* marker = DYNAMIC_CAST(
                    reference->extraDataList.GetByType(kExtraData_MapMarker),
                    BSExtraData, ExtraMapMarker);
            if (marker == nullptr || marker->data == nullptr) continue;
            const UInt16 flags = marker->data->flags;
            const bool visible = (flags & ExtraMapMarker::kFlag_Visible) != 0;
            const bool canTravel = (flags & ExtraMapMarker::kFlag_CanTravel) != 0;
            const bool hidden = (flags & ExtraMapMarker::kFlag_Hidden) != 0;
            if ((!visible && !canTravel) || hidden) continue;
            float x = 0.0f;
            float y = 0.0f;
            if (!NormalizeWorldMapReference(world, reference, x, y)) continue;
            const char* markerName = marker != nullptr && marker->data != nullptr
                    ? marker->data->fullName.name.m_data : nullptr;
            std::string displayName = markerName != nullptr && markerName[0] != '\0'
                    ? GameTextToUtf8(markerName) : FormDisplayName(reference->baseForm);
            if (displayName.empty()) displayName = "Location";
            if (canTravel) g_fastTravelMarkers[reference->refID] = reference;
            char point[128]{};
            std::snprintf(point, sizeof(point), "STATE MARKER %u %u %.6f %.6f %u ",
                    static_cast<unsigned int>(marker->data->type),
                    canTravel ? 1u : 0u,
                    x, y, reference->refID);
            snapshot += point;
            snapshot += Base64Encode(displayName);
            snapshot += '\n';
            ++emittedMarkers;
        }
    };

    // Scan the displayed map and worlds that inherit it, including persistent marker cells.
    auto scanWorld = [&](TESWorldSpace* sourceWorld) {
        auto* cellMap = sourceWorld->cellMap;
        if (cellMap != nullptr && cellMap->m_buckets != nullptr
                && cellMap->m_numBuckets > 0 && cellMap->m_numBuckets < 1000000) {
            for (UInt32 bucket = 0; bucket < cellMap->m_numBuckets; ++bucket) {
                for (auto* entry = cellMap->m_buckets[bucket]; entry != nullptr; entry = entry->next) {
                    scanCell(entry->data);
                }
            }
        }
        scanCell(sourceWorld->cell);
    };
    scanWorld(world);
    if (dataHandler != nullptr) {
        for (auto iter = dataHandler->worldSpaceList.Begin(); !iter.End(); ++iter) {
            TESWorldSpace* child = iter.Get();
            if (child && child != world && DisplayMapWorld(child) == world) scanWorld(child);
        }
    }
    if (dataHandler != nullptr && dataHandler->cellArray.data != nullptr) {
        const UInt32 cellCount = dataHandler->cellArray.Length();
        for (UInt32 cellIndex = 0; cellIndex < cellCount; ++cellIndex) {
            TESObjectCELL* cell = dataHandler->cellArray.Get(cellIndex);
            if (cell != nullptr && DisplayMapWorld(cell->worldSpace) == world) {
                scanCell(cell);
            }
        }
    }
    static UInt32 lastLoggedCellCount = UINT32_MAX;
    static UInt32 lastLoggedMarkerCount = UINT32_MAX;
    static UInt32 lastLoggedEmitted = UINT32_MAX, lastLoggedWorld = 0;
    if (scannedCells != lastLoggedCellCount || mapMarkerReferences != lastLoggedMarkerCount
            || emittedMarkers != lastLoggedEmitted || world->refID != lastLoggedWorld) {
        lastLoggedCellCount = scannedCells;
        lastLoggedMarkerCount = mapMarkerReferences;
        lastLoggedEmitted = emittedMarkers;
        lastLoggedWorld = world->refID;
        const std::string message = "FNVDS map scan: cells=" + std::to_string(scannedCells)
                + " markerRefs=" + std::to_string(mapMarkerReferences)
                + " emitted=" + std::to_string(emittedMarkers)
                + " world=" + std::to_string(world->refID);
        WriteLog(message.c_str());
    }
    snapshot += "STATE MARKERS END\n";
    return snapshot;
}

bool ReceiveLine(SOCKET socket, std::string& line) {
    line.clear();
    char character = 0;
    while (line.size() < 256) {
        const int received = recv(socket, &character, 1, 0);
        if (received <= 0) {
            return false;
        }
        if (character == '\n') {
            return true;
        }
        if (character != '\r') {
            line.push_back(character);
        }
    }
    return false;
}

void OnNVSEMessage(NVSEMessagingInterface::Message* message) {
    if (message == nullptr) return;
    if (message->type == NVSEMessagingInterface::kMessage_PreLoadGame) {
        TextEntry::Reset();
        Vats::Reset();
        ResetInventoryCopies();
        Challenges::Reset();
        ResetPerkDetails();
        g_loadingSave.store(true);
        Activity::fastTravel = false;
        Activity::cellChanging = false;
        Activity::Set(ActivityBlock::LoadingSave);
        // Clear note playback, scan candidates, and queued note actions before the save changes.
        StopNativeNoteAudio();
        g_gameReady.store(false, std::memory_order_relaxed);
        g_notesRequested.store(false, std::memory_order_relaxed);
        LocalMaps::Reset();
        g_newGamePending.store(false, std::memory_order_relaxed);
        ResetNoteScan();
        g_notesReadyAt = GetTickCount64() + 1000;
        g_notePlaybackError.store(false, std::memory_order_relaxed);
        {
            std::lock_guard<std::mutex> lock(g_snapshotMutex);
            g_readOnlyNoteSnapshot =
                    "STATE NOTES BEGIN\nSTATE NOTES END\n"
                    "STATE NOTEAUDIO 0 0 0 0\nSTATE NOTEAUDIOERROR 0\n";
            g_readOnlySnapshot = "STATE GAME WAITING\n";
        }
        {
            std::lock_guard<std::mutex> lock(g_pendingCommandMutex);
            g_pendingItemCommands.erase(
                    std::remove_if(g_pendingItemCommands.begin(), g_pendingItemCommands.end(),
                            [](const PendingItemCommand& command) {
                                return command.type == ItemCommandType::ReadNote
                                        || command.type == ItemCommandType::PlayNote
                                        || command.type == ItemCommandType::StopNote;
                            }),
                    g_pendingItemCommands.end());
        }
        return;
    }
    if (message->type == NVSEMessagingInterface::kMessage_PostLoadGame) {
        // xNVSE passes the success boolean as the pointer value (0 or 1), not
        // as an address to a bool.
        const bool loaded = message->data != nullptr;
        g_loadingSave.store(false);
        if (!loaded) Activity::Set(ActivityBlock::None);
        g_gameReady.store(loaded, std::memory_order_relaxed);
        g_newGamePending.store(false, std::memory_order_relaxed);
        if (loaded) {
            // Let the game finish post-load initialization before asking the
            // scripting engine about note ownership or resolving note text.
            g_notesReadyAt = GetTickCount64() + 1000;
            g_nextNoteSnapshotAt = 0;
        }
        return;
    }
    if (message->type == NVSEMessagingInterface::kMessage_NewGame) {
        TextEntry::Reset();
        ResetPerkDetails();
        Vats::Reset();
        ResetInventoryCopies();
        g_loadingSave.store(false);
        Activity::fastTravel = false;
        Activity::cellChanging = false;
        Activity::Set(ActivityBlock::None);
        g_gameReady.store(false, std::memory_order_relaxed);
        g_newGamePending.store(true, std::memory_order_relaxed);
        ResetNoteScan();
        g_notesReadyAt = GetTickCount64() + 1000;
        return;
    }
    if (message->type == NVSEMessagingInterface::kMessage_ExitToMainMenu
            || message->type == NVSEMessagingInterface::kMessage_ExitGame
            || message->type == NVSEMessagingInterface::kMessage_ExitGame_Console) {
        TextEntry::Reset();
        Vats::Reset();
        ResetInventoryCopies();
        g_loadingSave.store(false);
        Activity::fastTravel = false;
        Activity::cellChanging = false;
        Activity::Set(ActivityBlock::None);
        g_gameReady.store(false, std::memory_order_relaxed);
        g_newGamePending.store(false, std::memory_order_relaxed);
        return;
    }
    if (message->type != NVSEMessagingInterface::kMessage_MainGameLoop) return;

    // xNVSE's SDK exposes GetSingleton as a helper implemented in its own
    // core, so resolve the game's player singleton pointer directly here.
    static ULONGLONG lastSnapshotAt = 0;
    static ULONGLONG lastMapMarkerScanAt = 0;
    static TESWorldSpace* lastMapMarkerWorld = nullptr;
    static float lastMapX = 0.0f;
    static float lastMapY = 0.0f;
    static float lastMapHeading = 0.0f;
    static bool hasMapPoint = false;
    static UInt32 cachedRouteQuestId = 0;
    static UInt32 missingQuestTargetSnapshots = 0;
    static std::vector<QuestMapRoute> cachedQuestRoutes;
    static std::string cachedMapMarkers;
    auto** playerPointer = reinterpret_cast<PlayerCharacter**>(0x011DEA3C);
    PlayerCharacter* player = playerPointer == nullptr ? nullptr : *playerPointer;
    if (g_newGamePending.load(std::memory_order_relaxed) && player != nullptr
            && player->parentCell != nullptr) {
        g_gameReady.store(true, std::memory_order_relaxed);
        g_newGamePending.store(false, std::memory_order_relaxed);
    }
    TextEntry::Update();
    Activity::Update(player);
    Vats::Update(player);
    Dialogue::Update();
    BarterSummary::Update();
    LootSummary::Update(player);
    if (player == nullptr) {
        g_healthTenths.store(-1, std::memory_order_relaxed);
        g_actionPointsTenths.store(-1, std::memory_order_relaxed);
        for (auto& value : g_specialTenths) {
            value.store(-1, std::memory_order_relaxed);
        }
        for (auto& value : g_skillTenths) {
            value.store(-1, std::memory_order_relaxed);
        }
        for (auto& value : g_effectTenths) {
            value.store(-1, std::memory_order_relaxed);
        }
        for (auto& value : g_limbHealthTenths) {
            value.store(-1, std::memory_order_relaxed);
        }
        const ULONGLONG now = GetTickCount64();
        if (now - lastSnapshotAt >= 1000) {
            lastSnapshotAt = now;
            try {
                std::string noteSnapshot = BuildNoteSnapshot(nullptr);
                std::string snapshot = BuildLimbCareSnapshot(nullptr) + BuildStatusSnapshot(nullptr) + BuildInventorySnapshot(nullptr)
                        + BuildQuestSnapshot(nullptr) + BuildPerkSnapshot(nullptr)
                        + BuildReputationSnapshot(nullptr, nullptr) + BuildGeneralStatSnapshot(nullptr)
                        + Challenges::Snapshot(nullptr, nullptr)
                        + BuildActiveEffectsSnapshot(nullptr);
                snapshot += "STATE GAME WAITING\n";
                snapshot += "STATE MAPSPACE 0\n";
                snapshot += "STATE MAP UNAVAILABLE\n";
                snapshot += "STATE MAPPOINT UNAVAILABLE\n";
                snapshot += "STATE QUESTDESTINATIONS BEGIN\nSTATE QUESTDESTINATIONS END\n";
                snapshot += "STATE CUSTOMMARKEREXISTS 0\n";
                snapshot += "STATE CUSTOMMARKER UNAVAILABLE\n";
                snapshot += "STATE MARKERS BEGIN\nSTATE MARKERS END\n";
                snapshot += BuildRadioSnapshot(nullptr);
                hasMapPoint = false;
                cachedQuestRoutes.clear();
                cachedRouteQuestId = 0;
                missingQuestTargetSnapshots = 0;
                cachedMapMarkers.clear();
                std::lock_guard<std::mutex> lock(g_snapshotMutex);
                g_readOnlySnapshot = std::move(snapshot);
                g_readOnlyNoteSnapshot = std::move(noteSnapshot);
            } catch (...) {
                WriteLog("FNVDS bridge: snapshot update failed while no player was loaded.");
            }
        }
        return;
    }

    ProcessItemCommands(player);
    try {
        if (g_activityBlock.load() == ActivityBlock::None) LocalMaps::Update(player);
    } catch (const std::exception& error) {
        LocalMaps::requested.store(false);
        WriteLog((std::string("FNVDS local map capture failed: ") + error.what()).c_str());
    }

    const float health = player->avOwner.Fn_03(eActorVal_Health);
    const float actionPoints = player->avOwner.Fn_03(eActorVal_ActionPoints);
    if (std::isfinite(health)) {
        const int tenths = static_cast<int>(std::lround(health * 10.0f));
        g_healthTenths.store(tenths < 0 ? 0 : tenths, std::memory_order_relaxed);
    } else {
        g_healthTenths.store(-1, std::memory_order_relaxed);
    }

    if (std::isfinite(actionPoints)) {
        const int tenths = static_cast<int>(std::lround(actionPoints * 10.0f));
        g_actionPointsTenths.store(tenths < 0 ? 0 : tenths, std::memory_order_relaxed);
    } else {
        g_actionPointsTenths.store(-1, std::memory_order_relaxed);
    }

    for (int index = 0; index < 7; ++index) {
        const float value = player->avOwner.Fn_03(eActorVal_Strength + index);
        if (std::isfinite(value)) {
            const int tenths = static_cast<int>(std::lround(value * 10.0f));
            g_specialTenths[index].store(tenths < 0 ? 0 : tenths,
                                         std::memory_order_relaxed);
        } else {
            g_specialTenths[index].store(-1, std::memory_order_relaxed);
        }
    }
    for (int index = 0; index < 14; ++index) {
        const float value = player->avOwner.Fn_03(eActorVal_Barter + index);
        if (std::isfinite(value)) {
            const int tenths = static_cast<int>(std::lround(value * 10.0f));
            g_skillTenths[index].store(tenths < 0 ? 0 : tenths,
                                       std::memory_order_relaxed);
        } else {
            g_skillTenths[index].store(-1, std::memory_order_relaxed);
        }
    }
    const UInt32 effectActorValues[4] = {
            eActorVal_RadLevel, eActorVal_Dehydration,
            eActorVal_Hunger, eActorVal_Sleepdeprevation
    };
    for (int index = 0; index < 4; ++index) {
        const float value = player->avOwner.Fn_03(effectActorValues[index]);
        if (std::isfinite(value)) {
            const int tenths = static_cast<int>(std::lround(value * 10.0f));
            g_effectTenths[index].store(tenths < 0 ? 0 : tenths,
                                        std::memory_order_relaxed);
        } else {
            g_effectTenths[index].store(-1, std::memory_order_relaxed);
        }
    }
    const UInt32 limbActorValues[6] = {
            eActorVal_Head, eActorVal_Torso, eActorVal_LeftArm,
            eActorVal_RightArm, eActorVal_LeftLeg, eActorVal_RightLeg
    };
    for (int index = 0; index < 6; ++index) {
        const float value = player->avOwner.Fn_03(limbActorValues[index]);
        if (std::isfinite(value)) {
            const int tenths = static_cast<int>(std::lround(value * 10.0f));
            g_limbHealthTenths[index].store(tenths < 0 ? 0 : tenths,
                                            std::memory_order_relaxed);
        } else {
            g_limbHealthTenths[index].store(-1, std::memory_order_relaxed);
        }
    }

    const ULONGLONG now = GetTickCount64();
    if (now - lastSnapshotAt >= 250) {
        lastSnapshotAt = now;
        try {
            auto** dataHandlerPointer = reinterpret_cast<DataHandler**>(0x011C3F2C);
            DataHandler* dataHandler = dataHandlerPointer == nullptr ? nullptr : *dataHandlerPointer;
            TESObjectCELL* playerCell = player->parentCell;
            const bool interior = playerCell && playerCell->IsInterior();
            TESObjectREFR* worldPositionRef = nullptr;
            TESWorldSpace* cellWorld = nullptr;
            if (playerCell && !interior) {
                worldPositionRef = player;
                cellWorld = playerCell->worldSpace;
            } else if (interior) {
                // PlayerCharacter + 0x604 is lastExteriorDoor in the supported runtime.
                // Room coordinates are independent of exterior coordinates.
                // Use the exterior entrance even when loading a save indoors.
                TESObjectREFR* entrance = nullptr;
                if (ReadGameMemory<TESObjectREFR*>(reinterpret_cast<uintptr_t>(player)+0x604, entrance)
                        && entrance && entrance->parentCell
                        && !entrance->parentCell->IsInterior()) {
                    cellWorld = MapReferenceWorld(entrance);
                    if (cellWorld) worldPositionRef = entrance;
                }
            }
            TESWorldSpace* currentWorld = cellWorld ? DisplayMapWorld(cellWorld) : lastMapMarkerWorld;
            const bool worldChanged = currentWorld != lastMapMarkerWorld;
            if (worldChanged) hasMapPoint = false;
            float normalizedX = 0.0f;
            float normalizedY = 0.0f;
            if (currentWorld && worldPositionRef
                    && NormalizeWorldMapReference(currentWorld, worldPositionRef, normalizedX, normalizedY)
                    && normalizedX >= 0.0f && normalizedX <= 1.0f
                    && normalizedY >= 0.0f && normalizedY <= 1.0f) {
                lastMapX = normalizedX;
                lastMapY = normalizedY;
                lastMapHeading = worldPositionRef->rotZ;
                hasMapPoint = true;
            }
            // If no exterior anchor is available, preserve the last valid
            // point; never substitute an interior's local coordinates.
            static UInt32 loggedPositionCell = 0;
            UInt32 positionCellId = playerCell ? playerCell->refID : 0;
            if (loggedPositionCell != positionCellId) {
                loggedPositionCell = positionCellId;
                char positionLog[192]{};
                std::snprintf(positionLog, sizeof(positionLog),
                        "FNVDS world player position cell=%08X interior=%u source=%08X world=%08X point=%.6f,%.6f valid=%u",
                        positionCellId, interior ? 1u : 0u, worldPositionRef ? worldPositionRef->refID : 0,
                        currentWorld ? currentWorld->refID : 0, lastMapX, lastMapY, hasMapPoint ? 1u : 0u);
                WriteLog(positionLog);
            }
            if (worldChanged && currentWorld != nullptr) {
                const std::string worldName = FormDisplayName(currentWorld);
                char diagnostic[512]{};
                std::snprintf(diagnostic, sizeof(diagnostic),
                              "FNVDS map transform world=%s id=%08X player=%.1f,%.1f usable=%d,%d nw=%d,%d se=%d,%d scale=%.5f offset=%.3f,%.3f parentFlags=%04X bounds=%.1f,%.1f:%.1f,%.1f normalized=%.5f,%.5f",
                              worldName.c_str(), currentWorld->refID,
                              player->posX, player->posY,
                              currentWorld->mapData.usableDimensions.X,
                              currentWorld->mapData.usableDimensions.Y,
                              currentWorld->mapData.cellNWCoordinates.X,
                              currentWorld->mapData.cellNWCoordinates.Y,
                              currentWorld->mapData.cellSECoordinates.X,
                              currentWorld->mapData.cellSECoordinates.Y,
                              currentWorld->worldMapScale,
                              currentWorld->worldMapCellX,
                              currentWorld->worldMapCellY,
                              currentWorld->parentFlags,
                              currentWorld->min.X, currentWorld->min.Y,
                              currentWorld->max.X, currentWorld->max.Y,
                              normalizedX, normalizedY);
                WriteLog(diagnostic);
            }
            if (worldChanged) {
                lastMapMarkerWorld = currentWorld;
                cachedMapMarkers.clear();
                g_fastTravelMarkers.clear();
            }
            if (worldChanged || now - lastMapMarkerScanAt >= 5000
                    || cachedMapMarkers.empty()) {
                lastMapMarkerScanAt = now;
                cachedMapMarkers = BuildMapMarkerSnapshot(dataHandler, currentWorld);
            }
            std::string noteSnapshot = BuildNoteSnapshot(
                    g_gameReady.load(std::memory_order_relaxed) ? player : nullptr);
            std::string snapshot = BuildLimbCareSnapshot(player) + BuildStatusSnapshot(player) + BuildInventorySnapshot(player)
                    + BuildItemDetailsSnapshot(player)
                    + g_weaponModResult
                    + g_inventoryDropResult
                    + BuildQuestSnapshot(g_gameReady.load(std::memory_order_relaxed)
                            ? player : nullptr)
                    + BuildPerkSnapshot(player)
                    + BuildReputationSnapshot(player, dataHandler) + BuildGeneralStatSnapshot(player)
                    + Challenges::Snapshot(player, dataHandler)
                    + BuildActiveEffectsSnapshot(player);
            snapshot += g_gameReady.load(std::memory_order_relaxed)
                    ? "STATE GAME READY\n" : "STATE GAME WAITING\n";
            snapshot += "STATE WORLD " + Base64Encode(
                    currentWorld != nullptr ? FormDisplayName(currentWorld) : std::string()) + "\n";
            snapshot += "STATE MAPSPACE " + std::to_string(currentWorld ? currentWorld->refID : 0) + "\n";
            char mapState[96]{};
            std::snprintf(mapState, sizeof(mapState), "STATE MAP %.1f %.1f %.1f\n",
                          player->posX, player->posY, player->posZ);
            snapshot += mapState;
            if (hasMapPoint) {
                char mapPoint[96]{};
                std::snprintf(mapPoint, sizeof(mapPoint), "STATE MAPPOINT %.6f %.6f\n",
                              lastMapX, lastMapY);
                snapshot += mapPoint;
                char mapHeading[64]{};
                std::snprintf(mapHeading, sizeof(mapHeading), "STATE HEADING %.6f\n",
                              lastMapHeading);
                snapshot += mapHeading;
            } else {
                snapshot += "STATE MAPPOINT UNAVAILABLE\n";
            }
            const UInt32 trackedQuestId = player->quest != nullptr ? player->quest->refID : 0;
            snapshot += BuildQuestViewResult(player, currentWorld);
            if (worldChanged || trackedQuestId != cachedRouteQuestId) {
                cachedRouteQuestId = trackedQuestId;
                cachedQuestRoutes.clear();
                missingQuestTargetSnapshots = 0;
            }
            bool targetsAvailable = false;
            auto currentQuestRoutes = FindQuestMapRoutes(player, currentWorld, targetsAvailable);
            if (targetsAvailable) {
                // Replace the complete set, including current positions for moving references.
                cachedQuestRoutes = std::move(currentQuestRoutes);
                missingQuestTargetSnapshots = 0;
            } else if (++missingQuestTargetSnapshots >= 20) {
                cachedQuestRoutes.clear();
            }
            snapshot += "STATE QUESTDESTINATIONS BEGIN\n";
            for (const QuestMapRoute& route : cachedQuestRoutes) {
                const auto& destination = route.points.back();
                char questPoint[128]{};
                std::snprintf(questPoint, sizeof(questPoint),
                              "STATE QUESTDESTINATION %u %.6f %.6f\n",
                              route.endpointRefId, destination.first, destination.second);
                snapshot += questPoint;
                for (const auto& point : route.points) {
                    char routePoint[128]{};
                    std::snprintf(routePoint, sizeof(routePoint),
                                  "STATE QUESTPATH %u %.6f %.6f\n",
                                  route.endpointRefId, point.first, point.second);
                    snapshot += routePoint;
                }
            }
            snapshot += "STATE QUESTDESTINATIONS END\n";
            snapshot += HasCustomMapMarker(player) ? "STATE CUSTOMMARKEREXISTS 1\n"
                    : "STATE CUSTOMMARKEREXISTS 0\n";
            float customX = 0.0f;
            float customY = 0.0f;
            if (FindCustomMapPoint(player, currentWorld, customX, customY)) {
                char customPoint[96]{};
                std::snprintf(customPoint, sizeof(customPoint),
                              "STATE CUSTOMMARKER %.6f %.6f\n", customX, customY);
                snapshot += customPoint;
            } else {
                snapshot += "STATE CUSTOMMARKER UNAVAILABLE\n";
            }
            snapshot += cachedMapMarkers.empty()
                    ? "STATE MARKERS BEGIN\nSTATE MARKERS END\n" : cachedMapMarkers;
            snapshot += BuildRadioSnapshot(player);
            snapshot += BuildLocalMarkerSnapshot(player);
            {
                std::lock_guard<std::mutex> localLock(LocalMaps::mutex);
                snapshot += LocalMaps::positionState;
            }
            std::lock_guard<std::mutex> lock(g_snapshotMutex);
            g_readOnlySnapshot = std::move(snapshot);
            g_readOnlyNoteSnapshot = std::move(noteSnapshot);
        } catch (...) {
            WriteLog("FNVDS bridge: read-only page snapshot update failed.");
        }
    }
}

void BridgeWorker() {
    WSADATA winsock{};
    if (WSAStartup(MAKEWORD(2, 2), &winsock) != 0) {
        WriteLog("FNVDS bridge: WSAStartup failed.");
        return;
    }

    for (;;) {
        const SOCKET socketHandle = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
        if (socketHandle == INVALID_SOCKET) {
            WriteLog("FNVDS bridge: socket creation failed; retrying.");
            Sleep(1000);
            continue;
        }

        const int noDelay=1;
        setsockopt(socketHandle,IPPROTO_TCP,TCP_NODELAY,reinterpret_cast<const char*>(&noDelay),sizeof(noDelay));
        sockaddr_in address{};
        address.sin_family = AF_INET;
        address.sin_port = htons(kBridgePort);
        address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
        const DWORD timeoutMs = 5000;
        setsockopt(socketHandle, SOL_SOCKET, SO_RCVTIMEO,
                   reinterpret_cast<const char*>(&timeoutMs), sizeof(timeoutMs));

        if (connect(socketHandle, reinterpret_cast<const sockaddr*>(&address),
                    sizeof(address)) == SOCKET_ERROR) {
            closesocket(socketHandle);
            Sleep(1000);
            continue;
        }

        std::string response;
        const bool handshakeStarted = SendLine(socketHandle, "HELLO FNVDS/1");
        const bool receivedPing = handshakeStarted && ReceiveLine(socketHandle, response);
        const bool handshakeComplete = receivedPing && response == "PING FNVDS/1"
                && SendLine(socketHandle, "PONG FNVDS/1");
        if (handshakeComplete) {
            WriteLog("FNVDS bridge: two-way local handshake completed.");
            int lastSentHealth = -1;
            int lastSentActionPoints = -1;
            int lastSentSpecial[7] = {-1, -1, -1, -1, -1, -1, -1};
            int lastSentSkills[14] = {-1, -1, -1, -1, -1, -1, -1,
                                      -1, -1, -1, -1, -1, -1, -1};
            int lastSentEffects[4] = {-1, -1, -1, -1};
            int lastSentLimbs[6] = {-1, -1, -1, -1, -1, -1};
            std::string lastSentSnapshot;
            std::string lastSentNoteSnapshot;
            std::string lastSentDialogueSnapshot;
            std::string lastSentTextEntrySnapshot;
            std::string lastSentBarterSnapshot;
            std::string lastSentLootSnapshot;
            std::string lastSentVatsSnapshot;
            unsigned lastLocalGeneration = 0;
            LocalMaps::Batch lastLocalBatch;
            ULONGLONG lastLocalSendAt = 0;
            ULONGLONG lastVersionSentAt = 0;
            std::string lastSentActivity;
            std::string incomingBuffer;
            for (;;) {
                const std::string activity = Activity::Packet();
                if (activity != lastSentActivity) {
                    if (!SendLine(socketHandle, activity)) break;
                    lastSentActivity = activity;
                }
                // Report independently of save loading and snapshot generation.
                const ULONGLONG versionNow = GetTickCount64();
                if (lastVersionSentAt == 0 || versionNow - lastVersionSentAt >= 1000) {
                    if (!SendLine(socketHandle, std::string("STATE PLUGINVERSION ") + kPluginVersion)) break;
                    lastVersionSentAt = versionNow;
                }
                bool mapSendFailed = false;
                fd_set readable;
                FD_ZERO(&readable);
                FD_SET(socketHandle, &readable);
                timeval wait{};
                wait.tv_usec = 50000;
                const int ready = select(0, &readable, nullptr, nullptr, &wait);
                if (ready > 0) {
                    char incoming[256]{};
                    const int received = recv(socketHandle, incoming, sizeof(incoming), 0);
                    if (received <= 0) {
                        break;
                    }
                    for (int i = 0; i < received; ++i) {
                        const char character = incoming[i];
                        if (character == '\n') {
                            if (!incomingBuffer.empty() && incomingBuffer.back() == '\r') {
                                incomingBuffer.pop_back();
                            }
                            unsigned int formId = 0;
                            unsigned int wasEquipped = 0;
                            unsigned int instanceToken = 0;
                            unsigned int hotkeySlot = 0;
                            unsigned int modFormId = 0;
                            unsigned int quantity = 1;
                            float markerX = 0, markerY = 0;
                            int markerCenterX = 0, markerCenterY = 0;
                            char trailing = 0;
                            ItemCommandType commandType{};
                            bool isItemCommand = false;
                            const bool vatsControlsAllowed = g_gameReady.load()
                                    && g_activityBlock.load() == ActivityBlock::None;
                            const bool controlsAllowed = vatsControlsAllowed && !Vats::active.load()
                                    && TextEntry::activeSession.load() == 0;
                            if (std::sscanf(incomingBuffer.c_str(), "TEXTKEY %u %u %c",
                                            &formId, &wasEquipped, &trailing) == 2) {
                                TextEntry::Enqueue(formId, wasEquipped);
                            } else if (incomingBuffer == "CONSOLEOPEN") {
                                if (controlsAllowed) TextEntry::openConsole.store(true);
                            } else if (std::sscanf(incomingBuffer.c_str(), "VATSCMD %u %u %u %c",
                                            &formId, &wasEquipped, &instanceToken, &trailing) == 3
                                    && formId != 0 && wasEquipped >= 1 && wasEquipped <= 7) {
                                if (vatsControlsAllowed && Vats::active.load())
                                    Vats::Enqueue({formId, wasEquipped, instanceToken});
                            } else if (std::sscanf(incomingBuffer.c_str(), "DIALOGUESKIP %u %c",
                                            &formId, &trailing) == 1 && formId != 0) {
                                UInt32 empty = 0;
                                if (controlsAllowed) Dialogue::pendingSkip.compare_exchange_strong(empty, formId);
                            } else if (std::sscanf(incomingBuffer.c_str(), "DIALOGUESELECT %u %c",
                                            &formId, &trailing) == 1 && formId != 0) {
                                UInt32 empty = 0;
                                if (controlsAllowed) Dialogue::pendingChoice.compare_exchange_strong(empty, formId);
                            } else if (incomingBuffer == "LOCALON") {
                                if (controlsAllowed && !LocalMaps::requested.exchange(true)) {
                                    LocalMaps::forceRefresh.store(true);
                                    lastLocalGeneration = 0;
                                }
                            } else if (incomingBuffer == "LOCALOFF") {
                                LocalMaps::requested.store(false);
                            } else if (std::sscanf(incomingBuffer.c_str(), "PERKDETAIL %u %c",
                                            &formId, &trailing) == 1 && formId != 0) {
                                if (controlsAllowed) g_pendingPerkDetail.store(formId);
                            } else if (std::sscanf(incomingBuffer.c_str(), "CHALLENGEDETAIL %u %c",
                                            &formId, &trailing) == 1 && formId != 0) {
                                if (controlsAllowed) Challenges::pendingDetail.store(formId);
                            } else if (incomingBuffer == "NOTESON") {
                                g_notesRequested.store(controlsAllowed, std::memory_order_relaxed);
                            } else if (incomingBuffer == "NOTESOFF") {
                                g_notesRequested.store(false, std::memory_order_relaxed);
                            } else if (incomingBuffer.rfind("GETMAP ", 0) == 0) {
                                const std::string worldKey = incomingBuffer.substr(7);
                                if (MapTextureNames(worldKey).empty()) {
                                    SendLine(socketHandle, "STATE MAPDATA ERROR UNKNOWN INVALID_WORLD");
                                } else if (!SendGameMapTexture(socketHandle, worldKey)) {
                                    mapSendFailed = true;
                                }
                            } else if (std::sscanf(incomingBuffer.c_str(), "DROPITEM %u %u %u %c",
                                            &formId, &instanceToken, &quantity, &trailing) == 3
                                    && formId != 0 && quantity >= 1 && quantity <= 0x7FFFFFFFu) {
                                commandType = ItemCommandType::DropItem;
                                isItemCommand = true;
                            } else if (std::sscanf(incomingBuffer.c_str(), "DROPITEM %u %u %c",
                                            &formId, &instanceToken, &trailing) == 2 && formId != 0) {
                                quantity = 1;
                                commandType = ItemCommandType::DropItem;
                                isItemCommand = true;
                            } else if (std::sscanf(incomingBuffer.c_str(), "INSTALLMOD %u %u %u %c",
                                            &formId, &instanceToken, &modFormId, &trailing) == 3
                                    && formId != 0 && instanceToken != 0 && modFormId != 0) {
                                commandType = ItemCommandType::InstallWeaponMod;
                                isItemCommand = true;
                            } else if (std::sscanf(incomingBuffer.c_str(), "HOTKEY %u %u %u %c",
                                            &formId, &instanceToken, &hotkeySlot, &trailing) == 3
                                    && formId != 0 && hotkeySlot >= 1 && hotkeySlot <= 8 && hotkeySlot != 2) {
                                commandType = ItemCommandType::AssignHotkey;
                                isItemCommand = true;
                            } else if (std::sscanf(incomingBuffer.c_str(), "INSPECT %u %u %c",
                                            &formId, &instanceToken, &trailing) == 2) {
                                commandType = ItemCommandType::InspectItem;
                                isItemCommand = true;
                            } else if (std::sscanf(incomingBuffer.c_str(), "EQUIPCOPY %u %u %u %c",
                                            &formId, &instanceToken, &wasEquipped, &trailing) == 3
                                    && formId != 0 && instanceToken != 0 && wasEquipped <= 1) {
                                commandType = ItemCommandType::Equip;
                                isItemCommand = true;
                            } else if (std::sscanf(incomingBuffer.c_str(), "EQUIP %u %u %c",
                                            &formId, &wasEquipped, &trailing) == 2
                                    && formId != 0 && wasEquipped <= 1) {
                                commandType = ItemCommandType::Equip;
                                isItemCommand = true;
                            } else if (std::sscanf(incomingBuffer.c_str(), "TREATLIMB %u %c",
                                                  &hotkeySlot, &trailing) == 1 && hotkeySlot < 6) {
                                commandType = ItemCommandType::TreatLimb;
                                formId = 0;
                                isItemCommand = true;
                            } else if (std::sscanf(incomingBuffer.c_str(), "USENEED %u %u %c",
                                                  &formId, &hotkeySlot, &trailing) == 2 && formId != 0
                                    && hotkeySlot >= 1 && hotkeySlot <= 3) {
                                commandType = ItemCommandType::UseNeedAid;
                                wasEquipped = 0;
                                isItemCommand = true;
                            } else if (std::sscanf(incomingBuffer.c_str(), "USE %u %c",
                                                  &formId, &trailing) == 1 && formId != 0) {
                                commandType = ItemCommandType::UseAid;
                                wasEquipped = 0;
                                isItemCommand = true;
                            } else if (std::sscanf(incomingBuffer.c_str(), "VIEWQUEST %u %u %c",
                                                  &formId, &instanceToken, &trailing) == 2 && formId != 0) {
                                commandType = ItemCommandType::ViewQuest;
                                wasEquipped = 0;
                                isItemCommand = true;
                            } else if (std::sscanf(incomingBuffer.c_str(), "TRACKQUEST %u %c",
                                                  &formId, &trailing) == 1 && formId != 0) {
                                commandType = ItemCommandType::TrackQuest;
                                wasEquipped = 0;
                                isItemCommand = true;
                            } else if (std::sscanf(incomingBuffer.c_str(), "FASTTRAVEL %u %c",
                                                  &formId, &trailing) == 1 && formId != 0) {
                                commandType = ItemCommandType::FastTravel;
                                wasEquipped = 0;
                                isItemCommand = true;
                            } else if (incomingBuffer == "REMOVECUSTOM") {
                                commandType = ItemCommandType::RemoveCustomMarker;
                                formId = 0;
                                isItemCommand = true;
                            } else if (std::sscanf(incomingBuffer.c_str(), "PLACEWORLD %u %f %f %c",
                                                  &formId, &markerX, &markerY, &trailing) == 3
                                    && formId && std::isfinite(markerX) && std::isfinite(markerY)
                                    && markerX >= 0 && markerX <= 1 && markerY >= 0 && markerY <= 1) {
                                commandType = ItemCommandType::PlaceWorldMarker;
                                isItemCommand = true;
                            } else if (std::sscanf(incomingBuffer.c_str(), "PLACELOCAL %u %d %d %f %f %c",
                                                  &formId, &markerCenterX, &markerCenterY, &markerX, &markerY, &trailing) == 5
                                    && formId && std::isfinite(markerX) && std::isfinite(markerY)
                                    && markerX >= 0 && markerX <= 1 && markerY >= 0 && markerY <= 1) {
                                commandType = ItemCommandType::PlaceLocalMarker;
                                hotkeySlot = static_cast<UInt32>(markerCenterX);
                                modFormId = static_cast<UInt32>(markerCenterY);
                                isItemCommand = true;
                            } else if (std::sscanf(incomingBuffer.c_str(), "TUNERADIO %u %c",
                                                  &formId, &trailing) == 1 && formId != 0) {
                                commandType = ItemCommandType::TuneRadio;
                                wasEquipped = 0;
                                isItemCommand = true;
                            } else if (std::sscanf(incomingBuffer.c_str(), "READNOTE %u %c",
                                                  &formId, &trailing) == 1 && formId != 0) {
                                commandType = ItemCommandType::ReadNote;
                                wasEquipped = 0;
                                isItemCommand = true;
                            } else if (std::sscanf(incomingBuffer.c_str(), "PLAYNOTE %u %c",
                                                  &formId, &trailing) == 1 && formId != 0) {
                                commandType = ItemCommandType::PlayNote;
                                wasEquipped = 0;
                                isItemCommand = true;
                            } else if (incomingBuffer == "STOPNOTE") {
                                commandType = ItemCommandType::StopNote;
                                formId = 0;
                                wasEquipped = 0;
                                isItemCommand = true;
                            }
                            // Queue gameplay actions here; ProcessItemCommands executes them in the game loop.
                            if (isItemCommand && controlsAllowed) {
                                std::lock_guard<std::mutex> lock(g_pendingCommandMutex);
                                if (g_pendingItemCommands.size() < 32 && g_gameReady.load()
                                        && g_activityBlock.load() == ActivityBlock::None) {
                                    g_pendingItemCommands.push_back({
                                            static_cast<UInt32>(formId), commandType,
                                            wasEquipped != 0, instanceToken, hotkeySlot, modFormId, quantity, markerX, markerY});
                                    char logMessage[128]{};
                                    const char* queuedFormat = commandType == ItemCommandType::UseAid
                                            ? "FNVDS aid command queued form=%08X"
                                            : commandType == ItemCommandType::TrackQuest
                                                    ? "FNVDS quest track queued form=%08X"
                                                    : commandType == ItemCommandType::FastTravel
                                                            ? "FNVDS fast travel queued marker=%08X"
                                                    : commandType == ItemCommandType::TuneRadio
                                                            ? "FNVDS radio tune queued station=%08X"
                                                    : commandType == ItemCommandType::ReadNote
                                                            ? "FNVDS note read queued form=%08X"
                                                    : commandType == ItemCommandType::PlayNote
                                                            ? "FNVDS note play queued form=%08X"
                                                    : commandType == ItemCommandType::StopNote
                                                            ? "FNVDS note stop queued"
                                                    : "FNVDS item command queued form=%08X wasEquipped=%u";
                                    std::snprintf(logMessage, sizeof(logMessage), queuedFormat,
                                                  formId, wasEquipped);
                                    WriteLog(logMessage);
                                }
                            }
                            incomingBuffer.clear();
                        } else if (incomingBuffer.size() < 128) {
                            incomingBuffer.push_back(character);
                        } else {
                            incomingBuffer.clear();
                        }
                    }
                } else if (ready == SOCKET_ERROR) {
                    break;
                }
                if (mapSendFailed) break;

                const int health = g_healthTenths.load(std::memory_order_relaxed);
                const int actionPoints = g_actionPointsTenths.load(std::memory_order_relaxed);
                if (health >= 0 && actionPoints >= 0
                        && (health != lastSentHealth || actionPoints != lastSentActionPoints)) {
                    char state[64]{};
                    std::snprintf(state, sizeof(state), "STATE VITALS %d %d", health, actionPoints);
                    if (!SendLine(socketHandle, state)) {
                        break;
                    }
                    lastSentHealth = health;
                    lastSentActionPoints = actionPoints;
                }

                int special[7]{};
                bool specialAvailable = true;
                bool specialChanged = false;
                for (int index = 0; index < 7; ++index) {
                    special[index] = g_specialTenths[index].load(std::memory_order_relaxed);
                    if (special[index] < 0) {
                        specialAvailable = false;
                    }
                    if (special[index] != lastSentSpecial[index]) {
                        specialChanged = true;
                    }
                }
                if (specialAvailable && specialChanged) {
                    char state[128] = "STATE SPECIAL";
                    size_t used = std::strlen(state);
                    for (int index = 0; index < 7; ++index) {
                        const int written = std::snprintf(state + used, sizeof(state) - used,
                                                          " %d", special[index]);
                        if (written <= 0 || static_cast<size_t>(written) >= sizeof(state) - used) {
                            specialAvailable = false;
                            break;
                        }
                        used += static_cast<size_t>(written);
                    }
                    if (!specialAvailable || !SendLine(socketHandle, state)) {
                        break;
                    }
                    for (int index = 0; index < 7; ++index) {
                        lastSentSpecial[index] = special[index];
                    }
                }

                int skills[14]{};
                bool skillsAvailable = true;
                bool skillsChanged = false;
                for (int index = 0; index < 14; ++index) {
                    skills[index] = g_skillTenths[index].load(std::memory_order_relaxed);
                    if (skills[index] < 0) skillsAvailable = false;
                    if (skills[index] != lastSentSkills[index]) skillsChanged = true;
                }
                if (skillsAvailable && skillsChanged) {
                    char state[256] = "STATE SKILLS";
                    size_t used = std::strlen(state);
                    for (int index = 0; index < 14; ++index) {
                        const int written = std::snprintf(state + used, sizeof(state) - used,
                                                          " %d", skills[index]);
                        if (written <= 0 || static_cast<size_t>(written) >= sizeof(state) - used) {
                            skillsAvailable = false;
                            break;
                        }
                        used += static_cast<size_t>(written);
                    }
                    if (!skillsAvailable || !SendLine(socketHandle, state)) break;
                    for (int index = 0; index < 14; ++index) {
                        lastSentSkills[index] = skills[index];
                    }
                }

                int effects[4]{};
                bool effectsAvailable = true;
                bool effectsChanged = false;
                for (int index = 0; index < 4; ++index) {
                    effects[index] = g_effectTenths[index].load(std::memory_order_relaxed);
                    if (effects[index] < 0) {
                        effectsAvailable = false;
                        lastSentEffects[index] = -1;
                    }
                    if (effects[index] != lastSentEffects[index]) effectsChanged = true;
                }
                if (effectsAvailable && effectsChanged) {
                    char state[128] = "STATE EFFECTS";
                    size_t used = std::strlen(state);
                    for (int index = 0; index < 4; ++index) {
                        const int written = std::snprintf(state + used, sizeof(state) - used,
                                                          " %d", effects[index]);
                        if (written <= 0 || static_cast<size_t>(written) >= sizeof(state) - used) {
                            effectsAvailable = false;
                            break;
                        }
                        used += static_cast<size_t>(written);
                    }
                    if (!effectsAvailable || !SendLine(socketHandle, state)) break;
                    for (int index = 0; index < 4; ++index) {
                        lastSentEffects[index] = effects[index];
                    }
                }

                int limbs[6]{};
                bool limbsAvailable = true;
                bool limbsChanged = false;
                for (int index = 0; index < 6; ++index) {
                    limbs[index] = g_limbHealthTenths[index].load(std::memory_order_relaxed);
                    if (limbs[index] < 0) {
                        limbsAvailable = false;
                        lastSentLimbs[index] = -1;
                    }
                    if (limbs[index] != lastSentLimbs[index]) limbsChanged = true;
                }
                if (limbsAvailable && limbsChanged) {
                    char state[128]{};
                    const int headerLength = std::snprintf(state, sizeof(state), "STATE LIMBS");
                    if (headerLength <= 0 || static_cast<size_t>(headerLength) >= sizeof(state)) break;
                    size_t used = static_cast<size_t>(headerLength);
                    for (int index = 0; index < 6; ++index) {
                        const int written = std::snprintf(state + used, sizeof(state) - used,
                                                          " %d", limbs[index]);
                        if (written <= 0 || static_cast<size_t>(written) >= sizeof(state) - used) {
                            limbsAvailable = false;
                            break;
                        }
                        used += static_cast<size_t>(written);
                    }
                    if (!limbsAvailable || !SendLine(socketHandle, state)) break;
                    for (int index = 0; index < 6; ++index) {
                        lastSentLimbs[index] = limbs[index];
                    }
                }

                // Copy while locked, then perform socket writes after releasing the snapshot mutex.
                std::string snapshot;
                std::string noteSnapshot;
                std::string dialogueSnapshot;
                std::string textEntrySnapshot;
                std::string barterSnapshot;
                std::string lootSnapshot;
                std::string vatsSnapshot;
                {
                    std::lock_guard<std::mutex> lock(g_snapshotMutex);
                    snapshot = g_readOnlySnapshot;
                    noteSnapshot = g_readOnlyNoteSnapshot;
                    dialogueSnapshot = Dialogue::published;
                    textEntrySnapshot = TextEntry::published;
                    barterSnapshot = BarterSummary::published;
                    lootSnapshot = LootSummary::published;
                    vatsSnapshot = Vats::published;
                }
                if (textEntrySnapshot != lastSentTextEntrySnapshot) {
                    if (!SendBytes(socketHandle, textEntrySnapshot)) break;
                    lastSentTextEntrySnapshot = std::move(textEntrySnapshot);
                }
                if (lootSnapshot != lastSentLootSnapshot) {
                    if (!SendBytes(socketHandle, lootSnapshot)) break;
                    lastSentLootSnapshot = std::move(lootSnapshot);
                }
                if (barterSnapshot != lastSentBarterSnapshot) {
                    if (!SendBytes(socketHandle, barterSnapshot)) break;
                    lastSentBarterSnapshot = std::move(barterSnapshot);
                }
                if (dialogueSnapshot != lastSentDialogueSnapshot) {
                    if (!SendBytes(socketHandle, dialogueSnapshot)) break;
                    lastSentDialogueSnapshot = std::move(dialogueSnapshot);
                }
                if (vatsSnapshot != lastSentVatsSnapshot) {
                    if (!SendBytes(socketHandle, vatsSnapshot)) break;
                    lastSentVatsSnapshot = std::move(vatsSnapshot);
                }
                if (LocalMaps::requested.load() && GetTickCount64() - lastLocalSendAt >= 100) {
                    lastLocalSendAt = GetTickCount64();
                    if (!LocalMaps::Send(socketHandle, lastLocalGeneration, lastLocalBatch)) break;
                }
                if (!snapshot.empty() && snapshot != lastSentSnapshot) {
                    if (!SendBytes(socketHandle, snapshot)) {
                        break;
                    }
                    lastSentSnapshot = std::move(snapshot);
                }
                if (!noteSnapshot.empty() && noteSnapshot != lastSentNoteSnapshot) {
                    if (!SendBytes(socketHandle, noteSnapshot)) break;
                    lastSentNoteSnapshot = std::move(noteSnapshot);
                }
            }
        }

        closesocket(socketHandle);
        TextEntry::openConsole.store(false);
        TextEntry::ClearKeys();
        Dialogue::pendingChoice.store(0);
        Dialogue::pendingSkip.store(0);
        { std::lock_guard<std::mutex> lock(Vats::requestMutex); Vats::requests.clear(); }
        if (handshakeComplete) {
            WriteLog("FNVDS bridge: companion disconnected; retrying.");
        }
        LocalMaps::requested.store(false);
        Sleep(1000);
    }
}

}  

BOOL WINAPI DllMain(HINSTANCE instance, DWORD reason, LPVOID) {
    if (reason == DLL_PROCESS_ATTACH) {
        g_pluginModule = instance;
        DisableThreadLibraryCalls(instance);
    }
    return TRUE;
}

extern "C" __declspec(dllexport) bool __cdecl NVSEPlugin_Query(
    const NVSEInterface* nvse,
    PluginInfo* info) {
    if (nvse == nullptr || info == nullptr) {
        return false;
    }

    info->infoVersion = PluginInfo::kInfoVersion;
    info->name = "FNVDS";
    info->version = 1;

    if (nvse->isEditor != 0) {
        return true;
    }

    if (nvse->runtimeVersion != RUNTIME_VERSION_1_4_0_525) {
        return false;
    }

    return true;
}

extern "C" __declspec(dllexport) bool __cdecl NVSEPlugin_Load(
    NVSEInterface* nvse) {
    if (nvse == nullptr) {
        return false;
    }

    WriteLog("FNVDS native plugin loaded.");
    auto* messaging = static_cast<NVSEMessagingInterface*>(
            nvse->QueryInterface(kInterface_Messaging));
    g_consoleInterface = static_cast<NVSEConsoleInterface*>(
            nvse->QueryInterface(kInterface_Console));
    g_scriptInterface = static_cast<NVSEScriptInterface*>(
            nvse->QueryInterface(kInterface_Script));
    if (messaging == nullptr || g_consoleInterface == nullptr
            || !messaging->RegisterListener(nvse->GetPluginHandle(), "NVSE", OnNVSEMessage)) {
        WriteLog("FNVDS bridge: failed to register for xNVSE game-loop messages.");
        return false;
    }
    WriteLog("FNVDS bridge: registered for xNVSE game-loop messages.");

    try {
        std::thread(BridgeWorker).detach();
    } catch (...) {
        WriteLog("FNVDS bridge: failed to start worker thread.");
    }
    return true;
}



