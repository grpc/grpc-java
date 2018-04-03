package io.grpc.grpcbenchmarks;

import com.google.protobuf.MessageLite;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Random;
import java.util.logging.Level;
import java.util.logging.Logger;

import io.grpc.benchmarks.Attributes;
import io.grpc.benchmarks.FriendsList;
import io.grpc.benchmarks.Post;
import io.grpc.benchmarks.Profile;
import io.grpc.benchmarks.SmallRequest;
import io.grpc.benchmarks.Thing1;
import io.grpc.benchmarks.Things;
import io.grpc.benchmarks.AddressBook;
import io.grpc.benchmarks.Person;

/**
 * Created by davidcao on 6/13/16.
 */
public class ProtobufRandomWriter {
    private static final Logger logger = Logger.getLogger(ProtobufRandomWriter.class.getName());

    private static String randomAsciiStringFixed(Random r, int len) {
        char s[] = new char[len];
        for (int i = 0; i < len; ++i) {
            // generate a random ascii character that is a valid character
            s[i] = (char) (r.nextInt(95) + 32);
        }
        return new String(s);
    }

    private static String randomAsciiString(Random r, int maxLen) {
        // add 1 since this is exclusive
        int len = r.nextInt(maxLen) + 1;
        return randomAsciiStringFixed(r, len);
    }

    public static MessageLite randomProto(ProtoEnum protoEnum) {
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

    public static String protoToJsonString(ProtoEnum protoEnum, MessageLite message) {
        switch (protoEnum) {
            case SMALL_REQUEST:
                return ProtobufRandomWriter.protoToJsonString0(message);
            case ADDRESS_BOOK:
                return ProtobufRandomWriter.protoToJsonString1(message);
            case NEWSFEED:
                return ProtobufRandomWriter.protoToJsonString2(message);
            case LARGE:
            case LARGE_DENSE:
            case LARGE_SPARSE:
                return ProtobufRandomWriter.protoToJsonString3(message);
            default:
                return ProtobufRandomWriter.protoToJsonString0(message);
        }
    }

    // These catches should techinically never be happening
    public static String protoToJsonString0(MessageLite m) {
        SmallRequest s = (SmallRequest) m;
        JSONObject json = new JSONObject();
        try {
            json.put("name", s.getName());
            return json.toString();
        } catch (JSONException e) {
            logger.log(Level.WARNING, "Failed to parse JSON: " + e);
            return null;
        }
    }

    public static String protoToJsonString1(MessageLite m) {
        AddressBook a = (AddressBook) m;
        JSONObject json = new JSONObject();
        try {
            for (Person p : a.getPeopleList()) {
                JSONObject jsonPerson = new JSONObject();
                jsonPerson.put("name", p.getName())
                        .put("id", p.getId())
                        .put("email", p.getEmail());
                for (Person.PhoneNumber pn : p.getPhonesList()) {
                    JSONObject jsonPhoneNumber = new JSONObject();
                    jsonPhoneNumber.put("number", pn.getNumber())
                            .put("type", pn.getTypeValue());
                    jsonPerson.accumulate("phones", pn);
                }
                json.accumulate("people", jsonPerson);
            }
            return json.toString();
        } catch (JSONException e) {
            logger.log(Level.WARNING, "Failed to parse JSON: " + e);
            return null;
        }
    }

    public static String protoToJsonString2(MessageLite m) {
        FriendsList friendsList = (FriendsList) m;
        JSONObject friendsListJson = new JSONObject();
        try {
            for (Profile profile : friendsList.getProfilesList()) {
                JSONObject profileJson = new JSONObject();
                profileJson.put("id", profile.getId())
                        .put("name", profile.getName())
                        .put("about", profile.getAbout())
                        .put("gender", profile.getGender());
                for (Post post : profile.getPostsList()) {
                    JSONObject postJson = new JSONObject();
                    postJson.put("owner_id", post.getOwnerId())
                            .put("content", post.getContent());
                    for (Post.Reaction reaction : post.getReactionsList()) {
                        JSONObject reactionJson = new JSONObject();
                        reactionJson.put("owner_id", reaction.getOwnerId())
                                .put("type", reaction.getTypeValue());
                        postJson.accumulate("reactions", reactionJson);
                    }
                    for (Post.Comment comment : post.getCommentsList()) {
                        JSONObject commentJson = new JSONObject();
                        commentJson.put("owner_id", comment.getOwnerId())
                                .put("text", comment.getText());
                        postJson.accumulate("comments", commentJson);
                    }
                    profileJson.accumulate("posts", postJson);
                }
                friendsListJson.accumulate("profiles", profileJson);
            }
            return friendsListJson.toString();
        } catch (JSONException e) {
            logger.log(Level.WARNING, "Failed to parse JSON: " + e);
            return null;
        }
    }

    public static String protoToJsonString3(MessageLite m) {
        Things things = (Things) m;
        JSONObject thingsJson = new JSONObject();
        try {
            for (Thing1 thing1 : things.getThingsList()) {
                JSONObject thing1Attribute =
                        protoToJsonString3AttributeWriter(new JSONObject(), thing1.getAttributes());
                JSONObject thing1Json = new JSONObject();
                thing1Json.put("attributes", thing1Attribute);
                for (Thing1.Thing2 thing2 : thing1.getThings2List()) {
                    JSONObject thing2Attribute =
                            protoToJsonString3AttributeWriter(new JSONObject(),
                                    thing2.getAttributes());
                    JSONObject thing2Json = new JSONObject();
                    thing2Json.put("attributes", thing2Attribute);
                    for (Thing1.Thing2.Thing3 thing3 : thing2.getThings3List()) {
                        JSONObject thing3Attribute =
                                protoToJsonString3AttributeWriter(new JSONObject(),
                                        thing3.getAttributes());
                        JSONObject thing3Json = new JSONObject();
                        thing3Json.put("attributes", thing3Attribute);
                        thing2Json.accumulate("things3", thing3Json);
                    }
                    thing1Json.accumulate("things2", thing2Json);
                }
                thingsJson.accumulate("things1", thing1Json);
            }
            return thingsJson.toString();
        } catch (JSONException e) {
            logger.log(Level.WARNING, "Failed to parse JSON: " + e);
            return null;
        }
    }

    private static JSONObject protoToJsonString3AttributeWriter(JSONObject jsonAttribute,
                                                                Attributes a) {
        try {
            jsonAttribute.put("Int1", a.getInt1())
                    .put("Int2", a.getInt2())
                    .put("Int3", a.getInt3())
                    .put("Float1", a.getFloat1())
                    .put("Float2", a.getFloat2())
                    .put("Float3", a.getFloat3())
                    .put("String1", a.getString1())
                    .put("String2", a.getString2())
                    .put("String3", a.getString3())
                    .put("Bool1", a.getBool1())
                    .put("Bool2", a.getBool2())
                    .put("Bool3", a.getBool3())
                    .put("Double1", a.getDouble1())
                    .put("Double2", a.getDouble2())
                    .put("Double3", a.getDouble3());
        } catch (JSONException e) {
            logger.log(Level.WARNING, "Failed to translate proto to JSON: " + e);
        }
        return jsonAttribute;
    }

    public static MessageLite randomProto0() {
        Random r = new Random();
        return randomProto0(r);
    }

    private static MessageLite randomProto0(Random r) {
        SmallRequest m = SmallRequest.getDefaultInstance();
        return m.toBuilder().setName(randomAsciiString(r, 30)).build();
    }

    public static MessageLite randomProto1() {
        Random r = new Random();
        return randomProto1(r);
    }

    public static MessageLite randomProto1(Random r) {
        AddressBook.Builder addressBookBuilder = AddressBook.newBuilder();
        int numPeople = r.nextInt(10) + 1;
        for (int i = 0; i < numPeople; ++i) {
            Person.Builder personBuilder = Person.newBuilder();
            personBuilder
                    .setName(randomAsciiStringFixed(r, 16))
                    .setId(r.nextInt())
                    .setEmail(randomAsciiStringFixed(r, 30));
            int numPhones = r.nextInt(4) + 1;
            for (int j = 0; j < numPhones; ++j) {
                personBuilder.addPhones(Person.PhoneNumber
                        .newBuilder()
                        .setNumber(randomAsciiStringFixed(r, 9))
                        .setTypeValue(r.nextInt(3))
                        .build()
                );
            }
            addressBookBuilder.addPeople(personBuilder.build());
        }
        return addressBookBuilder.build();
    }

    public static MessageLite randomProto2() {
        Random r = new Random();
        return randomProto2(r);
    }

    public static MessageLite randomProto2(Random r) {
        int numProfiles = r.nextInt(50) + 1;
        FriendsList.Builder friendsListBuilder = FriendsList.newBuilder();
        for (int i = 0; i < numProfiles; ++i) {
            Profile.Builder profileBuilder = Profile.newBuilder();
            profileBuilder.setId(r.nextInt())
                    .setName(randomAsciiString(r, 16))
                    .setAbout(randomAsciiString(r, 120))
                    .setGender(r.nextBoolean());
            int numPosts = r.nextInt(12) + 1;
            for (int j = 0; j < numPosts; ++j) {
                Post.Builder postBuilder = Post.newBuilder();
                postBuilder.setOwnerId(r.nextInt())
                        .setContent(randomAsciiString(r, 400));
                int numReactions = r.nextInt(10) + 1;
                for (int k = 0; k < numReactions; ++k) {
                    postBuilder.addReactions(Post.Reaction
                            .newBuilder()
                            .setOwnerId(r.nextInt())
                            .setTypeValue(r.nextInt(5))
                    );
                }
                int numComments = r.nextInt(10) + 1;
                for (int k = 0; k < numComments; ++k) {
                    postBuilder.addComments(Post.Comment
                            .newBuilder()
                            .setOwnerId(r.nextInt())
                            .setText(randomAsciiString(r, 240))
                    );
                }
                profileBuilder.addPosts(postBuilder.build());
            }
            friendsListBuilder.addProfiles(profileBuilder.build());
        }
        return friendsListBuilder.build();
    }

    public static MessageLite randomProto3(int stringSize, boolean fixed) {
        Random r = new Random();
        return randomProto3(r, stringSize, fixed);
    }

    public static MessageLite randomProto3(Random r, int stringSize, boolean fixed) {
        Things.Builder thingBuilder = Things.newBuilder();
        thingBuilder.setAttributes(randomAttributes(r, fixed, stringSize));
        int numThings1 = (fixed ? 5 : r.nextInt(10) + 1);
        for (int i = 0; i < numThings1; ++i) {
            Thing1.Builder thing1Builder = Thing1.newBuilder();
            thing1Builder.setAttributes(randomAttributes(r, fixed, stringSize));
            int numThings2 = (fixed ? 5 : r.nextInt(10) + 1);
            for (int j = 0; j < numThings2; ++j) {
                Thing1.Thing2.Builder thing2Builder = Thing1.Thing2.newBuilder();
                thing2Builder.setAttributes(randomAttributes(r, fixed, stringSize));
                int numThings3 = (fixed ? 5 : r.nextInt(10) + 1);
                for (int k = 0; k < numThings3; ++k) {
                    Thing1.Thing2.Thing3.Builder thing3Builder = Thing1.Thing2.Thing3.newBuilder();
                    thing3Builder.setAttributes(randomAttributes(r, fixed, stringSize));
                    thing2Builder.addThings3(thing3Builder.build());
                }
                thing1Builder.addThings2(thing2Builder.build());
            }
            thingBuilder.addThings(thing1Builder.build());
        }
        return thingBuilder.build();
    }

    private static Attributes randomAttributes(Random r, boolean fixed, int stringSize) {
        Attributes.Builder attributeBuilder = Attributes.newBuilder();
        attributeBuilder.setInt1(r.nextInt())
                .setInt2(r.nextInt())
                .setInt3(r.nextInt())
                .setFloat1(r.nextFloat())
                .setFloat2(r.nextFloat())
                .setFloat3(r.nextFloat())
                .setString1(fixed ? randomAsciiStringFixed(r, stringSize) :
                        randomAsciiString(r, stringSize))
                .setString2(fixed ? randomAsciiStringFixed(r, stringSize) :
                        randomAsciiString(r, stringSize))
                .setString3(fixed ? randomAsciiStringFixed(r, stringSize) :
                        randomAsciiString(r, stringSize))
                .setBool1(r.nextBoolean())
                .setBool2(r.nextBoolean())
                .setBool3(r.nextBoolean())
                .setDouble1(r.nextDouble())
                .setDouble2(r.nextDouble())
                .setDouble3(r.nextDouble());
        return attributeBuilder.build();
    }
}
