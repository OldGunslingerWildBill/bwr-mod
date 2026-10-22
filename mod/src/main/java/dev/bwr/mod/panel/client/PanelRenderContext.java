package dev.bwr.mod.panel.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.PanelGeometry;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.model.Legends;
import dev.bwr.mod.panel.model.TextFade;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Per-frame drawing state handed to {@link ComponentModel#renderDynamic} and
 * used to replay {@link StaticMesh}es. One instance lives in each
 * {@link PanelRenderer} and is reused for every block and component, so
 * nothing here allocates: positions are computed straight into the vertex
 * buffer with primitive math, and the text matrix is a reused JOML matrix.
 *
 * <p>Everything is expressed in component space (see {@link QuadSink}); the
 * context maps it onto the panel face of the block being drawn.
 */
public final class PanelRenderContext implements QuadSink {
    public static final int LOD_NONE = 0;
    public static final int LOD_REDUCED = 1;
    public static final int LOD_FULL = 2;

    private static final ResourceLocation WHITE = ResourceLocation.withDefaultNamespace("textures/misc/white.png");
    /** Untextured, vertex coloured, lit, double sided: the one render type all procedural geometry uses. */
    public static final RenderType SOLID = RenderType.entityCutoutNoCull(WHITE);
    /**
     * Translucent, vertex coloured and lit: meter windows. Drawn after the solid geometry it covers, and, unlike the
     * vanilla {@code entity_translucent} type it is made from, it does not write depth. The printed scale of a meter
     * sits just behind its window, so a window that wrote depth would hide the text whenever a renderer draws it before
     * the text. That is what Sodium and Iris do to the translucent types of a block entity: with a window writing depth
     * every numeral, unit and tag of every meter vanished.
     */
    public static final RenderType GLASS = Glass.TYPE;

    /** Subclassing only reaches the protected shared render states. */
    private static final class Glass extends RenderType {
        private Glass() {
            super("bwr_panel_glass", DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS, 1536, true, true, () -> {
            }, () -> {
            });
        }

        static final RenderType TYPE = create("bwr_panel_glass", DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS, 1536,
                true, true, RenderType.CompositeState.builder()
                        .setShaderState(RENDERTYPE_ENTITY_TRANSLUCENT_SHADER)
                        .setTextureState(new TextureStateShard(WHITE, false, false))
                        .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                        .setCullState(NO_CULL)
                        .setLightmapState(LIGHTMAP)
                        .setOverlayState(OVERLAY)
                        .setWriteMaskState(COLOR_WRITE)
                        .createCompositeState(true));
    }

    private final Matrix4f basis = new Matrix4f();
    private final Matrix4f textMatrix = new Matrix4f();
    private final float[] normals = new float[15];
    private static final int FACE_AT = NORMAL_FACE * 3;
    private static final int RIGHT_AT = NORMAL_RIGHT * 3;
    private static final int DOWN_AT = NORMAL_DOWN * 3;

    private MultiBufferSource buffers;
    private VertexConsumer consumer;
    /** The open translucent builder, if any; dropped whenever another render type is used (that closes it). */
    private VertexConsumer glassConsumer;
    private PoseStack.Pose pose;
    private PanelGeometry.Frame frame;
    private Font font;
    private int light;
    private int overlay;
    private int lod;
    private float gameTime;
    private double originU;
    private double originV;
    /**
     * The distance fade of printed text (see {@link TextFade}): screen pixels one block spans at distance 1, 0 when the
     * fade is off (the default: a capture into a text buffer never fades), and the camera relative to the block.
     */
    private float fadeFocal;
    private double camX;
    private double camY;
    private double camZ;
    /** The component being drawn: its size, and the screen pixels one of its cells spans, or -1 until a text asks for it. */
    private float componentW;
    private float componentH;
    private float cellPixels = -1.0f;
    private final Vector3f stripCorner = new Vector3f();
    /** Lines drawn as a strip (fully or partly faded) since {@link #debugResetFadedLines}, for checks. */
    private static int fadedLines;
    private static int fadeFrames;
    private static float lastFadeTime = Float.NaN;

    /** Starts drawing the components of one panel block. */
    void begin(PanelBlockEntity be, PoseStack stack, MultiBufferSource buffers, int light, int overlay,
               float time, int lod, Font font) {
        this.buffers = buffers;
        this.consumer = null;
        this.glassConsumer = null;
        this.pose = stack.last();
        this.light = light;
        this.overlay = overlay;
        this.lod = lod;
        this.gameTime = time;
        this.font = font;
        this.fadeFocal = 0.0f;
        PanelGeometry.Frame f = PanelGeometry.frame(be.panelType(), be.facing());
        this.frame = f;
        setNormal(NORMAL_FACE, (float) f.nx, (float) f.ny, (float) f.nz);
        setNormal(NORMAL_LEFT, (float) -f.rightX, (float) -f.rightY, (float) -f.rightZ);
        setNormal(NORMAL_RIGHT, (float) f.rightX, (float) f.rightY, (float) f.rightZ);
        setNormal(NORMAL_UP, (float) -f.downX, (float) -f.downY, (float) -f.downZ);
        setNormal(NORMAL_DOWN, (float) f.downX, (float) f.downY, (float) f.downZ);
        // Text space: x along the cell columns, y down the rows, z into the panel (so glyphs are not mirrored).
        textBasis(f, basis);
    }

    /**
     * Text space of a panel face as a matrix: x along the cell columns (one font pixel is one cell step of the face, in
     * block space), y down the rows (the face's own row step, which is {@link PanelGeometry.Frame#vLength} columns long on a
     * slope) and z into the panel. Every legend is drawn through this one matrix ({@link #orientLegend} puts the real lengths
     * back), and the panel client check reads it for the orthogonality guard.
     */
    public static Matrix4f textBasis(PanelGeometry.Frame f, Matrix4f dest) {
        return dest.identity()
                .m00((float) f.ux).m01((float) f.uy).m02((float) f.uz)
                .m10((float) f.vx).m11((float) f.vy).m12((float) f.vz)
                .m20((float) (-f.nx / PanelGeometry.GRID)).m21((float) (-f.ny / PanelGeometry.GRID))
                .m22((float) (-f.nz / PanelGeometry.GRID));
    }

    /**
     * Applies what turns the face's text space into true lengths and then orients one legend, to {@code m} (already holding
     * the pose, the anchor translation and {@link #textBasis}): the row step is divided by the row aspect so a font pixel is
     * square in the world on a slope, then the legend is tilted along its length (a surface curved along its rows) about the
     * row line, then rotated in the surface about its anchor, then scaled to {@code scale} cells per font pixel. Rotating and
     * scaling are similarities and the row aspect makes the two axes the same length, so the result stays an orthogonal basis
     * (a glyph is never sheared) and, with no tilt, it lies in the surface.
     */
    public static Matrix4f orientLegend(Matrix4f m, float rowAspect, float rotDegrees, float tiltDegrees, float scale) {
        m.scale(1.0f, 1.0f / rowAspect, 1.0f);
        if (tiltDegrees != 0.0f) {
            // Text space runs y down the face and z into it: a surface facing up has its downward tangent leaning out of
            // the panel, which is a turn of minus the tilt about x.
            m.rotateX((float) Math.toRadians(-tiltDegrees));
        }
        if (rotDegrees != 0.0f) {
            m.rotateZ((float) Math.toRadians(rotDegrees));
        }
        return m.scale(scale);
    }

    // ---- the legend basis audit (checks only)

    /** Relative tolerance of the orthogonality guard: float matrices, so a few parts in a million. */
    public static final double BASIS_TOLERANCE = 2.0e-5;
    private static boolean basisAudit;
    private static int basisChecked;
    private static int basisBad;
    private static String basisFirstBad = "";
    private final Matrix4f auditMatrix = new Matrix4f();

    /** Turns the audit of every drawn legend on or off, and clears its counts. Off in play: the audit costs a matrix per line. */
    public static void debugBasisAudit(boolean on) {
        basisAudit = on;
        basisChecked = 0;
        basisBad = 0;
        basisFirstBad = "";
    }

    /** Legends audited since {@link #debugBasisAudit}. */
    public static int debugBasisChecked() {
        return basisChecked;
    }

    /** Legends whose text basis was not a rotated, uniformly scaled copy of the face since {@link #debugBasisAudit}. */
    public static int debugBasisBad() {
        return basisBad;
    }

    /** What the first bad legend looked like, or empty. */
    public static String debugBasisFirstBad() {
        return basisFirstBad;
    }

    /**
     * The violations of the text basis {@code m} (its three column vectors, the images of one font pixel along x, along y and
     * into the panel) against the face of {@code frame}: empty when it is a rotation of the face's own axes with one uniform
     * scale. That is the guard against a sheared (italic) or squashed legend and against glyphs off the surface: the three
     * columns must have one length and be pairwise orthogonal, the system must not be mirrored, and without a tilt the x and y
     * columns must lie in the face's plane and the z column must point into it. {@code tilted} turns the plane test off
     * (a tilt leans the text out of the plane on purpose, for a surface curved along its rows).
     */
    public static String basisViolations(Matrix4f m, PanelGeometry.Frame frame, boolean tilted) {
        double[] x = {m.m00(), m.m01(), m.m02()};
        double[] y = {m.m10(), m.m11(), m.m12()};
        double[] z = {m.m20(), m.m21(), m.m22()};
        double lx = Math.sqrt(dot(x, x));
        double ly = Math.sqrt(dot(y, y));
        double lz = Math.sqrt(dot(z, z));
        double tol = BASIS_TOLERANCE * Math.max(lx, Math.max(ly, lz));
        StringBuilder out = new StringBuilder();
        if (Math.abs(lx - ly) > tol || Math.abs(lx - lz) > tol) {
            out.append(String.format(" lengths x/y/z %.7f/%.7f/%.7f (squashed);", lx, ly, lz));
        }
        double xy = dot(x, y) / (lx * ly);
        double xz = dot(x, z) / (lx * lz);
        double yz = dot(y, z) / (ly * lz);
        if (Math.abs(xy) > BASIS_TOLERANCE || Math.abs(xz) > BASIS_TOLERANCE || Math.abs(yz) > BASIS_TOLERANCE) {
            out.append(String.format(" cos(x,y)=%.6f (shear %.3f deg) cos(x,z)=%.6f cos(y,z)=%.6f;", xy,
                    Math.toDegrees(Math.asin(Math.max(-1.0, Math.min(1.0, xy)))), xz, yz));
        }
        double[] cross = {x[1] * y[2] - x[2] * y[1], x[2] * y[0] - x[0] * y[2], x[0] * y[1] - x[1] * y[0]};
        if (dot(cross, z) <= 0.0) {
            out.append(" mirrored (x cross y does not point the way z does);");
        }
        if (!tilted) {
            double[] n = {frame.nx, frame.ny, frame.nz};
            double nx = dot(x, n) / lx;
            double ny = dot(y, n) / ly;
            double nz = dot(z, n) / lz;
            if (Math.abs(nx) > BASIS_TOLERANCE || Math.abs(ny) > BASIS_TOLERANCE || nz > -1.0 + BASIS_TOLERANCE) {
                out.append(String.format(" off the face plane: n.x=%.6f n.y=%.6f n.z=%.6f (want 0, 0, -1);", nx, ny, nz));
            }
        }
        return out.toString();
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    /** Audits the text basis one legend line is about to be drawn with, read off {@link #textMatrix} (only while {@link #debugBasisAudit} is on). */
    private void auditBasis(String text, float rotDegrees, float tiltDegrees) {
        // the matrix the line really is drawn with, less the pose and the anchor: the linear part is the text basis
        auditMatrix.set(pose.pose()).invertAffine().mul(textMatrix);
        String bad = basisViolations(auditMatrix, frame, tiltDegrees != 0.0f);
        basisChecked++;
        if (!bad.isEmpty()) {
            basisBad++;
            if (basisFirstBad.isEmpty()) {
                basisFirstBad = "\"" + text + "\" rot " + rotDegrees + " tilt " + tiltDegrees + ":" + bad;
            }
        }
    }

    private void setNormal(int kind, float x, float y, float z) {
        normals[kind * 3] = x;
        normals[kind * 3 + 1] = y;
        normals[kind * 3 + 2] = z;
    }

    private dev.bwr.mod.panel.model.Placement classicPlacement;
    void classicPlacement(dev.bwr.mod.panel.model.Placement fit) { classicPlacement=fit; }
    private float fittedX(float x) { return classicPlacement==null?x:classicPlacement.x(x); }
    private float fittedY(float y) { return classicPlacement==null?y:classicPlacement.y(y); }
    private float fittedZ(float z) { return classicPlacement==null?z:classicPlacement.z(z); }
    private float fittedScale(float scale) { return classicPlacement==null?scale:classicPlacement.length(scale); }

    /**
     * Turns the distance fade of text on for this block, after {@link #begin}: {@code focalPixels} is half the window
     * height times the projection's focal length (the pixels one block spans at distance 1), and the camera is given
     * relative to the block's origin, in blocks.
     */
    void fade(float focalPixels, double relX, double relY, double relZ) {
        this.fadeFocal = focalPixels;
        if (gameTime != lastFadeTime) {
            lastFadeTime = gameTime;
            fadeFrames++;
        }
        this.camX = relX;
        this.camY = relY;
        this.camZ = relZ;
    }

    /**
     * Screen pixels one cell of the component being drawn spans, at its centre: once per component per frame, on the
     * first text that asks; infinite (no fade) when the fade is off.
     */
    private float pixelsPerCell() {
        if (fadeFocal <= 0.0f) {
            return Float.POSITIVE_INFINITY;
        }
        if (cellPixels < 0.0f) {
            double u = originU + componentW * 0.5;
            double v = originV + componentH * 0.5;
            double dx = frame.x(u, v, 0.0) - camX;
            double dy = frame.y(u, v, 0.0) - camY;
            double dz = frame.z(u, v, 0.0) - camZ;
            cellPixels = fadeFocal / (float) Math.max(0.05, Math.sqrt(dx * dx + dy * dy + dz * dz)) / PanelGeometry.GRID;
        }
        return cellPixels;
    }

    /** Lines the distance fade has changed since the last reset, for checks. */
    public static int debugFadedLines() {
        return fadedLines;
    }

    /** Frames since the last reset in which the fade was on for some block, for checks. */
    public static int debugFadeFrames() {
        return fadeFrames;
    }

    public static void debugResetFadedLines() {
        fadedLines = 0;
        fadeFrames = 0;
        lastFadeTime = Float.NaN;
    }

    /** Starts drawing the block's mimic layer: its coordinates are the block's own cells, with no component origin. */
    void beginLayer() {
        classicPlacement = null;
        componentW = 0.0f;
        componentH = 0.0f;
        cellPixels = -1.0f;
        this.originU = 0.0;
        this.originV = 0.0;
    }

    void beginComponent(PanelComponent component) {
        classicPlacement=null;
        componentW = component.w();
        componentH = component.h();
        cellPixels = -1.0f;
        this.originU = component.x();
        this.originV = component.y();
    }

    /**
     * The packed light a quad or glyph is drawn with: full bright for glowing elements (a lit lamp, a lit
     * annunciator tile, a lit segment, a display), the world light at the block otherwise. Every emissive path in
     * this class goes through here.
     */
    public static int packedLight(boolean emissive, int worldLight) {
        return emissive ? LightTexture.FULL_BRIGHT : worldLight;
    }

    /** {@link #LOD_FULL} close up, {@link #LOD_REDUCED} beyond 24 blocks (skip text and fine detail). */
    public int lod() {
        return lod;
    }

    /** Game time in ticks including the partial tick. */
    public float gameTime() {
        return gameTime;
    }

    /**
     * Whether a blinking element is in its "on" half right now, for a blink
     * rate of {@code hz} full cycles per second. Derived from game time so all
     * clients agree and nothing is synced.
     */
    public boolean blinkOn(float hz) {
        return ((int) (gameTime * hz / 10.0f) & 1) == 0;
    }

    /** Real length of one row relative to one column; 1.0 on a vertical board, 1.41 on a benchboard. */
    public float rowAspect() {
        return (float) frame.vLength;
    }

    @Override
    public void quad(float x0, float y0, float z0, float x1, float y1, float z1,
                     float x2, float y2, float z2, float x3, float y3, float z3,
                     int rgb, int normal, boolean emissive) {
        int n = normal * 3;
        solid(x0, y0, z0, x1, y1, z1, x2, y2, z2, x3, y3, z3, rgb, emissive, normals[n], normals[n + 1], normals[n + 2]);
    }

    /**
     * A quad with its own normal, given in component space and mapped onto the panel frame here: the face
     * normal, the column direction and the row direction are the three axes. Curved handles and knobs shade
     * smoothly this way. A glowing quad still gets the world-up normal, as in the enum version.
     */
    @Override
    public void quad(float x0, float y0, float z0, float x1, float y1, float z1,
                     float x2, float y2, float z2, float x3, float y3, float z3,
                     int rgb, float nx, float ny, float nz, boolean emissive) {
        solid(x0, y0, z0, x1, y1, z1, x2, y2, z2, x3, y3, z3, rgb, emissive,
                worldNormalX(nx, ny, nz), worldNormalY(nx, ny, nz), worldNormalZ(nx, ny, nz));
    }

    /** Component-space normal to world: {@code nx} along the columns, {@code ny} down the rows, {@code nz} out of the face. */
    private float worldNormalX(float nx, float ny, float nz) {
        return nx * normals[RIGHT_AT] + ny * normals[DOWN_AT] + nz * normals[FACE_AT];
    }

    private float worldNormalY(float nx, float ny, float nz) {
        return nx * normals[RIGHT_AT + 1] + ny * normals[DOWN_AT + 1] + nz * normals[FACE_AT + 1];
    }

    private float worldNormalZ(float nx, float ny, float nz) {
        return nx * normals[RIGHT_AT + 2] + ny * normals[DOWN_AT + 2] + nz * normals[FACE_AT + 2];
    }

    private void solid(float x0, float y0, float z0, float x1, float y1, float z1,
                       float x2, float y2, float z2, float x3, float y3, float z3,
                       int rgb, boolean emissive, float worldNx, float worldNy, float worldNz) {
        int packedLight = packedLight(emissive, light);
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        // A glowing quad also ignores the directional shading of the entity shader: it gets the world-up normal,
        // which the shader lights to full strength, so its colour is shown as authored whatever the face's angle.
        float nx = emissive ? 0.0f : worldNx;
        float ny = emissive ? 1.0f : worldNy;
        float nz = emissive ? 0.0f : worldNz;
        vertex(x0, y0, z0, r, g, b, packedLight, nx, ny, nz);
        vertex(x1, y1, z1, r, g, b, packedLight, nx, ny, nz);
        vertex(x2, y2, z2, r, g, b, packedLight, nx, ny, nz);
        vertex(x3, y3, z3, r, g, b, packedLight, nx, ny, nz);
    }

    private void vertex(float x, float y, float z, int r, int g, int b, int packedLight, float nx, float ny, float nz) {
        double u = originU + fittedX(x);
        double v = originV + fittedY(y);
        if (consumer == null) {
            // Drawing text or glass switches the buffer source to another render type, which closes the solid builder.
            consumer = buffers.getBuffer(SOLID);
            glassConsumer = null;
        }
        consumer.addVertex(pose, (float) frame.x(u, v, fittedZ(z)), (float) frame.y(u, v, fittedZ(z)), (float) frame.z(u, v, fittedZ(z)))
                .setColor(r, g, b, 255)
                .setUv(0.5f, 0.5f)
                .setOverlay(overlay)
                .setLight(packedLight)
                .setNormal(pose, nx, ny, nz);
    }

    /**
     * A translucent quad (a meter window) with its own normal. It is lit by the world light like any other
     * non-glowing geometry. Callers draw glass after everything it covers: the buffer switch flushes what came
     * before, and the translucent layer then blends over it.
     */
    @Override
    public void glassQuad(float x0, float y0, float z0, float x1, float y1, float z1,
                          float x2, float y2, float z2, float x3, float y3, float z3,
                          int rgb, int alpha, float nx, float ny, float nz) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        float wx = worldNormalX(nx, ny, nz);
        float wy = worldNormalY(nx, ny, nz);
        float wz = worldNormalZ(nx, ny, nz);
        glassVertex(x0, y0, z0, r, g, b, alpha, wx, wy, wz);
        glassVertex(x1, y1, z1, r, g, b, alpha, wx, wy, wz);
        glassVertex(x2, y2, z2, r, g, b, alpha, wx, wy, wz);
        glassVertex(x3, y3, z3, r, g, b, alpha, wx, wy, wz);
    }

    private void glassVertex(float x, float y, float z, int r, int g, int b, int alpha, float nx, float ny, float nz) {
        double u = originU + fittedX(x);
        double v = originV + fittedY(y);
        if (glassConsumer == null) {
            glassConsumer = buffers.getBuffer(GLASS);
            consumer = null;
        }
        glassConsumer.addVertex(pose, (float) frame.x(u, v, fittedZ(z)), (float) frame.y(u, v, fittedZ(z)), (float) frame.z(u, v, fittedZ(z)))
                .setColor(r, g, b, alpha)
                .setUv(0.5f, 0.5f)
                .setOverlay(overlay)
                .setLight(light)
                .setNormal(pose, nx, ny, nz);
    }

    /**
     * Draws a line of text lying on the face with its top-left at {@code (x, y)}.
     * Only call at {@link #LOD_FULL}. The font itself allocates internally, which
     * is why text is limited to nearby panels.
     *
     * @param scale cells per font pixel (a normal 8 pixel line at 0.125 is one cell tall)
     * @param z     elevation above the face in 1/16 block
     */
    public void text(String text, float x, float y, float scale, float z, int rgb, boolean emissive) {
        double u = originU + fittedX(x);
        double v = originV + fittedY(y);
        textMatrix.set(pose.pose());
        textMatrix.translate((float) frame.x(u, v, fittedZ(z)), (float) frame.y(u, v, fittedZ(z)), (float) frame.z(u, v, fittedZ(z)));
        textMatrix.mul(basis);
        orientLegend(textMatrix, rowAspect(), 0.0f, 0.0f, fittedScale(scale));
        if (basisAudit) {
            auditBasis(text, 0.0f, 0.0f);
        }
        int color = fadedColor(text, 0.0f, 0.0f, fittedScale(scale), rgb, emissive);
        if (color != 0) {
            font.drawInBatch(text, 0.0f, 0.0f, color, false, textMatrix, buffers,
                    Font.DisplayMode.POLYGON_OFFSET, 0, packedLight(emissive, light));
        }
        consumer = null;
        glassConsumer = null;
    }

    /**
     * The colour (alpha first) a line of text is drawn in once the distance fade has had its say, or 0 when it is gone.
     * Where the glyphs are smaller than a few screen pixels they fade out and a plain strip in the same colour, which is
     * what the ink looks like from afar, is drawn instead ({@link TextFade}); the line is laid out from the length of the
     * string alone, so a faded line costs one quad. {@link #textMatrix} must already be set for the line; {@code
     * (x, y)} is its top-left in font pixels and {@code cellsPerPixel} the true size of a font pixel.
     */
    private int fadedColor(String text, float x, float y, float cellsPerPixel, int rgb, boolean emissive) {
        float cap = Legends.CAP_PIXELS * cellsPerPixel * pixelsPerCell();
        if (!(cap < TextFade.FULL_PX)) {
            return 0xFF000000 | rgb;
        }
        fadedLines++;
        strip(text.length(), x, y, TextFade.stripAlpha(cap), rgb, emissive);
        float alpha = TextFade.textAlpha(cap);
        return alpha < 0.03f ? 0 : (int) (alpha * 255.0f) << 24 | rgb;
    }

    /** A soft strip where a line of {@code chars} characters would be: the middle of the cap height, in the text's colour. */
    private void strip(int chars, float x, float y, float alpha, int rgb, boolean emissive) {
        int a = (int) (alpha * 255.0f);
        if (a < 2 || chars <= 0) {
            return;
        }
        VertexConsumer out = buffers.getBuffer(GLASS);
        float x1 = x + chars * 5.6f - 1.0f;
        float top = y + 1.5f;
        float bottom = y + 5.5f;
        int packed = packedLight(emissive, light);
        float nx = emissive ? 0.0f : normals[FACE_AT];
        float ny = emissive ? 1.0f : normals[FACE_AT + 1];
        float nz = emissive ? 0.0f : normals[FACE_AT + 2];
        stripVertex(out, x, top, rgb, a, packed, nx, ny, nz);
        stripVertex(out, x, bottom, rgb, a, packed, nx, ny, nz);
        stripVertex(out, x1, bottom, rgb, a, packed, nx, ny, nz);
        stripVertex(out, x1, top, rgb, a, packed, nx, ny, nz);
        consumer = null;
        glassConsumer = null;
    }

    private void stripVertex(VertexConsumer out, float x, float y, int rgb, int alpha, int packed, float nx, float ny, float nz) {
        textMatrix.transformPosition(x, y, 0.0f, stripCorner);
        out.addVertex(stripCorner.x, stripCorner.y, stripCorner.z)
                .setColor(rgb >> 16 & 0xFF, rgb >> 8 & 0xFF, rgb & 0xFF, alpha)
                .setUv(0.5f, 0.5f)
                .setOverlay(overlay)
                .setLight(packed)
                .setNormal(pose, nx, ny, nz);
    }

    /**
     * Draws a line of text lying on the face, rotated about its anchor. Baked models place legends this way: the
     * anchor is where the text sits, {@code rotDegrees} turns it clockwise as the operator sees it (the
     * rotation is done in real lengths, so on a sloped board it is still a true rotation), and
     * {@code (dx, dy)} is the offset of the string's top-left from the anchor in font pixels before scaling
     * (alignment and centring on the cap height). Only call at {@link #LOD_FULL}.
     *
     * @param scale cells per font pixel
     */
    public void textRotated(String text, float x, float y, float scale, float rotDegrees, float z, int rgb,
                            boolean emissive, float dx, float dy) {
        textRotated(text, x, y, scale, rotDegrees, 0.0f, z, rgb, emissive, dx, dy);
    }

    /**
     * {@link #textRotated(String, float, float, float, float, float, int, boolean, float, float)} on a surface that is
     * curved along its length: the text also leans back by {@code tiltDegrees} about the row line through its anchor,
     * so it lies on the tangent of the surface there (positive where the surface faces up). The tilt is a true angle on
     * a sloped board too: it is applied after the rows are brought to real lengths.
     */
    public void textRotated(String text, float x, float y, float scale, float rotDegrees, float tiltDegrees, float z, int rgb,
                            boolean emissive, float dx, float dy) {
        double u = originU + fittedX(x);
        double v = originV + fittedY(y);
        textMatrix.set(pose.pose());
        textMatrix.translate((float) frame.x(u, v, fittedZ(z)), (float) frame.y(u, v, fittedZ(z)), (float) frame.z(u, v, fittedZ(z)));
        textMatrix.mul(basis);
        orientLegend(textMatrix, rowAspect(), rotDegrees, tiltDegrees, fittedScale(scale));
        if (basisAudit) {
            auditBasis(text, rotDegrees, tiltDegrees);
        }
        int color = fadedColor(text, dx, dy, fittedScale(scale), rgb, emissive);
        if (color != 0) {
            font.drawInBatch(text, dx, dy, color, false, textMatrix, buffers,
                    Font.DisplayMode.POLYGON_OFFSET, 0, packedLight(emissive, light));
        }
        consumer = null;
        glassConsumer = null;
    }

    public Font font() {
        return font;
    }

    /**
     * Draws one textured rectangle parallel to the face at elevation {@code z}, with the whole texture of
     * {@code type} (u,v 0..1) mapped onto it. This is how displays show pixel content (the LCD and graphic
     * display render into dynamic textures). Not allocation free in the vertex buffer sense, but it makes no
     * objects of its own.
     */
    public void texturedRect(RenderType type, float x, float y, float w, float h, float z, boolean emissive) {
        int packedLight = packedLight(emissive, light);
        VertexConsumer out = buffers.getBuffer(type);
        float nx = emissive ? 0.0f : normals[0];
        float ny = emissive ? 1.0f : normals[1];
        float nz = emissive ? 0.0f : normals[2];
        textured(out, x, y, z, 0.0f, 0.0f, packedLight, nx, ny, nz);
        textured(out, x + w, y, z, 1.0f, 0.0f, packedLight, nx, ny, nz);
        textured(out, x + w, y + h, z, 1.0f, 1.0f, packedLight, nx, ny, nz);
        textured(out, x, y + h, z, 0.0f, 1.0f, packedLight, nx, ny, nz);
        consumer = null;
        glassConsumer = null;
    }

    private void textured(VertexConsumer out, float x, float y, float z, float u, float v, int packedLight,
                          float nx, float ny, float nz) {
        double cu = originU + fittedX(x);
        double cv = originV + fittedY(y);
        out.addVertex(pose, (float) frame.x(cu, cv, fittedZ(z)), (float) frame.y(cu, cv, fittedZ(z)), (float) frame.z(cu, cv, fittedZ(z)))
                .setColor(255, 255, 255, 255)
                .setUv(u, v)
                .setOverlay(overlay)
                .setLight(packedLight)
                .setNormal(pose, nx, ny, nz);
    }
}
