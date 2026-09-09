package hydraulic.url

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

internal const val ZSH_SETUP_BEGIN = "# >>> Hydraulic URL integration >>>"
internal const val ZSH_SETUP_END = "# <<< Hydraulic URL integration <<<"

internal val ZSH_SETUP_BLOCK = """
    $ZSH_SETUP_BEGIN
    # Rewrite an HTTP(S) command line before zsh treats its slashes as a local path.
    function _hydraulic_url_accept_line() {
      if [[ ${'$'}BUFFER =~ '^([[:space:]]*)(https?://[^[:space:]]+)' ]]; then
        local leading=${'$'}match[1]
        local command_name=${'$'}match[2]
        local rest=${'$'}{BUFFER:${'$'}{#MATCH}}
        BUFFER="${'$'}{leading}url --execute ${'$'}{(q)command_name} --${'$'}rest"
      fi
      zle _hydraulic_url_previous_accept_line
    }
    if [[ -o interactive && ${'$'}widgets[accept-line] != user:_hydraulic_url_accept_line ]]; then
      zle -A accept-line _hydraulic_url_previous_accept_line
      zle -N accept-line _hydraulic_url_accept_line
    fi
    $ZSH_SETUP_END
""".trimIndent()

internal fun installZshIntegration(zshrc: Path): Boolean {
    val existing = if (zshrc.exists()) zshrc.readText() else ""
    val managedBlock = Regex(
        "(?s)(?:\\n\\n)?${Regex.escape(ZSH_SETUP_BEGIN)}.*?${Regex.escape(ZSH_SETUP_END)}(?:\\n)?"
    )
    val withoutManagedBlock = existing.replace(managedBlock, "").trimEnd()
    val updated = buildString {
        if (withoutManagedBlock.isNotEmpty()) {
            append(withoutManagedBlock)
            append("\n\n")
        }
        append(ZSH_SETUP_BLOCK)
        append('\n')
    }
    if (existing == updated)
        return false
    Files.createDirectories(zshrc.toAbsolutePath().parent)
    zshrc.writeText(updated)
    return true
}
