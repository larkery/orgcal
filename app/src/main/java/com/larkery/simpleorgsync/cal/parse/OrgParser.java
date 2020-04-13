package com.larkery.simpleorgsync.cal.parse;


import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;

// [<\\[]([0-9]{4}-[0-9]{2}-[0-9]{2} ?[^\\]\\x{d}\\x{a}>]*?)[>\\]]|(<[0-9]+-[0-9]+-[0-9]+[^>\\x{a}]+?\\+[0-9]+[ymwd]>)|<%%\\([^>\\x{a}]+\\)>)?

public class OrgParser {
    static final String DATE_REGEX;

    static {
        final String L = "<";
        final String R = ">";
        final String YMD = "\\d{4}-\\d{2}-\\d{2}";
        final String HHMM = "\\d{2}:\\d{2}";
        final String REP = "\\+\\d+[wmdy]";

        DATE_REGEX =
                "(" + // group 2 = normal date
                        L +
                        "(" + YMD + ")(?: [A-Za-z]{3})?" + // group 3 = year month day
                        //groups 4, 5 start and end time of start date:
                        "(?: (" + HHMM + "(?:-(" + HHMM + "))?))?" + // optional time and optional end time
                        // group 6, repeat unit
                        "(?: (" + REP + "))?" + // optional repeater
                        R +

                        "(?:--" +
                        L + // don't really care about consistency with first part here
                        // group 7, end date
                        "(" + YMD + ")(?: [A-Za-z]{3})?" +
                        // group 8, end time
                        "(?: (" + HHMM + "))?" +
                        R +
                        ")?" +
                        ")" +
                        // group 9, s-expression form
                        "|(<%%\\([^>\\x{a}]+\\)>)"
        ;
    }

    public enum Token {
        //        1    1     2   2            3                   3
        HEADING("^(\\*+)(?: +(.*?))??(?:[ \t]+(:[A-Za-z0-9_@#%:]+:))?[ \t]*$"),
        DATETIME("(SCHEDULED: | DEADLINE: )?" + DATE_REGEX),
        BEGIN_PROPS("^[ \t]*:PROPERTIES:[ \t]*$"),
        PROPERTY("^ *:([A-Za-z0-9 _-]+): *(.+)$"), // a bit hacky
        END_PROPS("^[ \t]*:END:[ \t]*$"),
        FILETAGS("^#\\+FILETAGS: *(.+) *$"),
        CATEGORY("^#\\+CATEGORY: *(.+) *$")
        ;


        public final String pattern;

        Token(final String p) {
            this.pattern = p;
        }
    }

    public static final Tokenizer<Token> tokenizer;

    static {
        tokenizer = new Tokenizer<>();
        for (final Token t : Token.values()) {
            tokenizer.add(t, t.pattern);
        }
    }

    public static List<Heading> parse(final CharSequence input, final TimeZone zone, String category) {
        Heading thisHeading = null;
        boolean inProps = false;
        final LinkedList<Set<String>> inheritTags = new LinkedList<>();
        final List<Heading> headings = new ArrayList<>();
        final Set<String> filetags = new HashSet<>();

        for (Tokenizer.Item<Token> item : tokenizer.items(input)) {
            switch (item.tok) {
                case HEADING:
                    // start a new heading, which runs from the start
                    // of this heading to the start of the next one or EOF
                    int depth = item.groups[1].length();
                    while (depth <= inheritTags.size()) {
                        inheritTags.pop();
                    }
                    Heading newHeading = new Heading(item, inheritTags.peek(), category);

                    inheritTags.push(newHeading.getTags());

                    inProps = false;
                    if (newHeading.getHeading() == null) {
                        System.err.println("Invalid heading! " + item);
                    } else {
                        headings.add(newHeading);
                    }
                    thisHeading = newHeading;
                    break;
                case DATETIME:
                    if (thisHeading != null) {
                        thisHeading.addTimestamp(new Timestamp(zone, item));
                    }
                    break;
                case BEGIN_PROPS:
                    inProps = true;
                    break;
                case END_PROPS:
                    inProps = false;
                    break;
                case PROPERTY:
                    if (thisHeading != null && inProps) {
                        thisHeading.addProperty(new Property(item));
                    }
                    break;
                case FILETAGS:
                    final String[] tags = item.groups[1].split(":");
                    filetags.addAll(Arrays.asList(tags));
                    break;
                case CATEGORY:
                    category = item.groups[1];
                    break;
            }
        }

        filetags.remove("");

        for (final Heading h : headings) {
            h.addInheritedTags(filetags);
        }

        return headings;
    }
}
