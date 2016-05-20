package com.tma.exercises;

import java.util.ArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DocumentFilter;

/**
 * This class captures and remembers the text events of the given document on
 * which it is put as a filter. Normally a filter is used to put restrictions
 * on what can be written in a buffer. In out case we just use it to see all
 * the events and make a copy.
 *
 * @author Jesper Buus Nielsen
 */
public class DocumentEventCapturer extends DocumentFilter {
    private boolean enabled = true;
    private int[] clocks = new int[2];
    private final ArrayList<MyTextEvent> events = new ArrayList<>();

    // We are using a blocking queue for two reasons:
    // 1) They are thread safe, i.e., we can have two threads add and take elements
    //    at the same time without any race conditions, so we do not have to do
    //    explicit synchronization.
    // 2) It gives us a member take() which is blocking, i.e., if the queue is
    //    empty, then take() will wait until new elements arrive, which is what
    //    we want, as we then don't need to keep asking until there are new elements.
    protected LinkedBlockingQueue<MyTextEvent> eventHistory = new LinkedBlockingQueue<MyTextEvent>();
    private String ip;

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    private void incrementOurClock() {
        clocks[0]++;
    }

    /**
     * If the queue is empty, then the call will block until an element arrives.
     * If the thread gets interrupted while waiting, we throw InterruptedException.
     *
     * @return Head of the recorded event queue.
     */
    MyTextEvent take() throws InterruptedException {
        return eventHistory.take();
    }

    public void insertString(FilterBypass fb, int offset,
                             String str, AttributeSet a)
            throws BadLocationException {

        // Queue a copy of the event and then modify the textarea
        insertEvent(new TextInsertEvent(offset, str));

        super.insertString(fb, offset, str, a);
    }

    public void remove(FilterBypass fb, int offset, int length)
            throws BadLocationException {
        String text = fb.getDocument().getText(offset, length);
        super.remove(fb, offset, length);
        insertEvent(new TextRemoveEvent(offset, length, text));
    }

    public void replace(FilterBypass fb, int offset,
                        int length,
                        String str, AttributeSet a)
            throws BadLocationException {

        String text = fb.getDocument().getText(offset, length);
        super.replace(fb, offset, length, str, a);
        // Queue a copy of the event and then modify the text
        if (length > 0) {
            insertEvent(new TextRemoveEvent(offset, length, text));
        }
        insertEvent(new TextInsertEvent(offset, str));
    }

    private void insertEvent(MyTextEvent e) {
        if (enabled) {
            e.setSource(ip);
            incrementOurClock();
            e.setClocks(clocks.clone());
            // Queue a copy of the event and then modify the textarea
            eventHistory.add(e);
            events.add(e);
        }
    }

    // Remove all events that did not happen before 'other' and
    // return them in the reverse order of the one they were performed in.
    ArrayList<MyTextEvent> popEventsAfter(MyTextEvent other) {
        ArrayList<MyTextEvent> after = new ArrayList<>();

        // Find first event that happened concurrently with 'other'
        // or after 'other'
        int first;
        for (first = 0; first < events.size(); first++) {
            if (!events.get(first).happenedBefore(other))
                break;
        }

        if (first == events.size())
            return after;

        for (int i = events.size() - 1; i >= first; i--) {
            MyTextEvent event = events.get(i);
            after.add(event);
            events.remove(i);
        }

        return after;
    }

    void deleteEventsBefore(MyTextEvent other) {
        events.removeIf(e -> e.happenedBefore(other));
    }

    public void clear() {
        // Reset vector clocks for new events
        clocks[0] = 0;
        clocks[1] = 0;
        // There might be old events from old clients, so clear those too
        eventHistory.clear();
        events.clear();
    }

    public void clocksReceived(int[] clocks) {
        this.clocks[0] = Math.max(clocks[0], this.clocks[0]);
        this.clocks[1] = Math.max(clocks[1], this.clocks[1]);
        incrementOurClock();
    }

    public void insertAppliedEvent(MyTextEvent event) {
        events.add(event);
    }

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }
}
