# スライドショー機能強化 タスクリスト

スライドショーを「アルバム（Pager）形式」に変更し、一時停止・手動操作・高速再生に対応します。

- `[x]` **1. データ構造の準備**
    - `[x]` `OrganizerUiState.kt` の `SlideshowInterval` を更新 (0.3秒〜5.0秒)
    - `[x]` `OrganizerUiState.kt` に `slideshowPaused` フラグを追加
- `[x]` **2. ViewModelのロジック実装**
    - `[x]` `ImageOrganizerViewModel.kt` に一時停止切り替え・インデックス更新の関数を追加
    - `[x]` `runSlideshowLoop` を一時停止・新秒数設定に対応させる
- `[x]` **3. UIコンポーネントの刷新**
    - `[x]` `SlideshowOverlay.kt` に `HorizontalPager` を導入（手動スワイプ対応）
    - `[x]` 再生/一時停止ボタンの実装
    - `[x]` 秒数設定のプルダウン（メニュー）化
- `[x]` **4. 動作確認と仕上げ**
    - `[x]` 手動操作後の自動再生再開タイミングの調整
    - `[x]` 終了時のスクロール追従の確認
    - `[x]` コード内コメントの日本語による充実化
