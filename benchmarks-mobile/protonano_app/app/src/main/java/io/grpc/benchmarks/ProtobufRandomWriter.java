package io.grpc.benchmarks;

import com.google.gson.Gson;
import com.google.protobuf.nano.MessageNano;
import com.google.protobufbenchmarker.nano.AddressBook;
import com.google.protobufbenchmarker.nano.Person;

import org.json.JSONException;
import org.json.JSONObject;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Random;

import io.grpc.benchmarks.nano.FriendsList;
import io.grpc.benchmarks.nano.Post;
import io.grpc.benchmarks.nano.Profile;
import io.grpc.benchmarks.nano.Small1Request;
import io.grpc.benchmarks.nano.Thing1;
import io.grpc.benchmarks.nano.Things;

/**
 * Created by davidcao on 5/31/16.
 */
public class ProtobufRandomWriter {

    private static String randomAsciiStringFixed(Random r, int len) {
        char s[] = new char[len];
        for (int i = 0; i < len; ++i) {
            // generate a random ascii character that is a valid character
            s[i] = (char)(r.nextInt(95) + 32);
        }
        return new String(s);
    }

    private static String randomAsciiString(Random r, int maxLen) {
        // add 1 since this is exclusive
        int len = r.nextInt(maxLen) + 1;
        return randomAsciiStringFixed(r, len);
    }

    public static MessageNano randomProto(ProtoEnum protoEnum) {
        switch (protoEnum) {
            case SMALL_REQUEST:
                return ProtobufRandomWriter.randomProto0();
            case ADDRESS_BOOK:
                return ProtobufRandomWriter.randomProto1();
            case NEWSFEED:
                return ProtobufRandomWriter.randomProto2();
            case LARGE:
                return ProtobufRandomWriter.randomProto3(60, false);
            case LARGE_DENSE:
                return ProtobufRandomWriter.randomProto3(60, true);
            case LARGE_SPARSE:
                return ProtobufRandomWriter.randomProto3(10, true);
            default:
                return ProtobufRandomWriter.randomProto0();
        }
    }

    public static String protoToJsonString(MessageNano a) {
        Gson gson = new Gson();
        try {
            String jsonString = gson.toJson(a);
            JSONObject j = new JSONObject(jsonString);
            if (j.has("cachedSize")) {
                j.remove("cachedSize");
            }
            return j.toString();
        } catch (JSONException e) {
            // should never happen
            return gson.toJson(a);
        }
    }

    public static MessageNano randomProto0() {
        Random r = new Random();
        Small1Request m = new Small1Request();
        m.name = randomAsciiString(r, 20);
        return m;
    }

    public static MessageNano randomProto1() {
        Random r = new Random();
        int numPeople = r.nextInt(10) + 1;
        Person[] p = new Person[numPeople];
        for (int i = 0; i < numPeople; ++i) {
            p[i] = new Person();
            p[i].name = randomAsciiString(r, 16);
            p[i].id = r.nextInt();
            p[i].email = randomAsciiString(r, 30);
            int numPhones = r.nextInt(4) + 1;
            Person.PhoneNumber[] phones = new Person.PhoneNumber[numPhones];
            for (int j = 0; j < numPhones; ++j) {
                phones[j] = new Person.PhoneNumber();
                phones[j].number = randomAsciiStringFixed(r, 9);
                // this is hard coded for now
                phones[j].type = r.nextInt(3);
            }
            p[i].phones = phones;
        }
        AddressBook addressBook = new AddressBook();
        addressBook.people = p;
        return addressBook;
    }

    public static MessageNano randomProto2() {
        Random r = new Random();
        int numProfiles = r.nextInt(50) + 1;
        Profile[] p = new Profile[numProfiles];
        for (int i = 0; i < numProfiles; ++i) {
            p[i] = new Profile();
            p[i].id = r.nextInt();
            p[i].name = randomAsciiString(r, 16);
            p[i].about = randomAsciiString(r, 120);
            p[i].gender = r.nextBoolean();
            int numPosts = r.nextInt(12) + 1;
            Post[] posts = new Post[numPosts];
            for (int j = 0; j < numPosts; ++j) {
                posts[j] = new Post();
                posts[j].ownerId = r.nextInt();
                posts[j].content = randomAsciiString(r, 400);
                int numReactions = r.nextInt(10) + 1;
                posts[j].reactions = new Post.Reaction[numReactions];
                for (int k = 0; k < numReactions; ++k) {
                    posts[j].reactions[k] = new Post.Reaction();
                    posts[j].reactions[k].ownerId = r.nextInt();
                    posts[j].reactions[k].type = r.nextInt(5);
                }
                int numComments = r.nextInt(10) + 1;
                posts[j].comments = new Post.Comment[numComments];
                for (int k = 0; k < numComments; ++k) {
                    posts[j].comments[k] = new Post.Comment();
                    posts[j].comments[k].ownerId = r.nextInt();
                    posts[j].comments[k].text = randomAsciiString(r, 240);
                }
            }
        }
        FriendsList f = new FriendsList();
        f.profiles = p;
        return f;
    }

    public static MessageNano randomProto3(int stringSize, boolean fixed) {
        Random r = new Random();
        int numThings1 = (fixed ? 5 : r.nextInt(10) + 1);
        Thing1[] things1 = new Thing1[numThings1];
        for (int i = 0; i < numThings1; ++i) {
            things1[i] = (Thing1) random3Helper(r, new Thing1(), stringSize, fixed);
            int numThings2 = (fixed ? 5 : r.nextInt(10) + 1);
            Thing1.Thing2[] things2 = new Thing1.Thing2[numThings2];
            for (int j = 0; j < numThings2; ++j) {
                things2[j] = (Thing1.Thing2)
                        random3Helper(r, new Thing1.Thing2(), stringSize, fixed);
                int numThings3 = (fixed ? 5 : r.nextInt(10) + 1);
                Thing1.Thing2.Thing3[] things3 = new Thing1.Thing2.Thing3[numThings3];
                for (int k = 0; k < numThings3; ++k) {
                    things3[k] = (Thing1.Thing2.Thing3)
                            random3Helper(r, new Thing1.Thing2.Thing3(), stringSize, fixed);
                }
                things2[j].things3 = things3;
            }
            things1[i].things2 = things2;
        }

        Things things = (Things) random3Helper(r, new Things(), stringSize, fixed);
        things.things = things1;
        return things;
    }

    private static MessageNano random3Helper(Random r, MessageNano m, int stringSize, boolean fixed) {
        try {
            for (Field f: m.getClass().getFields()) {
                if (Modifier.isFinal(f.getModifiers())) {
                    continue;
                }
                if (f.getType().equals(int.class)) {
                    f.set(m, r.nextInt());
                } else if (f.getType().equals(float.class)) {
                    f.set(m, r.nextFloat());
                } else if (f.getType().equals(String.class)) {
                    f.set(m, (fixed ? randomAsciiStringFixed(r, stringSize)
                            : randomAsciiString(r, stringSize)));
                } else if (f.getType().equals(boolean.class)) {
                    f.set(m, r.nextBoolean());
                } else if (f.getType().equals(double.class)) {
                    f.set(m, r.nextDouble());
                }
            }
        } catch (Exception e) {
            System.out.println("Error with helper: " + e);
        }
        return m;
    }
}
