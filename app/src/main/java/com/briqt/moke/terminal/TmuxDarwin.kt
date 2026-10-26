package com.briqt.moke.terminal

// type: isolated Darwin identity backend
/** No installed helper, compilation, daemon, ps parsing, or environment disclosure. */
internal object TmuxDarwin {
    // Negative epoch microseconds identify Darwin births; positive values remain Linux ticks.
    // Both fit a signed Long, and Darwin values also fit JavaScript's exact-integer range.
    internal val native = """
        ObjC.import('Foundation');
        var ready = true;
        try {
            ObjC.bindFunction('getpid', ['int', []]);
            ObjC.bindFunction('getuid', ['unsigned int', []]);
            ObjC.bindFunction('malloc', ['void *', ['unsigned long']]);
            ObjC.bindFunction('free', ['void', ['void *']]);
            ObjC.bindFunction('__error', ['void *', []]);
            ObjC.bindFunction('open', ['int', ['char *', 'int']]);
            ObjC.bindFunction('close', ['int', ['int']]);
            ObjC.bindFunction('proc_pidinfo', ['int', ['int', 'int', 'unsigned long long', 'void *', 'int']]);
            ObjC.bindFunction('proc_pidfdinfo', ['int', ['int', 'int', 'int', 'void *', 'int']]);
            ObjC.bindFunction('sysctl', ['int', ['void *', 'unsigned int', 'void *', 'void *', 'void *', 'unsigned long']]);
            ObjC.bindFunction('sysctlbyname', ['int', ['char *', 'void *', 'void *', 'void *', 'unsigned long']]);
        } catch (_) { ready = false; }
        function fail() { throw new Error('FAILURE'); }
        function unsupported() { throw new Error('UNSUPPORTED'); }
        function bytes(pointer, size) {
            var value = ObjC.unwrap($.NSString.alloc.initWithDataEncoding(
                $.NSData.dataWithBytesLength(pointer, size), $.NSISOLatin1StringEncoding));
            if (typeof value !== 'string' || value.length !== size) fail();
            return value;
        }
        function u32(data, offset) {
            return data.charCodeAt(offset) + data.charCodeAt(offset + 1) * 256 +
                data.charCodeAt(offset + 2) * 65536 + data.charCodeAt(offset + 3) * 16777216;
        }
        function u64(data, offset) {
            var value = u32(data, offset) + u32(data, offset + 4) * 4294967296;
            if (!Number.isSafeInteger(value)) fail();
            return value;
        }
        function put(pointer, words) {
            var data = '';
            words.forEach(function (word) {
                for (var i = 0; i < 4; i++) {
                    data += String.fromCharCode(word & 255);
                    word = Math.floor(word / 256);
                }
            });
            $(data).dataUsingEncoding($.NSISOLatin1StringEncoding).getBytesLength(pointer, data.length);
        }
        function errno() { return u32(bytes($.__error(), 4), 0); }
        function bsd(pid, layout) {
            var pointer = $.malloc(136);
            try {
                var count = $.proc_pidinfo(pid, 3, 0, pointer, 136);
                if (count === 0) {
                    if (errno() === 3 && !layout) return null;
                    fail();
                }
                if (count !== 136) { if (layout) unsupported(); else fail(); }
                var data = bytes(pointer, 136), seconds = u64(data, 120), micros = u64(data, 128);
                if (u32(data, 12) !== pid || seconds < 1 || seconds > Math.floor(Date.now() / 1000) + 60 || micros > 999999) {
                    if (layout) unsupported(); else fail();
                }
                var birth = seconds * 1000000 + micros;
                if (!Number.isSafeInteger(birth) || birth < 1) fail();
                return {pid: pid, uid: u32(data, 20), birth: -birth, tty: u32(data, 108)};
            } finally { $.free(pointer); }
        }
        function same(a, b) {
            return a && b && a.pid === b.pid && a.uid === b.uid && a.birth === b.birth && a.tty === b.tty;
        }
        function boot() {
            var size = $.malloc(8), pointer = $.malloc(64);
            try {
                put(size, [64, 0]);
                if ($.sysctlbyname('kern.bootsessionuuid', pointer, size, null, 0) !== 0) fail();
                var length = u64(bytes(size, 8), 0);
                if (length !== 37) unsupported();
                var value = bytes(pointer, length);
                if (!/^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\x00$/.test(value)) unsupported();
                return value.slice(0, -1).toLowerCase();
            } finally { $.free(pointer); $.free(size); }
        }
        function argumentNonce(pid, nonce) {
            var limit = 1048576, mib = $.malloc(12), size = $.malloc(8), pointer = $.malloc(limit);
            try {
                put(mib, [1, 49, pid]); put(size, [limit, 0]);
                if ($.sysctl(mib, 3, pointer, size, null, 0) !== 0) fail();
                var length = u64(bytes(size, 8), 0);
                if (length < 4 || length > limit) fail();
                var data = bytes(pointer, length), argc = u32(data, 0), offset = 4;
                if (argc < 1 || argc > 16384) fail();
                var end = data.indexOf('\x00', offset);
                if (end < offset) fail();
                offset = end + 1;
                while (offset < length && data.charCodeAt(offset) === 0) offset++;
                var matches = 0;
                for (var i = 0; i < argc; i++) {
                    end = data.indexOf('\x00', offset);
                    if (end < offset) fail();
                    if (data.slice(offset, end) === nonce) matches++;
                    offset = end + 1;
                }
                // Stop at argc: following environment bytes are never parsed or emitted.
                return matches === 1;
            } finally { $.free(pointer); $.free(size); $.free(mib); }
        }
        function descriptor(pid, fd, layout) {
            var pointer = $.malloc(1200);
            try {
                var count = $.proc_pidfdinfo(pid, fd, 2, pointer, 1200);
                if (count !== 1200) { if (count > 0 && layout) unsupported(); else fail(); }
                var data = bytes(pointer, 1200), pathBytes = data.slice(176), end = pathBytes.indexOf('\x00');
                if (end < 1 || (data.charCodeAt(28) + data.charCodeAt(29) * 256 & 61440) !== 8192) {
                    if (layout) unsupported(); else fail();
                }
                return {uid: u32(data, 40), device: u32(data, 140), path: pathBytes.slice(0, end)};
            } finally { $.free(pointer); }
        }
        function run(args) {
            try {
                if (!ready || typeof Number.isSafeInteger !== 'function') unsupported();
                var self = bsd($.getpid(), true), uid = $.getuid();
                if (self.uid !== uid) unsupported();
                var bootId = boot(), action = args[0];
                if (action === 'capability') {
                    // Check the actual argv and vnode layouts, not merely the presence of osascript.
                    if (!argumentNonce(self.pid, 'moke-native-check')) unsupported();
                    var fd = $.open('/dev/null', 0);
                    if (fd < 0) fail();
                    try {
                        var devnull = descriptor(self.pid, fd, true);
                        if (devnull.path !== '/dev/null' || devnull.device === 0) unsupported();
                    } finally { $.close(fd); }
                    return 'SUPPORTED';
                }
                if (action === 'boot' && args.length === 1) return 'BOOT|' + bootId;
                if (args.length < 2 || !/^[0-9]{1,10}$/.test(args[1])) fail();
                var pid = Number(args[1]);
                if (pid < 2 || pid > 2147483647) fail();
                var first = bsd(pid, false);
                if (!first) return 'ABSENT';
                // Never inspect argv/fds of a different uid, even when the OS permits it.
                if (first.uid !== uid) fail();
                if (action === 'proc' && args.length === 2) {
                    if (!same(first, bsd(pid, false))) fail();
                    return ['PROC', pid, uid, first.birth, bootId].join('|');
                }
                if (action !== 'owner' || args.length !== 3 || !/^[a-f0-9]{32}$/.test(args[2])) fail();
                if (!argumentNonce(pid, args[2])) fail();
                var fd0 = descriptor(pid, 0, false);
                if (!/^\/dev\/ttys[0-9]+$/.test(fd0.path) || fd0.uid !== uid || first.tty === 0 ||
                    fd0.device !== first.tty || !same(first, bsd(pid, false))) fail();
                return ['OWNER', pid, uid, first.birth, fd0.path, first.tty, bootId].join('|');
            } catch (error) {
                // No native diagnostics, paths, argv, or environment can enter the protocol.
                return error.message === 'UNSUPPORTED' ? 'UNSUPPORTED' : 'FAILURE';
            }
        }
    """.trimIndent()

    internal val core: String get() =
        "moke_native() {\n/usr/bin/osascript -l JavaScript - \"\$@\" 2>/dev/null <<'MOKE_DARWIN_NATIVE'\n" +
            native + "\nMOKE_DARWIN_NATIVE\n}\n" + shellCore

    private val shellCore = """
        moke_canonical_dir() ( CDPATH= cd -P -- "§1" 2>/dev/null && pwd -P; )
        moke_stat() { /usr/bin/stat -f "§1" "§2" 2>/dev/null; }
        moke_boot() {
            moke_native_row=§(moke_native boot) || return 1
            case "§moke_native_row" in BOOT\|*) moke_boot_id=§{moke_native_row#BOOT|};; *) return 1;; esac
        }
        moke_darwin_proc() {
            moke_native_row=§(moke_native proc "§1") || return 3
            case "§moke_native_row" in ABSENT) return 2;; PROC\|*) :;; *) return 3;; esac
            IFS='|' read -r moke_tag moke_native_pid moke_native_uid moke_start_ticks moke_boot_id <<MOKE_DARWIN_PROC
        §moke_native_row
        MOKE_DARWIN_PROC
            [ "§moke_native_pid" = "§1" ] && [ "§moke_native_uid" = "§(id -u)" ] || return 3
            case "§moke_start_ticks" in -[1-9]*) :;; *) return 3;; esac
            moke_uint "§{moke_start_ticks#-}" || return 3
        }
        moke_ticks() { moke_darwin_proc "§1" && printf '%s\n' "§moke_start_ticks"; }
        moke_permissions() {
            # %Lp is only rwx on BSD: it loses sticky/setuid/setgid. %Op includes type
            # and all mode bits; normalize only after validating the complete octal value.
            moke_full_mode=§(moke_stat %Op "§1") || return 1
            case "§moke_full_mode" in ''|*[!0-7]*) return 1;; esac
            [ "§{#moke_full_mode}" -le 6 ] || return 1
            printf '%o\n' "§((0§moke_full_mode & 07777))"
        }
        moke_private_dir() {
            [ -d "§1" ] && [ ! -L "§1" ] || return 1
            [ "§(moke_stat %u "§1")" = "§(id -u)" ] || return 1
            moke_mode=§(moke_permissions "§1") || return 1
            [ "§((0§moke_mode & 077))" -eq 0 ]
        }
        moke_safe_base() {
            [ -d "§1" ] && [ ! -L "§1" ] || return 1
            moke_base_uid=§(moke_stat %u "§1") || return 1
            [ "§moke_base_uid" = 0 ] || [ "§moke_base_uid" = "§(id -u)" ] || return 1
            moke_base_mode=§(moke_permissions "§1") || return 1
            [ "§((0§moke_base_mode & 0022))" -eq 0 ] || [ "§((0§moke_base_mode & 01000))" -ne 0 ]
        }
        moke_default_at() {
            moke_uid=§(id -u) || return 1
            moke_uint "§moke_uid" || return 1
            moke_base=§1
            [ "§(moke_canonical_dir "§moke_base")" = "§moke_base" ] && moke_safe_base "§moke_base" || return 1
            moke_dir="§moke_base/tmux-§moke_uid"; moke_socket="§moke_dir/default"
            if [ -e "§moke_dir" ] || [ -L "§moke_dir" ]; then moke_private_dir "§moke_dir" || return 1; fi
        }
        moke_default() {
            moke_base=
            if [ -n "§{TMUX_TMPDIR-}" ]; then
                # realpath of an existing file succeeds upstream, but it is not a socket base.
                [ ! -e "§TMUX_TMPDIR" ] || [ -d "§TMUX_TMPDIR" ] || return 1
                moke_base=§(moke_canonical_dir "§TMUX_TMPDIR")
            fi
            [ -n "§moke_base" ] || moke_base=§(moke_canonical_dir /tmp)
            moke_default_at "§moke_base"
        }
        moke_select_socket() {
            moke_uid=§(id -u) || return 1
            moke_uint "§moke_uid" || return 1
            moke_known_parent=§{moke_socket%/*}; moke_base=§{moke_known_parent%/*}
            case "§moke_socket" in
                "§moke_base/tmux-§moke_uid/default") moke_default_at "§moke_base";;
                *) moke_dir=
                    [ "§(moke_canonical_dir "§moke_known_parent")" = "§moke_known_parent" ] && moke_safe_base "§moke_known_parent";;
            esac
        }
        moke_create_parent() {
            moke_parent=§{moke_socket%/*}
            if [ -e "§moke_parent" ] || [ -L "§moke_parent" ]; then moke_private_dir "§moke_parent" || return 1
            else
                [ "§moke_parent" = "§moke_dir" ] || return 1
                (umask 077; mkdir -m 700 "§moke_parent") 2>/dev/null || return 1
                moke_private_dir "§moke_parent" || return 1
            fi
            [ "§(moke_canonical_dir "§moke_parent")" = "§moke_parent" ]
        }
        moke_socket_identity() {
            [ -S "§moke_socket" ] && [ ! -L "§moke_socket" ] || return 1
            [ "§(moke_canonical_dir "§{moke_socket%/*}")/§{moke_socket##*/}" = "§moke_socket" ] || return 1
            moke_meta=§(moke_stat '%d %i %u' "§moke_socket") || return 1
            set -- §moke_meta
            [ "§#" -eq 3 ] && [ "§3" = "§(id -u)" ] || return 1
            moke_dev=§1; moke_ino=§2
            moke_uint "§moke_dev" && moke_uint "§moke_ino" && [ "§moke_ino" -gt 0 ]
        }
        moke_server() {
            [ ! -L "§moke_socket" ] || return 3
            moke_socket_before=
            if [ -e "§moke_socket" ]; then
                moke_socket_identity || return 3
                moke_socket_before="§moke_dev:§moke_ino"
            fi
            moke_raw=§(tmux -N -u -S "§moke_socket" display-message -p '#{pid}|#{start_time}|#{socket_path}' 2>&1)
            moke_rc=§?
            if [ "§moke_rc" -ne 0 ]; then
                [ ! -L "§moke_socket" ] || return 3
                if [ -n "§moke_socket_before" ]; then
                    moke_socket_identity && [ "§moke_socket_before" = "§moke_dev:§moke_ino" ] || return 3
                else [ ! -e "§moke_socket" ] || return 3; fi
                case "§moke_raw" in
                    "error connecting to §moke_socket (No such file or directory)"|"error connecting to §moke_socket (Connection refused)"|"no server running on §moke_socket") return 2;;
                    *) return 1;;
                esac
            fi
            IFS='|' read -r moke_pid moke_started moke_reported <<MOKE_DARWIN_SERVER
        §moke_raw
        MOKE_DARWIN_SERVER
            moke_uint "§moke_pid" && [ "§moke_pid" -gt 1 ] && moke_uint "§moke_started" && [ "§moke_started" -gt 0 ] || return 3
            [ "§moke_reported" = "§moke_socket" ] && [ "§moke_raw" = "§moke_pid|§moke_started|§moke_reported" ] || return 3
            moke_darwin_proc "§moke_pid" && moke_socket_identity || return 3
            [ "§moke_socket_before" = "§moke_dev:§moke_ino" ] || return 3
            moke_server_row="§moke_pid|§moke_started|§moke_start_ticks|§moke_dev|§moke_ino|§moke_boot_id|§moke_socket"
        }
        moke_verify() {
            moke_darwin_proc "§moke_expected_pid"
            case "§?" in 0) :;; 2) return 2;; *) return 3;; esac
            [ "§moke_boot_id" = "§moke_expected_boot" ] && [ "§moke_start_ticks" = "§moke_expected_ticks" ] || return 2
            moke_socket_identity || return 3
            [ "§moke_dev" = "§moke_expected_dev" ] && [ "§moke_ino" = "§moke_expected_ino" ] || return 2
            moke_server
            case "§?" in 0) :;; 2) return 2;; *) return 3;; esac
            [ "§moke_server_row" = "§moke_expected_row" ] || return 2
        }
        moke_owner_base() {
            moke_owner_uid=§(id -u) || return 1
            case "§{TMPDIR:-/tmp}" in /*) :;; *) return 1;; esac
            moke_owner_base=§(moke_canonical_dir "§{TMPDIR:-/tmp}") || return 1
            case "§moke_owner_base" in /*) :;; *) return 1;; esac
            [ ! -L "§moke_owner_base" ] || return 1
            moke_owner_base_uid=§(moke_stat %u "§moke_owner_base") || return 1
            moke_owner_base_mode=§(moke_permissions "§moke_owner_base") || return 1
            case "§moke_owner_base_uid §moke_owner_base_mode" in '0 1777'|"§moke_owner_uid 700") :;; *) return 1;; esac
            moke_record_dir="§moke_owner_base/.moke-tty-§nonce"
        }
        moke_owner_mode() {
            [ "§(moke_stat %u "§1")" = "§moke_owner_uid" ] && [ "§(moke_permissions "§1")" = "§2" ]
        }
        moke_owner_identity() {
            [ -d "§moke_record_dir" ] && [ ! -L "§moke_record_dir" ] &&
                moke_owner_mode "§moke_record_dir" 700 || return 1
            moke_record_dir_id=§(moke_stat '%d:%i:%u:%Op' "§moke_record_dir") || return 1
        }
        moke_darwin_capability() {
            for moke_tool in id tty mkdir chmod mv rm rmdir head; do
                command -v "§moke_tool" >/dev/null 2>&1 || { printf 'UNSUPPORTED\n'; return; }
            done
            [ -x /usr/bin/osascript ] && [ -x /usr/bin/stat ] && [ -x /usr/bin/head ] && [ -x /bin/bash ] || { printf 'UNSUPPORTED\n'; return; }
            [ "§(printf 'moke' | /usr/bin/head -c 4 2>/dev/null)" = moke ] || { printf 'UNSUPPORTED\n'; return; }
            moke_native_result=§(moke_native capability moke-native-check) || { printf 'FAILURE\n'; return; }
            [ "§moke_native_result" = SUPPORTED ] || { printf '%s\n' "§moke_native_result"; return; }
            # BSD stat, physical canonicalization, private owner base, and exec -a are required.
            moke_fields=§(moke_stat '%d %i %u %Op' /dev/null) || { printf 'FAILURE\n'; return; }
            set -- §moke_fields
            [ "§#" -eq 4 ] && moke_uint "§1" && moke_uint "§2" && moke_uint "§3" || { printf 'UNSUPPORTED\n'; return; }
            case "§4" in ''|*[!0-7]*) printf 'UNSUPPORTED\n'; return;; esac
            [ "§{#4}" -le 6 ] && [ "§((0§4 & 0170000))" -eq "§((020000))" ] || { printf 'UNSUPPORTED\n'; return; }
            nonce=00000000000000000000000000000000
            moke_default && moke_owner_base || { printf 'FAILURE\n'; return; }
            BASH_ENV=/dev/null /bin/bash --noprofile --norc -c \
                'exec -a moke-capability /bin/bash --noprofile --norc -c '\''[ "§0" = moke-capability ]'\''' || { printf 'UNSUPPORTED\n'; return; }
            printf 'SUPPORTED\n'
        }
    """.trimIndent().replace('§', '$')

    internal val observer = """
        nonce=§1; mode=§2; payload=§{3-}
        case "§nonce" in ''|*[!a-f0-9]*) exit 64;; esac
        [ "§{#nonce}" -eq 32 ] || exit 64
        owned=0; record_identity=; record_value=
        cleanup() {
            result=§?; trap - 0 HUP TERM
            if [ "§owned" -eq 1 ] && moke_owner_base && moke_owner_identity && [ "§moke_record_dir_id" = "§record_identity" ]; then
                for leaf in record record.new; do
                    if [ -f "§moke_record_dir/§leaf" ] && [ ! -L "§moke_record_dir/§leaf" ] &&
                        moke_owner_mode "§moke_record_dir/§leaf" 600 &&
                        [ "§(/usr/bin/head -c 258 "§moke_record_dir/§leaf" 2>/dev/null && printf '!')" = "§record_value
        !" ]; then rm -f "§moke_record_dir/§leaf"; fi
                done
                rmdir "§moke_record_dir" 2>/dev/null || :
            fi
            exit "§result"
        }
        trap cleanup 0; trap 'exit 129' HUP; trap 'exit 143' TERM
        # Keep this shell PID and nonce alive; the original terminal always starts on failure.
        if moke_owner_base; then
            record_value=§(moke_native owner "§§" "§nonce") || record_value=
            case "§record_value" in OWNER\|*)
                umask 077
                if mkdir -m 700 "§moke_record_dir" 2>/dev/null; then
                    owned=1
                    if moke_owner_identity; then
                        record_identity=§moke_record_dir_id
                        printf '%s\n' "§record_value" > "§moke_record_dir/record.new" &&
                            chmod 600 "§moke_record_dir/record.new" && mv "§moke_record_dir/record.new" "§moke_record_dir/record"
                    fi
                fi;;
            esac
        fi
        moke_restore_locale
        case "§mode" in
            shell)
                shell_path=§{SHELL:-/bin/sh}; shell_name=§{shell_path##*/}
                BASH_ENV=/dev/null /bin/bash --noprofile --norc -c '
                    if [ "§3" = x ]; then export BASH_ENV="§4"; else unset BASH_ENV; fi
                    exec -a "§1" "§2"
                ' moke-login "-§shell_name" "§shell_path" "§{BASH_ENV+x}" "§{BASH_ENV-}";;
            command) sh -c "§payload";;
            *) exit 64;;
        esac
        exit "§?"
    """.trimIndent().replace('§', '$')

    internal val probe = """
        nonce=§1
        unknown() { printf 'UNKNOWN\n'; exit 0; }
        case "§nonce" in ''|*[!a-f0-9]*) unknown;; esac
        [ "§{#nonce}" -eq 32 ] || unknown
        moke_owner_base || unknown
        if [ ! -e "§moke_record_dir" ] && [ ! -L "§moke_record_dir" ]; then printf 'PENDING\n'; exit 0; fi
        moke_owner_identity || unknown
        first_dir_id=§moke_record_dir_id
        [ -f "§moke_record_dir/record" ] && [ ! -L "§moke_record_dir/record" ] || unknown
        moke_owner_mode "§moke_record_dir/record" 600 || unknown
        first_record_id=§(moke_stat '%d:%i:%u:%Op:%z' "§moke_record_dir/record") || unknown
        record_size=§{first_record_id##*:}
        moke_uint "§record_size" && [ "§record_size" -gt 0 ] && [ "§record_size" -le 257 ] || unknown
        # Preserve the terminal LF and bound reads even if the file changes after stat.
        record_bytes=§(/usr/bin/head -c 258 "§moke_record_dir/record" 2>/dev/null && printf '!') || unknown
        case "§record_bytes" in
            *'
        !') expected=§{record_bytes%'
        !'};;
            *) unknown;;
        esac
        [ "§{#expected}" -le 256 ] || unknown
        IFS='|' read -r tag pid uid birth tty device boot extra <<MOKE_DARWIN_OWNER
        §expected
        MOKE_DARWIN_OWNER
        [ "§tag" = OWNER ] && [ -z "§extra" ] && [ "§uid" = "§moke_owner_uid" ] || unknown
        moke_uint "§pid" && [ "§pid" -gt 1 ] || unknown
        actual=§(moke_native owner "§pid" "§nonce") || unknown
        [ "§actual" = "§expected" ] || unknown
        moke_owner_identity && [ "§moke_record_dir_id" = "§first_dir_id" ] || unknown
        [ ! -L "§moke_record_dir/record" ] &&
            [ "§(moke_stat '%d:%i:%u:%Op:%z' "§moke_record_dir/record")" = "§first_record_id" ] || unknown
        printf 'WITNESS|%s|%s|%s|%s\n' "§pid" "§uid" "§birth" "§tty"
    """.trimIndent().replace('§', '$')

    internal fun previousServer(server: TmuxServerId): String = if (server.startedTicks >= 0)
        "moke_prior_gone() { return 2; }" else """
        moke_prior_gone() {
            moke_darwin_proc ${server.pid}
            case "§?" in
                0) [ "§moke_boot_id" = '${server.bootId}' ] || return 2
                   [ "§moke_start_ticks" = '${server.startedTicks}' ] && return 1
                   return 0;;
                2) moke_boot && [ "§moke_boot_id" = '${server.bootId}' ] || return 2; return 0;;
                *) return 2;;
            esac
        }
    """.trimIndent().replace('§', '$')
}
