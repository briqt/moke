package com.briqt.moke.terminal

import android.content.Context

// type: remote command protocol
/** Assets are injected in JVM tests; loading does not execute either script. */
data class TmuxScripts(val observer: String, val probe: String) {
    companion object {
        fun load(context: Context): TmuxScripts = TmuxScripts(
            context.assets.open("tmux/observer.sh").bufferedReader(Charsets.UTF_8).use { it.readText() },
            context.assets.open("tmux/probe.sh").bufferedReader(Charsets.UTF_8).use { it.readText() },
        )
    }
}

/** Linux witness protocol. A transport failure, truncated frame, or absent result is never success. */
object TmuxRemote {
    private const val P = "__MOKE_TMUX_V1__|"
    private val noncePattern = Regex("[a-f0-9]{32}")
    private val requestPattern = Regex("[A-Za-z0-9_-]{1,96}")
    private val bootPattern = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
    private val idPattern = Regex("\\$[0-9]+")
    private val ttyPattern = Regex("/dev/pts/[0-9]+")
    private val panePattern = Regex("%[0-9]{1,10}")

    // Escape only framing metacharacters. Returned values are never evaluated as shell or formats.
    // Nested substitution escapes percent first; a literal %7C must not become a pipe on decoding.
    private fun paneField(variable: String) = "#{s/[|]/%7C/:#{s/%/%25/:$variable}}"
    private fun validPaneField(variable: String, limit: Int) =
        "#{&&:#{e|<=:#{n:$variable},$limit},#{==:#{m/r:[[:cntrl:]],#{$variable}},0}}"
    private val clientFormat: String get() {
        val identity = "#{client_pid}|#{client_created}|#{session_id}"
        val ordinary = "${P}CLIENT|$identity|#{client_tty}"
        val pane = "${P}CLIENT_PANE|$identity|#{session_created}|#{client_tty}|#{pane_id}|" +
            paneField("pane_title") + "|" + paneField("pane_current_path")
        val safe = "#{&&:${validPaneField("pane_title", SESSION_TITLE_LIMIT)}," +
            "${validPaneField("pane_current_path", SESSION_PATH_LIMIT)}}"
        // Unavailable metadata does not invalidate an otherwise useful, witnessed management list.
        return "#{?$safe,$pane,$ordinary}"
    }
    private val termPattern = Regex("[A-Za-z0-9._-]{1,32}")
    private val terms = listOf("xterm-256color", "screen-256color", "xterm", "vt100")

    private fun q(value: String) = "'" + value.replace("'", "'\\''") + "'"
    // new-session -s and rename-session format-expand names even without a -F flag.
    private fun tmuxLiteral(value: String) = value.replace("#", "##")
    private fun shell(body: String, vararg args: String) =
        "sh -c ${q(body)} moke-tmux" + args.joinToString("") { " ${q(it)}" }
    private fun text(value: String) = value.isNotEmpty() && value.none { it.isISOControl() }
    private fun name(value: String) = text(value) && ':' !in value && '.' !in value
    private fun token(value: String) = require(requestPattern.matches(value)) { "Invalid request id" }

    // All discovery ignores inherited TMUX. Every operation pins an explicit socket; only create
    // may start a server. Stat %d/%i and tty %t/%T are available in GNU coreutils 8.32.
    private val core = """
        moke_saved_lc_set=${'$'}{LC_ALL+x}; moke_saved_lc=${'$'}{LC_ALL-}
        export LC_ALL=C
        moke_restore_locale() {
            if [ "${'$'}moke_saved_lc_set" = x ]; then export LC_ALL="${'$'}moke_saved_lc"; else unset LC_ALL; fi
        }
        moke_user_locale() ( moke_restore_locale; "${'$'}@"; )
        moke_uint() { case "${'$'}1" in ''|*[!0-9]*) return 1;; esac; }
        moke_boot() {
            IFS= read -r moke_boot_id < /proc/sys/kernel/random/boot_id || return 1
            case "${'$'}moke_boot_id" in ????????-????-????-????-????????????) :;; *) return 1;; esac
            case "${'$'}moke_boot_id" in *[!a-f0-9-]*) return 1;; esac
        }
        moke_ticks() {
            IFS= read -r moke_stat < "/proc/${'$'}1/stat" || return 1
            moke_tail=${'$'}{moke_stat##*) }
            set -- ${'$'}moke_tail
            [ "${'$'}#" -ge 20 ] || return 1
            moke_uint "${'$'}{20}" && [ "${'$'}{20}" -gt 0 ] || return 1
            printf '%s\n' "${'$'}{20}"
        }
        moke_private_dir() {
            [ -d "${'$'}1" ] && [ ! -L "${'$'}1" ] || return 1
            [ "${'$'}(stat -c %u -- "${'$'}1" 2>/dev/null)" = "${'$'}(id -u)" ] || return 1
            moke_mode=${'$'}(stat -c %a -- "${'$'}1" 2>/dev/null) || return 1
            case "${'$'}moke_mode" in ''|*[!0-7]*) return 1;; esac
            [ "${'$'}((0${'$'}moke_mode & 7))" -eq 0 ]
        }
        moke_safe_base() {
            [ -d "${'$'}1" ] && [ ! -L "${'$'}1" ] || return 1
            moke_base_uid=${'$'}(stat -c %u -- "${'$'}1" 2>/dev/null) || return 1
            [ "${'$'}moke_base_uid" = 0 ] || [ "${'$'}moke_base_uid" = "${'$'}(id -u)" ] || return 1
            moke_base_mode=${'$'}(stat -c %a -- "${'$'}1" 2>/dev/null) || return 1
            case "${'$'}moke_base_mode" in ''|*[!0-7]*) return 1;; esac
            [ "${'$'}((0${'$'}moke_base_mode & 0022))" -eq 0 ] || [ "${'$'}((0${'$'}moke_base_mode & 01000))" -ne 0 ]
        }
        moke_default() {
            moke_uid=${'$'}(id -u) || return 1
            moke_uint "${'$'}moke_uid" || return 1
            # Upstream make_label: first realpath-able TMUX_TMPDIR, then /tmp;
            # TMPDIR is not tmux's socket base. Do not invoke make_label: it mkdirs.
            moke_base=
            if [ -n "${'$'}{TMUX_TMPDIR-}" ]; then moke_base=${'$'}(readlink -f -- "${'$'}TMUX_TMPDIR" 2>/dev/null); fi
            if [ -z "${'$'}moke_base" ] || [ ! -e "${'$'}moke_base" ]; then moke_base=${'$'}(readlink -f -- /tmp 2>/dev/null); fi
            moke_safe_base "${'$'}moke_base" || return 1
            moke_dir="${'$'}moke_base/tmux-${'$'}moke_uid"
            moke_socket="${'$'}moke_dir/default"
            if [ -e "${'$'}moke_dir" ] || [ -L "${'$'}moke_dir" ]; then moke_private_dir "${'$'}moke_dir" || return 1; fi
        }
        moke_create_parent() {
            moke_parent=${'$'}{moke_socket%/*}
            if [ -e "${'$'}moke_parent" ] || [ -L "${'$'}moke_parent" ]; then
                moke_private_dir "${'$'}moke_parent" || return 1
            else
                [ "${'$'}moke_parent" = "${'$'}moke_dir" ] || return 1
                # Atomic single-directory creation; never claim an unverified existing path.
                (umask 077; mkdir -m 700 -- "${'$'}moke_parent") 2>/dev/null || return 1
                moke_private_dir "${'$'}moke_parent" || return 1
            fi
            [ "${'$'}(readlink -f -- "${'$'}moke_parent")" = "${'$'}moke_parent" ]
        }
        moke_socket_identity() {
            [ -S "${'$'}moke_socket" ] && [ ! -L "${'$'}moke_socket" ] || return 1
            [ "${'$'}(readlink -f -- "${'$'}moke_socket" 2>/dev/null)" = "${'$'}moke_socket" ] || return 1
            [ "${'$'}(stat -c %u -- "${'$'}moke_socket" 2>/dev/null)" = "${'$'}(id -u)" ] || return 1
            moke_dev=${'$'}(stat -c %d -- "${'$'}moke_socket" 2>/dev/null) || return 1
            moke_ino=${'$'}(stat -c %i -- "${'$'}moke_socket" 2>/dev/null) || return 1
            moke_uint "${'$'}moke_dev" && moke_uint "${'$'}moke_ino" && [ "${'$'}moke_ino" -gt 0 ]
        }
        moke_server() {
            [ ! -L "${'$'}moke_socket" ] || return 3
            moke_socket_before=
            if [ -e "${'$'}moke_socket" ]; then
                moke_socket_identity || return 3
                moke_socket_before="${'$'}moke_dev:${'$'}moke_ino"
            fi
            moke_raw=${'$'}(tmux -N -u -S "${'$'}moke_socket" display-message -p '#{pid}|#{start_time}|#{socket_path}' 2>&1)
            moke_rc=${'$'}?
            if [ "${'$'}moke_rc" -ne 0 ]; then
                [ ! -L "${'$'}moke_socket" ] || return 3
                if [ -n "${'$'}moke_socket_before" ]; then
                    moke_socket_identity && [ "${'$'}moke_socket_before" = "${'$'}moke_dev:${'$'}moke_ino" ] || return 3
                else
                    [ ! -e "${'$'}moke_socket" ] || return 3
                fi
                case "${'$'}moke_raw" in
                    "error connecting to ${'$'}moke_socket (No such file or directory)"|"error connecting to ${'$'}moke_socket (Connection refused)"|"no server running on ${'$'}moke_socket") return 2;;
                    *) return 1;;
                esac
            fi
            IFS='|' read -r moke_pid moke_started moke_reported <<MOKE_SERVER_ROW
        ${'$'}moke_raw
        MOKE_SERVER_ROW
            moke_uint "${'$'}moke_pid" && [ "${'$'}moke_pid" -gt 1 ] &&
                moke_uint "${'$'}moke_started" && [ "${'$'}moke_started" -gt 0 ] || return 3
            [ "${'$'}moke_reported" = "${'$'}moke_socket" ] || return 3
            moke_boot && moke_socket_identity || return 3
            [ "${'$'}moke_socket_before" = "${'$'}moke_dev:${'$'}moke_ino" ] || return 3
            [ "${'$'}(stat -c %u -- "/proc/${'$'}moke_pid" 2>/dev/null)" = "${'$'}(id -u)" ] || return 3
            moke_start_ticks=${'$'}(moke_ticks "${'$'}moke_pid") || return 3
            moke_server_row="${'$'}moke_pid|${'$'}moke_started|${'$'}moke_start_ticks|${'$'}moke_dev|${'$'}moke_ino|${'$'}moke_boot_id|${'$'}moke_socket"
        }
        moke_result() {
            printf '${P}OP_BEGIN\n${P}RESULT|%s\n' "${'$'}1"
            if [ -n "${'$'}{2-}" ]; then printf '${P}DETAIL|%s\n' "${'$'}2"; fi
            printf '${P}OP_END|0\n'
        }
        moke_verify() {
            # Frozen /proc birth + peer boot + socket inode are checked in this very invocation.
            moke_boot && moke_socket_identity || return 3
            [ "${'$'}moke_boot_id" = "${'$'}moke_expected_boot" ] &&
                [ "${'$'}moke_dev" = "${'$'}moke_expected_dev" ] && [ "${'$'}moke_ino" = "${'$'}moke_expected_ino" ] || return 2
            [ -d "/proc/${'$'}moke_expected_pid" ] || return 2
            moke_actual_ticks=${'$'}(moke_ticks "${'$'}moke_expected_pid") || return 3
            [ "${'$'}moke_actual_ticks" = "${'$'}moke_expected_ticks" ] || return 2
            moke_server
            case "${'$'}?" in 0) :;; 2) return 2;; *) return 3;; esac
            [ "${'$'}moke_server_row" = "${'$'}moke_expected_row" ] || return 2
        }
    """.trimIndent()

    /** Read-only preflight. Unsupported hosts must use their original unwrapped terminal path. */
    fun capabilityCommand(): String = shell(core + "\n" + """
        for moke_tool in id stat readlink grep tty mkdir chmod mv rm rmdir cat; do
            command -v "${'$'}moke_tool" >/dev/null 2>&1 || { printf '${P}CAPABILITY|UNSUPPORTED\n'; exit 0; }
        done
        moke_boot && moke_ticks "${'$'}${'$'}" >/dev/null && moke_default || { printf '${P}CAPABILITY|UNSUPPORTED\n'; exit 0; }
        moke_base=${'$'}{TMPDIR:-/tmp}
        moke_meta=${'$'}(stat -c '%u %a' -- "${'$'}moke_base" 2>/dev/null)
        [ -d "${'$'}moke_base" ] && [ ! -L "${'$'}moke_base" ] &&
            [ "${'$'}(readlink -f -- "${'$'}moke_base")" = "${'$'}moke_base" ] || { printf '${P}CAPABILITY|UNSUPPORTED\n'; exit 0; }
        case "${'$'}moke_meta" in '0 1777'|"${'$'}moke_uid 700") :;; *) printf '${P}CAPABILITY|UNSUPPORTED\n'; exit 0;; esac
        printf 'moke\000' | grep -Fzqx -- moke || { printf '${P}CAPABILITY|UNSUPPORTED\n'; exit 0; }
        moke_fields=${'$'}(stat -c '%d %i %u %a' -- "/proc/${'$'}${'$'}" 2>/dev/null) || { printf '${P}CAPABILITY|UNSUPPORTED\n'; exit 0; }
        set -- ${'$'}moke_fields
        [ "${'$'}#" -eq 4 ] && moke_uint "${'$'}1" && moke_uint "${'$'}2" && moke_uint "${'$'}3" && moke_uint "${'$'}4" || { printf '${P}CAPABILITY|UNSUPPORTED\n'; exit 0; }
        moke_hex=${'$'}(stat -c '%t:%T' -- /dev/null 2>/dev/null)
        case "${'$'}moke_hex" in ''|*[!0-9a-fA-F:]*|:|:*|*:) printf '${P}CAPABILITY|UNSUPPORTED\n'; exit 0;; esac
        [ -x /bin/bash ] && BASH_ENV=/dev/null /bin/bash --noprofile --norc -c \
            'exec -a moke-capability /bin/bash --noprofile --norc -c '\''[ "${'$'}0" = moke-capability ]'\''' || { printf '${P}CAPABILITY|UNSUPPORTED\n'; exit 0; }
        printf '${P}CAPABILITY|SUPPORTED\n'
    """.trimIndent())

    fun launch(scripts: TmuxScripts, nonce: String, originalCommand: String?): String {
        require(noncePattern.matches(nonce)) { "Invalid owner nonce" }
        return shell(scripts.observer, nonce, if (originalCommand == null) "shell" else "command", originalCommand.orEmpty())
    }

    private fun owner(scripts: TmuxScripts, nonce: String?, required: Boolean, observation: Boolean): String {
        val failure = if (observation) {
            "case \"\$moke_witness\" in PENDING) moke_finish PENDING;; *) moke_finish UNKNOWN;; esac"
        } else {
            "case \"\$moke_witness\" in PENDING) moke_result STALE;; *) moke_result UNKNOWN;; esac; exit 0"
        }
        if (nonce == null) return if (required) "moke_witness=PENDING; $failure\n" else ""
        require(noncePattern.matches(nonce)) { "Invalid owner nonce" }
        return "moke_witness=\$(${shell(scripts.probe, nonce)}); " +
            "case \"\$moke_witness\" in WITNESS\\|*) :;; *) " +
            (if (required) "$failure" else "moke_witness=''") + ";; esac\n" +
            if (observation) "if [ -n \"\$moke_witness\" ]; then printf '${P}OWNER|%s|%s\\n' ${q(nonce)} \"\${moke_witness#WITNESS|}\"; fi\n" else ""
    }

    private fun selectSocket(socketPath: String?): String {
        if (socketPath == null) return "moke_default"
        require(socketPath.startsWith('/') && text(socketPath)) { "Invalid socket path" }
        return "moke_socket=${q(socketPath)}; moke_known_parent=\${moke_socket%/*}; " +
            "[ \"\$(readlink -f -- \"\$moke_known_parent\")\" = \"\$moke_known_parent\" ] && moke_safe_base \"\$moke_known_parent\""
    }

    private fun previousServerGuard(server: TmuxServerId): String = """
        moke_prior_row=${q("${server.pid}|${server.started}|${server.startedTicks}|${server.socketDevice}|${server.socketInode}|${server.bootId}|${server.socketPath}")}
        # Absence is proof only on a readable, unhidden Linux procfs. A hidden PID or
        # an unsupported stat/proc layout is UNKNOWN, not evidence of remote exit.
        moke_prior_proc_visible() (
            [ "${'$'}(stat -f -c %T -- /proc 2>/dev/null)" = proc ] || return 1
            moke_prior_mount=0
            set -f
            while IFS=' ' read -r moke_source moke_mount moke_fs moke_options moke_rest; do
                [ "${'$'}moke_mount" = /proc ] || continue
                [ "${'$'}moke_fs" = proc ] || return 1
                moke_prior_mount=1
                IFS=,
                for moke_option in ${'$'}moke_options; do
                    case "${'$'}moke_option" in hidepid=0) :;; hidepid=*) return 1;; esac
                done
            done < /proc/mounts
            [ "${'$'}moke_prior_mount" -eq 1 ]
        )
        # 0: the old birth is known gone; 1: it is alive; 2: cannot prove either.
        moke_prior_gone() {
            moke_boot && [ "${'$'}moke_boot_id" = ${q(server.bootId)} ] || return 2
            [ "${'$'}(stat -f -c %T -- /proc 2>/dev/null)" = proc ] || return 2
            moke_prior_uid=${'$'}(stat -c %u -- /proc/${server.pid} 2>&1)
            moke_prior_rc=${'$'}?
            if [ "${'$'}moke_prior_rc" -ne 0 ]; then
                case "${'$'}moke_prior_uid" in
                    "stat: cannot stat '/proc/${server.pid}': No such file or directory"|"stat: cannot statx '/proc/${server.pid}': No such file or directory") :;;
                    *) return 2;;
                esac
                moke_prior_proc_visible 2>/dev/null || return 2
                return 0
            fi
            moke_uint "${'$'}moke_prior_uid" || return 2
            { IFS= read -r moke_prior_stat < /proc/${server.pid}/stat; } 2>/dev/null || return 2
            moke_prior_tail=${'$'}{moke_prior_stat##*) }
            set -- ${'$'}moke_prior_tail
            [ "${'$'}#" -ge 20 ] && moke_uint "${'$'}{20}" && [ "${'$'}{20}" -gt 0 ] || return 2
            [ "${'$'}{20}" = ${q(server.startedTicks.toString())} ] || return 0
            [ "${'$'}moke_prior_uid" = "${'$'}(id -u)" ] || return 2
            case "${'$'}1" in Z|X|x) return 0;; R|S|D|T|t|W|I|P) return 1;; *) return 2;; esac
        }
    """.trimIndent() + "\n"

    /** Only a binding/explicit Open may supply previousServer; an ordinary list is not presence.
     * An unavailable/replaced control socket cannot prove that a still-live old server ended.
     */
    fun observe(scripts: TmuxScripts, requestId: String, ownerNonce: String?, requireOwner: Boolean, socketPath: String? = null, previousServer: TmuxServerId? = null): String {
        token(requestId)
        if (previousServer != null) {
            require(previousServer.witnessed && previousServer.pid > 1 && previousServer.started > 0 &&
                previousServer.socketDevice >= 0 && bootPattern.matches(previousServer.bootId) &&
                previousServer.socketPath.startsWith('/') && text(previousServer.socketPath)) { "Invalid previous server witness" }
            require(socketPath == null || socketPath == previousServer.socketPath) { "Previous server socket conflicts with observation" }
        }
        val socketSelection = selectSocket(socketPath ?: previousServer?.socketPath)
        val prior = previousServer?.let(::previousServerGuard).orEmpty()
        val serverOutcome = if (previousServer == null) {
            "case \"\$?\" in 0) :;; 2) moke_finish NO_SERVER;; 3) moke_finish UNKNOWN;; *) moke_finish ERROR;; esac"
        } else {
            "case \"\$?\" in " +
                "0) [ \"\$moke_server_row\" = \"\$moke_prior_row\" ] || { moke_prior_gone || moke_finish UNKNOWN; };; " +
                "2) moke_prior_gone || moke_finish UNKNOWN; moke_finish NO_SERVER;; " +
                "3) moke_finish UNKNOWN;; *) moke_finish ERROR;; esac"
        }
        val body = core + "\n" + """
            moke_finish() { printf '${P}KIND|%s\n${P}END|$requestId|0\n' "${'$'}1"; exit 0; }
            printf '${P}BEGIN|$requestId\n'
            moke_boot || moke_finish UNKNOWN
            printf '${P}PEER|%s\n' "${'$'}moke_boot_id"
        """.trimIndent() + "\n" + owner(scripts, ownerNonce, requireOwner, true) + prior + """
            command -v tmux >/dev/null 2>&1 || moke_finish NOT_INSTALLED
            unset TMUX TMUX_PANE
            $socketSelection || moke_finish UNKNOWN
            printf '${P}DEFAULT_SOCKET|%s\n' "${'$'}moke_socket"
            moke_server
            $serverOutcome
            moke_before=${'$'}moke_server_row
            moke_sessions=${'$'}(tmux -N -u -S "${'$'}moke_socket" list-sessions -F '#{?#{m/r:[[:cntrl:]],#{session_name}},${P}INVALID,${P}SESSION|#{session_id}|#{session_created}|#{session_windows}|#{session_attached}|#{session_name}}' 2>/dev/null) || moke_finish ERROR
            moke_clients=${'$'}(tmux -N -u -S "${'$'}moke_socket" list-clients -F ${q(clientFormat)} 2>/dev/null) || moke_finish ERROR
            moke_server && [ "${'$'}moke_before" = "${'$'}moke_server_row" ] || moke_finish ERROR
            printf '${P}SERVER|%s\n' "${'$'}moke_before"
            [ -z "${'$'}moke_sessions" ] || printf '%s\n' "${'$'}moke_sessions"
            [ -z "${'$'}moke_clients" ] || printf '%s\n' "${'$'}moke_clients"
        """.trimIndent() + "\n" + negotiate(null, report = true) + "\nmoke_finish READY"
        return shell(body)
    }

    private fun negotiate(term: String?, report: Boolean = false): String {
        val candidates = (listOfNotNull(term?.takeIf(termPattern::matches)) + terms).distinct().joinToString(" ")
        return "moke_term=''; for t in $candidates; do " +
            "if command -v tput >/dev/null 2>&1; then TERM=\"\$t\" tput clear >/dev/null 2>&1 && { moke_term=\$t; break; }; " +
            "elif infocmp \"\$t\" >/dev/null 2>&1; then moke_term=\$t; break; fi; done; " +
            if (report) "[ -z \"\$moke_term\" ] || printf '${P}TERM|%s\\n' \"\$moke_term\"" else
                "if [ -n \"\$moke_term\" ]; then TERM=\$moke_term; export TERM; fi"
    }

    fun create(name: String, socketPath: String? = null): String {
        require(name(name)) { "Invalid session name" }
        require(socketPath == null || (socketPath.startsWith('/') && text(socketPath))) { "Invalid socket path" }
        val socketSetup = if (socketPath == null) "moke_default" else
            "moke_socket=${q(socketPath)}; moke_uid=\$(id -u); moke_dir=\${moke_socket%/*}; moke_base=\${moke_dir%/*}; " +
                "moke_safe_base \"\$moke_base\" && { case \"\$moke_socket\" in \"\$moke_base/tmux-\$moke_uid/default\") :;; *) [ -d \"\$moke_dir\" ];; esac; }"
        val createCommand = "new-session -d -P -F ${q("${P}CREATED|#{session_id}|#{session_created}|#{session_windows}|#{session_attached}|#{session_name}")} -s ${q(tmuxLiteral(name))}"
        return shell(core + "\n" + """
            command -v tmux >/dev/null 2>&1 || { moke_result FAILED 'tmux not found'; exit 0; }
            unset TMUX TMUX_PANE
            { $socketSetup; } && moke_boot || { moke_result UNKNOWN; exit 0; }
            moke_before=
            moke_server
            case "${'$'}?" in 0) moke_before=${'$'}moke_server_row;; 2) :;; *) moke_result UNKNOWN; exit 0;; esac
            moke_create_parent || { moke_result UNKNOWN; exit 0; }
            if [ -n "${'$'}moke_before" ]; then
                moke_server && [ "${'$'}moke_before" = "${'$'}moke_server_row" ] || { moke_result STALE; exit 0; }
            fi
            if [ -n "${'$'}moke_before" ]; then
                moke_create_guard="#{&&:#{==:#{pid},${'$'}moke_pid},#{==:#{start_time},${'$'}moke_started}}"
                moke_created=${'$'}(moke_user_locale tmux -N -u -S "${'$'}moke_socket" if-shell -F "${'$'}moke_create_guard" ${q(createCommand)} ${q("display-message -p '${P}STALE'")} 2>&1)
            else
                moke_created=${'$'}(moke_user_locale tmux -u -S "${'$'}moke_socket" new-session -d -P -F '${P}CREATED|#{session_id}|#{session_created}|#{session_windows}|#{session_attached}|#{session_name}' -s "${'$'}2" 2>&1)
            fi
            moke_rc=${'$'}?
            if [ "${'$'}moke_created" = '${P}STALE' ]; then moke_result STALE; exit 0; fi
            if [ "${'$'}moke_rc" -ne 0 ]; then
                if [ "${'$'}moke_created" = "duplicate session: ${'$'}1" ]; then moke_result EXISTS; else moke_result FAILED; fi
                exit 0
            fi
            moke_server || { moke_result UNKNOWN; exit 0; }
            [ -z "${'$'}moke_before" ] || [ "${'$'}moke_before" = "${'$'}moke_server_row" ] || { moke_result STALE; exit 0; }
            printf '${P}OP_BEGIN\n${P}SERVER|%s\n%s\n${P}RESULT|OK\n${P}OP_END|0\n' "${'$'}moke_server_row" "${'$'}moke_created"
        """.trimIndent(), name, tmuxLiteral(name))
    }

    private fun frozen(target: TmuxSessionRef): String {
        val s = target.server
        require(s.witnessed && s.pid > 1 && s.started > 0 && s.socketDevice >= 0 && bootPattern.matches(s.bootId))
        require(s.socketPath.startsWith('/') && text(s.socketPath) && idPattern.matches(target.id) && target.created > 0)
        return "moke_socket=${q(s.socketPath)}\n" +
            "moke_expected_pid=${s.pid}; moke_expected_ticks=${s.startedTicks}; moke_expected_boot=${q(s.bootId)}\n" +
            "moke_expected_dev=${s.socketDevice}; moke_expected_ino=${s.socketInode}\n" +
            "moke_expected_row=${q("${s.pid}|${s.started}|${s.startedTicks}|${s.socketDevice}|${s.socketInode}|${s.bootId}|${s.socketPath}")}\n"
    }

    private fun condition(target: TmuxSessionRef): String =
        "#{&&:#{==:#{pid},${target.server.pid}},#{&&:#{==:#{start_time},${target.server.started}}," +
            "#{&&:#{==:#{session_id},${target.id}},#{==:#{session_created},${target.created}}}}}"

    private fun mutation(target: TmuxSessionRef, command: String): String = shell(
        core + "\n" + frozen(target) + """
            unset TMUX TMUX_PANE
            moke_verify
            case "${'$'}?" in 0) :;; 2) moke_result STALE; exit 0;; *) moke_result UNKNOWN; exit 0;; esac
            moke_out=${'$'}(tmux -N -u -S "${'$'}moke_socket" if-shell -F -t ${q(target.id)} ${q(condition(target))} ${q(command)} ${q("display-message -p '${P}STALE'")} 2>&1)
            moke_rc=${'$'}?
            if [ "${'$'}moke_out" = '${P}STALE' ] || [ "${'$'}moke_out" = ${q("can't find session: ${target.id}")} ]; then moke_result STALE
            elif [ "${'$'}moke_rc" -eq 0 ]; then moke_result OK
            else moke_result FAILED; fi
        """.trimIndent(),
    )

    fun rename(target: TmuxSessionRef, name: String): String {
        require(name(name)) { "Invalid session name" }
        return mutation(target, "rename-session -t ${q(target.id)} -- ${q(tmuxLiteral(name))}")
    }
    fun detach(target: TmuxSessionRef): String = mutation(target, "detach-client -s ${q(target.id)}")
    fun kill(target: TmuxSessionRef): String = mutation(target, "kill-session -t ${q(target.id)}")

    /** Enable mouse only on the witnessed session; do not install new global wheel policy.
     * Existing root wheel commands are corrected individually only when their complete list-keys
     * bytes match the historical generators (N3, then N1). This proves content, not ownership or
     * the user's original value. Correcting either root key affects all clients of that server;
     * other root/prefix/copy bindings, including copy-mode N1, survive.
     *
     * Each candidate is re-read near its guarded write and checked afterwards. Shell comparison
     * yields to the server: external clients can still bind between comparison and write. This is
     * best effort, not atomic CAS. Any uncertain/partial result stops, without replay or rollback.
     */
    fun scrollSetup(target: TmuxSessionRef): String {
        val forward = "#{||:#{pane_in_mode},#{mouse_any_flag}}"
        // list-keys -T root <one key> avoids table/key padding. Its nested command strings are
        // intentionally NOT reparsed, whitespace-folded or evaluated. tmux 3.3a has no list-keys -F
        // or global key_command query. These are the canonical bytes on supported 3.2a/3.3a/3.4.
        fun historical(key: String, arrow: String, fallback: String) = listOf(1, 3).map { count ->
            "bind-key -T root $key if-shell -F \"$forward\" \"send -M\" " +
                "\"if -F '#{alternate_on}' 'send -N$count $arrow' '$fallback'\"\n"
        }
        val up = "bind-key -T root WheelUpPane if-shell -F ${q(forward)} ${q("send -M")} ${q("copy-mode -e; send -M")}"
        val down = "bind-key -T root WheelDownPane send-keys -M"
        val safeUp = "bind-key -T root WheelUpPane if-shell -F \"$forward\" \"send -M\" \"copy-mode -e; send -M\"\n"
        val safeDown = "$down\n"
        val uncertain = "moke_result UNKNOWN 'scroll setup not confirmed'; exit 0"
        fun correct(key: String, originals: List<String>, command: String, expected: String) = """
            moke_scroll_call ${q("list-keys -T root $key")} $key
            moke_scroll_before=${'$'}moke_scroll_out
            case "${'$'}moke_scroll_before" in
                ${originals.joinToString("|") { q(it) }})
                    moke_scroll_call ${q("list-keys -T root $key")} $key
                    [ "${'$'}moke_scroll_out" = "${'$'}moke_scroll_before" ] || { $uncertain; }
                    moke_scroll_call ${q(command)}
                    [ -z "${'$'}moke_scroll_out" ] || { $uncertain; }
                    moke_scroll_call ${q("list-keys -T root $key")} $key
                    [ "${'$'}moke_scroll_out" = ${q(expected)} ] || { $uncertain; }
                    ;;
            esac
        """.trimIndent()
        return shell(core + "\n" + frozen(target) + """
            unset TMUX TMUX_PANE
            command -v head >/dev/null 2>&1 || { $uncertain; }
            moke_scroll_suffix=${q("${P}SCROLL_ACK\n\n${P}SCROLL_EXIT|0!")}
            moke_scroll_call() {
                moke_verify
                case "${'$'}?" in 0) :;; 2) moke_result STALE; exit 0;; *) $uncertain;; esac
                # Bound the capture, preserving trailing newlines with a final status sentinel.
                # Only generated commands enter tmux's parser; read-back bytes are data throughout.
                moke_scroll_out=${'$'}(
                    {
                        # 3.2a can crash when format context has a missing session. has-session
                        # aborts this synchronous queue before that guard, not a separate preflight.
                        tmux -N -u -S "${'$'}moke_socket" has-session -t ${q(target.id)} \; \
                            if-shell -F -t ${q(target.id)} ${q(condition(target))} \
                            "${'$'}1 ; display-message -p '${P}SCROLL_ACK'" ${q("display-message -p '${P}STALE'")} 2>&1
                        printf '\n${P}SCROLL_EXIT|%s!' "${'$'}?"
                    } | head -c 4097
                )
                [ "${'$'}{#moke_scroll_out}" -le 4096 ] || { $uncertain; }
                case "${'$'}moke_scroll_out" in
                    ${q("${P}STALE\n\n${P}SCROLL_EXIT|0!")}|${q("can't find session: ${target.id}\n\n${P}SCROLL_EXIT|1!")})
                        moke_result STALE; exit 0;;
                    *"${'$'}moke_scroll_suffix")
                        moke_scroll_out=${'$'}{moke_scroll_out%"${'$'}moke_scroll_suffix"};;
                    *)
                        # Missing keys are preserved, never recreated. All other errors stop.
                        if [ -n "${'$'}{2-}" ] && [ "${'$'}moke_scroll_out" = "unknown key: ${'$'}2"${q("\n\n${P}SCROLL_EXIT|1!")} ]; then
                            moke_scroll_out=; return 0
                        else $uncertain; fi;;
                esac
                if [ -n "${'$'}{2-}" ]; then
                    # Check the one-key response envelope, without interpreting its command body.
                    case "${'$'}moke_scroll_out" in *${q("\n")}) :;; *) $uncertain;; esac
                    moke_scroll_line=${'$'}{moke_scroll_out%${q("\n")}}
                    case "${'$'}moke_scroll_line" in *${q("\n")}*) $uncertain;; esac
                    case "${'$'}moke_scroll_line" in
                        "bind-key -T root ${'$'}2 "?*|"bind-key -r -T root ${'$'}2 "?*) :;;
                        *) $uncertain;;
                    esac
                fi
            }
            moke_scroll_confirm_mouse() {
                moke_scroll_call ${q("show-options -v -t ${q(target.id)} mouse")}
                [ "${'$'}moke_scroll_out" = ${q("on\n")} ] || { $uncertain; }
                moke_verify || { $uncertain; }
            }
            moke_scroll_call ${q("display-message -p '#{version}'")}
            case "${'$'}moke_scroll_out" in
                *${q("\n")}) moke_scroll_version=${'$'}{moke_scroll_out%${q("\n")}};;
                *) $uncertain;;
            esac
            case "${'$'}moke_scroll_version" in ''|*[!A-Za-z0-9._+-]*) $uncertain;; esac
            moke_scroll_call ${q("set-option -t ${q(target.id)} mouse on")}
            [ -z "${'$'}moke_scroll_out" ] || { $uncertain; }
            # Version knowledge limits only historical key correction, not ordinary mouse setup.
            case "${'$'}moke_scroll_version" in
                3.2a|3.3a|3.4) :;;
                *) moke_scroll_confirm_mouse
                    moke_result OK SCROLL_LEGACY_SKIPPED_UNSUPPORTED_VERSION; exit 0;;
            esac
        """.trimIndent() + "\n" +
            correct("WheelUpPane", historical("WheelUpPane", "Up", "copy-mode -e; send -M"), up, safeUp) + "\n" +
            correct("WheelDownPane", historical("WheelDownPane", "Down", "send -M"), down, safeDown) + "\n" + """
            moke_scroll_confirm_mouse
            moke_result OK
        """.trimIndent())
    }

    /** Read the attached client's active pane, not the control channel's arbitrary default pane. */
    fun paneCwd(target: TmuxSessionRef, expectedClient: TmuxClientId): String {
        require(expectedClient.pid > 1 && expectedClient.created > 0 && ttyPattern.matches(expectedClient.tty))
        val clientGuard = "#{&&:#{==:#{client_pid},${expectedClient.pid}}," +
            "#{&&:#{==:#{client_created},${expectedClient.created}},#{==:#{client_tty},${expectedClient.tty}}}}"
        val matches = "#{&&:${condition(target)},$clientGuard}"
        val path = "#{?#{m/r:[[:cntrl:]],#{pane_current_path}},${P}INVALID," +
            "#{?#{m/r:^/,#{pane_current_path}},${P}PATH|#{pane_current_path},${P}INVALID}}"
        val format = "#{?$matches,$path,${P}SKIP}"
        val unavailable = "printf '${P}CWD_BEGIN\\n${P}UNAVAILABLE\\n${P}CWD_END|0\\n'; exit 0"
        // list-clients supplies each actual client's session/current window/active pane context.
        // display-message -c would not: an unrelated -t/default target can override that context.
        val read = "list-clients -F ${q(format)}"
        return shell(core + "\n" + frozen(target) + """
            unset TMUX TMUX_PANE
            moke_verify || { $unavailable; }
            moke_out=${'$'}(tmux -N -u -S "${'$'}moke_socket" if-shell -F -t ${q(target.id)} ${q(condition(target))} ${q(read)} ${q("display-message -p '${P}UNAVAILABLE'")} 2>/dev/null)
            moke_rc=${'$'}?
            [ "${'$'}moke_rc" -eq 0 ] && moke_verify || { $unavailable; }
            printf '${P}CWD_BEGIN\n%s\n${P}CWD_END|0\n' "${'$'}moke_out"
        """.trimIndent())
    }

    fun parsePaneCwd(output: String): String? {
        val rows = frame(output, "CWD_BEGIN", "CWD_END|0") ?: return null
        var path: String? = null
        for (row in rows) {
            when {
                row == "SKIP" -> Unit
                row.startsWith("PATH|") -> {
                    val value = row.removePrefix("PATH|")
                    if (path != null || !text(value) || !value.startsWith('/')) return null
                    path = value
                }
                else -> return null
            }
        }
        return path
    }

    private val attachFailure = """
        moke_attach_failure() {
            printf '\nmoke: tmux attach failed; the following terminal is an ordinary shell.\n'
            moke_restore_locale
            moke_login_shell=${'$'}{SHELL:-/bin/sh}
            moke_login_name=${'$'}{moke_login_shell##*/}
            # Reuse the default-login argv[0]/BASH_ENV contract, without making bash
            # capability a prerequisite for the user to retain an ordinary terminal.
            if [ -x /bin/bash ] && BASH_ENV=/dev/null /bin/bash --noprofile --norc -c \
                'exec -a moke-fallback-check /bin/bash --noprofile --norc -c '\''[ "${'$'}0" = moke-fallback-check ]'\''' >/dev/null 2>&1; then
                BASH_ENV=/dev/null /bin/bash --noprofile --norc -c '
                    if [ "${'$'}3" = x ]; then export BASH_ENV="${'$'}4"; else unset BASH_ENV; fi
                    exec -a "${'$'}1" "${'$'}2"
                ' moke-fallback "-${'$'}moke_login_name" "${'$'}moke_login_shell" "${'$'}{BASH_ENV+x}" "${'$'}{BASH_ENV-}"
                exit "${'$'}?"
            fi
            exec ${'$'}{SHELL:-/bin/sh} -l
        }
    """.trimIndent()

    // if-shell's false branch is otherwise a successful display-message and would close
    // the supervised source. Synchronous run-shell propagates exit 1 to an unattached
    // command client (tmux 3.2a/3.4), without attaching, creating, or injecting TTY input.
    private const val attachRejected = "run-shell 'exit 1'"

    fun open(target: TmuxSessionRef, takeOver: Boolean = false, term: String? = null): String {
        val attach = "attach-session${if (takeOver) " -d" else ""} -t ${q(target.id)}"
        return shell(core + "\n" + attachFailure + "\n" + frozen(target) + """
            unset TMUX TMUX_PANE
            moke_verify || moke_attach_failure
        """.trimIndent() + "\n" + negotiate(term) + "\n" + """
            unset TMUX TMUX_PANE
            moke_rc=0
            moke_user_locale tmux -N -u -S "${'$'}moke_socket" if-shell -F -t ${q(target.id)} ${q(condition(target))} ${q(attach)} ${q(attachRejected)} || moke_rc=${'$'}?
            if [ "${'$'}moke_rc" -eq 0 ]; then exit 0; fi
            moke_attach_failure
        """.trimIndent())
    }

    /** Exact-name lookup, then a frozen-id guard. Never new-session -A, even after reconnect. */
    fun restore(name: String, term: String? = null, socketPath: String? = null): String {
        require(name(name)) { "Invalid session name" }
        val socketSelection = selectSocket(socketPath)
        return shell(core + "\n" + attachFailure + "\n" + """
            unset TMUX TMUX_PANE
            { $socketSelection; } && moke_server || moke_attach_failure
            moke_expected_row=${'$'}moke_server_row
            moke_expected_pid=${'$'}moke_pid; moke_expected_ticks=${'$'}moke_start_ticks; moke_expected_boot=${'$'}moke_boot_id
            moke_expected_dev=${'$'}moke_dev; moke_expected_ino=${'$'}moke_ino
            # Even =name treats a leading dollar as a session ID in upstream tmux.
            # Match the raw name ourselves, without target syntax or format expansion.
            moke_rows=${'$'}(tmux -N -u -S "${'$'}moke_socket" list-sessions -F '#{?#{m/r:[[:cntrl:]],#{session_name}},INVALID,#{session_id}|#{session_created}|#{session_name}}' 2>/dev/null) || moke_attach_failure
            moke_id=; moke_created=; moke_seen='|'; moke_invalid_rows=0
            while IFS= read -r moke_row; do
                moke_sid=${'$'}{moke_row%%|*}; moke_rest=${'$'}{moke_row#*|}
                moke_time=${'$'}{moke_rest%%|*}; moke_name=${'$'}{moke_rest#*|}
                case "${'$'}moke_sid" in '${'$'}'*) moke_digits=${'$'}{moke_sid#?};; *) moke_invalid_rows=1; break;; esac
                moke_uint "${'$'}moke_digits" && moke_uint "${'$'}moke_time" && [ "${'$'}moke_time" -gt 0 ] || { moke_invalid_rows=1; break; }
                case "${'$'}moke_seen" in *"|${'$'}moke_sid|"*) moke_invalid_rows=1; break;; esac
                moke_seen="${'$'}moke_seen${'$'}moke_sid|"
                if [ "${'$'}moke_name" = "${'$'}1" ]; then
                    [ -z "${'$'}moke_id" ] || { moke_invalid_rows=1; break; }
                    moke_id=${'$'}moke_sid; moke_created=${'$'}moke_time
                fi
            done <<MOKE_RESTORE_ROWS
            ${'$'}moke_rows
            MOKE_RESTORE_ROWS
            # Never start a login shell inside the redirected read loop: its stdin must
            # be the original terminal, not the session-list here document.
            [ "${'$'}moke_invalid_rows" -eq 0 ] && [ -n "${'$'}moke_id" ] || moke_attach_failure
            moke_verify || moke_attach_failure
            moke_guard="#{&&:#{==:#{pid},${'$'}moke_expected_pid},#{&&:#{==:#{start_time},${'$'}moke_started},#{&&:#{==:#{session_id},${'$'}moke_id},#{==:#{session_created},${'$'}moke_created}}}}"
        """.trimIndent() + "\n" + negotiate(term) + "\n" + """
            unset TMUX TMUX_PANE
            moke_rc=0
            moke_user_locale tmux -N -u -S "${'$'}moke_socket" if-shell -F -t "${'$'}moke_id" "${'$'}moke_guard" "attach-session -t '${'$'}moke_id'" ${q(attachRejected)} || moke_rc=${'$'}?
            [ "${'$'}moke_rc" -eq 0 ] && exit 0
            moke_attach_failure
        """.trimIndent(), name)
    }

    fun guardPeer(scripts: TmuxScripts, ownerNonce: String?, requireOwner: Boolean, peerBootId: String?, command: String): String {
        if (peerBootId == null || !bootPattern.matches(peerBootId)) return shell(core + "\nmoke_result UNKNOWN")
        return shell(core + "\n" + """
            moke_boot || { moke_result UNKNOWN; exit 0; }
            [ "${'$'}moke_boot_id" = ${q(peerBootId)} ] || { moke_result STALE; exit 0; }
        """.trimIndent() + "\n" + owner(scripts, ownerNonce, requireOwner, false) + "\nmoke_restore_locale\n" + command)
    }

    private fun frame(output: String, begin: String, end: String): List<String>? {
        if (output.length > 2_000_000) return null
        val normalized = output.replace("\r\n", "\n")
        if (normalized.any { it.isISOControl() && it != '\n' }) return null
        val lines = normalized.split('\n')
        val protocol = lines.withIndex().filter { it.value.startsWith(P) }
        if (protocol.isEmpty() || protocol.first().value != P + begin || protocol.last().value != P + end) return null
        val first = protocol.first().index
        val last = protocol.last().index
        if (last <= first || last == lines.lastIndex || lines.subList(first + 1, last).any { !it.startsWith(P) }) return null
        return lines.subList(first + 1, last).map { it.removePrefix(P) }
    }

    /** Wire syntax only; frame() bounds the input. Optional text validity is checked separately. */
    private fun decodePaneField(value: String): String? {
        val decoded = StringBuilder()
        var i = 0
        while (i < value.length) {
            if (value[i] != '%') decoded.append(value[i++]) else {
                if (i + 2 >= value.length) return null
                decoded.append(when (value.substring(i, i + 3)) {
                    "%25" -> '%'
                    "%7C" -> '|'
                    else -> return null
                })
                i += 3
            }
        }
        return decoded.toString()
    }

    private fun uint(value: String): Long? = value.takeIf { it.isNotEmpty() && it.all { c -> c in '0'..'9' } }?.toLongOrNull()
    private fun positive(value: String): Long? = uint(value)?.takeIf { it > 0 }
    private fun server(row: String): TmuxServerId? {
        val p = row.split('|', limit = 7)
        if (p.size != 7 || !bootPattern.matches(p[5]) || !text(p[6]) || !p[6].startsWith('/')) return null
        return TmuxServerId(
            pid = positive(p[0])?.takeIf { it > 1 } ?: return null,
            started = positive(p[1]) ?: return null,
            socketPath = p[6], bootId = p[5], startedTicks = positive(p[2]) ?: return null,
            socketDevice = uint(p[3]) ?: return null, socketInode = positive(p[4]) ?: return null,
        )
    }
    private fun session(row: String): TmuxSession? {
        val p = row.split('|', limit = 5)
        if (p.size != 5 || !idPattern.matches(p[0]) || !name(p[4])) return null
        val created = positive(p[1]) ?: return null
        val windows = positive(p[2])?.takeIf { it <= Int.MAX_VALUE }?.toInt() ?: return null
        val clients = uint(p[3])?.takeIf { it <= Int.MAX_VALUE }?.toInt() ?: return null
        return TmuxSession(p[0], p[4], windows, clients, created)
    }

    fun parseObservation(output: String, requestId: String): TmuxObservation? {
        if (!requestPattern.matches(requestId)) return null
        val rows = frame(output, "BEGIN|$requestId", "END|$requestId|0") ?: return null
        var kind: TmuxProbeKind? = null
        var owner: TmuxOwnerProof? = null
        var server: TmuxServerId? = null
        var peer: String? = null
        var defaultSocket: String? = null
        var term: String? = null
        var error: String? = null
        val sessions = mutableListOf<TmuxSession>()
        val clients = mutableListOf<TmuxClient>()
        for (row in rows) {
            if (kind != null) return null // KIND is the final record, including non-READY results.
            val key = row.substringBefore('|')
            val value = row.substringAfter('|', "")
            when (key) {
                "PEER" -> { if (peer != null || !bootPattern.matches(value)) return null; peer = value }
                "OWNER" -> {
                    if (owner != null) return null
                    val p = value.split('|', limit = 5)
                    if (p.size != 5 || !noncePattern.matches(p[0]) || !ttyPattern.matches(p[4])) return null
                    owner = TmuxOwnerProof(p[0], positive(p[1])?.takeIf { it > 1 } ?: return null, uint(p[2]) ?: return null, positive(p[3]) ?: return null, p[4])
                }
                "DEFAULT_SOCKET" -> { if (defaultSocket != null || !text(value) || !value.startsWith('/')) return null; defaultSocket = value }
                "SERVER" -> { if (server != null) return null; server = server(value) ?: return null }
                "SESSION" -> sessions += session(value) ?: return null
                "CLIENT" -> {
                    val p = value.split('|', limit = 4)
                    if (p.size != 4 || !idPattern.matches(p[2]) || !ttyPattern.matches(p[3])) return null
                    clients += TmuxClient(TmuxClientId(positive(p[0])?.takeIf { it > 1 } ?: return null, positive(p[1]) ?: return null, p[3]), p[2])
                }
                "CLIENT_PANE" -> {
                    val p = value.split('|')
                    if (p.size != 8 || !idPattern.matches(p[2]) || !ttyPattern.matches(p[4]) || !panePattern.matches(p[5])) return null
                    val identity = TmuxClientId(positive(p[0])?.takeIf { it > 1 } ?: return null,
                        positive(p[1]) ?: return null, p[4])
                    val sessionCreated = positive(p[3]) ?: return null
                    val title = decodePaneField(p[6]) ?: return null
                    val path = decodePaneField(p[7]) ?: return null
                    // A C-locale remote guard cannot recognize every unsafe Unicode character.
                    // Invalid optional content withdraws this entire pane sample, not the client's
                    // independently witnessed identity or another client's management capability.
                    // Row shape, escaping and all required identities remain strict protocol errors.
                    val pane = if (safeSessionText(title, SESSION_TITLE_LIMIT) &&
                        safeSessionText(path, SESSION_PATH_LIMIT) && (path.isEmpty() || path.startsWith('/')))
                        TmuxPane(p[5], title, path.ifEmpty { null }) else null
                    clients += TmuxClient(identity, p[2], sessionCreated, pane)
                }
                "TERM" -> { if (term != null || !termPattern.matches(value)) return null; term = value }
                "ERROR" -> { if (error != null || !text(value)) return null; error = value }
                "KIND" -> kind = TmuxProbeKind.entries.firstOrNull { it.name == value } ?: return null
                else -> return null
            }
        }
        val actualKind = kind ?: return null
        if (actualKind == TmuxProbeKind.READY) {
            if (server == null || peer != server.bootId || sessions.isEmpty()) return null
        } else if (server != null || sessions.isNotEmpty() || clients.isNotEmpty() || term != null) return null
        if (actualKind == TmuxProbeKind.NO_SERVER && (defaultSocket == null || peer == null)) return null
        if (actualKind == TmuxProbeKind.READY && defaultSocket != null && defaultSocket != server?.socketPath) return null
        if ((owner != null && peer == null) || (actualKind == TmuxProbeKind.PENDING && owner != null)) return null
        if (sessions.map { it.id }.distinct().size != sessions.size || sessions.map { it.name }.distinct().size != sessions.size) return null
        if (clients.map { it.identity.pid }.distinct().size != clients.size || clients.map { it.identity.tty }.distinct().size != clients.size) return null
        if (clients.any { c -> sessions.none { it.id == c.sessionId && (c.sessionCreated == null || it.created == c.sessionCreated) } }) return null
        return TmuxObservation(actualKind, owner, server, sessions, clients, term, error, peer, defaultSocket)
    }

    fun parseOperation(output: String): TmuxOperationResult? {
        val rows = frame(output, "OP_BEGIN", "OP_END|0") ?: return null
        var code: TmuxOperationCode? = null
        var server: TmuxServerId? = null
        var created: TmuxSession? = null
        val details = mutableListOf<String>()
        for (row in rows) {
            val value = row.substringAfter('|', "")
            when (row.substringBefore('|')) {
                "RESULT" -> { if (code != null) return null; code = TmuxOperationCode.entries.firstOrNull { it.name == value } ?: return null }
                "SERVER" -> { if (server != null) return null; server = server(value) ?: return null }
                "CREATED" -> { if (created != null) return null; created = session(value) ?: return null }
                "DETAIL" -> { if (!text(value)) return null; details += value }
                else -> return null
            }
        }
        val actualCode = code ?: return null
        if ((server == null) != (created == null) || (created != null && actualCode != TmuxOperationCode.OK)) return null
        return TmuxOperationResult(actualCode, details.joinToString("\n"), created?.ref(server!!))
    }
}
