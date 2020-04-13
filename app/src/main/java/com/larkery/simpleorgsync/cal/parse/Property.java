package com.larkery.simpleorgsync.cal.parse;

import java.util.List;
import java.util.Objects;

public class Property {
    private Tokenizer.Item<OrgParser.Token> item;
    private String key;
    private String value;
    private int offset;

    public Property(final Tokenizer.Item<OrgParser.Token> item) {
        this.item = item;
        this.key = item.groups[1];
        this.value = item.groups[2];
    }

    public Property(int offset, String key, String value) {
        this.key = key;
        this.value = value;
        this.offset = offset;
    }

    public void edit(final List<Edit> edits) {
        if (this.item != null) {
            Edit.replace(item, 2, value, edits);
        } else {
            Edit.insert(offset, propertiesLine(), edits);
        }
    }

    public String getValue() {
        return value;
    }

    public void setTo(String to) {
        this.value = to;
    }

    public String propertiesLine() {
        return String.format(":%s: %s\n", key, value);
    }

    public String getKey() {
        return key;
    }

    public int getEndPosition() {
        if (this.item != null) return item.ends[0] + 1;
        else return -1;
    }

    public boolean exists() {
        return item != null;
    }

    public String toString() {
        return key+"="+value;
    }

    @Override
    public int hashCode() {
        return Objects.hash(key, value);
    }
}
