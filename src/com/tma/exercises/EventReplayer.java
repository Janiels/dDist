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

    private void performEvent(MyTextEvent newEvent) {
        // Get the events that are concurrent or happened after.
        ArrayList<MyTextEvent> events = dec.popEventsAfter(newEvent);

        for (MyTextEvent event : events)
                event.setAdjustOffset(0);

        // Rollback...
//        for (MyTextEvent appliedEvent : events) {
//            System.out.println("Undoing: " + appliedEvent);
//            appliedEvent.undo(area);
//        }

        area.setText("");

        // Add our new event, and order them all consistently.
        // The key point here is that both sides get the same ordering --
        // this ensures the text does not get desynchronized.
        events.add(newEvent);

        OptionalInt maxIndex = events.stream().mapToInt(MyTextEvent::getSourceIndex).max();
        ArrayList<ArrayList<MyTextEvent>> lists = new ArrayList<>();
        for (int i = 0; i <= maxIndex.getAsInt(); i++)
            lists.add(new ArrayList<>());

        for (MyTextEvent eventToRedo : events) {
            lists.get(eventToRedo.getSourceIndex()).add(eventToRedo);
        }

        int[] listIndices = new int[lists.size()];

        ArrayList<MyTextEvent> performed = new ArrayList<>();
        while (true) {
            MyTextEvent unconcurrent = findUnconcurrentEvent(lists, listIndices);

            if (unconcurrent != null) {
                System.out.println("Reapply: " + unconcurrent);
                unconcurrent.perform(area);
                performed.add(unconcurrent);
                listIndices[unconcurrent.getSourceIndex()]++;
                continue;
            }

            // All events are concurrent. Add them and adjust indices.
            if (!performConcurrentEvents(lists, listIndices, performed))
                break;
        }

        for (MyTextEvent event : performed)
            dec.insertAppliedEvent(event);
    }

    private boolean performConcurrentEvents(ArrayList<ArrayList<MyTextEvent>> lists, int[] listIndices, ArrayList<MyTextEvent> performed) {
        boolean any = false;
        for (int i = 0; i < listIndices.length; i++) {
            if (listIndices[i] >= lists.get(i).size())
                continue;

            any = true;

            MyTextEvent event = lists.get(i).get(listIndices[i]);
            boolean skip = false;
            int adjustOffset = 0;
            for (int j = 0; j < performed.size(); j++) {
                MyTextEvent performedEvent = performed.get(j);
                if (performedEvent.happenedBefore(event))
                    continue;

                if (performedEvent instanceof TextRemoveEvent
                        && event instanceof TextRemoveEvent
                        && performedEvent.getOffset() + performedEvent.getAdjustOffset() == event.getOffset() + event.getAdjustOffset()) {
                    System.out.println("Ignoring duplicate concurrent remove: " + event);
                    skip = true;
                    break;
                }

                adjustOffset += performedEvent.getAdjustOffset(event.getOffset());
            }

            if (!skip) {
                event.setAdjustOffset(adjustOffset);
                System.out.println("Reapply: " + event.toString());
                event.perform(area);
                performed.add(event);
            }
            listIndices[i]++;
            break;
        }

        return any;
    }

    private MyTextEvent findUnconcurrentEvent(ArrayList<ArrayList<MyTextEvent>> lists, int[] listIndices) {
        // If one event happened before the others, then start with that
        for (int i = 0; i < lists.size(); i++) {
            if (listIndices[i] >= lists.get(i).size())
                continue;

            MyTextEvent event = lists.get(i).get(listIndices[i]);
            boolean concurrent = false;
            for (int j = 0; j < lists.size(); j++) {
                if (i == j)
                    continue;

                for (int k = 0; k < listIndices[j]; k++) {
                    if (!event.happenedBefore(lists.get(j).get(k))) {
                        concurrent = true;
                        break;
                    }
                }

                if (concurrent)
                    break;
            }

            if (!concurrent)
                return event;
        }

        return null;
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
