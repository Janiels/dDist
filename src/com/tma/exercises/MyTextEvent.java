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

    int getOffset() {
        return offset;
    }

    abstract void perform(JTextArea area);
}
