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

    abstract void perform(JTextArea area);
    abstract void undo(JTextArea area);

    public abstract void fixUnseenEvent(MyTextEvent event);
}
