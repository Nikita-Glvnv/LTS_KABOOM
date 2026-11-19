package com.kaboom.server;

import net.minecraft.entity.Entity;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

import java.util.*;

public class EntitySnapshot {
    public static final class Hitbox {

        public final int entityId;
        public final UUID entityUuid;
        public final int partIndex;

        public final double minX, minY, minZ;
        public final double maxX, maxY, maxZ;

        Hitbox(Entity parent, int partIndex, AxisAlignedBB bb) {
            this.entityId = parent.getEntityId();
            this.entityUuid = parent.getUniqueID();
            this.partIndex = partIndex;

            this.minX = bb.minX;
            this.minY = bb.minY;
            this.minZ = bb.minZ;

            this.maxX = bb.maxX;
            this.maxY = bb.maxY;
            this.maxZ = bb.maxZ;
        }

        public boolean intersectsCell(int x, int y, int z) {
            return maxX > x && minX < x + 1
                    && maxY > y && minY < y + 1
                    && maxZ > z && minZ < z + 1;
        }
    }

    public static final class Frame
            implements chunksnapshot.SnapshotUpdate {

        private final int dimensionId;

        public final Map<Long, List<Hitbox>> sections;

        Frame(int dimensionId, Map<Long, List<Hitbox>> source) {
            this.dimensionId = dimensionId;

            Map<Long, List<Hitbox>> copy = new HashMap<>();

            for (Map.Entry<Long, List<Hitbox>> entry
                    : source.entrySet()) {

                copy.put(
                        entry.getKey(),
                        Collections.unmodifiableList(
                                new ArrayList<>(entry.getValue())
                        )
                );
            }

            this.sections = Collections.unmodifiableMap(copy);
        }

        @Override
        public int getDimensionId() {
            return dimensionId;
        }

        public List<Hitbox> getAtCell(int x, int y, int z) {
            List<Hitbox> result = sections.get(
                    sectionKey(x >> 4, y >> 4, z >> 4)
            );

            return result != null
                    ? result
                    : Collections.emptyList();
        }
    }

    private final chunksnapshot owner;

    public EntitySnapshot(chunksnapshot owner) {
        this.owner = owner;
    }

    public static long sectionKey(
            int chunkX, int sectionY, int chunkZ
    ) {
        return new BlockPos(
                chunkX, sectionY, chunkZ
        ).toLong();
    }

    private void addHitbox(
            Map<Long, List<Hitbox>> sections,
            int dimensionId,
            Entity parent,
            Entity part,
            int partIndex
    ) {
        AxisAlignedBB bb = part.getEntityBoundingBox();

        if (bb == null
                || !Double.isFinite(bb.minX)
                || !Double.isFinite(bb.minY)
                || !Double.isFinite(bb.minZ)
                || !Double.isFinite(bb.maxX)
                || !Double.isFinite(bb.maxY)
                || !Double.isFinite(bb.maxZ)
                || bb.minX >= bb.maxX
                || bb.minY >= bb.maxY
                || bb.minZ >= bb.maxZ
                || bb.maxY <= 0
                || bb.minY >= 256) {
            return;
        }

        int minCX = (int) Math.floor(bb.minX / 16.0);
        int maxCX = (int) Math.floor(
                Math.nextDown(bb.maxX) / 16.0
        );

        int minCZ = (int) Math.floor(bb.minZ / 16.0);
        int maxCZ = (int) Math.floor(
                Math.nextDown(bb.maxZ) / 16.0
        );

        int minSY = Math.max(0,
                (int) Math.floor(bb.minY / 16.0));

        int maxSY = Math.min(15,
                (int) Math.floor(
                        Math.nextDown(bb.maxY) / 16.0
                ));

        Hitbox hitbox = new Hitbox(parent, partIndex, bb);

        for (int cx = minCX; cx <= maxCX; cx++) {
            for (int cz = minCZ; cz <= maxCZ; cz++) {
                for (int sy = minSY; sy <= maxSY; sy++) {

                    if (!owner.isTrackedSection(
                            dimensionId, cx, cz, sy
                    )) {
                        continue;
                    }

                    long key = sectionKey(cx, sy, cz);

                    sections.computeIfAbsent(
                            key, k -> new ArrayList<>()
                    ).add(hitbox);
                }
            }
        }
    }

    public void captureAndSend(
            WorldServer world,
            boolean hasTrackedSections
    ) {
        int dimensionId = world.provider.getDimension();

        Map<Long, List<Hitbox>> sections = new HashMap<>();

        if (hasTrackedSections) {
            for (Entity entity : world.loadedEntityList) {

                if (entity == null || entity.isDead) {
                    continue;
                }

                addHitbox(
                        sections,
                        dimensionId,
                        entity,
                        entity,
                        -1
                );

                Entity[] parts = entity.getParts();

                if (parts != null) {
                    for (int i = 0; i < parts.length; i++) {

                        Entity part = parts[i];

                        if (part == null || part.isDead) {
                            continue;
                        }

                        addHitbox(
                                sections,
                                dimensionId,
                                entity,
                                part,
                                i
                        );
                    }
                }
            }
        }

        owner.offerUpdate(
                new Frame(dimensionId, sections)
        );
    }
}
