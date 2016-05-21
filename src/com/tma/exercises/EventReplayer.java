package com.tma.exercises;

import javax.swing.*;
import java.awt.EventQueue;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.net.Socket;
import java.util.ArrayList;
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
//                    dec.clocksReceived(welcome.getClocks());
//                    dec.setEnabled(false);
//                    try {
//                        area.setText(welcome.getText());
//                    } finally {
//                        dec.setEnabled(true);
//                    }
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
        ArrayList<MyTextEvent> events = dec.popEvents();

        // Roll back everything
        for (MyTextEvent event : events) {
            event.setAdjustOffset(0);
        }

        area.setText("");

        // Add our new event
        events.add(newEvent);

        // Create peer-local lists of events
        OptionalInt maxIndex = events.stream().mapToInt(MyTextEvent::getSourceIndex).max();
        ArrayList<ArrayList<MyTextEvent>> lists = new ArrayList<>();
        for (int i = 0; i <= maxIndex.getAsInt(); i++)
            lists.add(new ArrayList<>());

        for (MyTextEvent eventToRedo : events) {
            lists.get(eventToRedo.getSourceIndex()).add(eventToRedo);
        }

        // Now merge events into text field.
        // Keep track of index into each peer's events.
        int[] listIndices = new int[lists.size()];
        ArrayList<MyTextEvent> performed = new ArrayList<>();
        while (true) {
            // If any of peer's current events happened first among the events
            // then do that.
            MyTextEvent first = findUnconcurrentEvent(lists, listIndices);
            if (first != null) {
                redoEvent(first, performed);
                listIndices[first.getSourceIndex()]++;
                continue;
            }

            // No event happened first, so all current events are concurrent.
            MyTextEvent concurrent = findConcurrentEvent(lists, listIndices);
            // Do we have anymore events?
            if (concurrent == null)
                break;

            redoEvent(concurrent, performed);
            listIndices[concurrent.getSourceIndex()]++;
        }

        for (MyTextEvent event : performed)
            dec.insertAppliedEvent(event);
    }

    private MyTextEvent findUnconcurrentEvent(ArrayList<ArrayList<MyTextEvent>> lists, int[] listIndices) {
        for (int i = 0; i < lists.size(); i++) {
            if (listIndices[i] >= lists.get(i).size())
                continue;

            MyTextEvent event = lists.get(i).get(listIndices[i]);
            boolean concurrent = false;
            for (int j = 0; j < lists.size(); j++) {
                if (i == j || listIndices[j] >= lists.get(j).size())
                    continue;

                if (!event.happenedBefore(lists.get(j).get(listIndices[j]))) {
                    concurrent = true;
                    break;
                }
            }

            if (!concurrent)
                return event;
        }

        return null;
    }

    private MyTextEvent findConcurrentEvent(ArrayList<ArrayList<MyTextEvent>> lists, int[] listIndices) {
        for (int i = 0; i < listIndices.length; i++) {
            if (listIndices[i] >= lists.get(i).size())
                continue;

            return lists.get(i).get(listIndices[i]);
        }

        return null;
    }

    private void redoEvent(MyTextEvent myTextEvent, ArrayList<MyTextEvent> performed) {
        MyTextEvent event = myTextEvent;
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

            adjustOffset += performedEvent.getAdjustOffset(event.getOffset() + adjustOffset);
        }

        if (!skip) {
            event.setAdjustOffset(adjustOffset);
            System.out.println("Reapply: " + event.toString());
            event.perform(area);
            performed.add(event);
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
        EventQueue.invokeLater(() -> {
            synchronized (peers) {
                int index = peers.size() + 1;

                Peer peer;
                try {
                    peer = new Peer(socket, index);
                } catch (IOException ignored) {
                    return;
                }

                for (MyTextEvent event : dec.getEvents())
                    try {
                        peer.send(event);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }

                try {
                    peer.send(new Welcome(index));
                } catch (IOException e) {
                    e.printStackTrace();
                }

                peers.add(peer);

                new Thread(() -> acceptFromPeer(peer, false)).start();
            }
        });
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
