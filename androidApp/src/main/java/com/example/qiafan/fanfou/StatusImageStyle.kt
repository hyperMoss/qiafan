package com.example.qiafan.fanfou

import android.graphics.Color

internal enum class StatusImageStyle(
    val title: String,
    val description: String,
    val background: Int,
    val paper: Int,
    val ink: Int,
    val muted: Int,
    val accent: Int,
    val divider: Int
) {
    PAPER("便笺", "暖白纸感 · 细线边框",
        Color.rgb(247, 244, 236), Color.rgb(255, 253, 247), Color.rgb(31, 36, 42),
        Color.rgb(107, 113, 121), Color.rgb(116, 99, 82), Color.rgb(229, 232, 235)),
    MINIMAL("极简", "纯白留白 · 蓝色点缀",
        Color.WHITE, Color.WHITE, Color.rgb(24, 32, 43),
        Color.rgb(109, 117, 128), Color.rgb(0, 119, 181), Color.rgb(230, 234, 239)),
    MIDNIGHT("深色", "深蓝卡片 · 清透文字",
        Color.rgb(16, 24, 36), Color.rgb(27, 38, 54), Color.rgb(235, 241, 248),
        Color.rgb(169, 185, 204), Color.rgb(128, 206, 238), Color.rgb(55, 71, 91));

    val contentBlock: Int get() = when (this) {
        PAPER -> Color.rgb(246, 243, 235)
        MINIMAL -> Color.rgb(245, 247, 250)
        MIDNIGHT -> Color.rgb(20, 30, 46)
    }

}
