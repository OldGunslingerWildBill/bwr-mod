package dev.bwr.mod.panel.component;

/**
 * What a drawable display's {@link ComponentData} offers to CC:Tweaked's {@code bwr_panel} drawing calls
 * (spec section 8). The peripheral looks at {@code component.data()}; if it implements this interface the Lua
 * methods {@code getSize / clear / setPixel / fill / drawText / blit / setPalette / write} forward here, and if
 * not they raise "not a drawable display". The display data classes (graphic display, character LCD) implement
 * it; the peripheral and the wiring layer know nothing else about them.
 *
 * <p>Contract for implementors (all calls on the server thread, arguments already type-checked by Lua but
 * otherwise untrusted):
 * <ul>
 *   <li>Never throw on bad coordinates or colours: clip, or return {@code false} and change nothing.</li>
 *   <li>Mark the component changed and sync only when something actually changed (the peripheral does not
 *       know). Rate limiting is the implementor's job.</li>
 *   <li>Colours are palette indices (0..15) except {@link #setPalette}, which takes {@code 0xRRGGBB}.</li>
 *   <li>A character LCD implements only {@link #write} and {@link #clear} (colour ignored) and reports its
 *       size in characters; a graphic display implements the pixel methods and reports pixels. Methods a kind
 *       does not support keep their default and return {@code false}.</li>
 * </ul>
 */
public interface PanelDrawTarget {
    /** Width in pixels (graphic display) or characters (character LCD). */
    int drawWidth();

    /** Height in pixels (graphic display) or rows (character LCD). */
    int drawHeight();

    /** Fills the whole surface with a palette colour (an LCD clears its text and ignores the colour). */
    boolean clear(int color);

    default boolean setPixel(int x, int y, int color) {
        return false;
    }

    default boolean fill(int x, int y, int width, int height, int color) {
        return false;
    }

    default boolean drawText(int x, int y, String text, int color) {
        return false;
    }

    /**
     * Copies rows of hex digits (one palette index per digit) with the top-left at the origin of the surface.
     * Shorter rows and fewer rows leave the rest untouched.
     */
    default boolean blit(String[] rows) {
        return false;
    }

    default boolean setPalette(int index, int rgb) {
        return false;
    }

    /** Character LCD: writes {@code text} at 0-based row and column. */
    default boolean write(int row, int column, String text) {
        return false;
    }
}
