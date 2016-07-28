package io.grpc.grpcbenchmarks;

/**
 * Created by davidcao on 7/27/16.
 */
public enum ProtoEnum {
    SMALL_REQUEST("Small request (~15bytes)"),
    ADDRESS_BOOK("Address Book (from example ~500bytes)"),
    NEWSFEED("Large newsfeed-like protofile (~100kb)"),
    LARGE("Large generic protofile (~30kb)"),
    LARGE_DENSE("Large string-dense generic protofile (~41kb)"),
    LARGE_SPARSE("Large string-sparse generic protofile (-17kb)");

    private String title;

    ProtoEnum(String title) {
        this.title = title;
    }

    @Override
    public String toString() {
        return title;
    }
}
