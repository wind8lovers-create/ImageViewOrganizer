# 分類一覧をアルファベット順にソートする実装プラン

分類一覧（ClassificationListScreen）において、カテゴリ（A〜Z）の並び順が画像の状態（ソート設定）に依存している現状を、常にアルファベット昇順（A→Z）で表示されるように修正します。

## ユーザーレビューが必要な事項

> [!NOTE]
> カテゴリの並び順をアルファベット順に固定します。これにより、画像一覧で「新しい順」などにソートしていても、分類一覧の「A」の島は常に一番上に表示されるようになります。

## Proposed Changes

### ViewModelレイヤー

#### [MODIFY] [ImageOrganizerViewModel.kt](file:///C:/Users/Takafumi%20Kizuki/AndroidStudioProjects/ImageViewOrganizer/app/src/main/java/com/hazuki/imageorganizer/viewmodel/ImageOrganizerViewModel.kt)

`rebuildClassificationDerivedState` 関数内のカテゴリ並び替えロジックを修正します。

- 既存の「代表画像のプロパティによるソート」処理を削除またはコメントアウトします。
- カテゴリのリスト（`byCategory.keys`）を単純に `sorted()` することで、AからZの順序を取得するように変更します。

## Verification Plan

### Manual Verification
1. アプリを起動し、複数のカテゴリ（例：A, C, M）でグループを作成する。
2. 画像一覧のソート設定を「新しい順」や「名前順（降順）」などに切り替える。
3. 「分類一覧」画面に移動し、ソート設定に関わらずカテゴリが A → C → M の順で並んでいることを確認する。
4. グループ詳細画面に入り、左右のスワイプで隣のグループに移動する際も、アルファベット順に従っていることを確認する。
