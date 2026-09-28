# Multi-device architecture (added)

The app's design work and G-code generation were driven by a single, global
`JobSettings` — one depth, one feed rate, no tool, no per-machine travel
limits. That's why the mockups can show a "My Devices" list but the actual
job pipeline behind it had nowhere to put device-specific facts.

This adds the missing layer, under `app/src/main/java/feather/model/`:

```
Projects → Designs → Device → MachineProfile → ToolHead → GCodeGenerator → MachineLink → Machine
                        │            │              │
                        │            │              └ Spindle / Laser / Pen / Drill / PcbCutter /
                        │            │                Knife / Extruder / Custom — each with its own params
                        │            └ machine type, axes, travel, steps/mm, max feed/accel, homing,
                        │              soft limits, coordinate system, units
                        └ connection (BLE/Wi-Fi/USB/SD) + controller (board+firmware) + tool list
```

**`Controller.kt`** — board + firmware as one enum entry (`ARDUINO_UNO_GRBL`,
`ESP32_MARLIN`, `SMOOTHIEBOARD`, `CUSTOM`, ...), each carrying a
`GcodeDialect`. Arduino+GRBL is one supported profile, not the app's model of
what a controller is — adding SKR/Marlin or Smoothieboard is one new enum
entry, nothing else changes.

**`MachineProfile.kt`** — per-axis travel, steps/mm, max feed, max
acceleration, soft-limit flag, homing, units, coordinate system. A profile is
saved once and reused; it has no opinion about what tool is mounted.

**`ToolHead.kt`** — sealed class, one case per tool family, each with its own
real parameters (laser: power %, PWM max, watts; spindle: RPM range, tool
diameter, max cut depth; plotter: pen up/down heights; extruder: nozzle,
filament, temps; etc.). Named `ToolHead` rather than `Tool` on purpose —
`feather.link.Tool` already names the drawing-tool enum (pan/line/rect/…) and
the two must never collide.

**`Device.kt`** — what "My Devices" actually saves: connection + address,
controller, one `MachineProfile`, a list of `ToolHead`s, and which one is
active. Switching tool is `device.withActiveTool(id)`, not a re-flash.

**`MachineContext.kt`** — the one object `GCodeGenerator` needs: profile +
active tool + dialect. `Device.toMachineContext()` builds it. It:
- validates a job's footprint, cut depth, and feed rate against *this*
  machine before anything is sent (`MachineLimitException` instead of an
  ALARM on the controller);
- emits the right tool-on/off command for whatever's mounted — `M3 S<rpm>`
  for a spindle, `M3 S<pwm>` scaled from laser power %, pen up/down moves for
  a plotter, `M104/M109` for an extruder, or a fully custom pair.

**`DeviceStore.kt` / `DeviceRepository.kt`** — plain-JSON persistence for the
device list + active-device selection (same atomic-write approach as
`FeatherFile` uses for `.feather` projects), plus a `StateFlow`-backed
repository for Compose.

**`SampleDevices.kt`** — the four-device fleet from the spec (CNC Router
400×400×80, Laser 300×300 10 W, PCB Mill 200×150×50, Plotter 600×400) as real
`Device` values, for previews/tests/seed data.

**`GCodeGenerator.kt`** gained a second `generate(job, machine: MachineContext)`
overload that does the validation + tool-on/off; the original single-arg
`generate(job)` is kept for existing callers and tests. `app/src/test/java/feather/model/MultiDeviceGCodeTest.kt`
proves the same rectangle produces different, correctly-scoped G-code across
all four sample devices, and gets rejected outright when it doesn't fit.

## Wired up in 0.2

`FeatherViewModel` now owns a `DeviceRepository` (seeded with `Presets.starter()` on first run) and
generates G-code through `Device.toMachineContext()`. `MachineSession` keeps one live link open; the job
runner reuses it (BLE and Wi-Fi bridges accept one client). `ToolParams` / `ProfileParams` are the single
source for the editor, Copy and Apply. `ImageTracer`, `GcodeAnalysis` and `ShapeOps` are pure Kotlin with tests.

## What this didn't include (historic note, see README for the current list)

The Compose screens in the mockups (Devices list, Add Device, Machine
Profile, Tool Settings) still need to be built against `DeviceRepository` —
the previous single `JobSettings` in `FeatherViewModel` needs to shrink to
just the path-shape parameters (depth, step-down, feed, safe height) while
device/tool selection moves to its own state backed by this new layer. That's
the natural next slice.
