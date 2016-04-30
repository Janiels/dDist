package com.tma.exercises;

import javax.swing.*;
import java.awt.EventQueue;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.util.ArrayList;

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
    private final ArrayList<MyTextEvent> events = new ArrayList<>();

    public EventReplayer(DocumentEventCapturer dec, JTextArea area, DistributedTextEditor editor) {
        this.dec = dec;
        this.area = area;
        this.editor = editor;
    }

    private void acceptFromPeer(Socket peer) {
        try (ObjectInputStream in = new ObjectInputStream(peer.getInputStream())) {
            while (true) {
                MyTextEvent event = (MyTextEvent) in.readObject();
                EventQueue.invokeLater(() -> {
                    dec.setEnabled(false);
                    try {
                        // If we're a client then save the last event's sequence number
                        // we have seen.
                        if (!dec.isServer())
                            dec.setSequence(event.getSequence());
                        else
                            fixEvent(event);

                        event.perform(area);
                    } catch (Exception e) {
                        System.err.println(e);
                        // We catch all exceptions, as an uncaught exception would make the
                        // EDT unwind, which is not healthy.
                    } finally {
                        dec.setEnabled(true);
                    }
                });
            }
        } catch (IOException | ClassNotFoundException e) {
            disconnectPeer();
        }
    }

    // Fix an event if the client had a different picture of the text view
    // when it made the event, than the one the server has right now.
    private void fixEvent(MyTextEvent event) {
        int clientSeenSequence = event.getSequence();
        ArrayList<MyTextEvent> events = dec.getCurrentlyAppliedEventsAfter(clientSeenSequence);
        dec.deleteEventsBefore(clientSeenSequence);

        for (MyTextEvent appliedEvent : events) {
            appliedEvent.fixUnseenEvent(event);
        }
    }

    private void sendToPeer(Socket peer) {
        try (ObjectOutputStream out = new ObjectOutputStream(peer.getOutputStream())) {
            while (true) {
                MyTextEvent event = dec.take();
                out.writeObject(event);
            }
        } catch (IOException | InterruptedException e) {
            // Socket is closed by receiver
        }
    }

    public void setPeer(Socket peer) {
        // Do not send old messages that weren't sent out already.
        dec.eventHistory.clear();
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
