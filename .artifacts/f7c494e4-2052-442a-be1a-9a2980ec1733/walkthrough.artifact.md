# スライドショー機能強化：一時停止・手動めくり・高速再生の実装

スライドショーをより自由に、より高速に鑑賞できるように機能を大幅に強化しました。

## 主な変更点

### 1. 「めくれるアルバム」構造への進化
スライドショーの表示を、1枚ずつの切り替えから `HorizontalPager` を使った「左右にスワイプできる」構造に変更しました。これにより、4000枚を超える大量の画像があってもメモリ負荷を抑えつつ、サクサクと前後を確認できます。

### 2. 再生/一時停止機能の追加
画面上部の操作バーに「再生/一時停止」ボタンを追加しました。
- **一時停止中:** 自動更新が止まり、自分のペースで画像をじっくり鑑賞したりスワイプしたりできます。
- **再開:** 停止していた位置からタイマーが再開します。

### 3. 秒数設定の刷新とプルダウン化
ご要望に合わせて、再生間隔をより細かく、かつ直感的に選べるようにしました。
- **設定値:** `0.3秒, 0.5秒, 0.8秒, 2.0秒, 5.0秒`
- **UI:** 現在の秒数をタップすると選択メニューが表示されるプルダウン形式に変更しました。0.3秒などの高速設定では、パラパラ漫画のような鑑賞が可能です。

### 4. 終了時の位置追従を完璧に維持
スライドショー中に手動で別のページへ移動しても、終了ボタンを押したときに**「その時表示していた画像」**の位置まで一覧画面が自動でスクロールします。

## 変更ファイル

- [OrganizerUiState.kt](file:///C:/Users/Takafumi%20Kizuki/AndroidStudioProjects/ImageViewOrganizer/app/src/main/java/com/hazuki/imageorganizer/viewmodel/OrganizerUiState.kt): データ構造の定義（秒数設定・一時停止フラグ）
- [ImageOrganizerViewModel.kt](file:///C:/Users/Takafumi%20Kizuki/AndroidStudioProjects/ImageViewOrganizer/app/src/main/java/com/hazuki/imageorganizer/viewmodel/ImageOrganizerViewModel.kt): 再生制御ロジック、タイマー処理、手動操作時の同期処理
- [SlideshowOverlay.kt](file:///C:/Users/Takafumi%20Kizuki/AndroidStudioProjects/ImageViewOrganizer/app/src/main/java/com/hazuki/imageorganizer/ui/components/SlideshowOverlay.kt): 新しいUI（Pager、操作バー、プルダウンメニュー）
- [MainScreen.kt](file:///C:/Users/Takafumi%20Kizuki/AndroidStudioProjects/ImageViewOrganizer/app/src/main/java/com/hazuki/imageorganizer/ui/screens/MainScreen.kt): 新しいスライドショー画面へのバトンタッチ（引数の更新）

## 検証結果
- `gradle_build` によるコンパイル確認：**成功**
- 大量画像時のパフォーマンス考慮：`HorizontalPager` による遅延読み込みを実装済み
