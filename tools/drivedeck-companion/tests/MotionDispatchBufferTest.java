package ru.drivedeck.headswap;

import com.headswap.desk.shared.MotionDispatchBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;

public final class MotionDispatchBufferTest {
    public static void main(String[] args) {
        ArrayDeque<Runnable> pending = new ArrayDeque<>();
        ArrayList<String> delivered = new ArrayList<>();
        MotionDispatchBuffer buffer = new MotionDispatchBuffer(pending::add);
        buffer.submit(null, () -> delivered.add("down"));
        for (int i = 0; i < 10000; i++) {
            final int at = i;
            buffer.submit("lease/geometry/pointer0", () -> delivered.add("move" + at));
        }
        buffer.submit(null, () -> delivered.add("pointerDown"));
        buffer.submit("lease/geometry/pointer0,1", () -> delivered.add("pinch"));
        buffer.submit(null, () -> delivered.add("cancel"));
        if (pending.size() != 5) throw new AssertionError("Unbounded MOVE backlog: " + pending.size());
        while (!pending.isEmpty()) pending.removeFirst().run();
        if (!delivered.equals(Arrays.asList("down", "move9999", "pointerDown", "pinch", "cancel")))
            throw new AssertionError(delivered.toString());
        if (buffer.coalescedCount() != 9999) throw new AssertionError("Incorrect coalescing count");
        System.out.println("MotionDispatchBuffer: slow-consumer backlog, latest position, pointer/cancel ordering PASS");
    }
}
