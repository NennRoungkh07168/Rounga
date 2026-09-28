This folder is not part of the Android Gradle build above — it's the
Windows-side counterpart, meant to be dropped into its own WPF/Avalonia
`.csproj` in Visual Studio. `MachineLink.cs` defines `IMachineLink` and the
Serial/Wi-Fi/SD-card transports matching the Android side's `MachineLink`
interface field-for-field, so a Windows ViewModel (still to build — see
the main README/BUILD.md) can drive the same protocol.
