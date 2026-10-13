package dev.bwr.mod.cooling.client;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.bwr.mod.BwrMod;
import dev.bwr.mod.client.GpuVisualShaders;
import dev.bwr.mod.client.VisualEffectsMode;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.ParticleStatus;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.BlockPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.event.GameShuttingDownEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;

/** Client-level puff state in parallel arrays; one GL31 instanced draw per visible tower. */
@EventBusSubscriber(modid = BwrMod.MOD_ID, value = Dist.CLIENT)
public final class VaporPlumeRenderer {
    public static final int CAP = 1200;
    private static final Map<BlockPos, Tower> TOWERS = new HashMap<>();
    private static ClientLevel activeLevel;
    private static int total, instanceBuffer, instanceTexture, vertexArray;
    private static final FloatBuffer UPLOAD = BufferUtils.createFloatBuffer(CAP * 12);
    private static long instancedDraws;

    private VaporPlumeRenderer() {}

    /** Return true when the GPU path handled (or intentionally suppressed) this spawn. */
    public static boolean spawn(ClientLevel level, BlockPos tower, double x, double y, double z,
                                double size, double lift, double strength) {
        if (!VisualEffectsMode.gpu(VisualEffectsMode.Effect.VAPOR, true)
                || GpuVisualShaders.shader(VisualEffectsMode.Effect.VAPOR) == null
                || CoolingVaporParticle.spriteSet() == null) return false;
        if (activeLevel != level) reset(level);
        ParticleStatus status = Minecraft.getInstance().options.particles().get();
        if (status == ParticleStatus.MINIMAL || status == ParticleStatus.DECREASED && level.random.nextBoolean()
                || total >= CAP) return true;
        Tower puffs = TOWERS.computeIfAbsent(tower.immutable(), unused -> new Tower());
        int i = puffs.count++;
        puffs.x[i] = puffs.px[i] = puffs.sourceX[i] = x;
        puffs.y[i] = puffs.py[i] = puffs.sourceY[i] = y;
        puffs.z[i] = puffs.pz[i] = puffs.sourceZ[i] = z;
        puffs.base[i] = (float)Math.clamp(size, .5, 5);
        puffs.size[i] = puffs.base[i];
        puffs.lift[i] = Math.clamp(lift, .15, .55);
        puffs.opacity[i] = (float)Math.clamp(strength, .05, .9);
        puffs.phase[i] = level.random.nextDouble() * Math.PI * 2;
        puffs.life[i] = 420 + level.random.nextInt(180);
        puffs.age[i] = 0;
        var sprite = CoolingVaporParticle.spriteSet().get(level.random);
        puffs.u0[i] = sprite.getU0(); puffs.u1[i] = sprite.getU1();
        puffs.v0[i] = sprite.getV0(); puffs.v1[i] = sprite.getV1();
        total++;
        return true;
    }

    public static int count() { return total; }
    public static long drawCount() { return instancedDraws; }

    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        var level = Minecraft.getInstance().level;
        if (level == null) { reset(null); return; }
        if (activeLevel != level) { reset(level); return; }
        long salt = level.dimension().location().hashCode();
        for (Iterator<Tower> it = TOWERS.values().iterator(); it.hasNext();) {
            Tower tower = it.next();
            for (int i = 0; i < tower.count;) {
                tower.px[i] = tower.x[i]; tower.py[i] = tower.y[i]; tower.pz[i] = tower.z[i];
                int age = ++tower.age[i];
                if (age >= tower.life[i]) { tower.remove(i); total--; continue; }
                var step = CoolingVaporMotion.step(level, age, tower.life[i], tower.phase[i], tower.lift[i],
                        tower.sourceX[i], tower.sourceY[i], tower.sourceZ[i], salt,
                        tower.vx[i], tower.vy[i], tower.vz[i], tower.base[i], tower.opacity[i], tower.y[i]);
                tower.vx[i] = step.x(); tower.vy[i] = step.y(); tower.vz[i] = step.z();
                tower.x[i] += step.x(); tower.y[i] += step.y(); tower.z[i] += step.z();
                tower.size[i] = step.size(); tower.alpha[i] = step.alpha();
                i++;
            }
            if (tower.count == 0) it.remove();
        }
    }

    @SubscribeEvent public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || total == 0
                || !VisualEffectsMode.gpu(VisualEffectsMode.Effect.VAPOR, true)) return;
        var shader = GpuVisualShaders.shader(VisualEffectsMode.Effect.VAPOR);
        if (shader == null) return;
        RenderSystem.assertOnRenderThread();
        try {
            ensureBuffers();
            var texture = Minecraft.getInstance().getTextureManager().getTexture(TextureAtlas.LOCATION_PARTICLES);
            shader.setSampler("Sampler0", texture.getId());
            shader.safeGetUniform("ModelViewMat").set(event.getModelViewMatrix());
            shader.safeGetUniform("ProjMat").set(event.getProjectionMatrix());
            int oldVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
            int oldArray = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
            int oldActive = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
            boolean oldCull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
            RenderSystem.enableDepthTest();
            RenderSystem.depthMask(false);
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableCull();
            GL30.glBindVertexArray(vertexArray);
            shader.apply();
            GL13.glActiveTexture(GL13.GL_TEXTURE1);
            int oldTextureBuffer = GL11.glGetInteger(GL31.GL_TEXTURE_BINDING_BUFFER);
            GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, instanceTexture);
            GL20.glUniform1i(GL20.glGetUniformLocation(shader.getId(), "Instances"), 1);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            try {
                float partial = event.getPartialTick().getGameTimeDeltaPartialTick(true);
                var camera = event.getCamera().getPosition();
                for (Tower tower : TOWERS.values()) {
                    if (tower.count == 0) continue;
                    UPLOAD.clear();
                    for (int i = 0; i < tower.count; i++) {
                        UPLOAD.put((float)(tower.px[i] + (tower.x[i] - tower.px[i]) * partial - camera.x));
                        UPLOAD.put((float)(tower.py[i] + (tower.y[i] - tower.py[i]) * partial - camera.y));
                        UPLOAD.put((float)(tower.pz[i] + (tower.z[i] - tower.pz[i]) * partial - camera.z));
                        UPLOAD.put(tower.size[i]);
                        UPLOAD.put(tower.alpha[i]); UPLOAD.put(tower.u0[i]); UPLOAD.put(tower.v0[i]); UPLOAD.put(tower.u1[i]);
                        UPLOAD.put(tower.v1[i]); UPLOAD.put(0); UPLOAD.put(0); UPLOAD.put(0);
                    }
                    UPLOAD.flip();
                    GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, instanceBuffer);
                    GL15.glBufferSubData(GL31.GL_TEXTURE_BUFFER, 0, UPLOAD);
                    GL31.glDrawArraysInstanced(GL11.GL_TRIANGLE_STRIP, 0, 4, tower.count);
                    instancedDraws++;
                }
                int error = GL11.glGetError();
                if (error != GL11.GL_NO_ERROR)
                    throw new IllegalStateException("Vapor instancing GL error 0x" + Integer.toHexString(error));
            } finally {
                GL13.glActiveTexture(GL13.GL_TEXTURE1);
                GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, oldTextureBuffer);
                GL13.glActiveTexture(oldActive);
                shader.clear();
                GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, 0);
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, oldArray);
                GL30.glBindVertexArray(oldVao);
                RenderSystem.depthMask(true);
                RenderSystem.disableBlend();
                if (oldCull) RenderSystem.enableCull();
            }
        } catch (RuntimeException | Error error) {
            VisualEffectsMode.failed(VisualEffectsMode.Effect.VAPOR, error);
            clearBuffers();
        }
    }

    private static void ensureBuffers() {
        if (instanceBuffer != 0) return;
        vertexArray = GL30.glGenVertexArrays();
        instanceBuffer = GL15.glGenBuffers();
        instanceTexture = GL11.glGenTextures();
        GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, instanceBuffer);
        GL15.glBufferData(GL31.GL_TEXTURE_BUFFER, (long)CAP * 12 * Float.BYTES, GL15.GL_STREAM_DRAW);
        GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, 0);
        int old = GL11.glGetInteger(GL31.GL_TEXTURE_BINDING_BUFFER);
        GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, instanceTexture);
        GL31.glTexBuffer(GL31.GL_TEXTURE_BUFFER, GL30.GL_RGBA32F, instanceBuffer);
        GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, old);
    }

    public static void clearBuffers() {
        if (!RenderSystem.isOnRenderThread()) { RenderSystem.recordRenderCall(VaporPlumeRenderer::clearBuffers); return; }
        if (instanceTexture != 0) GL11.glDeleteTextures(instanceTexture);
        if (instanceBuffer != 0) GL15.glDeleteBuffers(instanceBuffer);
        if (vertexArray != 0) GL30.glDeleteVertexArrays(vertexArray);
        instanceTexture = instanceBuffer = vertexArray = 0;
        TOWERS.clear(); total = 0;
    }

    private static void reset(ClientLevel level) { activeLevel = level; clearBuffers(); }
    @SubscribeEvent public static void unload(LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) reset(null);
    }
    @SubscribeEvent public static void shutdown(GameShuttingDownEvent event) { reset(null); }

    private static final class Tower {
        int count;
        final double[] x = new double[CAP], y = new double[CAP], z = new double[CAP];
        final double[] px = new double[CAP], py = new double[CAP], pz = new double[CAP];
        final double[] vx = new double[CAP], vy = new double[CAP], vz = new double[CAP];
        final double[] sourceX = new double[CAP], sourceY = new double[CAP], sourceZ = new double[CAP];
        final double[] phase = new double[CAP], lift = new double[CAP];
        final float[] base = new float[CAP], opacity = new float[CAP], size = new float[CAP], alpha = new float[CAP];
        final float[] u0 = new float[CAP], v0 = new float[CAP], u1 = new float[CAP], v1 = new float[CAP];
        final int[] age = new int[CAP], life = new int[CAP];

        void remove(int index) {
            int last = --count;
            if (index == last) return;
            for (double[] array : new double[][]{x,y,z,px,py,pz,vx,vy,vz,sourceX,sourceY,sourceZ,phase,lift}) array[index] = array[last];
            for (float[] array : new float[][]{base,opacity,size,alpha,u0,v0,u1,v1}) array[index] = array[last];
            age[index] = age[last]; life[index] = life[last];
        }
    }
}
