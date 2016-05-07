package com.tma.exercises;

import javax.swing.*;
import java.awt.EventQueue;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;

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
                int[] clocks = event.getClocks();
                int temp = clocks[0];
                clocks[0] = clocks[1];
                clocks[1] = temp;

                System.out.println("Received: " + event);
                EventQueue.invokeLater(() -> {
                    dec.setEnabled(false);
                    dec.clocksReceived(clocks);
                    try {
                        performEvent(event);
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

    private void performEvent(MyTextEvent event) {
        ArrayList<MyTextEvent> events = dec.popEventsAfter(event);

        for (MyTextEvent appliedEvent : events) {
            System.out.println("Undoing: " + appliedEvent);
            appliedEvent.undo(area);
        }

        events.add(event);
        Collections.sort(events, (o1, o2) -> {
            if(o1.happenedBefore(o2))
                return -1;
            if(o2.happenedBefore(o1))
                return 1;

            if (o1 instanceof TextRemoveEvent && !(o2 instanceof TextRemoveEvent))
                return 1;
            if (o2 instanceof TextRemoveEvent && !(o1 instanceof TextRemoveEvent))
                return -1;

            if(o1.isFromServer() && !o2.isFromServer())
                return -1;
            if(o2.isFromServer() && !o1.isFromServer())
                return 1;
            return 0;
        });


        for (MyTextEvent appliedEvent : events) {
            System.out.println("Reapplying: " + appliedEvent);
            appliedEvent.perform(area);
            dec.insertAppliedEvent(appliedEvent);
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
