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


## 0.6.0 — 3D CAD starter workspace

The new **3D CAD** tab adds an initial mechanical solid-modelling workspace:

- Isometric 3D viewport with a millimetre grid and XYZ axes.
- Box and cylinder primitive creation.
- Model tree with selection, duplicate and delete.
- Editable width/depth/height and X/Y/Z position in millimetres.
- ASCII STL export through Android's document picker.

This is the first CAD milestone, not a complete FreeCAD replacement. STL is a tessellated output and does not retain editable feature history. Sketch constraints, parametric feature operations (Pad/Pocket/Revolve/Loft/Sweep), Boolean solid operations, assemblies, STEP/IGES and native FCStd compatibility remain future milestones.

Build with the existing GitHub Actions workflow (push the updated source to GitHub, then download the `Rounga-APK` artifact from Actions). On Termux, use the project's documented Gradle 8.7 setup and an installed Android SDK/JDK 17.


## 0.7.0 — 2D profile sketch and Pad

The CAD workspace now includes a first sketch-to-solid workflow:

- Choose a rectangular or circular 2D profile.
- Enter exact profile dimensions in millimetres.
- Enter an extrusion height and create a Pad solid.
- See the resulting solid in the isometric viewport and model tree.
- Each created object records a descriptive feature label; dimensions remain editable.
- Existing primitive creation, duplication, deletion and STL export remain available.

This is a constrained starter workflow, not yet a full interactive Sketcher: freehand geometry, geometric constraints, multi-feature dependency history, Pocket/Boolean operations and STEP/IGES exchange are still future work. The Android APK has not been compiled in this environment; use the included GitHub Actions workflow to build and verify it.


## 0.8.0 — Revolve (annular profile)

The CAD workspace adds a first rotational feature:

- Create a 360° hollow rotational solid from an annular rectangular profile around the Z axis.
- Set outer diameter, inner diameter and axial height in millimetres.
- Validate that the inner diameter is positive and smaller than the outer diameter.
- Display the hollow centre in the viewport and model tree.
- Export the revolved tube as a tessellated ASCII STL mesh, including outer wall, inner wall and annular end faces.

This is a deliberately limited first Revolve feature (a straight annular profile). It is not a general sketch-of-arbitrary-profile revolution. General Pocket/Boolean operations, geometric constraints, STEP/IGES and native FCStd compatibility remain unimplemented. The APK has not been compiled or device-tested in this environment; use GitHub Actions to build it and share any build log errors for correction.

## Version 0.9.0 — Pocket and box Boolean tools

- Added a rectangular Pocket operation for a selected axis-aligned Box. The pocket is centered on the top face and cuts downward by the requested depth.
- Added Boolean Cut, Union and Intersection for axis-aligned Box solids.
- Boolean results are represented as one or more non-overlapping axis-aligned Box features and remain editable as separate model-tree items.
- Added JVM unit tests for cut/intersection/union volume results.

Limitations: this is a lightweight box CSG implementation, not a general-purpose boundary-representation kernel. Boolean tools currently accept Box primitives only; cylinders, tubes, arbitrary sketches, curved surfaces and general manifold union are not supported. Union is decomposed into box components and may retain coplanar internal faces in STL; inspect/repair exported meshes before manufacturing. Pocket is a rectangular top-face cut only.


## 1.0.0 — Interactive 2D Sketcher and Pad

The CAD tab's former dimension-entry-only Sketch → Pad dialog is replaced by a touch sketch plane:

- Draw a rectangle or circle by dragging on a 2D grid.
- 1 mm grid snapping converts touch dimensions to millimetres.
- Rectangle profiles create axis-aligned Box pads; circle profiles create Cylinder pads.
- Enter a Pad height in millimetres and create the solid in the existing model tree.
- A line construction tool is included for measuring open sketch geometry; open lines cannot be padded.
- Sketch geometry has unit tests for snapping, minimum size, circle diameter and Pad conversion.

Constraint scope in this milestone is deliberately limited: rectangle edges are horizontal/vertical by construction and circles have equal radii. There is not yet a general parametric constraint solver (coincident, tangent, parallel, dimensional driving), nor freeform closed profiles. The sketcher currently creates one profile per Pad.
