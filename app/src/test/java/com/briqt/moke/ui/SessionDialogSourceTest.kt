package com.briqt.moke.ui

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** JVM wiring guards, not substitutes for Compose measurement, focus or animation tests. */
class SessionDialogSourceTest {
    private fun source(name: String): String = listOf(
        File("src/main/java/com/briqt/moke/ui/$name.kt"),
        File("app/src/main/java/com/briqt/moke/ui/$name.kt"),
    ).first { it.isFile }.readText()

    @Test fun `title editor submits only from Save and keeps user drafts separate from automatic props`() {
        val source = source("SessionTitleDialog")
        val beforeSave = source.substringBefore("confirmButton =")
        val save = source.substringAfter("confirmButton =").substringBefore("dismissButton =")
        val dismiss = source.substringAfter("dismissButton =").substringBefore("private fun SessionTitleField")
        assertFalse(Regex("onConfirm\\s*\\(").containsMatchIn(beforeSave))
        assertTrue(save.contains("onConfirm(draft.prefix, draft.title)"))
        assertFalse(Regex("onConfirm\\s*\\(").containsMatchIn(dismiss))
        assertTrue(dismiss.contains("onClick = onDismiss"))
        assertTrue(beforeSave.contains("remember { mutableStateOf(SessionTitleDraft(initialPrefix, initialTitle)) }"))
        assertTrue(beforeSave.contains("draft.copy(prefix = it)"))
        assertTrue(beforeSave.contains("draft.copy(title = it)"))
        assertTrue(beforeSave.contains("draft.preview(automaticPrefix, automaticTitle)"))
        assertTrue(beforeSave.contains("verticalScroll(rememberScrollState())"))
        assertTrue(source.contains("onValueChange(\"\")"))
        assertFalse(source.contains("hint: String"))
    }

    private fun blockAfter(source: String, marker: String): String {
        val markerIndex = source.indexOf(marker)
        require(markerIndex >= 0) { "Missing $marker" }
        val open = source.indexOf('{', markerIndex + marker.length)
        require(open >= 0) { "Missing block for $marker" }
        var depth = 1
        for (index in open + 1 until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return source.substring(open + 1, index)
            }
        }
        error("Unclosed block for $marker")
    }

    @Test fun `close title body and full footer share one dialog constrained scroll viewport`() {
        val source = source("SessionCloseDialog")
        val viewport = blockAfter(source, "verticalScroll(rememberScrollState())")
        assertTrue(source.contains("BasicAlertDialog("))
        assertFalse(Regex("\\bAlertDialog\\s*\\(").containsMatchIn(source))
        assertEquals(1, Regex("\\.verticalScroll\\(").findAll(source).count())
        assertTrue(viewport.contains("R.string.close_connection"))
        assertTrue(viewport.contains("target.targetLabel"))
        assertTrue(viewport.contains("state.message"))
        assertTrue(viewport.contains("FlowRow("))
        assertEquals(4, Regex("TextButton\\(").findAll(viewport).count())
        assertFalse(source.contains(".weight("))
        assertFalse(source.contains("confirmButton ="))
        assertFalse(source.contains("text = {"))
        assertTrue(source.contains("shape = AlertDialogDefaults.shape"))
        assertTrue(source.contains("color = AlertDialogDefaults.containerColor"))
        assertTrue(source.contains("tonalElevation = AlertDialogDefaults.TonalElevation"))
    }

    @Test fun `close body has no actions and footer keeps ordered wrapping choices with minimum touch sizes`() {
        val source = source("SessionCloseDialog")
        val viewport = blockAfter(source, "verticalScroll(rememberScrollState())")
        val body = blockAfter(viewport, "verticalArrangement = Arrangement.spacedBy(8.dp)")
        val footer = blockAfter(viewport, "FlowRow(")
        assertFalse(body.contains("TextButton("))
        assertTrue(body.contains("R.string.session_close_plain_message"))
        assertTrue(body.contains("R.string.tmux_close_message"))
        assertTrue(body.contains("R.string.tmux_close_unknown"))
        assertFalse(footer.contains("verticalScroll("))
        val labels = listOf("R.string.action_cancel", "R.string.action_refresh", "R.string.session_disconnect", "R.string.tmux_kill")
        val positions = labels.map { footer.indexOf(it).also { index -> assertTrue("Missing $it", index >= 0) } }
        assertEquals(positions.sorted(), positions)
        assertEquals(4, Regex("sizeIn\\(minWidth = 48.dp, minHeight = 48.dp\\)").findAll(footer).count())
        assertFalse(source.contains("dismissButton ="))
    }

    @Test fun `close keeps frozen unknown and acknowledged end failure semantics with a safe default focus`() {
        val source = source("SessionCloseDialog")
        val footer = blockAfter(source, "FlowRow(")
        assertTrue(source.contains("val selection = target.selection"))
        assertFalse(source.contains("currentTmuxSelection("))
        assertTrue(footer.contains("if (selection == null && target.unknown)"))
        assertTrue(footer.contains("if (selection != null)"))
        assertTrue(footer.contains("completeTmuxUiAction({ onEnd(selection) }, onDisconnect, { failed = true })"))
        assertTrue(footer.contains("enabled = !submitting && state.canManage"))
        assertTrue(footer.contains("contentColor = MaterialTheme.colorScheme.error"))
        assertTrue(footer.substringBefore("R.string.action_cancel").contains("focusRequester(cancelFocus)"))
        assertTrue(footer.contains("LaunchedEffect(target) { cancelFocus.requestFocus() }"))
    }

    @Test fun `menu expansion changes never discard exit content and capabilities consume live facts`() {
        val row = source("TmuxPanel").substringAfter("private fun TmuxSessionRow(").substringBefore("internal fun TmuxNameDialog(")
        assertTrue(row.contains("DropdownMenu(expanded = menuExpanded"))
        assertTrue(row.contains("onDismissRequest = { menuExpanded = false }"))
        assertTrue(row.contains("tmuxRowMenuState(frozen, target, managementEnabled, current)"))
        assertFalse(row.contains("menuTarget = null"))
        assertFalse(row.contains("delay("))
        assertTrue(row.contains("enabled = menu.canTakeOver"))
        assertTrue(row.contains("enabled = menu.canDetach"))
    }
}
