package com.tma.exercises;

import javax.swing.*;
import java.io.Serializable;

/**
 * @author Jesper Buus Nielsen
 */
public abstract class MyTextEvent implements Serializable {
    MyTextEvent(int offset) {
        this.offset = offset;
    }

    private int offset;
    private int[] clocks;
    private int sourceIndex;

    int getOffset() {
        return offset;
    }

    public int[] getClocks() {
        return clocks;
    }

    public void setClocks(int[] clocks) {
        this.clocks = clocks;
    }

    public boolean happenedBefore(MyTextEvent other) {
        int max = Math.max(this.clocks.length, other.clocks.length);
        // If any of ours are larger, then we did not happen before
        for (int i = 0; i < max; i++) {
            int ourClock = i >= this.clocks.length ? 0 : this.clocks[i];
            int otherClock = i >= other.clocks.length ? 0 : other.clocks[i];
            if (ourClock > otherClock)
                return false;
        }

        // If any of ours is smaller, then we happened before
        for (int i = 0; i < max; i++) {
            int ourClock = i >= this.clocks.length ? 0 : this.clocks[i];
            int otherClock = i >= other.clocks.length ? 0 : other.clocks[i];
            if (ourClock < otherClock)
                return true;
        }

        return false;
    }

    public int getSourceIndex() {
        return sourceIndex;
    }

    public void setSourceIndex(int sourceIndex) {
        this.sourceIndex = sourceIndex;
    }

    abstract void perform(JTextArea area);

    abstract void undo(JTextArea area);

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("offset = " + offset + ", source sourceIndex = " + sourceIndex);
        sb.append(", clocks: ");
        boolean first = true;
        for (int clock : clocks) {
            if (!first)
                sb.append(", ");

            sb.append(clock);
            first = false;
        }

        return sb.toString();
    }
}
