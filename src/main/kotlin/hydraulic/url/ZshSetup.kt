package hydraulic.url

import org.intellij.lang.annotations.Language
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

internal const val ZSH_SETUP_BEGIN = "# >>> Hydraulic URL integration >>>"
internal const val ZSH_SETUP_END = "# <<< Hydraulic URL integration <<<"

@Language("zsh")
internal val ZSH_SETUP_BLOCK = $$"""
    $$ZSH_SETUP_BEGIN
    # Prefix an HTTP(S) URL or bare domain before zsh treats its slashes as a local path.
    function _hydraulic_url_accept_line() {
      # Is this a dry run?
      if [[ $BUFFER =~ '^([[:space:]]*)[?][[:space:]]*(.*)' ]]; then
        local leading=$match[1]
        local command_line=$match[2]
        local command_name rest
        if [[ ${command_line[1]} == '"' || ${command_line[1]} == "'" ]]; then
          local -a parts
          parts=("${(@z)command_line}")
          command_name=${(Q)parts[1]}
          rest=${command_line:${#parts[1]}}
        else
          command_name=${command_line%%[[:space:]]*}
          rest=${command_line:${#command_name}}
        fi
        if [[ -n $command_name ]]; then
          BUFFER="${leading}run -d ${(q)command_name}$rest"
        fi
      elif [[ $BUFFER =~ '^([[:space:]]*)(https?://[^[:space:]]+)' ]]; then
        local leading=$match[1]
        local command_name=$match[2]
        local rest=${BUFFER:${#MATCH}}
        BUFFER="${leading}run ${(q)command_name}$rest"
      # Match an optional leading indent, then a domain with an alphanumeric
      # first character and a final alphabetic label of at least two letters.
      # A port and a slash-prefixed path may follow the domain. Stop the match
      # at whitespace so the remaining shell words stay as run arguments.
      elif [[ $BUFFER =~ '^([[:space:]]*)([[:alnum:]][[:alnum:].-]*\.[[:alpha:]]{2,}(:[0-9]+)?(/[^[:space:]]*)?)' ]]; then
        local leading=$match[1]
        local command_name=$match[2]
        local rest=${BUFFER:${#MATCH}}
        BUFFER="${leading}run ${(q)command_name}$rest"
      fi
      zle _hydraulic_url_previous_accept_line
    }
    if [[ -o interactive && $widgets[accept-line] != user:_hydraulic_url_accept_line ]]; then
      zle -A accept-line _hydraulic_url_previous_accept_line
      zle -N accept-line _hydraulic_url_accept_line
    fi
    $$ZSH_SETUP_END
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
