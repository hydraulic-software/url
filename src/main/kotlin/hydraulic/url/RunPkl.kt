package hydraulic.url

import org.pkl.core.Evaluator
import org.pkl.core.EvaluatorBuilder
import org.pkl.core.ModuleSource
import org.pkl.core.PModule
import org.pkl.core.PObject
import org.pkl.core.StackFrameTransformers
import org.pkl.core.module.ModuleKey
import org.pkl.core.module.ModuleKeyFactory
import org.pkl.core.module.ModuleKeyFactories
import org.pkl.core.module.ModuleKeys
import java.net.URI
import java.nio.file.Path
import java.util.Optional
import java.util.regex.Pattern

internal data class RunContext(
    val os: String,
    val arch: String,
    val ver: String?,
    val args: List<String>,
    val packageDir: Path,
    val resolved: Map<String, Path> = emptyMap()
)

internal data class LaunchPlan(val executable: Path, val arguments: List<String>)

internal data class RunPackage(
    val urls: Map<String, String>,
    val launchPlan: (Map<String, Path>) -> LaunchPlan
)

private const val CONTEXT_URI = "run:context"

/** Evaluates a run.pkl package in two phases: discover URLs, then build a command. */
internal fun evaluateRunPackage(
    packageFile: Path,
    context: RunContext
): RunPackage {
    val packageDir = packageFile.toRealPath().parent ?: error("run.pkl must have a parent directory")
    val source = ModuleSource.path(packageFile.toRealPath())
    val urls = evaluator(packageDir, context).use { it.evaluateExpression(source, "this.urls") }
    require(urls is PObject) { "run.pkl property 'urls' must be an object" }
    val urlValues = urls.getProperties().mapValues { (name, value) ->
        require(PKL_PROPERTY.matches(name)) { "Invalid run.pkl URL name: $name" }
        require(value is String) { "run.pkl URL '$name' must be a string" }
        value
    }

    return RunPackage(urlValues) { resolved ->
        val finalContext = context.copy(resolved = resolved)
        evaluator(packageDir, finalContext).use { evaluator ->
            launchPlan(evaluator.evaluate(source), packageDir)
        }
    }
}

private fun evaluator(packageDir: Path, context: RunContext): Evaluator {
    val packageURI = packageDir.toUri().toString()
    val allowedPackageModules = Pattern.compile("^" + Pattern.quote(packageURI) + ".*")
    return EvaluatorBuilder.unconfigured()
        .setStackFrameTransformer(StackFrameTransformers.empty)
        .setRootDir(packageDir)
        .setAllowedModules(listOf(
            allowedPackageModules,
            Pattern.compile("^$CONTEXT_URI$"),
            Pattern.compile("^repl:text$")
        ))
        .addModuleKeyFactory(ModuleKeyFactories.standardLibrary)
        .addModuleKeyFactory(ModuleKeyFactories.file)
        .addModuleKeyFactory(ContextModuleKeyFactory(contextModule(context)))
        .build()
}

private class ContextModuleKeyFactory(private val source: String) : ModuleKeyFactory {
    override fun create(uri: URI): Optional<ModuleKey> =
        if (uri.toString() == CONTEXT_URI)
            Optional.of(ModuleKeys.synthetic(uri, source))
        else
            Optional.empty()
}

private fun contextModule(context: RunContext): String = buildString {
    appendLine("module run.context")
    appendLine("class Resolved {")
    context.resolved.forEach { (name, _) ->
        require(PKL_PROPERTY.matches(name)) { "Invalid run.pkl URL name: $name" }
        appendLine("  $name: String")
    }
    appendLine("}")
    appendLine("os = ${pklString(context.os)}")
    appendLine("arch = ${pklString(context.arch)}")
    appendLine("ver: String? = ${context.ver?.let(::pklString) ?: "null"}")
    appendLine("args = List(${context.args.joinToString(", ") { pklString(it) }})")
    appendLine("packageDir = ${pklString(context.packageDir.toString())}")
    appendLine("resolved = new Resolved {")
    context.resolved.forEach { (name, path) ->
        appendLine("  $name = ${pklString(path.toAbsolutePath().normalize().toString())}")
    }
    appendLine("}")
}

private fun pklString(value: String): String = buildString {
    append('"')
    value.forEach { character ->
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(character)
        }
    }
    append('"')
}

private fun launchPlan(module: PModule, packageDir: Path): LaunchPlan {
    val executableValue = module.getProperty("executable")
    require(executableValue is String && executableValue.isNotEmpty()) {
        "run.pkl property 'executable' must be a non-empty string"
    }
    val executable = Path.of(executableValue).let { path ->
        when {
            path.isAbsolute() -> path.normalize()
            path.parent == null -> path
            else -> packageDir.resolve(path).toAbsolutePath().normalize()
        }
    }

    val argumentsValue = module.getProperty("arguments")
    require(argumentsValue is List<*>) { "run.pkl property 'arguments' must be a list of strings" }
    val arguments = argumentsValue.mapIndexed { index, value ->
        require(value is String) { "run.pkl property 'arguments[$index]' must be a string" }
        value
    }
    return LaunchPlan(executable, arguments)
}

private val PKL_PROPERTY = Regex("[A-Za-z_][A-Za-z0-9_]*")
