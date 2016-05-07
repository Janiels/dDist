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
    private int peerSequence;

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

    public int getPeerSequence() {
        return peerSequence;
    }

    public void setPeerSequence(int peerSequence) {
        this.peerSequence = peerSequence;
    }

    abstract void perform(JTextArea area);
    abstract void undo(JTextArea area);

    public abstract void fixUnseenEvent(MyTextEvent event);
}
