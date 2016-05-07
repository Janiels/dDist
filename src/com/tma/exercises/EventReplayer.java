package com.tma.exercises;

import javax.swing.*;
import java.awt.EventQueue;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;

import static java.util.Collections.reverse;

/**
 * Takes the event recorded by the DocumentEventCapturer and replays
 * them in a JTextArea. The delay of 1 sec is only to make the individual
 * steps in the reply visible to humans.
 *
 * @author Jesper Buus Nielsen
 */
public class EventReplayer {

    private final DistributedTextEditor editor;
    private DocumentEventCapturer dec;
    private JTextArea area;
    private Socket peer;
    private Thread send;

    public EventReplayer(DocumentEventCapturer dec, JTextArea area, DistributedTextEditor editor) {
        this.dec = dec;
        this.area = area;
        this.editor = editor;
    }

    private void acceptFromPeer(Socket peer) {
        try (ObjectInputStream in = new ObjectInputStream(peer.getInputStream())) {
            while (true) {
                MyTextEvent event = (MyTextEvent) in.readObject();
                System.out.println("Received: " + event);
                EventQueue.invokeLater(() -> {
                    dec.setEnabled(false);
                    try {
                        performEvent(event);
                    } catch (Exception e) {
                        System.err.println(e);
                        // We catch all exceptions, as an uncaught exception would make the
                        // EDT unwind, which is not healthy.
                    } finally {
                        dec.setEnabled(true);
                    }

                    dec.setPeerSequence(event.getSequence());
                    dec.setSequence(event.getSequence() + 1);
                });
            }
        } catch (IOException | ClassNotFoundException e) {
            disconnectPeer();
        }
    }

    private void performEvent(MyTextEvent event) {
        ArrayList<MyTextEvent> events = dec.getCurrentlyAppliedEventsAfter(event.getSequence());

        Collections.reverse(events);
        for (MyTextEvent appliedEvent : events) {
            System.out.println("Undoing: " + appliedEvent);
            appliedEvent.undo(area);
        }

        event.perform(area);

        Collections.reverse(events);
        for (MyTextEvent appliedEvent : events) {
            System.out.println("Reapplying: " + appliedEvent);
            appliedEvent.perform(area);
        }
    }

    private void sendToPeer(Socket peer) {
        try (ObjectOutputStream out = new ObjectOutputStream(peer.getOutputStream())) {
            while (true) {
                MyTextEvent event = dec.take();
                System.out.println("Sending: " + event);
                out.writeObject(event);
            }
        } catch (IOException | InterruptedException e) {
            // Socket is closed by receiver
        }
    }

    public void setPeer(Socket peer) {
        // Do not send old messages that weren't sent out already.
        dec.clear();
        this.peer = peer;
        new Thread(() -> acceptFromPeer(peer)).start();
        send = new Thread(() -> sendToPeer(peer));
        send.start();
    }

    public void disconnectPeer() {
        if (peer == null) {
            return;
        }
        try {
            send.interrupt();
            editor.setDisconnected();
            peer.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
