# 分類一覧のアルファベット順ソート対応

分類一覧画面において、カテゴリ（A〜Z）の表示順が常にアルファベットの昇順になるように変更しました。

## 変更内容

### ViewModel

#### [ImageOrganizerViewModel.kt](file:///C:/Users/Takafumi%20Kizuki/AndroidStudioProjects/ImageViewOrganizer/app/src/main/java/com/hazuki/imageorganizer/viewmodel/ImageOrganizerViewModel.kt)

- `rebuildClassificationDerivedState` 内の並び替えロジックを簡略化しました。
- 画像の撮影日やファイル名による影響を排除し、カテゴリの文字（'A', 'B', 'C'...）のみでソートするように修正しました。

## 検証結果

### 手動確認
- A, C, M の順でグループを作成し、どのソート設定（新しい順、古い順など）を選んでも分類一覧では常に A → C → M の順で並ぶことを確認しました。
- グループ詳細画面での左右スワイプによる移動順も、アルファベット順になっていることを確認しました。
