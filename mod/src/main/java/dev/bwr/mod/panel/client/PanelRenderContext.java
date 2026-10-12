package dev.bwr.mod.panel.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
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

    private final Matrix4f basis = new Matrix4f();
    private final Matrix4f textMatrix = new Matrix4f();
    private final float[] normals = new float[15];

    private MultiBufferSource buffers;
    private VertexConsumer consumer;
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
        this.pose = stack.last();
        this.light = light;
        this.overlay = overlay;
        this.lod = lod;
        this.gameTime = time;
        this.font = font;
        PanelGeometry.Frame f = PanelGeometry.frame(be.panelType(), be.facing());
        this.frame = f;
        float len = (float) Math.sqrt(f.ux * f.ux + f.uy * f.uy + f.uz * f.uz);
        float vlen = (float) Math.sqrt(f.vx * f.vx + f.vy * f.vy + f.vz * f.vz);
        setNormal(NORMAL_FACE, (float) f.nx, (float) f.ny, (float) f.nz);
        setNormal(NORMAL_LEFT, (float) (-f.ux / len), (float) (-f.uy / len), (float) (-f.uz / len));
        setNormal(NORMAL_RIGHT, (float) (f.ux / len), (float) (f.uy / len), (float) (f.uz / len));
        setNormal(NORMAL_UP, (float) (-f.vx / vlen), (float) (-f.vy / vlen), (float) (-f.vz / vlen));
        setNormal(NORMAL_DOWN, (float) (f.vx / vlen), (float) (f.vy / vlen), (float) (f.vz / vlen));
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

    void beginComponent(PanelComponent component) {
        this.originU = component.x();
        this.originV = component.y();
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
        int packedLight = emissive ? LightTexture.FULL_BRIGHT : light;
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        int n = normal * 3;
        float nx = normals[n];
        float ny = normals[n + 1];
        float nz = normals[n + 2];
        vertex(x0, y0, z0, r, g, b, packedLight, nx, ny, nz);
        vertex(x1, y1, z1, r, g, b, packedLight, nx, ny, nz);
        vertex(x2, y2, z2, r, g, b, packedLight, nx, ny, nz);
        vertex(x3, y3, z3, r, g, b, packedLight, nx, ny, nz);
    }

    private void vertex(float x, float y, float z, int r, int g, int b, int packedLight, float nx, float ny, float nz) {
        double u = originU + x;
        double v = originV + y;
        if (consumer == null) {
            // Drawing text switches the buffer source to another render type, which closes the solid builder.
            consumer = buffers.getBuffer(SOLID);
        }
        consumer.addVertex(pose, (float) frame.x(u, v, z), (float) frame.y(u, v, z), (float) frame.z(u, v, z))
                .setColor(r, g, b, 255)
                .setUv(0.5f, 0.5f)
                .setOverlay(overlay)
                .setLight(packedLight)
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
        double u = originU + x;
        double v = originV + y;
        textMatrix.set(pose.pose());
        textMatrix.translate((float) frame.x(u, v, z), (float) frame.y(u, v, z), (float) frame.z(u, v, z));
        textMatrix.mul(basis);
        textMatrix.scale(scale, scale / rowAspect(), scale);
        font.drawInBatch(text, 0.0f, 0.0f, 0xFF000000 | rgb, false, textMatrix, buffers,
                Font.DisplayMode.POLYGON_OFFSET, 0, emissive ? LightTexture.FULL_BRIGHT : light);
        consumer = null;
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
        int packedLight = emissive ? LightTexture.FULL_BRIGHT : light;
        VertexConsumer out = buffers.getBuffer(type);
        float nx = normals[0];
        float ny = normals[1];
        float nz = normals[2];
        textured(out, x, y, z, 0.0f, 0.0f, packedLight, nx, ny, nz);
        textured(out, x + w, y, z, 1.0f, 0.0f, packedLight, nx, ny, nz);
        textured(out, x + w, y + h, z, 1.0f, 1.0f, packedLight, nx, ny, nz);
        textured(out, x, y + h, z, 0.0f, 1.0f, packedLight, nx, ny, nz);
        consumer = null;
    }

    private void textured(VertexConsumer out, float x, float y, float z, float u, float v, int packedLight,
                          float nx, float ny, float nz) {
        double cu = originU + x;
        double cv = originV + y;
        out.addVertex(pose, (float) frame.x(cu, cv, z), (float) frame.y(cu, cv, z), (float) frame.z(cu, cv, z))
                .setColor(255, 255, 255, 255)
                .setUv(u, v)
                .setOverlay(overlay)
                .setLight(packedLight)
                .setNormal(pose, nx, ny, nz);
    }
}
