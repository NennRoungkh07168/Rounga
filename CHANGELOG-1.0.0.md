# Rounga 1.0.0 — Interactive Sketcher

## Added
- Touch-driven 2D sketch plane in the CAD tab.
- Rectangle and circle drag tools.
- Construction-line measurement tool.
- 1 mm grid snapping.
- Pad height entry and conversion to existing Box/Cylinder solid features.
- Pure Kotlin sketch geometry helper and unit tests.

## Scope and limitations
- Rectangle horizontal/vertical relationships and circle equal-radius property are implicit constraints only.
- No general geometric constraint solver yet.
- No arbitrary polygon, spline, tangent, coincident, parallel or dimensional-driving constraints yet.
- Existing solid kernel remains the lightweight Rounga starter model, not FreeCAD/OpenCASCADE B-rep.
- APK not built in this environment; use GitHub Actions workflow.
