package dev.bwr.mod.panel.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.PanelGeometry;
import dev.bwr.mod.panel.component.PanelComponent;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

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
        PanelGeometry.Frame f = PanelGeometry.frame(be.panelType(), be.facing());
        this.frame = f;
        setNormal(NORMAL_FACE, (float) f.nx, (float) f.ny, (float) f.nz);
        setNormal(NORMAL_LEFT, (float) -f.rightX, (float) -f.rightY, (float) -f.rightZ);
        setNormal(NORMAL_RIGHT, (float) f.rightX, (float) f.rightY, (float) f.rightZ);
        setNormal(NORMAL_UP, (float) -f.downX, (float) -f.downY, (float) -f.downZ);
        setNormal(NORMAL_DOWN, (float) f.downX, (float) f.downY, (float) f.downZ);
        // Text space: x along the cell columns, y down the rows, z into the panel (so glyphs are not mirrored).
        basis.identity()
                .m00((float) f.ux).m01((float) f.uy).m02((float) f.uz)
                .m10((float) f.vx).m11((float) f.vy).m12((float) f.vz)
                .m20((float) (-f.nx / PanelGeometry.GRID)).m21((float) (-f.ny / PanelGeometry.GRID))
                .m22((float) (-f.nz / PanelGeometry.GRID));
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

    void beginComponent(PanelComponent component) {
        classicPlacement=null;
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
        textMatrix.scale(fittedScale(scale), fittedScale(scale) / rowAspect(), fittedScale(scale));
        font.drawInBatch(text, 0.0f, 0.0f, 0xFF000000 | rgb, false, textMatrix, buffers,
                Font.DisplayMode.POLYGON_OFFSET, 0, packedLight(emissive, light));
        consumer = null;
        glassConsumer = null;
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
        double u = originU + fittedX(x);
        double v = originV + fittedY(y);
        textMatrix.set(pose.pose());
        textMatrix.translate((float) frame.x(u, v, fittedZ(z)), (float) frame.y(u, v, fittedZ(z)), (float) frame.z(u, v, fittedZ(z)));
        textMatrix.mul(basis);
        textMatrix.scale(1.0f, 1.0f / rowAspect(), 1.0f);
        if (rotDegrees != 0.0f) {
            textMatrix.rotateZ((float) Math.toRadians(rotDegrees));
        }
        textMatrix.scale(fittedScale(scale));
        font.drawInBatch(text, dx, dy, 0xFF000000 | rgb, false, textMatrix, buffers,
                Font.DisplayMode.POLYGON_OFFSET, 0, packedLight(emissive, light));
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
