package com.tma.exercises;

import javax.swing.JTextArea;
import java.awt.EventQueue;

/**
 * Takes the event recorded by the DocumentEventCapturer and replays
 * them in a JTextArea. The delay of 1 sec is only to make the individual
 * steps in the reply visible to humans.
 *
 * @author Jesper Buus Nielsen
 */
public class EventReplayer implements Runnable {

    private DocumentEventCapturer dec;
    private JTextArea area;

    public EventReplayer(DocumentEventCapturer dec, JTextArea area) {
        this.dec = dec;
        this.area = area;
    }

    public void run() {
        boolean wasInterrupted = false;
        while (!wasInterrupted) {
            try {
                Thread.sleep(1000);
                final MyTextEvent mte = dec.take();
                EventQueue.invokeLater(() -> {
                    try {
                        mte.perform(area);
                    } catch (Exception e) {
                        System.err.println(e);
                        // We catch all exceptions, as an uncaught exception would make the
                        // EDT unwind, which is not healthy.
                    }
                });
            } catch (Exception _) {
                wasInterrupted = true;
            }
        }
        System.out.println("I'm the thread running the EventReplayer, now I die!");
    }

}
