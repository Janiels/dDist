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
    private int sequence;

    int getOffset() {
        return offset;
    }

    public void setOffset(int offset) {
        this.offset = offset;
    }

    public int getSequence() {
        return sequence;
    }

    public void setSequence(int sequence) {
        this.sequence = sequence;
    }

    abstract void perform(JTextArea area);

    public abstract void fixUnseenEvent(MyTextEvent event);
}
