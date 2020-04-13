package com.larkery.simpleorgsync.con;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import com.google.gson.reflect.TypeToken;

import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Created by hinton on 10/04/20.
 */

public class ContactsJson {
    public static class Tuple {
        public String lbl;
        public String val;

        public Tuple(String lbl, String val) {
            if (lbl == null) lbl = "other";
            if (val == null) val = "";
            this.lbl = lbl;
            this.val = val;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Tuple tuple = (Tuple) o;
            return Objects.equals(lbl, tuple.lbl) &&
                    Objects.equals(val, tuple.val);
        }

        @Override
        public int hashCode() {
            return Objects.hash(lbl, val);
        }
    }

    public static class Contact {
        public LinkedHashSet<Tuple> address;
        public LinkedHashSet<Tuple> phone;
        public LinkedHashSet<Tuple> email;
        public String firstName;
        public String middleName;
        public String surname;
        public String organisation;
        public String birthday;
        public String anniversary;
        public String notes;

        public Contact() {

        }

        public void mergeWith(final Contact other) {
            address = mergeWith(address, other.address);
            phone = mergeWith(phone, other.phone);
            email = mergeWith(email, other.email);

            firstName = leastNull(firstName, other.firstName);
            surname = leastNull(surname, other.surname);
            middleName = leastNull(middleName, other.middleName);
            organisation = leastNull(organisation, other.organisation);
            birthday = leastNull(birthday, other.birthday);
            anniversary = leastNull(anniversary, other.anniversary);
            notes = leastNull(notes, other.notes);
        }

        private LinkedHashSet<Tuple> mergeWith(LinkedHashSet<Tuple> into, LinkedHashSet<Tuple> from) {
            if (from != null) {
                if (into == null) into = new LinkedHashSet<>();
                into.addAll(from);
            }
            return into;
        }

        public String leastNull(String a, String b) {
            if (a == null) return b;
            return a;
        }

        public void addPhone(String types, String number) {
            if (phone == null) phone = new LinkedHashSet<>();
            phone.add(new Tuple(types, number));
        }

        public void addAddress(String l, String n) {
            if (address == null) address = new LinkedHashSet<>();
            address.add(new Tuple(l, n));
        }

        public void addEmail(String label, String e) {
            if (email == null) email = new LinkedHashSet<>();
            email.add(new Tuple(label, e));
        }

        static void upd(MessageDigest digest, String value) {
            if (value == null) {
                digest.update((byte)0);
            } else {
                digest.update(value.getBytes(StandardCharsets.UTF_8));
            }
        }

        static void upd(MessageDigest digest, LinkedHashSet<Tuple> vs) {
            if (vs == null) {
                digest.update((byte) 0);
            } else {
                for (final Tuple t : vs) {
                    upd(digest, t.lbl);
                    upd(digest, t.val);
                }
            }
        }


        public String checksum() {
            try {
                final MessageDigest digest = MessageDigest.getInstance("MD5");

                upd(digest, firstName);
                upd(digest, surname);
                upd(digest, middleName);
                upd(digest, organisation);
                upd(digest, birthday);
                upd(digest, anniversary);
                upd(digest, email);
                upd(digest, phone);
                upd(digest, address);

                return new BigInteger(1, digest.digest()).toString(16);
            } catch (NoSuchAlgorithmException e) {
                return "" + hashCode();
            }
        }

    }

    static class TupleAdapter implements JsonSerializer<Tuple>,
            JsonDeserializer<Tuple> {
        @Override
        public Tuple deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            if (json == JsonNull.INSTANCE) {
                return null;
            } else if (json instanceof JsonArray) {
                return new Tuple(
                        ((JsonArray) json).get(0).getAsString(),
                        ((JsonArray) json).get(1).getAsString()
                );
            } else {
                throw new IllegalArgumentException("Cannot deserialize " + json + " to a tuple");
            }
        }

        @Override
        public JsonElement serialize(Tuple src, Type typeOfSrc, JsonSerializationContext context) {
            if (src == null) {
                return JsonNull.INSTANCE;
            } else {
                final JsonArray out = new JsonArray();
                out.add(src.lbl);
                out.add(src.val);
                return out;
            }
        }
    }

    private static final Gson gson =
            new GsonBuilder()
            .registerTypeAdapter(Tuple.class, new TupleAdapter())
            .create();

    public static Map<String, Contact> load(final Reader in) {
        return gson.fromJson(in, new TypeToken<Map<String, Contact>>(){}.getType());
    }

    public static void save(final Writer out, final Map<String, Contact> data) {
        gson.toJson(data, out);
    }
}
