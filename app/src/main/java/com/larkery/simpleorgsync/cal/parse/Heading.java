package com.larkery.simpleorgsync.cal.parse;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public class Heading {
    private Tokenizer.Item<OrgParser.Token> item;

    private String category;
    private String heading;
    private Set<String> tags = new LinkedHashSet<>();
    private Set<String> inheritedTags = new HashSet<>();
    private Map<String, Property> properties = new LinkedHashMap<>();
    private List<Timestamp> timestamps = new ArrayList<>();

    private int depth = 1;
    private int endOfLastProperty = 0;

    public Heading(final Tokenizer.Item<OrgParser.Token> item, final Set<String> inheritedTags, String category) {
        this.item = item;

        this.endOfLastProperty = item.ends[0] + 1; //maybe

        this.heading = item.groups[2];
        this.depth = item.groups[1].length();
        if (item.groups[3] != null) {
            this.tags.addAll(Arrays.asList(item.groups[3].split(":")));
            this.tags.remove("");
        }

        if (inheritedTags != null)
            this.inheritedTags.addAll(inheritedTags);

        this.category = category;
    }

    public Heading(final String heading, final Set<String> tags, String category) {
        this.heading = heading;
        this.tags.addAll(tags);
        this.setProperty("ID", UUID.randomUUID().toString());
        this.category = category;
    }

    public String getCategory() { return category; }

    public String getHeading() {
        return heading;
    }

    public void setHeading(String heading) {
        this.heading = heading;
    }

    public boolean hasTag(String s) {
        return tags.contains(s) || inheritedTags.contains(s);
    }

    public void addTag(final String tag) {
        tags.add(tag);
    }

    public void removeTag(final String tag) {
        tags.remove(tag);
    }

    public boolean hasProperty(final String key) {
        return properties.containsKey(key);
    }

    public void setProperty(final String key, String value) {
        if (hasProperty(key)) {
            properties.get(key).setTo(value);
        } else {
            properties.put(key, new Property(endOfLastProperty, key, value));
        }
    }

    public String getProperty(final String key) {
        if (hasProperty(key)) {
            return properties.get(key).getValue();
        } else {
            return null;
        }
    }

    public String tagsString() {
        if (tags.isEmpty()) return "";
        else {
            final StringBuffer sb = new StringBuffer();
            for (final String tag : tags) {
                sb.append(":");
                sb.append(tag);
            }
            sb.append(":");
            return sb.toString();
        }
    }

    @Override
    public int hashCode() {
        return Objects.hash(heading, tags, properties, timestamps, depth);
    }

    public String checksum() {
        return heading + ":" + tags + ":" + properties + ":" + timestamps + ":" + depth;
    }

    @Override
    public String toString() {
        return checksum();
    }

    public String propertiesString() {
        if (properties.isEmpty()) return "";
        final StringBuffer sb = new StringBuffer();
        sb.append(":PROPERTIES:\n");
        for (final Property p : properties.values()) {
            sb.append(p.propertiesLine());
        }
        sb.append(":END:\n");
        return sb.toString();
    }

    public String stars() {
        return new String(new char[depth]).replace("\0", "*");
    }

    public void edit(final List<Edit> edits) {
        if (item != null) {
            Edit.replace(item, 1, stars(), edits);

            String hStr = heading;
            if (tags.isEmpty() & item.groups[3] != null) {
                Edit.replace(item, 3, "", edits);
            } else if (item.groups[3] == null && !tags.isEmpty()) {
                hStr = heading + " " + tagsString();
            } else if (item.groups[3] != null) {
                Edit.replace(item, 3, tagsString(), edits);
            }

            Edit.replace(item, 2, hStr, edits);

            if (!properties.isEmpty()) {
                boolean needsPropertiesBlock = true;
                for (final Property p : properties.values()) {
                    if (p.exists()) {
                        needsPropertiesBlock = false;
                        break;
                    }
                }

                if (needsPropertiesBlock) Edit.insert(endOfLastProperty, ":PROPERTIES:\n", edits);

                for (final Property p : properties.values()) {
                    p.edit(edits);
                }

                if (needsPropertiesBlock) Edit.insert(endOfLastProperty, ":END:\n", edits);
            }

            for (final Timestamp t : timestamps) {
                t.edit(edits);
            }
        }
    }

    public void append(final StringBuffer buffer) {
        buffer.append(stars());
        buffer.append(" ");
        buffer.append(heading);
        if (!tags.isEmpty()) {
            buffer.append(" ");
            buffer.append(tagsString());
        }
        buffer.append("\n");
        final StringBuffer lateStamps = new StringBuffer();
        boolean needsNewline = false;
        for (final Timestamp ts : getTimestamps()) {
            switch (ts.getType()) {
                case SCHEDULED:
                case DEADLINE:
                    needsNewline = true;
                    buffer.append(ts); buffer.append(" ");
                    break;
                case ACTIVE:
                    lateStamps.append(ts);
                    lateStamps.append("\n");
                    break;
            }
        }
        if (needsNewline) buffer.append("\n");
        buffer.append(propertiesString());
        buffer.append(lateStamps);
    }

    public void addTimestamp(Timestamp timestamp) {
        if (timestamp.isValid()) {
            this.timestamps.add(timestamp);
        }
    }

    public void addProperty(Property property) {
        this.properties.put(property.getKey(), property);
        this.endOfLastProperty = Math.max(property.getEndPosition(), endOfLastProperty);
    }

    private String ensureID() {
        if (!hasProperty("ID")) {
            setProperty("ID", UUID.randomUUID().toString());
        }
        return getProperty("ID");
    }

    public String getTitle() {
        return heading;
    }

    public List<Timestamp> getTimestamps() {
        return timestamps;
    }

    public boolean exists () { return item != null; }

    public boolean hasAllTags(final Set<String> tags) {
        for (final String t : tags) {
            if (!(this.tags.contains(t) || inheritedTags.contains(t))) return false;
        }
        return true;
    }

    public boolean hasAnyTags(final Set<String> tags) {
        return !(Collections.disjoint(tags, this.tags) && Collections.disjoint(tags, inheritedTags));
    }

    public void addInheritedTags(Set<String> filetags) {
        inheritedTags.addAll(filetags);
    }

    public Set<String> getTags() {
        return tags;
    }

    public String syncID(boolean readOnly) {
        if (readOnly) return checksum();
        else return ensureID();
    }
}
