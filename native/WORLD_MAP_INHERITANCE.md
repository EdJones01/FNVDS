# Shared world-map handling

Child worlds can share a parent world map while retaining their own coordinates. Marker collection and projection must use the world whose map is displayed.

`DisplayMapWorld` follows parent links only while bit 2 of parentFlags is set. A bounded, cycle-checked traversal returns the world whose map is actually displayed. Worlds with independent maps remain separate. Player snapshot identity, marker scans and quest/custom projections use this display world, so moving into a child world that shares the same map does not discard the parent map's markers.

`NormalizeWorldMapReference` retains the reference's actual source world. For an inheriting child, it calls the native forward point transform at 0x00588E40 on a stack copy of its position, then applies the existing normalization against the display world's bounds. References already in the display world use the existing projection directly. No game reference or worldspace is modified. Local-map generation and marker projection continue to use the actual cell/world coordinates.

Marker collection scans the display world and all loaded worldspace records whose map parent chain resolves to it, including their persistent cells. References/cells are deduplicated and existing known/discovered/hidden filtering remains. Fast-travel destinations still retain their original game reference IDs. The existing scan diagnostic now reports the actual emitted count rather than the number of all encountered references.

Runtime references inspected for behavior/addresses (no implementation copied):
- https://github.com/momusdigital/fnv_decomp/blob/main/src/fallout_shared/tesworldspace.cpp — GetParentWorld 0x00586390, GetWorldMapData 0x00586400, TransformWorldPoint 0x00588E40, shared-map name collection 0x005883C0.
- https://github.com/momusdigital/fnv_decomp/blob/main/src/fallout/interface/menus/mapmenu.cpp — WorldToMapPosition 0x0079C380 applies the source world's forward transform before normalizing to the map bounds.
- Local xNVSE 6.4.9 GameForms.h TESWorldSpace layout: parentFlags +0x4E, parent +0x70, MapData +0x80, scale/offsets +0x90/+0x94/+0x98.

Build checks cannot establish GameNative runtime behavior. Verify the McCarran exterior/terminal round trip, markers/search, player position and quest/custom markers; also check an independent DLC map.
