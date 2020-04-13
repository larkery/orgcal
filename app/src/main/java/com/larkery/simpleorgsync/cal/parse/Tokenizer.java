package com.larkery.simpleorgsync.cal.parse;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Created by hinton on 13/01/18.
 */

class Tokenizer<T> {
    static class Re<T> {
        public final int group;
        public final int subGroups;
        public final T tok;
        public final String pattern;

        public Re(int group, int subGroups, T tok, String pattern) {
            this.group = group;
            this.subGroups = subGroups;
            this.tok = tok;
            this.pattern = pattern;
        }
    }

    static class Item<T> {
        public final T tok;
        public final String[] groups;
        public final int[] starts;
        public final int[] ends;

        public Item(Re<T> re, Matcher m) {
            tok = re.tok;
            groups = new String[re.subGroups+1];
            starts = new int[re.subGroups+1];
            ends = new int[re.subGroups+1];
            for (int i = 0; i <= re.subGroups; i++) {
                groups[i] = m.group(i+re.group);
                starts[i] = m.start(i+re.group);
                ends[i] = m.end(i+re.group);
            }
        }

        @Override
        public String toString() {
            return String.format("<%s %s %d %d>", tok, groups[0], starts[0], ends[0]);
        }
    }

    final List<Re<T>> res = new LinkedList<>();
    int nextGroup = 1;

    public void add(final T tok, final String pattern) {
        int nGroups = Pattern.compile(pattern).matcher("").groupCount();

        res.add(new Re(nextGroup, nGroups, tok, pattern));
        nextGroup += nGroups+1;
    }

    public Iterable<Item<T>> items(final CharSequence in) {
        final Pattern pattern = getPattern();

        return new Iterable<Item<T>>() {
            @Override
            public Iterator<Item<T>> iterator() {
                final Matcher m = pattern.matcher(in);
                return new Iterator<Item<T>>() {
                    boolean found = m.find();

                    @Override
                    public boolean hasNext() {
                        return found;
                    }

                    @Override
                    public Item<T> next() {
                        if (found) {
                            for (final Re<T> re : res) {
                                if (m.group(re.group) != null) {
                                    final Item<T> out = new Item<>(re, m);

                                    found = m.find(m.end());

                                    return out;
                                }
                            }
                        }
                        throw new NoSuchElementException();
                    }

                    @Override
                    public void remove() {
                        throw new UnsupportedOperationException();
                    }
                };
            }
        };
    }

    private Pattern getPattern() {
        final StringBuffer sb = new StringBuffer();
        for (final Re<T> re : res) {
            if (sb.length() > 0) sb.append("|");
            sb.append("(");
            sb.append(re.pattern);
            sb.append(")");
        }
        return Pattern.compile(sb.toString(), Pattern.MULTILINE);
    }
}
