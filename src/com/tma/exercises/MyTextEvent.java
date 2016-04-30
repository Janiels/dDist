package com.tma.exercises;

import javax.swing.*;
import java.io.Serializable;

/**
 * @author Jesper Buus Nielsen
 */
public abstract class MyTextEvent implements Serializable {
    private int offset;

    MyTextEvent(int offset) {
        this.offset = offset;
    }

    int getOffset() {
        return offset;
    }

    abstract void perform(JTextArea area);
}
