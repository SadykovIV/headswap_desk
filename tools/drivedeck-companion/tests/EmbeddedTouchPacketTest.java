package ru.drivedeck.headswap;

public final class EmbeddedTouchPacketTest {
    private static void check(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] args) {
        int[] ids = {4, 9};
        float[] x = {200, 1200}, y = {400, 800};
        EmbeddedTouchPacket down = EmbeddedTouchPacket.snapshot(5, 1, ids, x, y, 1884, 1752);
        check(down != null && down.actionIndex == 1 && down.xs[1] == 1200);
        ids[1] = 7; x[1] = 0;
        check(down.ids[1] == 9 && down.xs[1] == 1200); // No recycled MotionEvent/array alias.
        EmbeddedTouchPacket move = EmbeddedTouchPacket.snapshot(2, 0, new int[]{4, 9},
                new float[]{-1, 3000}, new float[]{-5, 9000}, 1884, 1752);
        check(move != null && move.xs[0] == 0 && move.xs[1] == 1883 && move.ys[1] == 1751);
        check(down.cancel().action == 3 && down.cancel().ids.length == 2);
        check(EmbeddedTouchPacket.snapshot(5, 2, new int[]{1, 2}, new float[]{1, 2}, new float[]{1, 2}, 100, 100) == null);
        check(EmbeddedTouchPacket.snapshot(2, 0, new int[]{1, 1}, new float[]{1, 2}, new float[]{1, 2}, 100, 100) == null);
        check(EmbeddedTouchPacket.snapshot(2, 0, new int[]{1}, new float[]{Float.NaN}, new float[]{1}, 100, 100) == null);
        check(EmbeddedTouchPacket.snapshot(2, 0, new int[]{1, 2, 3}, new float[]{1, 2, 3}, new float[]{1, 2, 3}, 100, 100) == null);
        check(EmbeddedTouchPacket.snapshot(0, 0, new int[]{1, 2}, new float[]{1, 2}, new float[]{1, 2}, 100, 100) == null);
        check(EmbeddedTouchPacket.snapshot(1, 0, new int[]{9}, new float[]{1200}, new float[]{800}, 1884, 1752) != null);
        System.out.println("EmbeddedTouchPacket: pinch IDs/index, single transform, snapshots, bounds and malformed packets PASS");
    }
}
