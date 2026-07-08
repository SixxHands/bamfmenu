package com.retro.bamfmenu.early.core.panel;

/* How a left-panel animation's art sits inside the panel cell (Phase C1): horizontal + vertical alignment and a
   small padding, so the renderer can FORMAT panel content data-drivenly instead of hardcoding "vertical-center,
   left-inset" for everything. A LeftPanelAnimation supplies one via its layout() default; the renderer reads it
   and positions/clips the art accordingly. defaults() reproduces the original placement exactly (LEFT / CENTER,
   1 column of left pad, 0 row pad), so existing panels (e.g. Recovery's face) are unchanged unless they opt in.
   Pure data -- no GL, no other deps; reusable by the future on-disk panel authoring. */
public record PanelLayout(HAlign hAlign, VAlign vAlign, int padCols, int padRows) {

    public enum HAlign { LEFT, CENTER, RIGHT }

    public enum VAlign { TOP, CENTER, BOTTOM }

    private static final PanelLayout DEFAULT = new PanelLayout(HAlign.LEFT, VAlign.CENTER, 1, 0);

    /* The original placement: left-aligned with a 1-column inset, vertically centered. Byte-identical to the
       pre-C1 drawLeftPanel, so any animation that doesn't override layout() looks exactly as it did. */
    public static PanelLayout defaults() {
        return DEFAULT;
    }
}
