package com.termux.view;

import android.content.Context;
import android.text.Editable;
import android.text.Selection;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;

import com.termux.terminal.TerminalSession;
import java.util.function.BooleanSupplier;

/** Full-editor selection/composition feedback, independent of terminal byte submission. */
class TerminalInputConnection extends BaseInputConnection {
    interface SelectionReporter {
        void report(int start, int end, int composingStart, int composingEnd);
    }

    private final TerminalView view;
    private final TerminalSession session;
    private final SelectionReporter reporter;
    private final BooleanSupplier active;
    private int batchDepth;
    private boolean closing, closed, reporting;
    private int lastStart = Integer.MIN_VALUE, lastEnd = Integer.MIN_VALUE;
    private int lastComposingStart = Integer.MIN_VALUE, lastComposingEnd = Integer.MIN_VALUE;

    TerminalInputConnection(TerminalView view) {
        this(view, (start, end, composingStart, composingEnd) -> {
            InputMethodManager imm = (InputMethodManager) view.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm == null) throw new IllegalStateException("No input method service");
            imm.updateSelection(view, start, end, composingStart, composingEnd);
        }, () -> view.isAttachedToWindow() && view.hasFocus() && view.hasWindowFocus());
    }

    /** The reporter and activity predicate also allow deterministic Android contract tests. */
    TerminalInputConnection(TerminalView view, SelectionReporter reporter, BooleanSupplier active) {
        super(view, true);
        this.view = view;
        this.session = view.mTermSession;
        this.reporter = reporter;
        this.active = active;
    }

    void initializeEditorInfo(EditorInfo attrs) {
        Editable content = getEditable();
        attrs.initialSelStart = Selection.getSelectionStart(content);
        attrs.initialSelEnd = Selection.getSelectionEnd(content);
        attrs.setInitialSurroundingText(content);
        remember(attrs.initialSelStart, attrs.initialSelEnd,
            getComposingSpanStart(content), getComposingSpanEnd(content));
    }

    @Override public boolean beginBatchEdit() {
        if (closed) return false;
        batchDepth++;
        return true;
    }

    @Override public boolean endBatchEdit() {
        if (batchDepth == 0) return false;
        batchDepth--;
        if (batchDepth == 0) publishState();
        return batchDepth > 0;
    }

    @Override public boolean setComposingText(CharSequence text, int newCursorPosition) {
        beginBatchEdit();
        try { return super.setComposingText(text, newCursorPosition); }
        finally { endBatchEdit(); }
    }

    @Override public boolean setComposingRegion(int start, int end) {
        beginBatchEdit();
        try { return super.setComposingRegion(start, end); }
        finally { endBatchEdit(); }
    }

    @Override public boolean setSelection(int start, int end) {
        beginBatchEdit();
        try { return super.setSelection(start, end); }
        finally { endBatchEdit(); }
    }

    @Override public boolean deleteSurroundingTextInCodePoints(int beforeLength, int afterLength) {
        beginBatchEdit();
        try { return super.deleteSurroundingTextInCodePoints(beforeLength, afterLength); }
        finally { endBatchEdit(); }
    }

    @Override public void closeConnection() {
        closing = true;
        try { super.closeConnection(); }
        finally { closed = true; closing = false; }
    }

    private void publishState() {
        if (closed || closing || reporting || view.mCurrentInputConnection != this || view.mTermSession != session || !active.getAsBoolean()) return;
        Editable content = getEditable();
        int start = Selection.getSelectionStart(content), end = Selection.getSelectionEnd(content);
        int composingStart = getComposingSpanStart(content), composingEnd = getComposingSpanEnd(content);
        if (start == lastStart && end == lastEnd && composingStart == lastComposingStart && composingEnd == lastComposingEnd) return;
        reporting = true;
        try {
            reporter.report(start, end, composingStart, composingEnd);
            remember(start, end, composingStart, composingEnd);
        } catch (RuntimeException ignored) {
            // State feedback must not fail terminal input; retain the old snapshot for a later retry.
        } finally { reporting = false; }
    }

    private void remember(int start, int end, int composingStart, int composingEnd) {
        lastStart = start;
        lastEnd = end;
        lastComposingStart = composingStart;
        lastComposingEnd = composingEnd;
    }
}
