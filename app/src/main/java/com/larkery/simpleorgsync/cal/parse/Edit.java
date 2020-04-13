package com.larkery.simpleorgsync.cal.parse;

import java.util.Collections;
import java.util.List;

public class Edit implements Comparable<Edit> {
    private final int order;
    private int offset;
    private int length;
    private String insert;

    public Edit(int order, int offset, int length, String insert) {
        this.order = order;
        this.offset = offset;
        this.length = length;
        this.insert = insert;
    }

    @Override
    public int compareTo(Edit edit) {
        int byOffset = Integer.compare(offset, edit.offset);
        if (byOffset != 0) return byOffset;
        return Integer.compare(order, edit.order);
    }

    public static StringBuffer apply(final List<Edit> edits, final StringBuffer buffer) {
        if (edits.isEmpty()) return buffer;

        final StringBuffer out = new StringBuffer(buffer.length());

        Collections.sort(edits);

        int offset = 0;

        for (final Edit e : edits) {
            out.append(buffer.subSequence(offset, e.offset));
            out.append(e.insert);
            offset = e.offset + e.length;
        }

        // copy the end
        out.append(buffer.subSequence(offset, buffer.length()));

        return out;
    }

    public static void replace(Tokenizer.Item<?> item, int group, String replace, final List<Edit> edits) {
        if (!replace.equals(item.groups[group])) {
            edits.add(new Edit(edits.size(), item.starts[group], item.ends[group] - item.starts[group], replace));
        }
    }

    public static void insert(int offset, String format, List<Edit> edits) {
        if (format.isEmpty()) return;
        edits.add(new Edit(edits.size(), offset, 0, format));
    }

    @Override
    public String toString() {
        return "Edit{" +
                "order=" + order +
                ", offset=" + offset +
                ", length=" + length +
                ", insert='" + insert + '\'' +
                '}';
    }
}
