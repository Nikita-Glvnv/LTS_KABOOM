package com.kaboom.server;

import com.kaboom.network.BlockDestroyPackage;
import net.minecraft.util.math.BlockPos;

import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraft.init.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.boss.EntityDragon;
import net.minecraft.util.DamageSource;

import java.util.UUID;
import java.util.Map;
import java.util.HashMap;
import java.util.LinkedHashMap;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.EnumFacing;
import com.kaboom.network.EffectMessage;

import static com.kaboom.ModLogger.LOGGER;

public class DestroyApplyManager {
    private static final int MAX_WORLD_UPDATE_REQUESTS = 65536;
    private static final int MAX_DESTROY_REQUESTS = 16384;
    private static final int MAX_DESTROYS_PER_TICK = 256;
    private static final long MAX_APPLY_NANOS = 2_000_000L;
    private static final int MAX_DAMAGE_REQUESTS = 16384;
    private static final int MAX_WORLD_UPDATES_PER_TICK = 512;
    private static final long MAX_WORLD_UPDATE_NANOS = 2_000_000L;

    private static final double MAX_IGNITION_CHANCE = 0.25;
    private static final double IGNITION_ENERGY_SCALE = 100.0;

    private final BlockingQueue<DamageRequest> damageQueue =
            new ArrayBlockingQueue<>(MAX_DAMAGE_REQUESTS);

    private final BlockingQueue<DestroyRequest> destroyQueue =
            new ArrayBlockingQueue<>(MAX_DESTROY_REQUESTS);

    private final BlockDestroyPackage blockDestroyPackage =
            new BlockDestroyPackage();

    private final BlockingQueue<WorldUpdateRequest> worldUpdateQueue =
            new ArrayBlockingQueue<>(MAX_WORLD_UPDATE_REQUESTS);

    public enum WorldUpdateType {
        EXPLOSION_CENTER,
        AIR,
        SOLID_OR_LIQUID
    }

    private static final class DestroyRequest {
        final int dimensionId;
        final BlockPos pos;


        DestroyRequest(int dimensionId, BlockPos pos) {
            this.dimensionId = dimensionId;
            this.pos = new BlockPos(
                    pos.getX(),
                    pos.getY(),
                    pos.getZ()
            );
        }
    }
    public boolean submitDestroy(int dimensionId, BlockPos pos) {
        Objects.requireNonNull(pos, "pos");

        return destroyQueue.offer(
                new DestroyRequest(dimensionId, pos)
        );
    }

    private void applyPendingChanges() {
        long start = System.nanoTime();

        try {
            processDestroyQueue(start);
            processDamageQueue();
            processWorldUpdateQueue();

            // Здесь позже появятся другие очереди.
        } finally {
            blockDestroyPackage.flush();
        }
    }

    public void resetDimension(int dimensionId) {

        destroyQueue.removeIf(
                request -> request.dimensionId == dimensionId
        );
        damageQueue.removeIf(
                request -> request.dimensionId == dimensionId
        );
        worldUpdateQueue.removeIf(
                request -> request.dimensionId == dimensionId
        );
    }

    private void processDestroyQueue(long start) {

        for (int i = 0; i < MAX_DESTROYS_PER_TICK; i++) {

            if (System.nanoTime() - start >= MAX_APPLY_NANOS) {
                break;
            }

            DestroyRequest request = destroyQueue.poll();

            if (request == null) {
                break;
            }

            try {
                applyDestroy(request);
            } catch (RuntimeException e) {
                LOGGER.error(
                        "Failed to apply destruction: dimension={}, pos={}",
                        request.dimensionId,
                        request.pos,
                        e
                );
            }
        }
    }

    private void applyDestroy(DestroyRequest request) {

        WorldServer world = DimensionManager.getWorld(
                request.dimensionId,
                false
        );

        if (world == null) {
            return;
        }

        BlockPos pos = request.pos;

        if (pos.getY() < 0 || pos.getY() >= 256) {
            return;
        }

        if (world.getChunkProvider().getLoadedChunk(
                pos.getX() >> 4,
                pos.getZ() >> 4
        ) == null) {
            return;
        }

        if (world.setBlockState(
                pos,
                Blocks.AIR.getDefaultState(),
                0
        )) {
            blockDestroyPackage.recordDestroy(
                    request.dimensionId,
                    pos
            );
        }
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {

        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        applyPendingChanges();
    }

    private static final class DamageRequest {
        final int dimensionId;
        final int entityId;
        final UUID entityUuid;
        final int partIndex;
        final double damage;

        DamageRequest(
                int dimensionId,
                int entityId,
                UUID entityUuid,
                int partIndex,
                double damage
        ) {
            this.dimensionId = dimensionId;
            this.entityId = entityId;
            this.entityUuid = entityUuid;
            this.partIndex = partIndex;
            this.damage = damage;
        }
    }

    public boolean submitDamage(
            int dimensionId,
            int entityId,
            UUID entityUuid,
            int partIndex,
            double damage
    ) {
        Objects.requireNonNull(entityUuid, "entityUuid");

        if (partIndex < -1
                || !Double.isFinite(damage)
                || damage < 0.0) {
            throw new IllegalArgumentException(
                    "Invalid damage request"
            );
        }

        if (damage == 0.0) {
            return true;
        }

        return damageQueue.offer(
                new DamageRequest(
                        dimensionId,
                        entityId,
                        entityUuid,
                        partIndex,
                        damage
                )
        );
    }

    private static final class DamageKey {
        final int dimensionId;
        final int entityId;
        final UUID entityUuid;

        DamageKey(DamageRequest request) {
            dimensionId = request.dimensionId;
            entityId = request.entityId;
            entityUuid = request.entityUuid;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof DamageKey)) return false;

            DamageKey other = (DamageKey) obj;

            return dimensionId == other.dimensionId
                    && entityId == other.entityId
                    && entityUuid.equals(other.entityUuid);
        }

        @Override
        public int hashCode() {
            int result = 31 * dimensionId + entityId;
            return 31 * result + entityUuid.hashCode();
        }
    }

    private void processDamageQueue() {

        Map<DamageKey, Map<Integer, Double>> aggregated =
                new LinkedHashMap<>();

        int count = damageQueue.size();

        for (int i = 0; i < count; i++) {

            DamageRequest request = damageQueue.poll();

            if (request == null) {
                break;
            }

            DamageKey key = new DamageKey(request);

            Map<Integer, Double> parts =
                    aggregated.computeIfAbsent(
                            key, k -> new HashMap<>()
                    );

            parts.merge(
                    request.partIndex,
                    request.damage,
                    (a, b) -> Math.min(
                            (double) Float.MAX_VALUE,
                            a + b
                    )
            );
        }

        for (Map.Entry<DamageKey, Map<Integer, Double>> entry
                : aggregated.entrySet()) {

            try {
                applyDamage(entry.getKey(), entry.getValue());
            } catch (RuntimeException e) {
                LOGGER.error(
                        "Failed to apply entity damage: dimension={}, entityId={}",
                        entry.getKey().dimensionId,
                        entry.getKey().entityId,
                        e
                );
            }
        }
    }

    private void applyDamage(
            DamageKey key,
            Map<Integer, Double> damageByPart
    ) {
        WorldServer world = DimensionManager.getWorld(
                key.dimensionId, false
        );

        if (world == null) {
            return;
        }

        Entity parent = world.getEntityByID(key.entityId);

        if (parent == null
                || parent.isDead
                || !key.entityUuid.equals(parent.getUniqueID())) {
            return;
        }

        Entity[] parts = parent.getParts();

        DamageSource source = DamageSource.causeExplosionDamage(
                (EntityLivingBase) null
        );

        for (Map.Entry<Integer, Double> entry
                : damageByPart.entrySet()) {

            int partIndex = entry.getKey();

            Entity target;

            if (partIndex == -1) {

                // У дракона прямой урон родителю обычно
                // не проходит, поэтому используем тело.
                target = parent instanceof EntityDragon
                        ? ((EntityDragon) parent).dragonPartBody
                        : parent;

            } else if (parts != null
                    && partIndex < parts.length) {

                target = parts[partIndex];

            } else {
                continue;
            }

            if (target == null || target.isDead) {
                continue;
            }

            // Обход стандартного периода неуязвимости.
            if (parent instanceof EntityLivingBase) {
                ((EntityLivingBase) parent).hurtResistantTime = 0;
            }

            if (target != parent
                    && target instanceof EntityLivingBase) {
                ((EntityLivingBase) target).hurtResistantTime = 0;
            }

            float amount = (float) Math.min(
                    (double) Float.MAX_VALUE,
                    entry.getValue()
            );

            target.attackEntityFrom(source, amount);
        }
    }

    private static final class WorldUpdateRequest {
        final int dimensionId;
        final int x, y, z;
        final WorldUpdateType type;
        final double energy;

        WorldUpdateRequest(
                int dimensionId,
                int x, int y, int z,
                WorldUpdateType type,
                double energy
        ) {
            this.dimensionId = dimensionId;
            this.x = x;
            this.y = y;
            this.z = z;
            this.type = type;
            this.energy = energy;
        }
    }

    public boolean submitWorldUpdate(
            int dimensionId,
            int x, int y, int z,
            WorldUpdateType type,
            double energy
    ) {
        Objects.requireNonNull(type, "type");

        if (!Double.isFinite(energy) || energy < 0.0) {
            throw new IllegalArgumentException(
                    "Invalid world update energy"
            );
        }

        return worldUpdateQueue.offer(
                new WorldUpdateRequest(
                        dimensionId,
                        x, y, z,
                        type,
                        energy
                )
        );
    }

    private void processWorldUpdateQueue() {
        long start = System.nanoTime();

        for (int i = 0; i < MAX_WORLD_UPDATES_PER_TICK; i++) {

            if (System.nanoTime() - start >= MAX_WORLD_UPDATE_NANOS) {
                break;
            }

            WorldUpdateRequest request = worldUpdateQueue.poll();

            if (request == null) {
                break;
            }

            try {
                switch (request.type) {

                    case EXPLOSION_CENTER:
                        blockDestroyPackage.recordEffect(
                                request.dimensionId,
                                request.x,
                                request.y,
                                request.z,
                                EffectMessage.EXPLOSION_CENTER,
                                request.energy
                        );
                        break;

                    case AIR:
                        blockDestroyPackage.recordEffect(
                                request.dimensionId,
                                request.x,
                                request.y,
                                request.z,
                                EffectMessage.AIR,
                                request.energy
                        );
                        break;

                    case SOLID_OR_LIQUID:
                        applyBlockUpdate(request);
                        break;
                }

            } catch (RuntimeException e) {
                LOGGER.error(
                        "Failed to apply world update: dimension={}, pos=({}, {}, {}), type={}",
                        request.dimensionId,
                        request.x,
                        request.y,
                        request.z,
                        request.type,
                        e
                );
            }
        }
    }

    private void applyBlockUpdate(WorldUpdateRequest request) {

        WorldServer world = DimensionManager.getWorld(
                request.dimensionId, false
        );

        if (world == null || request.y < 0 || request.y >= 256) {
            return;
        }

        if (world.getChunkProvider().getLoadedChunk(
                request.x >> 4,
                request.z >> 4
        ) == null) {
            return;
        }

        BlockPos pos = new BlockPos(
                request.x, request.y, request.z
        );

        IBlockState state = world.getBlockState(pos);

        if (state.getMaterial() == Material.AIR) {
            return;
        }

        // Обновляем сам блок, не рассылая напрямую
        // уведомления соседним блокам.
        world.scheduleUpdate(pos, state.getBlock(), 1);

        // Вероятность поджога зависит от энергии.
        double chance = MAX_IGNITION_CHANCE
                * request.energy
                / (request.energy + IGNITION_ENERGY_SCALE);

        if (request.y >= 255
                || world.rand.nextDouble() >= chance
                || !state.getBlock().isFlammable(
                world, pos, EnumFacing.UP
        )) {
            return;
        }

        BlockPos firePos = pos.up();

        if (world.isAirBlock(firePos)
                && Blocks.FIRE.canPlaceBlockAt(world, firePos)) {

            world.setBlockState(
                    firePos,
                    Blocks.FIRE.getDefaultState(),
                    2
            );
        }
    }
}
