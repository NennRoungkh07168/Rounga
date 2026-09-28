package feather.model

/**
 * The exact four-device fleet from the architecture spec, as real [Device]
 * values — used for Compose previews, tests, and (optionally) first-run seed
 * data so "My Devices" isn't empty on a fresh install:
 *
 *     Device 1 -> CNC Router  400x400x80  GRBL     Spindle
 *     Device 2 -> Laser       300x300     GRBL     10 W Laser
 *     Device 3 -> PCB Mill    200x150x50  GRBL     Milling spindle
 *     Device 4 -> Plotter     600x400     GRBL     Pen
 */
object SampleDevices {

    val cncRouter = Device(
        id = "device-cnc-router",
        name = "CNC Router",
        connection = ConnectionType.WIFI,
        address = "192.168.1.45",
        controller = Controller.ARDUINO_NANO_GRBL,
        profile = MachineProfile(
            id = "profile-cnc-router",
            name = "CNC Router",
            machineType = MachineType.CNC_ROUTER,
            axes = mapOf(
                Axis.X to AxisLimits(travelMm = 400.0, maxFeedMmPerMin = 3_000.0),
                Axis.Y to AxisLimits(travelMm = 400.0, maxFeedMmPerMin = 3_000.0),
                Axis.Z to AxisLimits(travelMm = 80.0, maxFeedMmPerMin = 800.0),
            ),
        ),
        tools = listOf(ToolHead.Spindle(id = "tool-spindle", maxCutDepthMm = 20.0)),
    )

    val laserEngraver = Device(
        id = "device-laser",
        name = "Laser Engraver",
        connection = ConnectionType.BLE,
        address = "EC:12:34:56:78:9A",
        controller = Controller.ESP32_GRBL,
        profile = MachineProfile(
            id = "profile-laser",
            name = "Laser 10W",
            machineType = MachineType.LASER,
            axes = mapOf(
                Axis.X to AxisLimits(travelMm = 300.0, maxFeedMmPerMin = 6_000.0),
                Axis.Y to AxisLimits(travelMm = 300.0, maxFeedMmPerMin = 6_000.0),
            ),
        ),
        tools = listOf(ToolHead.Laser(id = "tool-laser-10w", maxPowerWatts = 10.0)),
    )

    val pcbMill = Device(
        id = "device-pcb-mill",
        name = "PCB Mill",
        connection = ConnectionType.BLE,
        address = "COM3",
        controller = Controller.ARDUINO_UNO_GRBL,
        profile = MachineProfile(
            id = "profile-pcb-mill",
            name = "PCB Mill",
            machineType = MachineType.PCB_MILL,
            axes = mapOf(
                Axis.X to AxisLimits(travelMm = 200.0, maxFeedMmPerMin = 1_500.0),
                Axis.Y to AxisLimits(travelMm = 150.0, maxFeedMmPerMin = 1_500.0),
                Axis.Z to AxisLimits(travelMm = 50.0, maxFeedMmPerMin = 300.0),
            ),
        ),
        tools = listOf(ToolHead.PcbCutter(id = "tool-pcb-cutter")),
    )

    val plotter = Device(
        id = "device-plotter",
        name = "Plotter",
        connection = ConnectionType.WIFI,
        address = "192.168.1.47",
        controller = Controller.ARDUINO_NANO_GRBL,
        profile = MachineProfile(
            id = "profile-plotter",
            name = "Plotter",
            machineType = MachineType.PLOTTER,
            axes = mapOf(
                Axis.X to AxisLimits(travelMm = 600.0, maxFeedMmPerMin = 4_000.0),
                Axis.Y to AxisLimits(travelMm = 400.0, maxFeedMmPerMin = 4_000.0),
            ),
        ),
        tools = listOf(ToolHead.Pen(id = "tool-pen")),
    )

    val fleet: List<Device> = listOf(cncRouter, laserEngraver, pcbMill, plotter)
}
