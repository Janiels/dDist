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
    private final ArrayList<String> serverPeerEndPoints = new ArrayList<>();

    public EventReplayer(DocumentEventCapturer dec, JTextArea area, DistributedTextEditor editor) {
        this.dec = dec;
        this.area = area;
        this.editor = editor;
        send = new Thread(() -> sendToPeers());
        send.start();
    }

    private void acceptFromPeer(Peer peer, boolean isClient) {
        try {
            if (isClient) {
                Welcome welcome = (Welcome) peer.receive();
                System.out.println("Received welcome! My index is " + welcome.getIndex());
                EventQueue.invokeLater(() -> {
                    dec.setOurIndex(welcome.getIndex());
                });
            }

            while (true) {
                Object message = peer.receive();
                if (message instanceof PeerChange) {
                    PeerChange peerChange = (PeerChange) message;
                    if (isClient) {
                        if (peerChange.isConnected()) {
                            serverPeerEndPoints.add(peerChange.getEndPoint());
                            System.out.println("Added peer end point '" + peerChange.getEndPoint() + "'");
                        } else {
                            boolean removed = serverPeerEndPoints.remove(peerChange.getEndPoint());
                            System.out.println("Removed peer '" + peerChange.getEndPoint() + "': " + removed);
                        }
                    } else {
                        // Server distributes peer IPs to other peers
                        assert peerChange.isConnected();

                        System.out.println("Client '" + peer.getIp() + ":" + peer.getPort() + "' is listening on '" + peerChange.getEndPoint() + "'");
                        peer.setListenEndPoint(peerChange.getEndPoint());

                        synchronized (peers) {
                            for (Peer otherPeer : peers) {
                                if (otherPeer != peer)
                                    otherPeer.send(peerChange);
                            }
                        }
                    }
                } else {
                    MyTextEvent event = (MyTextEvent) message;
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
            }
        } catch (IOException ex) {
            ex.printStackTrace();

            try {
                peer.close();
            } catch (IOException e) {
                e.printStackTrace();
            }

            if (isClient) {
                serverPeerEndPoints.sort((s1, s2) -> s1.compareTo(s2));
                if (serverPeerEndPoints.size() > 0)
                    editor.reconnect(serverPeerEndPoints.get(0));
            } else {
                synchronized (peers) {
                    peers.remove(peer);

                    for (Peer otherPeer : peers) {
                        try {
                            otherPeer.send(new PeerChange(false, peer.getListenEndPoint()));
                        } catch (IOException ignored) {
                        }
                    }
                }
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

        StringBuilder text = new StringBuilder("");

        // Add our new event
        events.add(newEvent);

        // Create peer-local lists of events in the correct peer order.
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
            // Find the earliest event of all peer events
            MyTextEvent first = findEarliestEvent(lists, listIndices);
            if (first == null)
                break;

            redoEvent(text, first, performed);
            listIndices[first.getSourceIndex()]++;
        }

        for (MyTextEvent event : performed)
            dec.insertAppliedEvent(event);

        int selectStart = area.getSelectionStart();
        int selectEnd = area.getSelectionEnd();
        area.setText(text.toString());

        area.setCaretPosition(selectEnd + newEvent.getAdjustOffset(selectEnd));
        area.moveCaretPosition(selectStart + newEvent.getAdjustOffset(selectStart));
    }

    private MyTextEvent findEarliestEvent(ArrayList<ArrayList<MyTextEvent>> lists, int[] listIndices) {
        MyTextEvent earliest = null;
        for (int i = 0; i < listIndices.length; i++) {
            if (listIndices[i] >= lists.get(i).size())
                continue;

            MyTextEvent event = lists.get(i).get(listIndices[i]);
            if (earliest == null || event.happenedBefore(earliest))
                earliest = event;
        }

        return earliest;
    }

    private void redoEvent(StringBuilder text, MyTextEvent event, ArrayList<MyTextEvent> performed) {
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
            event.perform(text);
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

    public void addPeer(Peer peer) {
        EventQueue.invokeLater(() -> {
            synchronized (peers) {
                int index = peers.size() + 1;

                peer.setIndex(index);

                try {
                    peer.send(new RedirectPeer(false, null, 0));
                    peer.send(new Welcome(index));

                    for (Peer otherPeer : peers) {
                        String listenEndPoint = otherPeer.getListenEndPoint();
                        if (listenEndPoint != null)
                            peer.send(new PeerChange(true, listenEndPoint));
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }

                for (MyTextEvent event : dec.getEvents())
                    try {
                        peer.send(event);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }

                peers.add(peer);

                new Thread(() -> acceptFromPeer(peer, false)).start();
            }
        });
    }

    public void setServer(Peer server) {
        // Server has index 0
        server.setIndex(0);

        synchronized (peers) {
            peers.add(server);
        }

        new Thread(() -> acceptFromPeer(server, true)).start();
    }

    public void disconnect() {
        synchronized (peers) {
            for (Peer peer : peers) {
                try {
                    peer.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }

            serverPeerEndPoints.clear();
            peers.clear();
            dec.clear();
        }

        editor.setDisconnected();
    }

    public void setListenEndPoint(String listenEndPoint) {
        serverPeerEndPoints.add(listenEndPoint);
        // Tell server the address we are listening on
        synchronized (peers) {
            // Should only be called for clients
            assert peers.size() == 1;
            for (Peer peer : peers) {
                try {
                    peer.send(new PeerChange(true, listenEndPoint));
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }
}
