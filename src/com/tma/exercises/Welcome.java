package com.tma.exercises;

import java.io.Serializable;

public class Welcome implements Serializable {
    private String text;
    private int index;
    private int[] clocks;

    public Welcome(String text, int index, int[] clocks) {
        this.text = text;
        this.index = index;
        this.clocks = clocks;
    }

    public String getText() {
        return text;
    }

    public int getIndex() {
        return index;
    }

    public int[] getClocks() {
        return clocks;
    }
}
