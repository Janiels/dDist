package com.tma.exercises;

import javax.swing.*;

/**
 * @author Jesper Buus Nielsen
 */
public abstract class MyTextEvent {
    MyTextEvent(int offset) {
        this.offset = offset;
    }

    private int offset;

    int getOffset() {
        return offset;
    }

    abstract void perform(JTextArea area);
}
