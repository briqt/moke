package com.briqt.moke.terminal

/** 附加键上的修饰键。Shift 只作用于附加键与宏（Shift+Tab / Shift+方向），字母大小写仍归输入法。 */
enum class ModKind { Ctrl, Alt, Shift }

/**
 * 修饰键三态：点一下 = 只对下一个键生效；再点一下 = 锁定（连续生效）；第三下 = 关。
 * 连发 Ctrl+C、按住 Ctrl 连走光标这类操作没有锁定态就很难用。
 */
enum class ModState {
    Off, Once, Locked;

    val active: Boolean get() = this != Off

    fun next(): ModState = when (this) {
        Off -> Once
        Once -> Locked
        Locked -> Off
    }
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

    fun toggle(kind: ModKind): Modifiers = when (kind) {
        ModKind.Ctrl -> copy(ctrl = ctrl.next())
        ModKind.Alt -> copy(alt = alt.next())
        ModKind.Shift -> copy(shift = shift.next())
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
