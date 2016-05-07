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
    private boolean fromServer;

    int getOffset() {
        return offset;
    }

    public void setOffset(int offset) {
        this.offset = offset;
    }

    public int[] getClocks() {
        return clocks;
    }

    public void setClocks(int[] clocks) {
        this.clocks = clocks;
    }

    public boolean happenedBefore(MyTextEvent other) {
        if (this.clocks[0] > other.clocks[0])
            return false;
        if (this.clocks[1] > other.clocks[1])
            return false;

        return this.clocks[0] < other.clocks[0] || this.clocks[1] < other.clocks[1];
    }

    public boolean isFromServer() {
        return fromServer;
    }

    public void setFromServer(boolean fromServer) {
        this.fromServer = fromServer;
    }

    abstract void perform(JTextArea area);

    abstract void undo(JTextArea area);
}
