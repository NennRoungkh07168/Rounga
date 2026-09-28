package feather.model

/**
 * The G-code dialect a controller speaks. Two boards with different chips can
 * share a dialect (Uno and ESP32 both running GRBL); the generator only ever
 * branches on this, never on the board name.
 */
enum class GcodeDialect {
    GRBL,
    MARLIN,
    SMOOTHIEWARE,
    /** Anything else: emit plain RS-274 and let the operator's Custom tool fill gaps. */
    GENERIC,
}

/**
 * A supported controller board + firmware pairing.
 *
 * Arduino Uno/Nano + GRBL is *one entry in this list*, not the shape of the
 * whole app: every other screen, file and toolpath is written against
 * [GcodeDialect] and [feather.link.MachineLink], never against this enum
 * directly, so adding a new board is additive.
 */
enum class Controller(val displayName: String, val dialect: GcodeDialect, val defaultBaudRate: Int) {
    ARDUINO_UNO_GRBL("Arduino Uno (GRBL)", GcodeDialect.GRBL, 115_200),
    ARDUINO_NANO_GRBL("Arduino Nano (GRBL)", GcodeDialect.GRBL, 115_200),
    ESP32_GRBL("ESP32 (GRBL / grblHAL)", GcodeDialect.GRBL, 115_200),
    ESP32_MARLIN("ESP32 (Marlin)", GcodeDialect.MARLIN, 115_200),
    RAMPS_MARLIN("RAMPS 1.4 (Marlin)", GcodeDialect.MARLIN, 250_000),
    SKR_MARLIN("SKR / 32-bit board (Marlin)", GcodeDialect.MARLIN, 250_000),
    SMOOTHIEBOARD("Smoothieboard", GcodeDialect.SMOOTHIEWARE, 115_200),
    CUSTOM("Custom controller", GcodeDialect.GENERIC, 115_200),
}

/** How the app talks to a device. Every [Controller] can be reached over any of these. */
enum class ConnectionType { BLE, WIFI, USB, SD }
