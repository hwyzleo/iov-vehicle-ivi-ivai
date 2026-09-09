package net.hwyz.iov.vehicle.ivi.ivai.tool.registry.schemas

/**
 * JSON Schema (subset) texts for the first-batch climate tools.
 * Parsed and enforced by tool-runtime.
 */
object ClimateSchemas {

    private const val POSITION_PROPERTY =
        """"position": { "type": "string", "enum": ["driver", "passenger", "front", "rear"], "default": "driver" }"""

    val powerSchema: String = """
        {
          "type": "object",
          "properties": { $POSITION_PROPERTY },
          "required": []
        }
    """.trimIndent()

    val stepSchema: String = """
        {
          "type": "object",
          "properties": {
            $POSITION_PROPERTY,
            "step": { "type": "integer", "minimum": 1, "maximum": 5, "default": 1 }
          },
          "required": []
        }
    """.trimIndent()

    val temperatureSetSchema: String = """
        {
          "type": "object",
          "properties": {
            $POSITION_PROPERTY,
            "temperature": { "type": "number", "minimum": 16.0, "maximum": 30.0 }
          },
          "required": ["temperature"]
        }
    """.trimIndent()

    val statusQuerySchema: String = """
        {
          "type": "object",
          "properties": {
            "queryItem": {
              "type": "string",
              "enum": ["power", "driverTemperature", "passengerTemperature", "frontTemperature", "rearTemperature"]
            }
          },
          "required": []
        }
    """.trimIndent()
}
