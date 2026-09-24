package app.manka

import app.manka.core.Args
import app.manka.core.Engine
import app.manka.core.PresetRenderer
import app.manka.core.Strategies
import app.manka.core.Z1ToZ2
import org.junit.Test
import java.io.File

/**
 * Writes every built-in strategy as resolved command lines (arguments separated by \u001f)
 * to build/strategies/<engine>.txt. scripts/validate-strategies.sh feeds them to the real binaries.
 */
class ExportStrategiesTest {
    @Test
    fun export() {
        val out = File("build/strategies").apply { mkdirs() }
        for (engine in Engine.entries) {
            val templates = Strategies.builtinPresets().filter { it.engine == engine }.map { it.template } +
                Strategies.candidates(engine, full = true).map { it.template }
            val lines = templates.distinct().map { t ->
                val args = PresetRenderer.fromWinws(Args.split(t), null, null).args
                Args.resolve(args, fakeSni = "www.google.com").joinToString("\u001f")
            }
            File(out, "${engine.id}.txt").writeText(lines.joinToString("\n", postfix = "\n"))
        }
        // zapret strategies converted for zapret2, like the "LEGACY" store presets
        val legacy = (
            Strategies.builtinPresets().filter { it.engine == Engine.ZAPRET }.map { it.template } +
                Strategies.candidates(Engine.ZAPRET, full = true).map { it.template }
            ).distinct().map { t ->
            val converted = Z1ToZ2.convert(PresetRenderer.fromWinws(Args.split(t), null, null).args)
            check(converted.errors.isEmpty()) { "cannot convert $t: ${converted.errors}" }
            Args.resolve(converted.args, fakeSni = "www.google.com").joinToString("\u001f")
        }
        File(out, "zapret2-legacy.txt").writeText(legacy.joinToString("\n", postfix = "\n"))
    }
}
