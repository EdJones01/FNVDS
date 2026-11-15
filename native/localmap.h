#include <d3d9.h>
#include "nvse/NiNodes.h"

// Generate off-screen textures through the cell renderer under its lock.
namespace LocalMaps {
constexpr int kGridSide = 5;
constexpr int kRadius = kGridSide / 2;
constexpr int kTileCount = kGridSide * kGridSide;
constexpr float kWorldSpan = kGridSide * 4096.0f;
struct Point { float x, y; };
struct Batch {
    unsigned generation = 0;
    UInt32 cellId = 0;
    int centerX = 0, centerY = 0;
    std::vector<MapTexturePayload> tiles;
    std::array<bool,kTileCount> ready{};
};
std::atomic<bool> requested{false}, forceRefresh{false};
std::mutex mutex;
Batch published, building;
unsigned sequence = 0, epoch = 0;
std::string status = "GENERATING_LOCAL_MAP", positionState;
float west = 0, north = 0;
float latestPlayerX=0.5f,latestPlayerY=0.5f,latestHeading=0;
bool interiorContext = false;
bool ReadPointer(uintptr_t address, uintptr_t& value) {
    SIZE_T copied = 0;
    return address && ReadProcessMemory(GetCurrentProcess(), reinterpret_cast<const void*>(address),
            &value, sizeof(value), &copied) && copied == sizeof(value);
}
void Put32(std::vector<unsigned char>& bytes, size_t at, uint32_t value) {
    for (int i=0;i<4;++i) bytes[at+i] = static_cast<unsigned char>(value >> (i*8));
}
MapTexturePayload EmptyTile() {
    // Build a 256-by-256 opaque black DDS tile with a 128-byte header and BGRA pixels.
    MapTexturePayload payload;
    payload.bytes.resize(128 + 256*256*4, 0);
    auto& b = payload.bytes;
    Put32(b,0,0x20534444); Put32(b,4,124); Put32(b,8,0x100F);
    Put32(b,12,256); Put32(b,16,256); Put32(b,20,1024);
    Put32(b,76,32); Put32(b,80,0x41); Put32(b,88,32);
    Put32(b,92,0x00FF0000); Put32(b,96,0x0000FF00); Put32(b,100,0x000000FF);
    Put32(b,104,0xFF000000); Put32(b,108,0x1000);
    for (size_t i=131;i<b.size();i+=4) b[i]=255;
    payload.decodedSize=static_cast<uint32_t>(b.size());
    return payload;
}

// Accept both rendered targets and source textures returned by the cell
// renderer. Wine/DXVK may expose these as directly lockable surfaces.
bool ReadTexture(void** output, MapTexturePayload& payload) {
    auto failure=[](const char* stage,HRESULT hr,uintptr_t type=0) {
        static ULONGLONG lastLog=0;
        ULONGLONG now=GetTickCount64();
        if(now-lastLog>=5000) {
            lastLog=now;char message[192]{};
            std::snprintf(message,sizeof(message),"FNVDS local readback stage=%s hr=%08X textureType=%08X",
                          stage,static_cast<unsigned>(hr),static_cast<unsigned>(type));
            WriteLog(message);
        }
        return false;
    };
    uintptr_t texture=0,type=0,data=0,d3d=0;
    if(!ReadPointer(reinterpret_cast<uintptr_t>(output),texture) || !texture)
        return failure("no-engine-texture",E_POINTER);
    if(!ReadPointer(texture,type) || (type!=0x0109DF8C && type!=0x0109B9EC))
        return failure("texture-type",E_NOINTERFACE,type);
    if(!ReadPointer(texture+0x24,data) || !data || !ReadPointer(data+0x64,d3d) || !d3d) {
        uintptr_t buffer=0;
        if(type!=0x0109DF8C || !ReadPointer(texture+0x30,buffer) || !buffer
                || !ReadPointer(buffer+0x10,data) || !data || !ReadPointer(data+0x20,d3d) || !d3d)
            return failure("no-d3d-texture",E_POINTER,type);
    }
    auto* base=reinterpret_cast<IDirect3DBaseTexture9*>(d3d);
    if(base->GetType()!=D3DRTYPE_TEXTURE) return failure("d3d-type",E_NOINTERFACE,type);
    IDirect3DSurface9* source=nullptr;
    HRESULT hr=static_cast<IDirect3DTexture9*>(base)->GetSurfaceLevel(0,&source);
    if(FAILED(hr)) return failure("surface",hr,type);
    D3DSURFACE_DESC desc{};hr=source->GetDesc(&desc);
    if(FAILED(hr) || !desc.Width || !desc.Height || desc.Width>4096 || desc.Height>4096) {
        source->Release();return failure("surface-description",FAILED(hr)?hr:E_INVALIDARG,type);
    }
    bool bgr32=desc.Format==D3DFMT_A8R8G8B8 || desc.Format==D3DFMT_X8R8G8B8;
    bool rgb32=desc.Format==D3DFMT_A8B8G8R8 || desc.Format==D3DFMT_X8B8G8R8;
    bool rgb565=desc.Format==D3DFMT_R5G6B5;
    bool rgb1555=desc.Format==D3DFMT_A1R5G5B5 || desc.Format==D3DFMT_X1R5G5B5;
    bool gray=desc.Format==D3DFMT_L8 || desc.Format==D3DFMT_A8;
    if(!bgr32 && !rgb32 && !rgb565 && !rgb1555 && !gray) {
        source->Release();return failure("pixel-format",static_cast<HRESULT>(desc.Format),type);
    }
    IDirect3DSurface9* readable=source;
    IDirect3DSurface9* staging=nullptr;
    D3DLOCKED_RECT locked{};
    // Source/managed textures may be lockable without a GPU copy.
    hr=source->LockRect(&locked,nullptr,D3DLOCK_READONLY);
    if(FAILED(hr)) {
        IDirect3DDevice9* device=nullptr;
        hr=source->GetDevice(&device);
        if(SUCCEEDED(hr)) {
            hr=device->CreateOffscreenPlainSurface(desc.Width,desc.Height,desc.Format,D3DPOOL_SYSTEMMEM,&staging,nullptr);
            if(SUCCEEDED(hr)) hr=device->GetRenderTargetData(source,staging);
            device->Release();
        }
        if(SUCCEEDED(hr)) {readable=staging;hr=readable->LockRect(&locked,nullptr,D3DLOCK_READONLY);}
    }
    if(FAILED(hr)) {
        if(staging)staging->Release();source->Release();return failure("surface-readback",hr,type);
    }
    try {
        payload=EmptyTile();
        unsigned stride=(bgr32||rgb32)?4:(rgb565||rgb1555)?2:1;
        // Sample to a fixed tile size; locked.Pitch supplies the source row stride.
        for(unsigned y=0;y<256;++y) {
            const auto* row=static_cast<const unsigned char*>(locked.pBits)+(y*desc.Height/256)*locked.Pitch;
            for(unsigned x=0;x<256;++x) {
                const auto* pixel=row+(x*desc.Width/256)*stride;
                unsigned red=0,green=0,blue=0;
                if(bgr32){blue=pixel[0];green=pixel[1];red=pixel[2];}
                else if(rgb32){red=pixel[0];green=pixel[1];blue=pixel[2];}
                else if(gray){red=green=blue=pixel[0];}
                else {
                    unsigned value=pixel[0]|(pixel[1]<<8);
                    blue=(value&31)*255/31;
                    if(rgb565){green=((value>>5)&63)*255/63;red=((value>>11)&31)*255/31;}
                    else{green=((value>>5)&31)*255/31;red=((value>>10)&31)*255/31;}
                }
                size_t at=128+(y*256+x)*4;
                payload.bytes[at]=static_cast<unsigned char>(blue);
                payload.bytes[at+1]=static_cast<unsigned char>(green);
                payload.bytes[at+2]=static_cast<unsigned char>(red);
            }
        }
    } catch(...) {
        readable->UnlockRect();if(staging)staging->Release();source->Release();throw;
    }
    readable->UnlockRect();if(staging)staging->Release();source->Release();return true;
}

// Off-screen local-map generation. These are the cell-level engine routines
// used by the Pip-Boy, not MapMenu or the image-export command.
struct RendererLock {
    void* renderer;
    explicit RendererLock(void* value) : renderer(value) {
        reinterpret_cast<void (__thiscall *)(void*)>(0x004A0370)(renderer);
    }
    ~RendererLock() {
        reinterpret_cast<void (__thiscall *)(void*)>(0x004A03C0)(renderer);
    }
};
void ReleaseTexture(void* texture) {
    if (!texture) return;
    uintptr_t type=0;
    if (!ReadPointer(reinterpret_cast<uintptr_t>(texture),type) || (type!=0x0109DF8C && type!=0x0109B9EC)) return;
    auto* refs=reinterpret_cast<volatile LONG*>(static_cast<unsigned char*>(texture)+4);
    if (InterlockedDecrement(refs)==0) {
        // SDK NiRefObject::Free is vtable slot 1 and takes no arguments.
        // Use the declared virtual method so the x86 call matches the engine.
        reinterpret_cast<NiRefObject*>(texture)->Free();
    }
}
struct TextureOwner {
    // Four slots also accommodate engine output wrappers without putting
    // neighbouring stack objects at risk; the NiTexture is the first slot.
    void* output[4]{};
    ~TextureOwner() {ReleaseTexture(output[0]);}
};

void GenerateTile(PlayerCharacter* player) {
    static ULONGLONG lastRender=0;
    static unsigned activeEpoch=0, tileCursor=0;
    // Center and its immediate neighbours precede the additional outer ring.
    static const int order[kTileCount]={12,7,11,13,17,6,8,16,18,
            2,10,14,22,1,3,5,9,15,19,21,23,0,4,20,24};
    TESObjectCELL* cell=player->parentCell;
    unsigned captureEpoch;int centerX,centerY,index;bool inside;
    ULONGLONG now=GetTickCount64();
    {
        std::lock_guard<std::mutex> lock(mutex);
        if (activeEpoch!=epoch) {activeEpoch=epoch;tileCursor=0;lastRender=0;}
        // Fill new tiles first (up to 20/second); retain overlapping pixels
        // across cell boundaries. Refresh existing terrain at four tiles/sec.
        bool missing=std::any_of(building.ready.begin(),building.ready.end(),[](bool ready){return !ready;});
        ULONGLONG interval=missing?50:250;
        if (now-lastRender<interval || building.tiles.size()!=kTileCount) return;
        captureEpoch=epoch;centerX=building.centerX;centerY=building.centerY;
        inside=interiorContext;index=order[tileCursor%kTileCount];
        if(missing) {
            for(unsigned i=0;i<kTileCount;++i) {
                int candidate=order[(tileCursor+i)%kTileCount];
                if(!building.ready[candidate]) {index=candidate;tileCursor+=i;break;}
            }
        }
    }
    lastRender=now;
    int x=centerX+(index%kGridSide)-kRadius, y=centerY+kRadius-(index/kGridSide);
    TESObjectCELL* sourceCell=cell;
    if (!inside) {
        using GetCellFn=TESObjectCELL* (__thiscall *)(TESWorldSpace*,int,int);
        sourceCell=reinterpret_cast<GetCellFn>(0x005875A0)(cell->worldSpace,x,y);
        if (!sourceCell || sourceCell->cellState!=TESObjectCELL::kCellLoadState_Attached) {
            ++tileCursor;
            std::lock_guard<std::mutex> lock(mutex);
            if(captureEpoch==epoch) building.ready[index]=true;
            return; // Never load remote cells just to draw a map.
        }
    }
    uintptr_t renderer=0;
    if (!ReadPointer(0x011F4748,renderer) || !renderer) return;
    if (tileCursor<kTileCount) {
        char detail[192]{};
        std::snprintf(detail,sizeof(detail),"FNVDS local offscreen render start cell=%08X interior=%u tile=%d coords=%d,%d",
                      sourceCell->refID,inside?1u:0u,index,x,y);
        WriteLog(detail);
    }
    MapTexturePayload payload;
    bool copied=false;
    {
        RendererLock rendererLock(reinterpret_cast<void*>(renderer));
        TextureOwner texture;
        if (inside) {
            using RenderInterior=void (__thiscall *)(TESObjectCELL*,int,int,void**);
            reinterpret_cast<RenderInterior>(0x0054E750)(sourceCell,x,y,texture.output);
        } else {
            using RenderExterior=void (__thiscall *)(TESObjectCELL*,void**);
            reinterpret_cast<RenderExterior>(0x0054E640)(sourceCell,texture.output);
        }
        copied=ReadTexture(texture.output,payload);
    }
    ++tileCursor;
    std::lock_guard<std::mutex> lock(mutex);
    // Discard this render if the map context was reset while it was being generated.
    if (captureEpoch!=epoch) return;
    if (!copied) {
        if (published.generation==0) status="LOCAL_MAP_RENDER_PENDING";
        if (tileCursor<=kTileCount) WriteLog("FNVDS local offscreen render returned no readable texture");
        return;
    }
    building.ready[index]=true;
    if (building.tiles[index].bytes!=payload.bytes || published.generation==0) {
        building.tiles[index]=std::move(payload);
        building.generation=++sequence;
        published=building;status="READY";
        WriteLog(("FNVDS local offscreen texture streamed tile="+std::to_string(index)
                +" generation="+std::to_string(sequence)).c_str());
    }
}

void Reset() {
    std::lock_guard<std::mutex> lock(mutex);
    ++epoch;building=Batch{};published=Batch{};positionState.clear();
    status="GENERATING_LOCAL_MAP";
}
void Update(PlayerCharacter* player) {
    if (!requested.load() || !g_gameReady.load() || !player || !player->parentCell
            || player->parentCell->cellState != TESObjectCELL::kCellLoadState_Attached) return;
    TESObjectCELL* cell=player->parentCell;
    const bool inside=cell->IsInterior();
    float rotation=0;
    if (inside) rotation=reinterpret_cast<float (__thiscall *)(TESObjectCELL*)>(0x00555AD0)(cell);
    // Same north rotation as the native local map.
    Point point{player->posX,player->posY};
    if (inside) point={point.x*std::cos(rotation)+point.y*std::sin(rotation),
                      point.y*std::cos(rotation)-point.x*std::sin(rotation)};
    float offset=inside?2048.0f:0;
    int cx=static_cast<int>(std::floor((point.x-offset)/4096.0f));
    int cy=static_cast<int>(std::floor((point.y-offset)/4096.0f));
    UInt32 id=inside?cell->refID:cell->worldSpace->refID;
    std::unique_lock<std::mutex> lock(mutex);
    const bool reset=forceRefresh.exchange(false);
    if (reset || building.cellId!=id || interiorContext!=inside
            || cx!=building.centerX || cy!=building.centerY) {
        Batch previous=std::move(building);
        bool sameArea=!reset && previous.cellId==id && interiorContext==inside && previous.tiles.size()==kTileCount;
        ++epoch;building=Batch{};
        building.cellId=id;building.centerX=cx;building.centerY=cy;
        building.tiles.assign(kTileCount,EmptyTile());interiorContext=inside;
        bool retained=false;
        // Reuse overlapping tiles when the window moves within the same map area.
        if(sameArea) for(int row=0;row<kGridSide;++row) for(int col=0;col<kGridSide;++col) {
            int oldCol=col+cx-previous.centerX,oldRow=row+previous.centerY-cy;
            if(oldCol>=0 && oldCol<kGridSide && oldRow>=0 && oldRow<kGridSide) {
                int index=row*kGridSide+col,oldIndex=oldRow*kGridSide+oldCol;
                building.tiles[index]=std::move(previous.tiles[oldIndex]);
                building.ready[index]=previous.ready[oldIndex];
                retained=retained || building.ready[index];
            }
        }
        west=(cx-kRadius)*4096.0f+offset;north=(cy+kRadius+1)*4096.0f+offset;
        if(retained) {
            building.generation=++sequence;published=building;status="READY";
        } else {published=Batch{};status="GENERATING_LOCAL_MAP";}
    }
    latestPlayerX=(point.x-west)/kWorldSpan;
    latestPlayerY=(north-point.y)/kWorldSpan;
    latestHeading=player->rotZ-rotation;
    lock.unlock();
    GenerateTile(player);
    lock.lock();
    char line[192]{};
    std::snprintf(line,sizeof(line),"STATE LOCALPOS %u %.6f %.6f %.6f\n",
                  published.generation,(point.x-west)/kWorldSpan,(north-point.y)/kWorldSpan,player->rotZ-rotation);
    positionState=line;
}

bool Send(SOCKET socket, unsigned& lastGeneration, Batch& lastBatch) {
    Batch batch;
    std::string state;
    float playerX,playerY,heading;
    {
        std::lock_guard<std::mutex> lock(mutex);
        state = status;
        playerX=latestPlayerX;playerY=latestPlayerY;heading=latestHeading;
        if (published.generation != lastGeneration) batch = published;
    }
    if (!SendLine(socket, "STATE LOCALSTATUS " + state)) return false;
    if (batch.generation == 0) return true;
    if (!SendLine(socket, "STATE LOCALBEGIN " + std::to_string(batch.generation)
            + " " + std::to_string(batch.cellId) + " " + std::to_string(batch.centerX)
            + " " + std::to_string(batch.centerY))) return false;
    for (size_t i = 0; i < batch.tiles.size(); ++i) {
        const auto& payload = batch.tiles[i];
        // Compare by grid position: a retained tile can occupy a different slot after the window moves.
        if(lastGeneration!=0 && lastBatch.cellId==batch.cellId && lastBatch.tiles.size()==kTileCount) {
            int oldCol=static_cast<int>(i%kGridSide)+batch.centerX-lastBatch.centerX;
            int oldRow=static_cast<int>(i/kGridSide)+lastBatch.centerY-batch.centerY;
            if(oldCol>=0 && oldCol<kGridSide && oldRow>=0 && oldRow<kGridSide
                    && payload.bytes==lastBatch.tiles[oldRow*kGridSide+oldCol].bytes) continue;
        }
        const std::string key = "LOCAL" + std::to_string(i);
        if (!SendLine(socket, "STATE MAPDATA BEGIN " + key + " " + std::to_string(payload.bytes.size())
                + " " + std::to_string(payload.bytes.size()) + " 0")) return false;
        for (size_t offset = 0; offset < payload.bytes.size(); offset += 1200) {
            const size_t length = (std::min)(size_t(1200), payload.bytes.size() - offset);
            if (!SendLine(socket, "STATE MAPDATA CHUNK " + key + " " + Base64Encode(std::string(
                    reinterpret_cast<const char*>(payload.bytes.data() + offset), length)))) return false;
        }
        if (!SendLine(socket, "STATE MAPDATA END " + key)) return false;
    }
    if(!SendLine(socket,"STATE LOCALEND "+std::to_string(batch.generation))) return false;
    char position[192]{};
    std::snprintf(position,sizeof(position),"STATE LOCALPOS %u %.6f %.6f %.6f",
                  batch.generation,playerX,playerY,heading);
    if(!SendLine(socket,position)) return false;
    lastGeneration=batch.generation;lastBatch=std::move(batch);return true;
}
}
