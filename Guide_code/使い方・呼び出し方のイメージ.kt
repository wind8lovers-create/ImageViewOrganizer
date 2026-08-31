// 例: TopAppBar や画面の上部に配置
RenameActionMenu(
    label = "猫",                     // 現在選択しているラベル名
    selectedCount = selectedFiles.size, // 選択中の枚数
    currentFolderName = currentFolder.name, // 今のフォルダ名
    isSubfolder = true,                // 下層フォルダにいるか
    onRenameMove = {
        // コアロジックを呼び出すだけ！
        RenameMoveHelper.execute(
            sourceFiles = selectedFiles,
            destFolderRoot = currentFolder,
            label = "猫",
            mode = RenameMoveHelper.ExecuteMode.MOVE
        )
    },
    onRenameOnly = {
        RenameMoveHelper.renameInPlace(selectedFiles, "猫")
    },
    onCopyRequested = {
        // フォルダピッカーを開く
    },
    onMoveRequested = {
        // フォルダピッカーを開く
    },
    onDeleteConfirmed = {
        RenameMoveHelper.deleteFiles(selectedFiles)
    },
    onRenameGroupLabel = {
        RenameMoveHelper.renameGroupLabel(currentFolder, "旧ラベル", "猫")
    }
)
