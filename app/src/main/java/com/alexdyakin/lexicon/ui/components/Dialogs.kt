package com.alexdyakin.lexicon.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

/**
 * "Are you sure?" for actions that cannot be undone.
 *
 * Deleting media, removing a torrent and removing an avatar all happened on a single tap,
 * with the destructive button sitting beside ordinary ones in a wrapping row, where a
 * mistap is easy. The destructive choice is named for what it does, never "OK".
 */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            LexButton(
                confirmLabel,
                onClick = {
                    onDismiss()
                    onConfirm()
                },
                tone = LexTone.Destructive,
            )
        },
        dismissButton = { LexButton("Cancel", onClick = onDismiss) },
    )
}
