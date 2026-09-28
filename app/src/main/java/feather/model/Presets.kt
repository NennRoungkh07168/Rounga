package feather.model

import java.util.UUID

/**
 * First-run machine presets, starred so they sit at the top of My Devices. They
 * carry no address: the operator picks "Find machine" once per device, and the
 * address is remembered on that device from then on.
 */
object Presets {

    fun starter(): List<Device> = listOf(
        Device(
            id = "preset-cnc-400",
            name = "My CNC 400×400",
            connection = ConnectionType.BLE,
            controller = Controller.ARDUINO_NANO_GRBL,
            profile = MachineProfile(
                id = "profile-preset-cnc-400",
                name = "My CNC 400×400",
                machineType = MachineType.CNC_ROUTER,
                axes = mapOf(
                    Axis.X to AxisLimits(travelMm = 400.0, maxFeedMmPerMin = 3_000.0),
                    Axis.Y to AxisLimits(travelMm = 400.0, maxFeedMmPerMin = 3_000.0),
                    Axis.Z to AxisLimits(travelMm = 80.0, maxFeedMmPerMin = 800.0),
                ),
            ),
            tools = listOf(ToolHead.Spindle(id = "tool-cnc-spindle", name = "Spindle 3.175 mm")),
            favorite = true,
        ),
        Device(
            id = "preset-laser-300",
            name = "My Laser 300×300",
            connection = ConnectionType.BLE,
            controller = Controller.ESP32_GRBL,
            profile = MachineProfile(
                id = "profile-preset-laser-300",
                name = "My Laser 300×300",
                machineType = MachineType.LASER,
                axes = mapOf(
                    Axis.X to AxisLimits(travelMm = 300.0, maxFeedMmPerMin = 6_000.0),
                    Axis.Y to AxisLimits(travelMm = 300.0, maxFeedMmPerMin = 6_000.0),
                ),
            ),
            // Two heads of the same kind so "Copy settings > Apply to" has something real to do.
            tools = listOf(
                ToolHead.Laser(id = "tool-laser-10w", name = "Laser 10W", maxPowerWatts = 10.0, defaultPowerPercent = 60),
                ToolHead.Laser(id = "tool-laser-20w", name = "Laser 20W", maxPowerWatts = 20.0, defaultPowerPercent = 40),
            ),
            favorite = true,
        ),
        Device(
            id = "preset-pcb-mill",
            name = "PCB Mill",
            connection = ConnectionType.BLE,
            controller = Controller.ARDUINO_UNO_GRBL,
            profile = MachineProfile(
                id = "profile-preset-pcb-mill",
                name = "PCB Mill",
                machineType = MachineType.PCB_MILL,
                axes = mapOf(
                    Axis.X to AxisLimits(travelMm = 200.0, maxFeedMmPerMin = 1_500.0),
                    Axis.Y to AxisLimits(travelMm = 150.0, maxFeedMmPerMin = 1_500.0),
                    Axis.Z to AxisLimits(travelMm = 50.0, maxFeedMmPerMin = 300.0),
                ),
            ),
            tools = listOf(
                ToolHead.PcbCutter(id = "tool-pcb-cutter", name = "V-bit 0.2 mm"),
                ToolHead.Drill(id = "tool-pcb-drill", name = "Drill 0.8 mm"),
            ),
            favorite = true,
        ),
        Device(
            id = "preset-pen-plotter",
            name = "Pen Plotter",
            connection = ConnectionType.BLE,
            controller = Controller.ARDUINO_NANO_GRBL,
            profile = MachineProfile(
                id = "profile-preset-pen-plotter",
                name = "Pen Plotter",
                machineType = MachineType.PLOTTER,
                axes = mapOf(
                    Axis.X to AxisLimits(travelMm = 600.0, maxFeedMmPerMin = 4_000.0),
                    Axis.Y to AxisLimits(travelMm = 400.0, maxFeedMmPerMin = 4_000.0),
                ),
            ),
            tools = listOf(ToolHead.Pen(id = "tool-pen", name = "Pen")),
            favorite = true,
        ),
    )

    /** "Create machine": a valid device for [type] with sensible defaults, never a blank invalid form. */
    fun blank(type: MachineType, name: String, controller: Controller, connection: ConnectionType): Device {
        val suffix = UUID.randomUUID().toString().take(8)
        val profile = MachineProfile.default("profile-$suffix", type).copy(name = name)
        return Device(
            id = "device-$suffix",
            name = name,
            connection = connection,
            controller = controller,
            profile = profile,
            tools = listOf(ToolParams.newTool(type, "tool-$suffix")),
            favorite = true,
        )
    }

    /** Turns a scanned [QrPayload.DeviceImport] into a real [Device], with a fresh id of its own. */
    fun fromImport(imp: QrPayload.DeviceImport, connection: ConnectionType): Device {
        var device = blank(imp.machineType, imp.name, imp.controller, connection)
        for ((key, value) in imp.profileRows) device = device.copy(profile = ProfileParams.withValue(device.profile, key, value))
        val tool = device.tools.firstOrNull()
        if (tool != null && ToolParams.familyName(tool) == imp.toolFamily) {
            var updated = ToolParams.withName(tool, imp.toolName)
            for ((key, value) in imp.toolRows) updated = ToolParams.withValue(updated, key, value)
            device = device.withTool(updated)
        }
        return device
    }

    /** One-line summary shown under a device name: "GRBL / Nano". */
    fun controllerSummary(c: Controller): String = when (c) {
        Controller.ARDUINO_UNO_GRBL -> "GRBL / Uno"
        Controller.ARDUINO_NANO_GRBL -> "GRBL / Nano"
        Controller.ESP32_GRBL -> "GRBL / ESP32"
        Controller.ESP32_MARLIN -> "Marlin / ESP32"
        Controller.RAMPS_MARLIN -> "Marlin / RAMPS"
        Controller.SKR_MARLIN -> "Marlin / SKR"
        Controller.SMOOTHIEBOARD -> "Smoothie"
        Controller.CUSTOM -> "Custom"
    }
}
