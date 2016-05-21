package com.tma.exercises;

import javax.swing.*;
import java.awt.EventQueue;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.OptionalInt;

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
    private final ArrayList<Peer> peers = new ArrayList<>();
    private Thread send;

    public EventReplayer(DocumentEventCapturer dec, JTextArea area, DistributedTextEditor editor) {
        this.dec = dec;
        this.area = area;
        this.editor = editor;
        send = new Thread(() -> sendToPeers());
        send.start();
    }

    private void acceptFromPeer(Peer peer, boolean isClient) {
        try (ObjectInputStream in = new ObjectInputStream(peer.getSocket().getInputStream())) {
            if (isClient) {
                Welcome welcome = (Welcome) in.readObject();
                System.out.println("Received welcome! My index is " + welcome.getIndex());
                EventQueue.invokeLater(() -> {
                    dec.setOurIndex(welcome.getIndex());
                    dec.clocksReceived(welcome.getClocks());
                    dec.setEnabled(false);
                    try {
                        area.setText(welcome.getText());
                    } finally {
                        dec.setEnabled(true);
                    }
                });
            }

            while (true) {
                MyTextEvent event = (MyTextEvent) in.readObject();
                EventQueue.invokeLater(() -> {
                    System.out.println("Receive: " + event);
                    // Make sure later events are timestamped correctly according
                    // to this received one..
                    dec.clocksReceived(event.getClocks());

                    // If we're the server then send this event out to all
                    // other peers
                    if (!isClient) {
                        try {
                            dec.eventHistory.put(event);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }

                    // Do not capture received events again
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

                    System.out.println("");
                });
            }
        } catch (IOException | ClassNotFoundException e) {
            try {
                peer.close();
            } catch (IOException e1) {
                e1.printStackTrace();
            }
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
        OptionalInt maxIndex = events.stream().mapToInt(MyTextEvent::getSourceIndex).max();
        assert maxIndex.isPresent();

        MyTextEvent[] firstEvents = new MyTextEvent[maxIndex.getAsInt() + 1];

        for (MyTextEvent e : events) {
            if (firstEvents[e.getSourceIndex()] == null || e.happenedBefore(firstEvents[e.getSourceIndex()]))
                firstEvents[e.getSourceIndex()] = e;
        }

        Collections.sort(events, (o1, o2) -> {
            // If one event happened before the other according to vector clocks
            // then apply it first!
            if (o1.happenedBefore(o2))
                return -1;
            if (o2.happenedBefore(o1))
                return 1;

            // Events are concurrent.
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
            int o1Offset = firstEvents[o1.getSourceIndex()].getOffset();
            int o2Offset = firstEvents[o2.getSourceIndex()].getOffset();
            if (o1Offset > o2Offset)
                return -1;
            if (o1Offset < o2Offset)
                return 1;

            // Concurrent removes/inserts at the same position.
            if (o1 instanceof TextInsertEvent && o2 instanceof TextInsertEvent) {
                // Two inserts at same position
                // Use lower index first.
                if (o1.getSourceIndex() < o2.getSourceIndex())
                    return -1;

                assert o1.getSourceIndex() != o2.getSourceIndex();
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

        for (int i = 0; i < events.size(); i++) {
            MyTextEvent appliedEvent = events.get(i);
            boolean skip = false;
            for (int j = 0; j < i; j++) {
                MyTextEvent previousEvent = events.get(j);

                if (!previousEvent.happenedBefore(appliedEvent)
                        && previousEvent.getOffset() == appliedEvent.getOffset()
                        && previousEvent instanceof TextRemoveEvent
                        && appliedEvent instanceof TextRemoveEvent) {
                    // Ignore one of the concurrent removes at the same location
                    System.out.println("Ignoring duplicate concurrent remove: " + appliedEvent);
                    skip = true;
                    break;
                }
            }

            if (skip)
                continue;

            System.out.println("Reapply: " + appliedEvent);
            appliedEvent.perform(area);
            dec.insertAppliedEvent(appliedEvent);
        }
    }

    private void sendToPeers() {
        while (true) {
            MyTextEvent event;
            try {
                event = dec.take();
            } catch (InterruptedException e) {
                return;
            }

            System.out.println("Sending: " + event);
            synchronized (peers) {
                for (Peer peer : peers) {
                    // Don't send back to source peer
                    if (peer.getIndex() == event.getSourceIndex())
                        continue;

                    try {
                        peer.send(event);
                    } catch (IOException ignored) {
                    }
                }
            }
        }
    }

    public void addPeer(Socket socket) {
        int index = peers.size() + 1;

        Peer peer;
        try {
            peer = new Peer(socket, index);
        } catch (IOException ignored) {
            return;
        }

        synchronized (peers) {
            peers.add(peer);
        }

        try {
            peer.send(new Welcome(area.getText(), index, dec.getClocks()));
        } catch (IOException e) {
            e.printStackTrace();
        }

        new Thread(() -> acceptFromPeer(peer, false)).start();
    }

    public void setServer(Socket socket) {
        Peer server;
        try {
            // Server has index 0
            server = new Peer(socket, 0);
        } catch (IOException ignored) {
            return;
        }

        synchronized (peers) {
            peers.add(server);
        }

        new Thread(() -> acceptFromPeer(server, true)).start();
    }

    public void disconnect() {
        for (Peer peer : peers) {
            try {
                peer.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        send.interrupt();
        editor.setDisconnected();
    }
}
