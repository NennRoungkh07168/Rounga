# Rounga (was Feather) — Android machine control app

A phone app for driving GRBL/Marlin CNC routers, lasers, plotters and PCB
mills over Bluetooth, Wi-Fi or SD export: design, edit, calibrate, and run
jobs, all in one place.

> **Status:** not compiled by me — this was built without a working Kotlin/
> Gradle toolchain (no network, no Android SDK, in the environment I wrote
> it in). It's been through several rounds of manual brace/paren balance
> checks and cross-reference checks (every function called from the UI
> confirmed to exist in the ViewModel, etc.), but it has **never actually
> been run through `gradlec` or a real device**. Expect a handful of
> first-sync errors in Android Studio — send me the Build panel output and
> I'll fix them in one pass.

## What's in this build

**Five tabs:** Design · Devices · Tools · Machine · Files

- **Design** has three modes: **Drawing** (the CAD-style board — draw,
  select, transform, trace, simplify, generate toolpath), **Interior 3D**
  (furnish a room in plan view, look around it in 3D, plot the floor plan
  onto the board at a chosen scale) and **Photo** (crop, tone, presets,
  masking, background removal, then trace straight into cutting paths).
- **Devices**: starred machine presets, Create machine, per-machine Profile
  and Tool settings, **Copy settings → Apply to** with a tick-list showing
  exactly what would change, and **Share via QR** / **Scan QR** to move a
  whole machine setup to another phone.
- **Tools**: Calibration (steps/mm, squareness, backlash, screen scale,
  **auto-level** height-map probing), Workpiece (origin, probe Z, edge
  finder, stock dimensions), Tool, **Material** (a starter library of
  common materials with power/speed/passes, shareable by QR), Manufacturing
  (generate, simulate, estimate, optimise, validate), Machine (home,
  unlock, jog, reset, status, console).
- **Machine**: one live connection shared by jog, position readout,
  Start/Pause/STOP and a console.
- **Files**: projects saved inside the app, open/save/export, picture
  import (including a **macro** mode for close-up shots), settings.

**Also:** a hand-drawn signature wordmark and quill splash screen (no font
file, just Bezier curves), a light teal/indigo/orange colour theme, and a
**material guess (beta)** in the Photo editor — an honestly-labelled rough
guess from colour/texture/glare, not a real material identification (no
phone camera can do that; see ARCHITECTURE.md for why).

## Known gaps

- Never run against real hardware. The BLE/Wi-Fi transports, the GRBL
  probe-report parsing, and especially the new auto-level probing sequence
  all need real-machine testing before you trust them on actual material.
- Offset, laser fill/hatching, USB serial, text tool, and multi-select by
  rubber-band are not implemented.
- The 3D interior view is a plain software renderer — no textures, no
  shadows, no wall thickness.
- The generator still emits Z moves for laser/plotter tools that have no Z
  axis defined; harmless on GRBL, just unused.

See ARCHITECTURE.md and BUILD.md for how the pieces fit together and how
to get a clean first build.
