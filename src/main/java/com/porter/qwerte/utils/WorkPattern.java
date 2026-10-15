package com.porter.qwerte.utils;

/**
 * The order auto-walk goes about covering an area in. Every spot in the area gets a sort key from the pattern and the
 * lowest key is walked to first, so as spots get finished the sweep moves on by itself. Nearest has no keys at all.
 */
public enum WorkPattern {
    Nearest("Nearest"),
    Rows("Rows"),
    SpiralOut("Spiral Out"),
    SpiralIn("Spiral In");

    private final String title;

    WorkPattern(String title) {
        this.title = title;
    }

    @Override
    public String toString() {
        return title;
    }

    /**
     * Which way the lanes of Rows run.
     */
    public enum RowDirection {
        Auto("Auto"),
        NorthSouth("North-South"),
        EastWest("East-West");

        private final String title;

        RowDirection(String title) {
            this.title = title;
        }

        @Override
        public String toString() {
            return title;
        }
    }

    /**
     * Lane width that lets one pass cover everything the player can reach from the middle of the lane.
     */
    public static int autoLaneWidth(double reach) {
        return Math.max(1, (int) Math.floor(2 * reach) - 1);
    }

    /**
     * The sort keys for one search area. Call {@link #prepare} before a batch of {@link #key} calls, which only do
     * integer math (and one atan2 for spirals) so they're fine to run for every column of the area.
     *
     * Rows are laid out over the box the real work sits in, which is measured once per run on the first explore scan
     * ({@link #needsBounds}, {@link #beginBounds}, {@link #include}, {@link #endBounds}). Until then Rows has no keys.
     */
    public static class Sweep {
        private static final int STEP = 4096;
        // Added to keys of rings or lanes we've already moved past, so a straggler behind only wins when nothing is ahead
        private static final long BEHIND = 1L << 40;

        private float yaw = Float.NaN;
        private int currentRank;

        private WorkPattern pattern = Nearest;
        private boolean alongX;
        private int homeX, homeZ, radius, width = 1;
        private int bands;
        private double angleStart;

        // Rows, in world coordinates of the lane axes
        private boolean latched, collecting;
        private int alongMin, alongMax, perpMin, perpMax;
        private int alongSign, perpSign, lanes;

        /**
         * Forgets the layout and the sweep position. The facing is taken from {@code yaw} (NaN to take it from the
         * first prepare instead, for when the player isn't there yet).
         */
        public void reset(float yaw) {
            this.yaw = yaw;
            latched = collecting = false;
            currentRank = 0;
        }

        /**
         * @param laneWidth 0 to work it out from the reach
         * @param playerYaw only used if reset didn't have a facing
         */
        public void prepare(WorkPattern pattern, RowDirection direction, int laneWidth, double reach, int homeX, int homeZ, int radius, float playerYaw) {
            if (Float.isNaN(yaw)) yaw = playerYaw;

            // Facing as a vector on the ground: x = -sin(yaw), z = cos(yaw)
            double rad = Math.toRadians(yaw);
            double lookX = -Math.sin(rad);
            double lookZ = Math.cos(rad);

            alongX = switch (direction) {
                case Auto -> Math.abs(lookX) > Math.abs(lookZ);
                case NorthSouth -> false;
                case EastWest -> true;
            };
            // Spirals start at the angle the player was facing
            angleStart = Math.atan2(lookX, -lookZ);

            this.pattern = pattern;
            this.homeX = homeX;
            this.homeZ = homeZ;
            this.radius = radius;
            this.width = laneWidth > 0 ? laneWidth : autoLaneWidth(reach);
            this.bands = radius / width + 1;
        }

        /**
         * Whether keys mean anything right now. Rows need their layout first.
         */
        public boolean active() {
            return pattern == SpiralOut || pattern == SpiralIn || (pattern == Rows && latched);
        }

        public boolean needsBounds() {
            return pattern == Rows && !latched;
        }

        public void beginBounds() {
            collecting = true;
            alongMin = perpMin = Integer.MAX_VALUE;
            alongMax = perpMax = Integer.MIN_VALUE;
        }

        /**
         * Adds a column of work to the box the lanes are laid over.
         */
        public void include(int x, int z) {
            int along = alongX ? x : z;
            int perp = alongX ? z : x;
            alongMin = Math.min(alongMin, along);
            alongMax = Math.max(alongMax, along);
            perpMin = Math.min(perpMin, perp);
            perpMax = Math.max(perpMax, perp);
        }

        /**
         * Lays the lanes over the box from the corner nearest the player: the first lane is the one along the nearest
         * side and runs away from the corner, the lanes after it go across to the far side, alternating direction.
         * Does nothing if there was no work, so the next scan measures again.
         */
        public void endBounds(int playerX, int playerZ) {
            collecting = false;
            if (alongMin > alongMax) return;

            int playerAlong = alongX ? playerX : playerZ;
            int playerPerp = alongX ? playerZ : playerX;

            perpSign = Math.abs(playerPerp - perpMin) <= Math.abs(perpMax - playerPerp) ? 1 : -1;
            alongSign = Math.abs(playerAlong - alongMin) <= Math.abs(alongMax - playerAlong) ? 1 : -1;
            lanes = (perpMax - perpMin + width) / width;
            latched = true;
        }

        /**
         * Remembers which lane or ring a pick came from, so work behind it only comes up when nothing is ahead.
         */
        public void picked(long key) {
            currentRank = (int) ((key % BEHIND) / STEP);
        }

        /**
         * The sort key of a column, lower is worked first. Columns outside the area are treated as its nearest edge.
         *
         * @param bestKey the lowest key found so far, a key that can't beat it may be returned without being worked out
         */
        public long key(int x, int z, long bestKey) {
            if (!active()) return 0;
            return pattern == Rows ? rowKey(x, z) : spiralKey(x, z, bestKey);
        }

        private long rowKey(int x, int z) {
            int along = alongX ? x : z;
            int perp = alongX ? z : x;

            int offset = perpSign > 0 ? perp - perpMin : perpMax - perp;
            int rank = clamp(offset / width, 0, lanes - 1);

            int span = alongMax - alongMin;
            int forward = clamp(alongSign > 0 ? along - alongMin : alongMax - along, 0, span);
            // Every other lane is walked back the other way
            if ((rank & 1) == 1) forward = span - forward;

            return withRank(rank, forward);
        }

        private long spiralKey(int x, int z, long bestKey) {
            int dx = x - homeX;
            int dz = z - homeZ;

            int band = Math.min(Math.max(Math.abs(dx), Math.abs(dz)) / width, bands - 1);
            int rank = pattern == SpiralOut ? band : bands - 1 - band;

            // The angle is only worth working out if the ring can still win
            long base = withRank(rank, 0);
            if (base > bestKey) return base;

            // The little extra keeps the column straight ahead from wrapping round to the end of the ring
            double angle = Math.atan2(dx, -dz) - angleStart + 0.02;
            angle %= Math.PI * 2;
            if (angle < 0) angle += Math.PI * 2;

            return base + (int) (angle / (Math.PI * 2) * (STEP - 1));
        }

        private long withRank(int rank, int progress) {
            return (rank < currentRank ? BEHIND : 0) + (long) rank * STEP + progress;
        }

        private static int clamp(int value, int min, int max) {
            return Math.max(min, Math.min(max, value));
        }
    }
}
