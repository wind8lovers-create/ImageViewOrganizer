package com.hazuki.imageorganizer.data

/**
 * 手動グルーピング(分類)1件の情報。
 * 「カテゴリ文字(A〜Z) + カテゴリ内の連番」で一意に識別する。
 * (要件定義: 個別グループ名とカテゴリのテーマ名は区別しない、ツリー構造にしない、という方針のため
 *  データ構造もこの1つのフラットなクラスだけで表現している)
 */
data class ClassificationGroup(
    val category: Char,                       // 'A'〜'Z'
    val sequence: Int,                         // カテゴリ内の連番(1,2,3...)。欠番(空になったグループの削除)を許容する
    val name: String,                          // ユーザーが付けた名前。空文字なら「連番名のみ」として扱う
    val imageIds: List<Long> = emptyList(),    // 所属画像のID(追加された順序を保持)
    val sortOption: SortOption = SortOption.DEFAULT, // このグループ内画面(GroupDetailScreen)でのソート状態
    val representativeId: Long? = null         // 「サムネ指定」で選ばれた代表画像ID。nullなら imageIds の1枚目を使う
) {
    /** グループを一意に識別するキー文字列(例: "C_04")。永続化・画面遷移時の参照キーとして使う */
    val key: String
        get() = "${category}_${sequence.toString().padStart(2, '0')}"

    /** 一覧・スライドショー長押しなどで表示する名前。name が空なら key をそのまま表示する */
    val displayName: String
        get() = if (name.isBlank()) key else "$key:$name"

    /** 分類一覧のタイルに表示する代表画像のID。指定した画像が既にグループから外れていた場合は1枚目にフォールバックする */
    val effectiveRepresentativeId: Long?
        get() = representativeId?.takeIf { it in imageIds } ?: imageIds.firstOrNull()
}
