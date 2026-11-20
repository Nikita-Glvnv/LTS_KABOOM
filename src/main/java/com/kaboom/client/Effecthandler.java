package com.kaboom.client;

import com.kaboom.network.EffectMessage;

import com.kaboom.sound.ExplosionAcoustics;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.init.SoundEvents;
import net.minecraft.util.SoundCategory;
import com.kaboom.sound.KaboomSounds;
import com.kaboom.sound.ExplosionAcoustics;

import net.minecraft.client.audio.ISound;
import net.minecraft.client.audio.PositionedSound;

import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;

import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import org.lwjgl.opengl.GL11;

import java.util.ArrayDeque;
import java.util.Iterator;

public class Effecthandler
        implements IMessageHandler<EffectMessage, IMessage> {
    private static final int MAX_FOG = 8192;

    private static final ArrayDeque<Fog> ACTIVE_FOG =
            new ArrayDeque<>();

    private static final Effecthandler FOG_RENDERER =
            new Effecthandler();

    private static WorldClient fogWorld;
    private static boolean rendererRegistered;

    private static final class Fog {
        final double x, y, z;
        final long lifetimeNs;
        final long createdAt;

        Fog(double x, double y, double z, double energy) {
            this.x = x;
            this.y = y;
            this.z = z;

            double strength = Math.min(
                    1.0,
                    Math.log1p(energy) / Math.log1p(1000.0)
            );

            this.lifetimeNs = 10_000_000_000L
                    + (long) (5_000_000_000L * strength);

            this.createdAt = System.nanoTime();
        }
    }

    @Override
    public IMessage onMessage(
            EffectMessage message,
            MessageContext ctx
    ) {
        Minecraft.getMinecraft().addScheduledTask(
                () -> applyEffects(message)
        );

        return null;
    }

    private static void applyEffects(EffectMessage message) {
        WorldClient world = Minecraft.getMinecraft().world;

        if (world == null
                || world.provider.getDimension() != message.getDimensionId()) {
            return;
        }

        for (int i = 0; i < message.getCount(); i++) {

            int x = message.getX(i);
            int y = message.getY(i);
            int z = message.getZ(i);

            double energy = message.getEnergy(i);

            switch (message.getType(i)) {

                case EffectMessage.EXPLOSION_CENTER:
                    playExplosionSound(x, y, z, energy);
                    break;

                case EffectMessage.AIR:
                    if (world.getChunkProvider().getLoadedChunk(
                            message.getChunkX(),
                            message.getChunkZ()
                    ) != null) {
                        spawnWhiteFog(world, x, y, z, energy);
                    }
                    break;
            }
        }
    }

    private static void playExplosionSound(
            int x, int y, int z,
            double energy
    ) {
        Minecraft mc = Minecraft.getMinecraft();

        if (mc.player == null) {
            return;
        }

        double sx = x + 0.5;
        double sy = y + 0.5;
        double sz = z + 0.5;

        double distance = mc.player.getDistance(sx, sy, sz);

        if (distance >= ExplosionAcoustics.radius(energy)) {
            return;
        }

        // 20 блоков/сек при 20 TPS = 1 тик на блок.
        int delayTicks = (int) Math.ceil(distance);

        // Основной звук на любой слышимой дистанции.
        mc.getSoundHandler().playDelayedSound(
                new ExplosionSound(sx, sy, sz, energy, 1.0F, 1.0F),
                delayTicks
        );

// Дополнительное усиление на расстоянии до 32 блоков.
        if (distance < 32.0) {

            double t = 1.0 - distance / 32.0;

            // Плавное уменьшение дополнительной громкости.
            double boost = t * t * (3.0 - 2.0 * t);

            mc.getSoundHandler().playDelayedSound(
                    new ExplosionSound(
                            sx, sy, sz, energy,
                            (float) (0.8 * boost),
                            0.97F
                    ),
                    delayTicks
            );

            mc.getSoundHandler().playDelayedSound(
                    new ExplosionSound(
                            sx, sy, sz, energy,
                            (float) (0.6 * boost),
                            1.03F
                    ),
                    delayTicks
            );
        }
    }

    private static void spawnWhiteFog(
            WorldClient world,
            int x, int y, int z,
            double energy
    ) {
        if (!rendererRegistered) {
            MinecraftForge.EVENT_BUS.register(FOG_RENDERER);
            rendererRegistered = true;
        }

        if (fogWorld != world) {
            ACTIVE_FOG.clear();
            fogWorld = world;
        }

        if (ACTIVE_FOG.size() >= MAX_FOG) {
            ACTIVE_FOG.removeFirst();
        }

        ACTIVE_FOG.addLast(
                new Fog(
                        x + 0.5,
                        y + 0.5,
                        z + 0.5,
                        energy
                )
        );
    }

    @SubscribeEvent
    public void onRenderFog(RenderWorldLastEvent event) {
        Minecraft mc = Minecraft.getMinecraft();

        if (mc.world == null || mc.world != fogWorld) {
            ACTIVE_FOG.clear();
            fogWorld = null;
            return;
        }

        if (ACTIVE_FOG.isEmpty()) {
            return;
        }

        Entity camera = mc.getRenderViewEntity();

        if (camera == null) {
            return;
        }

        float partial = event.getPartialTicks();

        double camX = camera.lastTickPosX
                + (camera.posX - camera.lastTickPosX) * partial;

        double camY = camera.lastTickPosY
                + (camera.posY - camera.lastTickPosY) * partial;

        double camZ = camera.lastTickPosZ
                + (camera.posZ - camera.lastTickPosZ) * partial;

        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.getBuffer();

        GlStateManager.enableBlend();
        GlStateManager.blendFunc(
                GlStateManager.SourceFactor.SRC_ALPHA,
                GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA
        );

        GlStateManager.disableTexture2D();
        GlStateManager.disableLighting();
        GlStateManager.disableCull();
        GlStateManager.disableAlpha();
        GlStateManager.depthMask(false);
        GlStateManager.shadeModel(GL11.GL_FLAT);

        try {
            buffer.begin(
                    GL11.GL_QUADS,
                    DefaultVertexFormats.POSITION_COLOR
            );

            long now = System.nanoTime();
            boolean hasVertices = false;

            Iterator<Fog> iterator = ACTIVE_FOG.iterator();

            while (iterator.hasNext()) {
                Fog fog = iterator.next();

                double age = (double) (now - fog.createdAt)
                        / fog.lifetimeNs;

                if (age >= 1.0) {
                    iterator.remove();
                    continue;
                }

                double cx = fog.x - camX;
                double cy = fog.y - camY;
                double cz = fog.z - camZ;

                int cameraChunkX = ((int) Math.floor(camX)) >> 4;
                int cameraChunkZ = ((int) Math.floor(camZ)) >> 4;

                int renderDistanceChunks = mc.gameSettings.renderDistanceChunks;
                int chunkX = ((int) Math.floor(fog.x)) >> 4;
                int chunkZ = ((int) Math.floor(fog.z)) >> 4;

                if (Math.abs(chunkX - cameraChunkX) > renderDistanceChunks
                        || Math.abs(chunkZ - cameraChunkZ) > renderDistanceChunks
                        || mc.world.getChunkProvider().getLoadedChunk(
                        chunkX, chunkZ
                ) == null) {
                    continue;
                }

                // Последние 20% времени куб постепенно исчезает.
                double fade = Math.min(1.0, (1.0 - age) * 5.0);

                int alpha = (int) (18.0 * fade);

                renderFogCube(
                        buffer,
                        cx - 0.5,
                        cy - 0.5,
                        cz - 0.5,
                        alpha
                );

                hasVertices = true;
            }

            if (hasVertices) {
                tessellator.draw();
            } else {
                buffer.finishDrawing();
            }

        } finally {
            GlStateManager.shadeModel(GL11.GL_FLAT);
            GlStateManager.depthMask(true);
            GlStateManager.enableAlpha();
            GlStateManager.enableCull();
            GlStateManager.enableLighting();
            GlStateManager.enableTexture2D();
            GlStateManager.disableBlend();
            GlStateManager.resetColor();
        }
    }

    private static void fogVertex(
            BufferBuilder buffer,
            double x, double y, double z,
            int alpha
    ) {
        buffer.pos(x, y, z)
                .color(255, 255, 255, alpha)
                .endVertex();
    }

    private static void renderFogCube(
            BufferBuilder b,
            double x, double y, double z,
            int alpha
    ) {
        double x1 = x + 1.0;
        double y1 = y + 1.0;
        double z1 = z + 1.0;

        // Левая грань
        fogVertex(b, x, y, z, alpha);
        fogVertex(b, x, y, z1, alpha);
        fogVertex(b, x, y1, z1, alpha);
        fogVertex(b, x, y1, z, alpha);

        // Правая грань
        fogVertex(b, x1, y, z1, alpha);
        fogVertex(b, x1, y, z, alpha);
        fogVertex(b, x1, y1, z, alpha);
        fogVertex(b, x1, y1, z1, alpha);

        // Нижняя грань
        fogVertex(b, x, y, z1, alpha);
        fogVertex(b, x, y, z, alpha);
        fogVertex(b, x1, y, z, alpha);
        fogVertex(b, x1, y, z1, alpha);

        // Верхняя грань
        fogVertex(b, x, y1, z, alpha);
        fogVertex(b, x, y1, z1, alpha);
        fogVertex(b, x1, y1, z1, alpha);
        fogVertex(b, x1, y1, z, alpha);

        // Передняя грань
        fogVertex(b, x1, y, z, alpha);
        fogVertex(b, x, y, z, alpha);
        fogVertex(b, x, y1, z, alpha);
        fogVertex(b, x1, y1, z, alpha);

        // Задняя грань
        fogVertex(b, x, y, z1, alpha);
        fogVertex(b, x1, y, z1, alpha);
        fogVertex(b, x1, y1, z1, alpha);
        fogVertex(b, x, y1, z1, alpha);
    }

    private static final class ExplosionSound extends PositionedSound {
        private final float gain;
        private final float pitchMultiplier;

        private final double sourceX;
        private final double sourceY;
        private final double sourceZ;
        private final double energy;

        ExplosionSound(
                double x, double y, double z,
                double energy,
                float gain,
                float pitchMultiplier
        ) {
            super(KaboomSounds.EXPLOSION, SoundCategory.BLOCKS);

            this.sourceX = x;
            this.sourceY = y;
            this.sourceZ = z;
            this.energy = energy;

            this.gain = gain;
            this.pitchMultiplier = pitchMultiplier;

            this.xPosF = (float) x;
            this.yPosF = (float) y;
            this.zPosF = (float) z;

            this.attenuationType = ISound.AttenuationType.NONE;
        }

        private double listenerDistance() {
            Minecraft mc = Minecraft.getMinecraft();

            if (mc.player == null) {
                return ExplosionAcoustics.radius(energy);
            }

            return mc.player.getDistance(
                    sourceX, sourceY, sourceZ
            );
        }

        @Override
        public float getVolume() {
            return ExplosionAcoustics.volume(
                    listenerDistance(),
                    energy
            ) * gain;
        }

        @Override
        public float getPitch() {
            return ExplosionAcoustics.pitch(
                    listenerDistance(),
                    energy
            ) * pitchMultiplier;
        }
    }
}
