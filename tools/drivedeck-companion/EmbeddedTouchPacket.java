package ru.drivedeck.headswap;

/** A detached snapshot of coordinates that v7 already transformed into child-buffer pixels. */
public final class EmbeddedTouchPacket {
    public final int action, actionIndex;
    public final int[] ids;
    public final float[] xs, ys;
    private EmbeddedTouchPacket(int action, int index, int[] ids, float[] xs, float[] ys) {
        this.action = action; this.actionIndex = index;
        this.ids = ids.clone(); this.xs = xs.clone(); this.ys = ys.clone();
    }
    public static EmbeddedTouchPacket snapshot(int action, int index, int[] ids, float[] xs, float[] ys,
                                               int width, int height) {
        if (ids == null || xs == null || ys == null || ids.length < 1 || ids.length > 2 ||
                xs.length != ids.length || ys.length != ids.length || index < 0 || index >= ids.length ||
                width <= 0 || height <= 0) return null;
        if (action != 0 && action != 1 && action != 2 && action != 3 && action != 5 && action != 6) return null;
        if ((action == 0 || action == 1) && ids.length != 1 || (action == 5 || action == 6) && ids.length != 2) return null;
        float[] x = xs.clone(), y = ys.clone();
        for (int i = 0; i < ids.length; i++) {
            if (ids[i] < 0 || ids[i] > 31 || !Float.isFinite(x[i]) || !Float.isFinite(y[i])) return null;
            if (i > 0 && ids[0] == ids[i]) return null;
            x[i] = Math.max(0, Math.min(width - 1, x[i]));
            y[i] = Math.max(0, Math.min(height - 1, y[i]));
        }
        return new EmbeddedTouchPacket(action, index, ids, x, y);
    }
    public EmbeddedTouchPacket cancel() { return new EmbeddedTouchPacket(3, 0, ids, xs, ys); }
}
