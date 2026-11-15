# Pip-Boy radio stations

`RefreshPipBoyRadioList` invokes the native MapMenu radio-list refresh on the
game thread, throttled to two seconds for an unchanged menu instance. It uses
the game's discovered/in-range filtering instead of scanning world references
for station emitters. The player does not need to tune a station first.

The station snapshot is built from that live list and records the allowed
station references in `g_pipboyRadioStations`. Current tuning reads the native
Pip-Boy radio state. Packets are `RADIO`, `RADIOSTATIONS BEGIN/END` and
`RADIOSTATION` records; names are encoded for transport.

`TUNERADIO <station>` is revalidated against the current station registry.
The game-thread action uses the native `PipboyRadio` script command through
xNVSE's console interface: tuning the already-playing station turns it off.
No additional NVSE plugin is required.

The Android waveform is decorative and does not sample the radio's audio.
See `RadioWaveformView.java` for its shared periodic animation clock.
