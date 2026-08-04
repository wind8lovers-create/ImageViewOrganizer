package com.hazuki.imageorganizer.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * リネーム機能: 表示(ソート)順に連番を振り直す。
 * 命名規則: REN + 年月日時分(10桁) + "_" + 連番 + 拡張子
 *
 * 連番は 01〜99 の後、AA〜ZZ (アルファベット2桁、26×26=676通り) に切り替わる。
 * 拡張子は元のファイルのものをそのまま維持する(再圧縮による画質劣化を避けるため)。
 */
object RenameUtil {

    private val TIMESTAMP_FORMAT = SimpleDateFormat("yyMMddHHmm", Locale.US)

    /** 現在時刻から "REN" プレフィックス用のタイムスタンプ(10桁)を生成 */
    fun buildTimestampPrefix(now: Date = Date()): String {
        return "REN" + TIMESTAMP_FORMAT.format(now)
    }

    /**
     * index(0始まり)に対応する連番文字列を返す。
     * 0〜97 -> "01"〜"99"
     * 98〜  -> "AA","AB",...,"AZ","BA",...,"ZZ" (676通り)
     */
    fun sequenceFor(index: Int): String {
        return if (index < 99) {
            // index 0 -> "01" ... index 98 -> "99"
            (index + 1).toString().padStart(2, '0')
        } else {
            val alphaIndex = index - 99 // 0始まり: 0 -> "AA"
            if (alphaIndex >= 26 * 26) {
                // 676通りを使い切った場合は、末尾に追加のインデックスを付けて衝突回避
                val overflow = alphaIndex - 26 * 26
                "ZZ${overflow}"
            } else {
                val first = 'A' + (alphaIndex / 26)
                val second = 'A' + (alphaIndex % 26)
                "$first$second"
            }
        }
    }

    /**
     * @param originalExtension ドット無しの拡張子(例: "jpg")。空なら拡張子なし。
     * @return 例: "REN2608041228_01.jpg"
     */
    fun buildFileName(prefix: String, index: Int, originalExtension: String): String {
        val seq = sequenceFor(index)
        return if (originalExtension.isNotBlank()) {
            "${prefix}_$seq.$originalExtension"
        } else {
            "${prefix}_$seq"
        }
    }
}
