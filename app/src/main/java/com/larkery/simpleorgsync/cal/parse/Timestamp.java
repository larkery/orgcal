package com.larkery.simpleorgsync.cal.parse;

import android.annotation.SuppressLint;
import android.os.Build;
import android.support.annotation.RequiresApi;
import android.util.Log;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RequiresApi(api = Build.VERSION_CODES.N)
public class Timestamp {
    private static final long ONE_SEC = 1000;
    private static final long ONE_MINUTE = 60 * ONE_SEC;
    private static final long ONE_HOUR = 60 * ONE_MINUTE;
    private static final long ONE_DAY = 24 * ONE_HOUR;
    private static final long ONE_WEEK = 7 * ONE_DAY;

    private boolean modified = false;

    public Timestamp(TimeZone timeZone,
                     long dtStart,
                     long dtEnd,
                     boolean allDay,
                     Type type,
                     String rrule) {
        this.zone = timeZone;
        this.startTime = dtStart;
        this.endTime = dtEnd;
        this.allDay = allDay;
        this.type = type;
        setRRule(rrule);
        modified = false;
    }

    public long getStartTime() {
        return startTime;
    }

    public boolean isAllDay() {
        return allDay;
    }

    public long getEndTime() {
        if (allDay) {
            return endTime + ONE_DAY;
        } else {
            return endTime;
        }
    }

    public void setStartTime(long startTime) {
        this.startTime = startTime;
        modified = true;
    }

    public void setEndTime(long endTime) {
        if (allDay) {
            this.endTime = endTime - ONE_DAY;
        } else {
            this.endTime = endTime;
        }
        modified = true;
    }

    public void setAllDay(boolean allDay) {
        if (this.allDay == allDay) return;
        this.allDay = allDay;
        if (this.allDay) {
            this.endTime = this.endTime - ONE_DAY;
        } else {
            this.endTime = this.endTime + ONE_DAY;
        }
        modified = true;
    }

    public boolean isValid() {
        return type != Type.INVALID;
    }

    public void edit(List<Edit> edits) {
        if (item != null && isValid() && modified) {
            Edit.replace(item, 0, toString(), edits);
        }
    }

    public boolean isRepeating() {
        return recurrence != RecurrenceInterval.NONE;
    }

    public String getRRULE() {
        return recurrence.getRRULE(frequency, repeatEndTime);
    }

    public String getDuration() {
        final long durationMillis = getEndTime() - getStartTime();
        //https://tools.ietf.org/html/rfc2445#section-4.3.6
        final long weeks = (durationMillis / ONE_WEEK);
        long remainder = (durationMillis % ONE_WEEK);
        final long days = remainder / ONE_DAY;
        remainder = remainder % ONE_DAY;
        final long hours = remainder / ONE_HOUR;
        remainder = remainder % ONE_HOUR;
        final long minutes = remainder / ONE_MINUTE;
        remainder = remainder % ONE_MINUTE;

        final StringBuffer out = new StringBuffer();
        out.append("P");
        if (weeks > 0) {
            out.append(weeks);
            out.append("W");
        }
        if (days > 0) {
            out.append(days);
            out.append("D");
        }
        out.append("T");
        if (hours > 0) {
            out.append(hours);
            out.append("H");
        }
        if (minutes > 0) {
            out.append(minutes);
            out.append("M");
        }
        return out.toString();
    }

    @SuppressLint("NewApi")
    static final SimpleDateFormat icalSDF = new SimpleDateFormat( "yyyyMMdd'T'HHmmssX");
    static {
        icalSDF.setTimeZone(TimeZone.getTimeZone("Z"));
    }
    public void setRRule(final String rrule) {
        // rrule is a semicolon separated list of equals separated values
        String[] parts = rrule.split(";");
        RecurrenceInterval newrecurrence = RecurrenceInterval.NONE;
        int newfrequency = 1;
        Long newrepeatEndTime = null;
        for (String part : parts) {
            String[] keyval = part.split("=", 2);
            if (keyval.length == 2) {
                final String key = keyval[0];
                final String val = keyval[1];
                switch (key) {
                    case "FREQ":
                        switch (val) {
                            case "DAILY":
                            case "WEEKLY":
                            case "MONTHLY":
                            case "YEARLY":
                                newrecurrence = RecurrenceInterval.valueOf(RecurrenceInterval.class, val);
                                break;
                        }
                        break;
                    case "INTERVAL":
                        try {
                            newfrequency = Integer.parseInt(val);
                        } catch (NumberFormatException nfe) {
                        }
                        break;
                    case "UNTIL": //19971224T000000Z
                        try {
                            Date d = icalSDF.parse(val);
                            newrepeatEndTime = d.getTime();
                        } catch (ParseException e) {

                        }
                        break;
                    case "COUNT": // TODO convert into an until
                        break;
                }
            }
        }
        if (newfrequency != frequency ||
                newrepeatEndTime != repeatEndTime ||
                newrecurrence != recurrence) {
            modified = true;
            frequency = newfrequency;
            repeatEndTime = newrepeatEndTime;
            recurrence = newrecurrence;
        }
    }

    static final Pattern durationPattern =
            Pattern.compile(
                    "P(?:(\\d+)W)?" +
                            "(?:(\\d+)D)?" +
                            "(?:(\\d+)T)?" +
                            "(?:(\\d+)M)?"
            );

    public void setEndTimeFromDuration(String string) {
        Matcher g = durationPattern.matcher(string);
        String W = g.group(1);
        String D = g.group(2);
        String T = g.group(3);
        String M = g.group(4);

        int w = W == null ? 0 : Integer.parseInt(W);
        int d = D == null ? 0 : Integer.parseInt(D);
        int t = T == null ? 0 : Integer.parseInt(T);
        int m = M == null ? 0 : Integer.parseInt(M);

        long millis = m * ONE_MINUTE +
                t * ONE_HOUR +
                d * ONE_DAY +
                w * ONE_WEEK;

        setEndTime(startTime + millis);
    }

    public enum Type {
        SCHEDULED, DEADLINE, ACTIVE, INVALID;

        public String asPrefixString() {
            switch (this) {
                case SCHEDULED:
                case DEADLINE:
                    return String.valueOf(this) +": ";
                default:
                    return "";
            }
        }
    }

    public enum RecurrenceInterval {
        NONE,
        DAILY,
        WEEKLY,
        MONTHLY,
        YEARLY;

        public static RecurrenceInterval fromOrgString(String s) {
            switch (s) {
                case "d": return DAILY;
                case "w": return WEEKLY;
                case "m": return MONTHLY;
                case "y": return YEARLY;
                default:  return NONE;
            }
        }

        public String stringFor(int frequency) {
            switch (this) {
                case DAILY:   return " +" + frequency + "d";
                case WEEKLY:  return " +" + frequency + "w";
                case MONTHLY: return " +" + frequency + "m";
                case YEARLY:  return " +" + frequency + "y";
                default:      return "";
            }
        }

        public String getRRULE(int frequency, Long repeatEndTime) {
            if (repeatEndTime != null) {
                Date dt = new Date(repeatEndTime);
                return String.format("FREQ=%s;INTERVAL=%d;UNTIL=%s", this, frequency,
                        icalSDF.format(dt));
            } else {
                return String.format("FREQ=%s;INTERVAL=%d", this, frequency);
            }
        }
    }

    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");
    private final TimeZone zone;

    private Tokenizer.Item<OrgParser.Token> item;
    private Type type = Type.INVALID;

    private boolean allDay;

    private RecurrenceInterval recurrence = RecurrenceInterval.NONE;
    private int frequency = 0;

    private long startTime;
    private long endTime;

    private Long repeatEndTime;

    private static final SimpleDateFormat LONG_FORMAT =
            new SimpleDateFormat("yyyy-MM-dd HH:mm");

    public Timestamp(TimeZone tz, Tokenizer.Item<OrgParser.Token> item) {
        this.zone = tz;
        this.item = item;

        final String prefix = item.groups[1];
        final String wholeDate = item.groups[2];
        final String firstDate = item.groups[3];
        final String firstTime = item.groups[4];
        final String firstEndTime = item.groups[5];
        final String repeater = item.groups[6];
        final String secondDate = item.groups[7];
        final String secondTime = item.groups[8];
        final String sexpDate = item.groups[9];

        TimeZone zoneToUse;

        if (firstTime != null || secondTime != null) {
            zoneToUse = zone;
            allDay = false;
        } else {
            zoneToUse = UTC;
            allDay = true;
        }

        LONG_FORMAT.setTimeZone(zoneToUse);

        if (wholeDate != null) {
            try {
                Date startDate;
                Date endDate;

                if (allDay) {
                    startDate = LONG_FORMAT.parse(firstDate + " 00:00");
                    if (secondDate != null) {
                        endDate = LONG_FORMAT.parse(secondDate + " 00:00");
                    } else {
                        endDate = startDate;
                    }
                } else {
                    startDate = LONG_FORMAT.parse(firstDate + " " +
                            (firstTime == null ? "00:00" : firstTime)
                    );

                    if (firstEndTime != null) {
                        endDate = LONG_FORMAT.parse(firstDate + " " + firstEndTime);
                    } else if (secondDate != null && repeater == null) {
                        endDate = LONG_FORMAT.parse(secondDate + " " +
                                (secondTime == null ? "00:00" : secondTime)
                        );
                    } else {
                        // duration is zero
                        endDate = startDate;
                    }
                }

                this.startTime = startDate.getTime();
                this.endTime = endDate.getTime();
                this.repeatEndTime = null;

                // now handle rrule
                if (repeater != null) {
                    this.frequency = Integer.parseInt(
                            repeater.substring(1, repeater.length()-1)
                    );
                    this.recurrence = RecurrenceInterval
                            .fromOrgString(repeater.substring(repeater.length()-1));
                    if (secondDate != null) {
                        Date finishDate = LONG_FORMAT.parse(secondDate + " " +
                                (secondTime == null ? "00:00" : secondTime)
                        );
                        this.repeatEndTime = finishDate.getTime();
                    }
                }

                type = prefix == null ? Type.ACTIVE :
                        prefix.startsWith("SCHEDULED") ? Type.SCHEDULED : Type.DEADLINE;
            } catch (ParseException e) {
                type = Type.INVALID;
            }
        }
    }

    public Type getType() {
        return type;
    }

    @Override
    public int hashCode() {
        return Objects.hash(startTime, endTime, allDay, recurrence, frequency, repeatEndTime);
    }

    @Override
    public String toString() {
        LONG_FORMAT.setTimeZone(allDay ? UTC : zone);

        final String start = LONG_FORMAT.format(new Date(startTime));
        final String startDate = start.substring(0, 10);
        String out = "";

        final String end = LONG_FORMAT.format(new Date(endTime));
        final String endDate = end.substring(0, 10);

        if (recurrence != RecurrenceInterval.NONE) {
            if (startTime == endTime || (allDay && (endTime == startTime + ONE_DAY))) {
                out = allDay ? startDate : start;
            } else {
                out = allDay ? startDate : (start + "-" + end.substring(11));
            }
            out = "<" + out + recurrence.stringFor(frequency) + ">";
            if (repeatEndTime != null) {
                final String rend = LONG_FORMAT.format(new Date(repeatEndTime));
                final String rendDate = rend.substring(0, 10);
                out = out +"--<" + rendDate + ">";
            }
        } else if (startTime == endTime) {
            if (allDay) {
                out = startDate;
            } else {
                out = start;
            }
            out = "<" + out + ">";
        } else {
            if (allDay && (endTime == startTime + ONE_DAY)) {
                out = startDate;
                out = "<" + out + ">";
            } else if (startDate.equals(endDate)) {
                if (allDay) {
                    out = startDate;
                } else {
                    out = start + "-" + end.substring(11);
                }

                out = "<" + out + ">";
            } else {
                // these events cannot repeat, because org mode doesn't do repeating
                // ranges I don't think. At least, it's pretty confusing.
                if (allDay) {
                    out = "<" + startDate + ">--<" + endDate + ">";
                } else {
                    out = "<" + start + ">--<" + end +">";
                }
            }
        }

        out = type.asPrefixString() + out;

        return out;
    }
}
