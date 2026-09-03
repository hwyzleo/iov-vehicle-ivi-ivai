package net.hwyz.iov.vehicle.ivi.ivai.tool.registry

import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.definitions.ToolDefinition

/**
 * In-memory registry of tool definitions. Registration order is preserved.
 */
class ToolRegistry {

    private val tools = LinkedHashMap<String, ToolDefinition>()

    fun register(tool: ToolDefinition): ToolRegistry {
        require(tools.put(tool.toolId, tool) == null) {
            "Duplicate tool registration: ${tool.toolId}"
        }
        return this
    }

    fun get(toolId: String): ToolDefinition? = tools[toolId]

    fun toolIds(): Set<String> = tools.keys

    fun all(): List<ToolDefinition> = tools.values.toList()

    fun count(): Int = tools.size
}
