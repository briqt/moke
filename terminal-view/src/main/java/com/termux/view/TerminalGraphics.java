package com.termux.view;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;

/** Canvas adapter for cell-local graphics; owns all mutable drawing state it uses. */
public final class TerminalGraphics implements CellGlyph.Sink {
    private final CellGlyph geometry;
    private final Paint axisPaint = new Paint(0);
    private final Paint smoothPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path axisPath = new Path();
    private final Path smoothPath = new Path();
    private float offsetX;

    public TerminalGraphics(float cellWidth, int cellHeight) {
        geometry = new CellGlyph(cellWidth, cellHeight);
        axisPaint.setStyle(Paint.Style.FILL);
        smoothPaint.setStyle(Paint.Style.FILL);
    }

    public static boolean supports(int codePoint) {
        return CellGlyph.supports(codePoint);
    }

    /**
     * Draws using the already resolved foreground colour and the actual cell top-left.
     * Returns false without changing the canvas for unsupported characters or invalid positions.
     * No font, italic, shader, or other paint state is inherited from the text renderer.
     */
    public boolean draw(Canvas canvas, int codePoint, float left, float top, int color) {
        return drawRepeated(canvas, codePoint, left, top, color, 1);
    }

    boolean drawRepeated(Canvas canvas, int codePoint, float left, float top, int color, int count) {
        float width = count * geometry.width;
        if (!supports(codePoint) || count <= 0 || !Float.isFinite(left) || !Float.isFinite(top)
            || !Float.isFinite(left + width) || !Float.isFinite(top + geometry.height)) return false;
        axisPaint.setColor(color);
        if (codePoint == 0x2588) {
            canvas.drawRect(left, top, left + width, top + geometry.height, axisPaint);
            return true;
        }
        // Retain path storage and batch repeated graphics with the same resolved style.
        axisPath.reset();
        smoothPath.reset();
        for (int i = 0; i < count; i++) {
            offsetX = i * geometry.width;
            geometry.draw(codePoint, this);
        }
        smoothPaint.setColor(color);
        int saveCount = canvas.save();
        try {
            canvas.translate(left, top);
            canvas.clipRect(0, 0, width, geometry.height);
            // One winding fill prevents repeated alpha compositing at shared joins.
            if (!axisPath.isEmpty()) canvas.drawPath(axisPath, axisPaint);
            if (!smoothPath.isEmpty()) canvas.drawPath(smoothPath, smoothPaint);
        } finally {
            canvas.restoreToCount(saveCount);
        }
        return true;
    }

    @Override public void rectangle(float left, float top, float right, float bottom) {
        axisPath.addRect(left + offsetX, top, right + offsetX, bottom, Path.Direction.CW);
    }

    @Override public void moveTo(float x, float y) {
        smoothPath.moveTo(x + offsetX, y);
    }

    @Override public void lineTo(float x, float y) {
        smoothPath.lineTo(x + offsetX, y);
    }

    @Override public void quadTo(float controlX, float controlY, float x, float y) {
        smoothPath.quadTo(controlX + offsetX, controlY, x + offsetX, y);
    }

    @Override public void closePath() {
        smoothPath.close();
    }
}
