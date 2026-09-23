package com.hazuki.imageorganizer.data

import java.io.File

/**
 * =====================================================================
 * 【ファイル操作・連番リネーム ヘルパークラス】
 *
 * ■ 命名規則: [ラベル名]_[グループ番号2文字]_[画像番号2文字].[拡張子]
 *   例: 猫_00_00.jpg, 猫_00_01.jpg, 猫_AA_00.jpg
 *
 * ■ 2文字固定カウント方式（最大776通り）:
 *   ・0 〜 99   ➔ "00" 〜 "99" (100通り)
 *   ・100 〜 775 ➔ "AA" 〜 "ZZ" (26 × 26 = 676通り)
 * =====================================================================
 */
object RenameMoveHelper {

    /** 2文字で表現できる上限数（01〜99: 99通り + AA〜ZZ: 676通り = 775通り） */
    const val MAX_SEQUENCE = 775

    /** 実行モード（移動 or コピー） */
    enum class ExecuteMode { MOVE, COPY }

    /** 処理の結果を安全に受け取るためのクラス */
    sealed class Result {
        /** 成功時（成功した件数, 保存先フォルダ） */
        data class Success(val count: Int, val destFolder: File) : Result()

        /** 失敗時（途中で成功した件数, 全体件数, 後始末の失敗有無） */
        data class Failure(
            val succeededCount: Int,
            val total: Int,
            val cleanupFailed: Boolean
        ) : Result()

        /** 枚数上限（775枚）を超えた場合 */
        data class TooMany(val requested: Int) : Result()
    }

    /**
     * 【リネーム済みファイル情報のデータクラス】
     * 規則: [ラベル名]_[グループ2文字]_[連番2文字].[拡張子]
     * 例: "01犬_01_01.jpg" ➔ label="01犬", groupCode="01", seqCode="01"
     */
    data class RenamedFileInfo(
        val label: String,      // ラベル名（例: "01犬"）
        val groupCode: String,  // グループ2文字（例: "01", "02", "AA"）
        val seqCode: String     // 画像連番2文字（例: "01", "02"）
    ) {
        /** ラベルとグループ番号を組み合わせた一意のキー（枠色分けのグループ単位） */
        val groupKey: String get() = "${label}_${groupCode}"
    }

    /**
     * 【ファイル名がリネーム規則（_nn_mm形式）に合致するか判定】
     * 正規表現でファイル名を検査し、合致すればラベルやグループコードを返します。
     * 合致しない場合は null を返します。
     */
    fun parseRenamedFileInfo(displayName: String): RenamedFileInfo? {
        // パターン: [ラベル名]_[グループ2文字]_[画像番号2文字].[拡張子]
        // ※OSの重複回避で末尾に付く「(1)」や「（１）」などの半角・全角枝番も安全に吸収して認識します
        val pattern = Regex("^(.+)_([0-9A-Z]{2})_([0-9A-Z]{2})(?:[\\s　]*[\\(（][\\d０-９]+[\\)）])?\\.[^.]+$")
        val match = pattern.matchEntire(displayName) ?: return null
        val label = match.groupValues[1]
        val groupCode = match.groupValues[2]
        val seqCode = match.groupValues[3]

        // 2文字コードとして有効な値（01〜99 または AA〜ZZ）かチェック
        if (fromSeqCode(groupCode) == null || fromSeqCode(seqCode) == null) return null

        return RenamedFileInfo(label, groupCode, seqCode)
    }

    /**
     * 【数値 ➔ 2文字コード変換（1始まり・01スタート）】
     * 1始まりの通し番号を、2文字の連番文字列に変換します。
     * 例: 1 -> "01", 5 -> "05", 99 -> "99", 100 -> "AA", 775 -> "ZZ"
     */
    fun toSeqCode(n: Int): String {
        require(n in 1..MAX_SEQUENCE) { "連番の上限(1〜$MAX_SEQUENCE)を超えています: $n" }
        return if (n <= 99) {
            // 1〜99は数字2桁（01〜99）
            "%02d".format(n)
        } else {
            // 100〜775はアルファベット2文字（AA〜ZZ）
            val letterIndex = n - 100 // 0 〜 675
            val first = 'A' + (letterIndex / 26)
            val second = 'A' + (letterIndex % 26)
            "$first$second"
        }
    }

    /**
     * 【2文字コード ➔ 数値変換】
     * "00"〜"ZZ" の2文字コードを、元の数値（0〜775）に戻します。
     * 
     * ■ 救済措置について:
     * 本来の現行ルールは 1始まり（"01"スタート）ですが、
     * 過去に "00" 始まり（例: 01犬_01_00.jpg）で生成されたファイルが存在するため、
     * "00" を 0 として正当に認識・復元できるように救済（0..99 を許容）しています。
     * これにより、「ー / ー」にならずグループ連番ソートや枠色分けに正しく反映されます。
     * 
     * @param code 2文字のコード（"00"〜"99" または "AA"〜"ZZ"）
     * @return 変換後の数値（不正な文字列の場合は null）
     */
    fun fromSeqCode(code: String): Int? {
        if (code.length != 2) return null
        return if (code[0].isDigit() && code[1].isDigit()) {
            // 【救済対応】"00"〜"99"（00の場合は数値の0を返す）
            code.toIntOrNull()?.takeIf { it in 0..99 }
        } else if (code[0] in 'A'..'Z' && code[1] in 'A'..'Z') {
            // "AA"〜"ZZ"（100〜775）
            100 + (code[0] - 'A') * 26 + (code[1] - 'A')
        } else {
            null
        }
    }

    /**
     * 【ファイル名の禁則文字除去】
     * WindowsやAndroidで使えない記号（/ \ : * ? " < > |）を「_」に置き換えます。
     * ひらがな、カタカナ、漢字、英数字はそのまま使えます。
     */
    fun sanitizeForFilename(name: String): String {
        val invalidChars = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')
        val sanitized = name
            .map { c -> if (c in invalidChars || c.code < 0x20) '_' else c }
            .joinToString("")
            .trim()
        return sanitized.ifBlank { "untitled" }
    }

    /**
     * 【既存ファイル名一覧を読み取って、次のグループ番号を探す】
     * ファイル名一覧（同じラベル名で始まるもの）をスキャンし、
     * 次に使うべきグループ番号（1始まり: 1➔"01", 2➔"02"...）を自動計算して返します。
     * SAFやMediaStoreなど、Fileオブジェクトが直接使えない場合にも利用できます。
     */
    fun findNextGroupIndexFromNames(existingNames: Collection<String>, sanitizedLabel: String): Int {
        // パターン: [ラベル名]_[グループ2文字]_[画像番号2文字].[拡張子]
        // ※「(1)」や「（１）」などの半角・全角枝番が付いていても元のグループ番号として計算に含めます
        val pattern = Regex("^${Regex.escape(sanitizedLabel)}_([0-9A-Z]{2})_([0-9A-Z]{2})(?:[\\s　]*[\\(（][\\d０-９]+[\\)）])?\\..+$")
        var maxIndex = 0 // 既存が無ければ 0 ➔ 次は 1（"01"）

        for (name in existingNames) {
            val match = pattern.matchEntire(name) ?: continue
            val groupCode = match.groupValues[1] // グループ2文字
            val groupIndex = fromSeqCode(groupCode) ?: continue
            if (groupIndex > maxIndex) {
                maxIndex = groupIndex
            }
        }
        // 見つかった最大番号の「次 (+1)」を返します（既存が無ければ 1 = "01"）
        return maxIndex + 1
    }

    /**
     * 【既存ファイルを読み取って、次のグループ番号を探す】
     * フォルダ内の既存ファイル（同じラベル名で始まるもの）をスキャンし、
     * 次に使うべきグループ番号（1始まり）を自動計算して返します。
     */
    fun findNextGroupIndex(destFolder: File, sanitizedLabel: String): Int {
        if (!destFolder.exists() || !destFolder.isDirectory) return 1
        val names = destFolder.listFiles()?.map { it.name } ?: emptyList()
        return findNextGroupIndexFromNames(names, sanitizedLabel)
    }

    /**
     * 【① 連番リネーム ＋ ラベル名フォルダを作成して移動/コピー】
     *
     * @param sourceFiles    選択された元ファイル一覧（並び順どおりに 01, 02... と番号が付きます）
     * @param destFolderRoot 保存先の親フォルダ（この中に「ラベル名」のフォルダが作られます）
     * @param label          ラベル名（例: "猫"）
     * @param mode           MOVE（移動）または COPY（コピー）
     */
    fun execute(
        sourceFiles: List<File>,
        destFolderRoot: File,
        label: String,
        mode: ExecuteMode
    ): Result {
        if (sourceFiles.isEmpty()) return Result.Success(0, destFolderRoot)
        if (sourceFiles.size > MAX_SEQUENCE) return Result.TooMany(sourceFiles.size)

        // 1. ラベル名の安全化とフォルダ作成
        val sanitizedLabel = sanitizeForFilename(label)
        val labelFolder = File(destFolderRoot, sanitizedLabel)
        if (!labelFolder.exists()) {
            labelFolder.mkdirs()
        }

        // 2. ラベルフォルダ内の既存ファイルから次のグループ番号を取得（1始まり）
        val groupIndex = findNextGroupIndex(labelFolder, sanitizedLabel)
        if (groupIndex > MAX_SEQUENCE) {
            return Result.TooMany(sourceFiles.size)
        }
        val groupCode = toSeqCode(groupIndex)

        // 3. 安全のため、まずは全件「コピー」を行う（連番は 01 スタート）
        val copiedFiles = mutableListOf<File>()
        for ((i, srcFile) in sourceFiles.withIndex()) {
            val imageCode = toSeqCode(i + 1) // 1枚目=01, 5枚目=05
            val ext = srcFile.extension
            val destFileName = if (ext.isNotBlank()) {
                "${sanitizedLabel}_${groupCode}_${imageCode}.$ext"
            } else {
                "${sanitizedLabel}_${groupCode}_${imageCode}"
            }
            val destFile = File(labelFolder, destFileName)

            val copyOk = try {
                srcFile.copyTo(destFile, overwrite = false)
                true
            } catch (e: Exception) {
                false
            }

            if (!copyOk) {
                // 途中で失敗した場合：作成したコピー先ファイルを削除して元に戻す（元ファイルは触らない）
                var cleanupFailed = false
                copiedFiles.forEach { f ->
                    if (f.exists() && !f.delete()) cleanupFailed = true
                }
                return Result.Failure(
                    succeededCount = i,
                    total = sourceFiles.size,
                    cleanupFailed = cleanupFailed
                )
            }
            copiedFiles.add(destFile)
        }

        // 4. 全件コピー成功。「移動」モードの時だけ、最後に元ファイルを削除
        if (mode == ExecuteMode.MOVE) {
            sourceFiles.forEach { it.delete() }
        }

        return Result.Success(sourceFiles.size, labelFolder)
    }

    /**
     * 【② 同じフォルダ内で連番リネームのみ実行】
     * フォルダ移動はせず、現在のフォルダの中で [ラベル名]_[グループ]_[連番] に名前を変えます。
     * 途中で失敗した場合は自動的に元の名前にロールバック（復元）します。
     */
    fun renameInPlace(sourceFiles: List<File>, label: String): Result {
        if (sourceFiles.isEmpty()) return Result.Success(0, File("."))
        if (sourceFiles.size > MAX_SEQUENCE) return Result.TooMany(sourceFiles.size)

        val parent = sourceFiles.first().parentFile ?: return Result.Failure(0, sourceFiles.size, false)
        val sanitizedLabel = sanitizeForFilename(label)
        val groupIndex = findNextGroupIndex(parent, sanitizedLabel)
        if (groupIndex >= MAX_SEQUENCE) return Result.TooMany(sourceFiles.size)
        val groupCode = toSeqCode(groupIndex)

        val renamedPairs = mutableListOf<Pair<File, File>>()
        for ((i, srcFile) in sourceFiles.withIndex()) {
            val imageCode = toSeqCode(i + 1) // 1枚目=01, 5枚目=05
            val ext = srcFile.extension
            val newName = if (ext.isNotBlank()) {
                "${sanitizedLabel}_${groupCode}_${imageCode}.$ext"
            } else {
                "${sanitizedLabel}_${groupCode}_${imageCode}"
            }
            val newFile = File(parent, newName)
            // 【安全ガード】すでに目的の名前と同じなら、OSにリネーム命令を出さず成功扱いとする（誤作動防止）
            val ok = if (srcFile.name == newName) {
                true
            } else {
                try {
                    srcFile.renameTo(newFile)
                } catch (e: Exception) {
                    false
                }
            }

            if (!ok) {
                // 失敗時：それまでリネームしたファイルを元の名前に戻す
                var rollbackFailed = false
                renamedPairs.asReversed().forEach { (original, renamed) ->
                    if (renamed.exists() && !renamed.renameTo(original)) rollbackFailed = true
                }
                return Result.Failure(succeededCount = i, total = sourceFiles.size, cleanupFailed = rollbackFailed)
            }
            renamedPairs.add(srcFile to newFile)
        }
        return Result.Success(sourceFiles.size, parent)
    }

    /**
     * 【③ そのままコピー / 移動】
     * 連番リネームは行わず、指定フォルダへそのままの名前でコピーまたは移動します。
     */
    fun copyOrMovePlain(sourceFiles: List<File>, destFolder: File, mode: ExecuteMode): Result {
        if (sourceFiles.isEmpty()) return Result.Success(0, destFolder)
        if (!destFolder.exists()) destFolder.mkdirs()

        val copiedFiles = mutableListOf<File>()
        for ((i, srcFile) in sourceFiles.withIndex()) {
            val destFile = File(destFolder, srcFile.name)
            val copyOk = try {
                srcFile.copyTo(destFile, overwrite = false)
                true
            } catch (e: Exception) {
                false
            }

            if (!copyOk) {
                var cleanupFailed = false
                copiedFiles.forEach { f -> if (f.exists() && !f.delete()) cleanupFailed = true }
                return Result.Failure(succeededCount = i, total = sourceFiles.size, cleanupFailed = cleanupFailed)
            }
            copiedFiles.add(destFile)
        }

        if (mode == ExecuteMode.MOVE) {
            sourceFiles.forEach { it.delete() }
        }
        return Result.Success(sourceFiles.size, destFolder)
    }

    /**
     * 【④ ファイル削除】
     * 選択したファイルを完全に削除します。
     */
    fun deleteFiles(sourceFiles: List<File>): Result {
        if (sourceFiles.isEmpty()) return Result.Success(0, File("."))
        val parent = sourceFiles.first().parentFile ?: File(".")
        for ((i, f) in sourceFiles.withIndex()) {
            val ok = try { f.delete() } catch (e: Exception) { false }
            if (!ok) {
                return Result.Failure(succeededCount = i, total = sourceFiles.size, cleanupFailed = false)
            }
        }
        return Result.Success(sourceFiles.size, parent)
    }

    /** 連番ファイル名の解析結果 */
    data class ParsedSeqName(val label: String, val groupIndex: Int, val imageIndex: Int)

    // 連番ファイル名の正規表現パターン（半角・全角の「(1)」「（１）」等が付いていても安全に吸収）
    private val seqNamePattern = Regex("^(.+)_([0-9A-Z]{2})_([0-9A-Z]{2})(?:[\\s　]*[\\(（][\\d０-９]+[\\)）])?\\.[^.]+$")

    /**
     * 【ファイル名の形式チェック＆分解】
     * ファイル名が「ラベル_XX_XX.拡張子」か判定し、中身を分解して数値で返します。
     */
    fun parseSeqName(fileName: String): ParsedSeqName? {
        val match = seqNamePattern.matchEntire(fileName) ?: return null
        val label = match.groupValues[1]
        val groupIndex = fromSeqCode(match.groupValues[2]) ?: return null
        val imageIndex = fromSeqCode(match.groupValues[3]) ?: return null
        return ParsedSeqName(label, groupIndex, imageIndex)
    }

    /**
     * 【⑤ フォルダ内一括ラベル変更】
     * フォルダ内の既存連番ファイルの「ラベル名」だけを新しいラベル名に一括置換します。
     */
    fun renameGroupLabel(folder: File, oldLabel: String, newLabel: String): Result {
        if (!folder.exists() || !folder.isDirectory) return Result.Failure(0, 0, false)
        val allFiles = folder.listFiles()?.toList() ?: return Result.Success(0, folder)

        data class TargetFile(val file: File, val parsed: ParsedSeqName)
        val targets = allFiles.mapNotNull { f ->
            parseSeqName(f.name)?.let { TargetFile(f, it) }
        }
        if (targets.isEmpty()) return Result.Success(0, folder)

        val sameLabel = targets.filter { it.parsed.label == oldLabel }
        val otherLabel = targets.filter { it.parsed.label != oldLabel }
        val renamedPairs = mutableListOf<Pair<File, File>>()

        // 同名カテゴリーのラベルを新ラベルに置換
        for (target in sameLabel) {
            val groupCode = toSeqCode(target.parsed.groupIndex)
            val imageCode = toSeqCode(target.parsed.imageIndex)
            val ext = target.file.extension
            val newName = if (ext.isNotBlank()) "${newLabel}_${groupCode}_${imageCode}.$ext" else "${newLabel}_${groupCode}_${imageCode}"
            val newFile = File(folder, newName)

            // 【安全ガード】すでに目的の名前と同じなら、OSにリネーム命令を出さず成功扱いとする（誤作動防止）
            val ok = if (target.file.name == newName) {
                true
            } else {
                try { target.file.renameTo(newFile) } catch (e: Exception) { false }
            }
            if (!ok) {
                var rollbackFailed = false
                renamedPairs.asReversed().forEach { (orig, ren) ->
                    if (ren.exists() && !ren.renameTo(orig)) rollbackFailed = true
                }
                return Result.Failure(renamedPairs.size, targets.size, rollbackFailed)
            }
            renamedPairs.add(target.file to newFile)
        }

        // 別カテゴリーのファイルがある場合、グループ番号を続きから振り直して統一
        val maxGroupIndex = if (sameLabel.isEmpty()) -1 else sameLabel.maxOf { it.parsed.groupIndex }
        val otherGroups = otherLabel.groupBy { "${it.parsed.label}_${it.parsed.groupIndex}" }
        var nextGroupIndex = maxGroupIndex + 1

        for ((_, groupFiles) in otherGroups) {
            if (nextGroupIndex >= MAX_SEQUENCE) {
                var rollbackFailed = false
                renamedPairs.asReversed().forEach { (orig, ren) ->
                    if (ren.exists() && !ren.renameTo(orig)) rollbackFailed = true
                }
                return Result.TooMany(targets.size)
            }

            val groupCode = toSeqCode(nextGroupIndex)
            for (target in groupFiles) {
                val imageCode = toSeqCode(target.parsed.imageIndex)
                val ext = target.file.extension
                val newName = if (ext.isNotBlank()) "${newLabel}_${groupCode}_${imageCode}.$ext" else "${newLabel}_${groupCode}_${imageCode}"
                val newFile = File(folder, newName)

                // 【安全ガード】すでに目的の名前と同じなら、OSにリネーム命令を出さず成功扱いとする（誤作動防止）
                val ok = if (target.file.name == newName) {
                    true
                } else {
                    try { target.file.renameTo(newFile) } catch (e: Exception) { false }
                }
                if (!ok) {
                    var rollbackFailed = false
                    renamedPairs.asReversed().forEach { (orig, ren) ->
                        if (ren.exists() && !ren.renameTo(orig)) rollbackFailed = true
                    }
                    return Result.Failure(renamedPairs.size, targets.size, rollbackFailed)
                }
                renamedPairs.add(target.file to newFile)
            }
            nextGroupIndex++
        }

        return Result.Success(renamedPairs.size, folder)
    }
}
