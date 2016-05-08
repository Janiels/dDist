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
                // Reverse vector clocks. On both sides we keep our own local
                // clock in clocks[0], so the received one is reversed from our perspective.
                int[] clocks = event.getClocks();
                int temp = clocks[0];
                clocks[0] = clocks[1];
                clocks[1] = temp;

                System.out.println("Received: " + event);
                EventQueue.invokeLater(() -> {
                    // Make sure later events are timestamped correctly according
                    // to this received one..
                    dec.clocksReceived(clocks);

                    // Do not capture the events we make when inserting
                    // other side's events
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
                });
            }
        } catch (IOException | ClassNotFoundException e) {
            disconnectPeer();
        }
    }

    private void performEvent(MyTextEvent event) {
        // Get the events that are concurrent or happened after.
        ArrayList<MyTextEvent> events = dec.popEventsAfter(event);

        // Rollback...
        for (MyTextEvent appliedEvent : events) {
            System.out.println("Undoing: " + appliedEvent);
            appliedEvent.undo(area);
        }

        // Add our new event, and order them all consistently.
        // The key point here is that both sides get the same ordering --
        // this ensures the text does not get desynchronized.
        events.add(event);

        // Find first events for both sides.
        MyTextEvent firstClient = null, firstServer = null;
        for (MyTextEvent e : events) {
            if (e.isFromServer() && (firstServer == null || e.happenedBefore(firstServer)))
                firstServer = e;
            else if (!e.isFromServer() && (firstClient == null || e.happenedBefore(firstClient)))
                firstClient = e;
        }

        MyTextEvent finalFirstServer = firstServer;
        MyTextEvent finalFirstClient = firstClient;
        Collections.sort(events, (o1, o2) -> {
            // If one event happened before the other according to vector clocks
            // then apply it first!
            if (o1.happenedBefore(o2))
                return -1;
            if (o2.happenedBefore(o1))
                return 1;

            // Events are concurrent.
            // When they are concurrent they shouldn't be from the same machine
            assert o1.isFromServer() == !o2.isFromServer();
            // Perform concurrent events backwards: this ensures that
            // offsets are not 'pushed'. However, we can not simply use
            // the offset of o1 and o2 as we could have this situation:
            // Client inserts a at 0 (c1), c at 2 (c2)
            // Server inserts c at 1 (s1)
            // The server event must come before the client's event due to a larger index
            // so s1 < c1. But c1 happens-before c2, so c1 < c2.
            // However c3 must come before s1 due to a larger index: so
            // c3 < s1 < c1 < c3. To resolve this we only use the offset from the first
            // event to determine the order of client/server events.
            int o1Offset = o1.isFromServer() ? finalFirstServer.getOffset() : finalFirstClient.getOffset();
            int o2Offset = o2.isFromServer() ? finalFirstServer.getOffset() : finalFirstClient.getOffset();
            if (o1Offset > o2Offset)
                return -1;
            if (o1Offset < o2Offset)
                return 1;

            // Concurrent removes/inserts at the same position.
            if (o1 instanceof TextInsertEvent && o2 instanceof TextInsertEvent) {
                // Two inserts at same position
                // Either order would be fine as long as it is consistent on both sides.
                // Use server's first.
                if (o1.isFromServer() && !o2.isFromServer())
                    return -1;
                if (o2.isFromServer() && !o1.isFromServer())
                    return 1;
            } else if (o1 instanceof TextInsertEvent) {
                // Remove and insert: Perform remove first to ensure we don't delete whatever
                // the other one just added. In this case o2 is remove,
                // so o1 > o2.
                return 1;
            } else if (o2 instanceof TextInsertEvent) {
                // o1 is remove, perform o1 first
                return -1;
            }

            // Two removes at same position: Ignore one of them, we handle this later
            return 0;
        });

        MyTextEvent previousEvent = null;
        for (MyTextEvent appliedEvent : events) {
            if (previousEvent != null && !previousEvent.happenedBefore(appliedEvent)
                    && previousEvent.getOffset() == appliedEvent.getOffset()
                    && previousEvent instanceof TextRemoveEvent
                    && appliedEvent instanceof TextRemoveEvent) {
                // Ignore one of the concurrent removes at the same location
                System.out.println("Ignoring duplicate concurrent remove: " + appliedEvent);
                continue;
            }

            previousEvent = appliedEvent;
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
        // Clear the event capturer as it might have events for old clients
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
