package com.briqt.moke.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.width
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.briqt.moke.R
import com.briqt.moke.ui.theme.MokeMono
import com.briqt.moke.ui.theme.MokeShapes

/**
 * 更换底部两排里可以换的格子。「更多」和「文本」不在这里，它们固定在每排末尾。
 */
@Composable
fun ExtraKeysLayoutDialog(
    stored: String,
    onChange: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var slots by remember(stored) { mutableStateOf(ExtraKeyLayout.slots(stored)) }
    var picking by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MokeShapes.card, color = MaterialTheme.colorScheme.surface) {
            Column(
                modifier = Modifier.padding(16.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    stringResource(R.string.menu_extra_keys),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                slots.forEachIndexed { row, ids ->
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val keyWidth = maxOf(48.dp, (maxWidth - 20.dp) / 6)
                        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            ids.forEachIndexed { col, id ->
                                val description = stringResource(R.string.extra_key_slot, row + 1, col + 1, ExtraKeyLayout.label(id))
                                Surface(
                                    onClick = { picking = row to col },
                                    modifier = Modifier.width(keyWidth).heightIn(min = 48.dp).semantics { contentDescription = description },
                                    shape = MokeShapes.keycap,
                                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                ) {
                                    Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(4.dp), contentAlignment = Alignment.Center) {
                                        Text(ExtraKeyLayout.label(id), fontFamily = MokeMono, style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                            }
                        }
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = {
                        onChange("")
                        slots = ExtraKeyLayout.slots("")
                    }) { Text(stringResource(R.string.extra_keys_reset)) }
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
                }
            }
        }
    }

    val target = picking
    if (target != null) {
        AlertDialog(
            onDismissRequest = { picking = null },
            title = { Text(stringResource(R.string.extra_keys_pick)) },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    ExtraKeyLayout.groups.forEach { (titleRes, ids) ->
                        Text(
                            stringResource(titleRes),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                        )
                        ids.forEach { id ->
                            TextButton(
                                onClick = {
                                    val next = slots.map { it.toMutableList() }
                                    next[target.first][target.second] = id
                                    val frozen = next.map { it.toList() }
                                    slots = frozen
                                    onChange(ExtraKeyLayout.encode(frozen))
                                    picking = null
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    ExtraKeyLayout.label(id),
                                    fontFamily = MokeMono,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { picking = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}
