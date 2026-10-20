#!/bin/sh
# type: live owner witness verification
nonce=$1
unknown() { printf 'UNKNOWN\n'; exit 0; }
pending() { printf 'PENDING\n'; exit 0; }
case "$nonce" in ''|*[!a-f0-9]*) unknown;; esac
[ "${#nonce}" -eq 32 ] || unknown
uid=$(id -u) || unknown
base=${TMPDIR:-/tmp}
case "$base" in /*) :;; *) unknown;; esac
[ -d "$base" ] && [ ! -L "$base" ] || unknown
[ "$(readlink -f -- "$base" 2>/dev/null)" = "$base" ] || unknown
base_meta=$(stat -c '%u %a' -- "$base" 2>/dev/null) || unknown
case "$base_meta" in '0 1777'|"$uid 700") :;; *) unknown;; esac
record_dir="$base/.moke-tty-$nonce"
# Only a truly absent nonce is pending. A symlink or malformed/stale record is unknown.
if [ ! -e "$record_dir" ] && [ ! -L "$record_dir" ]; then pending; fi
[ -d "$record_dir" ] && [ ! -L "$record_dir" ] || unknown
[ -f "$record_dir/record" ] && [ ! -L "$record_dir/record" ] || unknown
[ "$(stat -c '%u %a' -- "$record_dir" 2>/dev/null)" = "$uid 700" ] || unknown
[ "$(stat -c '%u %a' -- "$record_dir/record" 2>/dev/null)" = "$uid 600" ] || unknown
record_identity=$(stat -c '%d:%i' -- "$record_dir/record" 2>/dev/null) || unknown
{
    IFS= read -r expected_nonce && IFS= read -r pid && IFS= read -r expected_uid &&
    IFS= read -r expected_tty && IFS= read -r expected_fields && IFS= read -r expected_boot &&
    ! IFS= read -r extra
} < "$record_dir/record" || unknown
[ "$expected_nonce" = "$nonce" ] && [ "$expected_uid" = "$uid" ] || unknown
IFS= read -r actual_boot < /proc/sys/kernel/random/boot_id || unknown
[ "$expected_boot" = "$actual_boot" ] || unknown
case "$pid" in ''|*[!0-9]*) unknown;; esac
[ "$pid" -gt 1 ] || unknown
case "$expected_tty" in /dev/pts/*) tty_index=${expected_tty#/dev/pts/};; *) unknown;; esac
case "$tty_index" in ''|*[!0-9]*) unknown;; esac
[ -c "$expected_tty" ] || unknown
[ "$(stat -c %u -- "/proc/$pid" 2>/dev/null)" = "$uid" ] || unknown
proc_fields() {
    IFS= read -r proc_stat < "/proc/$pid/stat" || return 1
    proc_tail=${proc_stat##*) }
    set -- $proc_tail
    [ "$#" -ge 20 ] || return 1
    printf '%s %s\n' "$5" "${20}"
}
first=$(proc_fields) || unknown
[ "$first" = "$expected_fields" ] || unknown
grep -Fzqx -- "$nonce" "/proc/$pid/cmdline" 2>/dev/null || unknown
[ "$(readlink -- "/proc/$pid/fd/0" 2>/dev/null)" = "$expected_tty" ] || unknown
set -- $first
[ "$#" -eq 2 ] || unknown
actual_tty_nr=$1
actual_start=$2
case "$actual_start" in ''|*[!0-9]*) unknown;; esac
[ "$actual_start" -gt 0 ] || unknown
case "$actual_tty_nr" in -*) tty_digits=${actual_tty_nr#-};; *) tty_digits=$actual_tty_nr;; esac
case "$tty_digits" in ''|*[!0-9]*) unknown;; esac
[ "$actual_tty_nr" != 0 ] || unknown
# Linux tty_nr is signed dev_t32. %t/%T also work on coreutils 8.32 (%r does not).
[ "$actual_tty_nr" -ge 0 ] || actual_tty_nr=$((actual_tty_nr + 4294967296))
major_hex=$(stat -c %t -- "$expected_tty" 2>/dev/null) || unknown
minor_hex=$(stat -c %T -- "$expected_tty" 2>/dev/null) || unknown
case "$major_hex:$minor_hex" in *[!0-9a-fA-F:]*|:|:*|*:) unknown;; esac
major=$((0x$major_hex))
minor=$((0x$minor_hex))
[ "$major" -le 4095 ] && [ "$minor" -le 1048575 ] || unknown
device=$(((major << 8) | (minor & 255) | ((minor & 1048320) << 12)))
[ "$device" = "$actual_tty_nr" ] || unknown
second=$(proc_fields) || unknown
[ "$second" = "$first" ] || unknown
[ "$(stat -c '%d:%i' -- "$record_dir/record" 2>/dev/null)" = "$record_identity" ] || unknown
[ ! -L "$record_dir" ] && [ ! -L "$record_dir/record" ] || unknown
printf 'WITNESS|%s|%s|%s|%s\n' "$pid" "$uid" "$actual_start" "$expected_tty"
