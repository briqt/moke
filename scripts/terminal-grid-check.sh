#!/usr/bin/env bash
# 终端网格人工验收：把四类字形各打一行，肉眼对照"应该是"的说明。
#
# 为什么要人工看：格子几何、字形落位、字体度量这些都能被单测覆盖（GridMetricsTest、
# BundledFontMetricsTest），但"看起来对不对"只有真机能回答——尤其在换字体/换字体回退时。
#
# 用法：在 moke 终端里运行
#     bash scripts/terminal-grid-check.sh
#   或者只打印某一段：bash scripts/terminal-grid-check.sh blocks
#
# 每段的"通过"标准写在对应函数上方。设计取舍见 docs/terminal-experience.md。

set -u

# 2 格宽字符组成的实心块：列间距、行间距、24 位色都要同时正确，块才连成一片没有缝。
# 通过标准：整体是一个完整的彩虹心形，横向没有竖缝、纵向没有横缝，颜色沿对角线平滑过渡。
blocks() {
    awk 'BEGIN {
        m[1]="     ##     ";  m[2]="    ####    ";  m[3]="   ######   ";  m[4]="   ######   ";
        m[5]="  ########  ";  m[6]="  ###  ###  ";  m[7]="  ##    ##  ";  m[8]=" ###    ### ";
        m[9]=" ##      ## ";  m[10]="##        ##";
        for (y = 1; y <= 10; y++) {
            for (x = 1; x <= 12; x++) {
                if (substr(m[y], x, 1) == "#") {
                    r = int(128 + 127 * sin((x * 0.35 - y * 0.25)));
                    g = int(128 + 127 * sin((x * 0.35 - y * 0.25) + 2.094));
                    b = int(128 + 127 * sin((x * 0.35 - y * 0.25) + 4.188));
                    printf "\033[38;2;%d;%d;%dm██\033[0m", r, g, b;
                } else {
                    printf "  ";
                }
            }
            printf "\n";
        }
    }'
}

# 标尺：每 10 列一个数字。通过标准：数字落在整列上，行尾不出现半格偏移。
ruler() {
    printf '列标尺（每格递增，10 的倍数为 |）:\n'
    awk 'BEGIN {
        for (i = 1; i <= 78; i++) {
            if (i % 10 == 0) printf "|";
            else if (i % 5 == 0) printf "+";
            else printf ".";
        }
        printf "\n";
        for (i = 1; i <= 78; i++) printf((i % 10 == 0) ? "%d" : " ", i);
        printf "\n";
    }'
}

# Nerd 图标（devicons / Font Awesome）：按 1em 设计尺寸绘制，允许对称溢出到相邻空格。
# 通过标准：图标是方的、比例正常、与同一行的拉丁字母高度相当（不应明显偏小）。
icons() {
    printf 'Nerd 图标（应保持方形比例，不应偏小）:\n'
    printf '  \xee\x9c\x82  \xef\x80\x8c  \xef\x80\x95  \xee\x9c\xa1  \xef\x81\xbb  \xef\x84\xa1  \xee\x98\xa2  \xef\x82\xb0\n'
    printf '  U+E702 U+F00C U+F015 U+E721 U+F07B U+F121 U+E622 U+F0B0\n'
}

# Powerline / box drawing：必须与格子严丝合缝，否则提示符会出现断缝。
# 通过标准：箭头之间没有竖缝，箭头高度填满整行，方块之间没有横缝。
lineart() {
    printf 'Powerline 与线画（应无缝、填满行高）:\n'
    printf '  \xee\x82\xb0\xee\x82\xb0\xee\x82\xb0\xee\x82\xb2\xee\x82\xb2\xee\x82\xb2\n'
    printf '  \xe2\x94\x8c\xe2\x94\x80\xe2\x94\x80\xe2\x94\x80\xe2\x94\xac\xe2\x94\x80\xe2\x94\x80\xe2\x94\x80\xe2\x94\x90\n'
    printf '  \xe2\x94\x82\xe2\x96\x88\xe2\x96\x88\xe2\x96\x88\xe2\x94\x82\xe2\x96\x91\xe2\x96\x91\xe2\x96\x91\xe2\x94\x82\n'
    printf '  \xe2\x94\x94\xe2\x94\x80\xe2\x94\x80\xe2\x94\x80\xe2\x94\xb4\xe2\x94\x80\xe2\x94\x80\xe2\x94\x80\xe2\x94\x98\n'
}

# 中日韩宽字符：占 2 列。
# 通过标准：汉字之间间距均匀、与上方标尺对齐（一个汉字正好两个标尺列），不出现被拉宽或留大缝。
cjk() {
    printf 'CJK 宽字符（每字两列，间距均匀）:\n'
    printf '  中文字符宽度测试：终端网格应当精确对齐\n'
    printf '  一二三四五六七八九十 あいうえお 한글テスト\n'
}

# 连字：等宽连字字形的 advance 等于它替换掉的列宽之和，因此可以无缝嵌进网格。
# 通过标准：连字是一体形状、没有横向拉伸，且该行右侧的内容仍落在正确的列上。
ligatures() {
    printf '连字（若字体支持）：\n'
    printf '  -> => != === <= >= |> <| :: www\n'
    printf '  右侧标记应落在同一列: |\n'
}

# 光标与选中：块光标应正好覆盖一个格子，反色文字的基线与其他行一致。
cursor_hint() {
    printf '光标检查：块光标应刚好盖住一个字符格，上下不压到相邻行。\n'
}

section="${1:-all}"
case "$section" in
    blocks)    blocks ;;
    ruler)     ruler ;;
    icons)     icons ;;
    lineart)   lineart ;;
    cjk)       cjk ;;
    ligatures) ligatures ;;
    all)
        ruler; printf '\n'
        blocks; printf '\n'
        icons; printf '\n'
        lineart; printf '\n'
        cjk; printf '\n'
        ligatures; printf '\n'
        cursor_hint
        ;;
    *)
        printf '未知段落：%s\n可选：all blocks ruler icons lineart cjk ligatures\n' "$section" >&2
        exit 2
        ;;
esac
