package dev.macromod.fabric

import dev.macromod.engine.action.ConfigController

/**
 * Routes the `config` / `import` / `unimport` actions to the host. `config(name)` switches the
 * active profile (the host's [onSwitch] calls engine.configs.switchTo + fires onConfigChange);
 * import / unimport stay visible feedback until config-file loading exists. Dependency-free (only
 * the engine interface) so it compiles on every version.
 */
class FabricConfigController(
    private val onSwitch: (String) -> Unit,
    private val feedback: (String) -> Unit,
) : ConfigController {
    override fun switchConfig(name: String) { onSwitch(name) }
    override fun importConfig(file: String) { feedback("[import] $file") }
    override fun unimportConfig(file: String) { feedback("[unimport] $file") }
}
