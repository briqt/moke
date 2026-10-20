#!/bin/sh
# type: connection owner witness
# Arguments remain in this process's argv for the complete terminal lifetime.
nonce=$1
mode=$2
payload=${3-}
case "$nonce" in ''|*[!a-f0-9]*) exit 64;; esac
[ "${#nonce}" -eq 32 ] || exit 64
owner_uid=$(id -u)
base=${TMPDIR:-/tmp}
record_dir="$base/.moke-tty-$nonce"
owned=0
record_identity=
record_value=
safe_base() {
    case "$base" in /*) :;; *) return 1;; esac
    [ -d "$base" ] && [ ! -L "$base" ] || return 1
    [ "$(readlink -f -- "$base" 2>/dev/null)" = "$base" ] || return 1
    base_meta=$(stat -c '%u %a' -- "$base" 2>/dev/null) || return 1
    case "$base_meta" in '0 1777'|"$owner_uid 700") return 0;; *) return 1;; esac
}
proc_fields() {
    IFS= read -r proc_stat < "/proc/$1/stat" || return 1
    proc_tail=${proc_stat##*) }
    set -- $proc_tail
    [ "$#" -ge 20 ] || return 1
    printf '%s %s\n' "$5" "${20}"
}
cleanup() {
    result=$?
    trap - 0 HUP TERM
    # Never remove a replacement directory, symlink, or another nonce's record.
    if [ "$owned" -eq 1 ] && safe_base && [ -d "$record_dir" ] && [ ! -L "$record_dir" ] &&
        [ "$(stat -c '%d:%i:%u:%a' -- "$record_dir" 2>/dev/null)" = "$record_identity" ]; then
        if [ -f "$record_dir/record" ] && [ ! -L "$record_dir/record" ]; then
            current_value=$(cat -- "$record_dir/record" 2>/dev/null)
            if [ "$current_value" = "$record_value" ]; then rm -f -- "$record_dir/record"; fi
        fi
        if [ -f "$record_dir/record.new" ] && [ ! -L "$record_dir/record.new" ] &&
            [ "$(cat -- "$record_dir/record.new" 2>/dev/null)" = "$record_value" ]; then
            rm -f -- "$record_dir/record.new"
        fi
        rmdir -- "$record_dir" 2>/dev/null || :
    fi
    exit "$result"
}
trap cleanup 0
trap 'exit 129' HUP
trap 'exit 143' TERM
owner_tty=$(tty 2>/dev/null) || owner_tty=
# A missing/unsupported witness must not prevent the original terminal starting.
if [ -n "$owner_tty" ] && safe_base && [ -r "/proc/$$/stat" ]; then
    umask 077
    if mkdir -m 700 -- "$record_dir" 2>/dev/null; then
        owned=1
        record_identity=$(stat -c '%d:%i:%u:%a' -- "$record_dir" 2>/dev/null)
        fields=$(proc_fields "$$") || fields=
        IFS= read -r boot_id < /proc/sys/kernel/random/boot_id || boot_id=
        if [ -n "$fields" ] && [ -n "$boot_id" ]; then
            record_value=$(printf '%s\n%s\n%s\n%s\n%s\n%s\n' "$nonce" "$$" "$owner_uid" "$owner_tty" "$fields" "$boot_id")
            printf '%s\n' "$record_value" > "$record_dir/record.new" &&
                chmod 600 -- "$record_dir/record.new" && mv -- "$record_dir/record.new" "$record_dir/record"
        fi
    fi
fi
case "$mode" in
    shell)
        shell_path=${SHELL:-/bin/sh}
        shell_name=${shell_path##*/}
        if [ -x /bin/bash ]; then
            # mosh execvp(shell, ["-basename"]), including argv[0] and BASH_ENV.
            BASH_ENV=/dev/null /bin/bash --noprofile --norc -c '
                if [ "$3" = x ]; then export BASH_ENV="$4"; else unset BASH_ENV; fi
                exec -a "$1" "$2"
            ' moke-login "-$shell_name" "$shell_path" "${BASH_ENV+x}" "${BASH_ENV-}"
        else
            exit 69
        fi
        ;;
    command) sh -c "$payload" ;;
    *) exit 64 ;;
esac
result=$?
# Do not exec away the owner PID/nonce when the foreground child is running.
exit "$result"
