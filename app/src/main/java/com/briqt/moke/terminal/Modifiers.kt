package com.briqt.moke.terminal

/** 附加键上的修饰键。Shift 只作用于附加键与宏（Shift+Tab / Shift+方向），字母大小写仍归输入法。 */
enum class ModKind { Ctrl, Alt, Shift }

/**
 * 修饰键三态。点一下进入 [Once]（只对下一个键生效），再点一下关掉；
 * 按住（系统「按住延迟」）进入 [Locked]（连续生效，直到再点一下）。
 * 连发 Ctrl+C、按住 Ctrl 连走光标靠锁定态。
 */
enum class ModState {
    Off, Once, Locked;

    val active: Boolean get() = this != Off
}

/** 三个修饰键的当前状态。纯数据，供 UI 与编码器共用。 */
data class Modifiers(
    val ctrl: ModState = ModState.Off,
    val alt: ModState = ModState.Off,
    val shift: ModState = ModState.Off,
) {
    val ctrlOn: Boolean get() = ctrl.active
    val altOn: Boolean get() = alt.active
    val shiftOn: Boolean get() = shift.active

    fun state(kind: ModKind): ModState = when (kind) {
        ModKind.Ctrl -> ctrl
        ModKind.Alt -> alt
        ModKind.Shift -> shift
    }

    /** 点一下：关 → 一次性；一次性或锁定 → 关。 */
    fun tap(kind: ModKind): Modifiers = set(kind, when (state(kind)) {
        ModState.Off -> ModState.Once
        ModState.Once, ModState.Locked -> ModState.Off
    })

    /** 按住：进入锁定，已锁定则保持。 */
    fun hold(kind: ModKind): Modifiers = set(kind, ModState.Locked)

    private fun set(kind: ModKind, value: ModState): Modifiers = when (kind) {
        ModKind.Ctrl -> copy(ctrl = value)
        ModKind.Alt -> copy(alt = value)
        ModKind.Shift -> copy(shift = value)
    }

    /** 一次性修饰被一个按键消费后复位；锁定态保持不变。 */
    fun consumeOnce(): Modifiers = Modifiers(
        ctrl = if (ctrl == ModState.Once) ModState.Off else ctrl,
        alt = if (alt == ModState.Once) ModState.Off else alt,
        shift = if (shift == ModState.Once) ModState.Off else shift,
    )

    /** 按当前修饰把一个按键编码成字节序列。 */
    fun encode(key: KeyId): String = KeySeq.encode(key, ctrl = ctrlOn, alt = altOn, shift = shiftOn)
}
