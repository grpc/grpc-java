// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: grpc/channelz/v1/channelz.proto

package io.grpc.channelz.v1;

/**
 * <pre>
 * ServerData is data for a specific Server.
 * </pre>
 *
 * Protobuf type {@code grpc.channelz.v1.ServerData}
 */
public  final class ServerData extends
    com.google.protobuf.GeneratedMessageV3 implements
    // @@protoc_insertion_point(message_implements:grpc.channelz.v1.ServerData)
    ServerDataOrBuilder {
private static final long serialVersionUID = 0L;
  // Use ServerData.newBuilder() to construct.
  private ServerData(com.google.protobuf.GeneratedMessageV3.Builder<?> builder) {
    super(builder);
  }
  private ServerData() {
    callsStarted_ = 0L;
    callsSucceeded_ = 0L;
    callsFailed_ = 0L;
  }

  @java.lang.Override
  public final com.google.protobuf.UnknownFieldSet
  getUnknownFields() {
    return this.unknownFields;
  }
  private ServerData(
      com.google.protobuf.CodedInputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    this();
    if (extensionRegistry == null) {
      throw new java.lang.NullPointerException();
    }
    int mutable_bitField0_ = 0;
    com.google.protobuf.UnknownFieldSet.Builder unknownFields =
        com.google.protobuf.UnknownFieldSet.newBuilder();
    try {
      boolean done = false;
      while (!done) {
        int tag = input.readTag();
        switch (tag) {
          case 0:
            done = true;
            break;
          case 10: {
            io.grpc.channelz.v1.ChannelTrace.Builder subBuilder = null;
            if (trace_ != null) {
              subBuilder = trace_.toBuilder();
            }
            trace_ = input.readMessage(io.grpc.channelz.v1.ChannelTrace.parser(), extensionRegistry);
            if (subBuilder != null) {
              subBuilder.mergeFrom(trace_);
              trace_ = subBuilder.buildPartial();
            }

            break;
          }
          case 16: {

            callsStarted_ = input.readInt64();
            break;
          }
          case 24: {

            callsSucceeded_ = input.readInt64();
            break;
          }
          case 32: {

            callsFailed_ = input.readInt64();
            break;
          }
          case 42: {
            com.google.protobuf.Timestamp.Builder subBuilder = null;
            if (lastCallStartedTimestamp_ != null) {
              subBuilder = lastCallStartedTimestamp_.toBuilder();
            }
            lastCallStartedTimestamp_ = input.readMessage(com.google.protobuf.Timestamp.parser(), extensionRegistry);
            if (subBuilder != null) {
              subBuilder.mergeFrom(lastCallStartedTimestamp_);
              lastCallStartedTimestamp_ = subBuilder.buildPartial();
            }

            break;
          }
          default: {
            if (!parseUnknownFieldProto3(
                input, unknownFields, extensionRegistry, tag)) {
              done = true;
            }
            break;
          }
        }
      }
    } catch (com.google.protobuf.InvalidProtocolBufferException e) {
      throw e.setUnfinishedMessage(this);
    } catch (java.io.IOException e) {
      throw new com.google.protobuf.InvalidProtocolBufferException(
          e).setUnfinishedMessage(this);
    } finally {
      this.unknownFields = unknownFields.build();
      makeExtensionsImmutable();
    }
  }
  public static final com.google.protobuf.Descriptors.Descriptor
      getDescriptor() {
    return io.grpc.channelz.v1.ChannelzProto.internal_static_grpc_channelz_v1_ServerData_descriptor;
  }

  @java.lang.Override
  protected com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internalGetFieldAccessorTable() {
    return io.grpc.channelz.v1.ChannelzProto.internal_static_grpc_channelz_v1_ServerData_fieldAccessorTable
        .ensureFieldAccessorsInitialized(
            io.grpc.channelz.v1.ServerData.class, io.grpc.channelz.v1.ServerData.Builder.class);
  }

  public static final int TRACE_FIELD_NUMBER = 1;
  private io.grpc.channelz.v1.ChannelTrace trace_;
  /**
   * <pre>
   * A trace of recent events on the server.  May be absent.
   * </pre>
   *
   * <code>.grpc.channelz.v1.ChannelTrace trace = 1;</code>
   */
  public boolean hasTrace() {
    return trace_ != null;
  }
  /**
   * <pre>
   * A trace of recent events on the server.  May be absent.
   * </pre>
   *
   * <code>.grpc.channelz.v1.ChannelTrace trace = 1;</code>
   */
  public io.grpc.channelz.v1.ChannelTrace getTrace() {
    return trace_ == null ? io.grpc.channelz.v1.ChannelTrace.getDefaultInstance() : trace_;
  }
  /**
   * <pre>
   * A trace of recent events on the server.  May be absent.
   * </pre>
   *
   * <code>.grpc.channelz.v1.ChannelTrace trace = 1;</code>
   */
  public io.grpc.channelz.v1.ChannelTraceOrBuilder getTraceOrBuilder() {
    return getTrace();
  }

  public static final int CALLS_STARTED_FIELD_NUMBER = 2;
  private long callsStarted_;
  /**
   * <pre>
   * The number of incoming calls started on the server
   * </pre>
   *
   * <code>int64 calls_started = 2;</code>
   */
  public long getCallsStarted() {
    return callsStarted_;
  }

  public static final int CALLS_SUCCEEDED_FIELD_NUMBER = 3;
  private long callsSucceeded_;
  /**
   * <pre>
   * The number of incoming calls that have completed with an OK status
   * </pre>
   *
   * <code>int64 calls_succeeded = 3;</code>
   */
  public long getCallsSucceeded() {
    return callsSucceeded_;
  }

  public static final int CALLS_FAILED_FIELD_NUMBER = 4;
  private long callsFailed_;
  /**
   * <pre>
   * The number of incoming calls that have a completed with a non-OK status
   * </pre>
   *
   * <code>int64 calls_failed = 4;</code>
   */
  public long getCallsFailed() {
    return callsFailed_;
  }

  public static final int LAST_CALL_STARTED_TIMESTAMP_FIELD_NUMBER = 5;
  private com.google.protobuf.Timestamp lastCallStartedTimestamp_;
  /**
   * <pre>
   * The last time a call was started on the server.
   * </pre>
   *
   * <code>.google.protobuf.Timestamp last_call_started_timestamp = 5;</code>
   */
  public boolean hasLastCallStartedTimestamp() {
    return lastCallStartedTimestamp_ != null;
  }
  /**
   * <pre>
   * The last time a call was started on the server.
   * </pre>
   *
   * <code>.google.protobuf.Timestamp last_call_started_timestamp = 5;</code>
   */
  public com.google.protobuf.Timestamp getLastCallStartedTimestamp() {
    return lastCallStartedTimestamp_ == null ? com.google.protobuf.Timestamp.getDefaultInstance() : lastCallStartedTimestamp_;
  }
  /**
   * <pre>
   * The last time a call was started on the server.
   * </pre>
   *
   * <code>.google.protobuf.Timestamp last_call_started_timestamp = 5;</code>
   */
  public com.google.protobuf.TimestampOrBuilder getLastCallStartedTimestampOrBuilder() {
    return getLastCallStartedTimestamp();
  }

  private byte memoizedIsInitialized = -1;
  @java.lang.Override
  public final boolean isInitialized() {
    byte isInitialized = memoizedIsInitialized;
    if (isInitialized == 1) return true;
    if (isInitialized == 0) return false;

    memoizedIsInitialized = 1;
    return true;
  }

  @java.lang.Override
  public void writeTo(com.google.protobuf.CodedOutputStream output)
                      throws java.io.IOException {
    if (trace_ != null) {
      output.writeMessage(1, getTrace());
    }
    if (callsStarted_ != 0L) {
      output.writeInt64(2, callsStarted_);
    }
    if (callsSucceeded_ != 0L) {
      output.writeInt64(3, callsSucceeded_);
    }
    if (callsFailed_ != 0L) {
      output.writeInt64(4, callsFailed_);
    }
    if (lastCallStartedTimestamp_ != null) {
      output.writeMessage(5, getLastCallStartedTimestamp());
    }
    unknownFields.writeTo(output);
  }

  @java.lang.Override
  public int getSerializedSize() {
    int size = memoizedSize;
    if (size != -1) return size;

    size = 0;
    if (trace_ != null) {
      size += com.google.protobuf.CodedOutputStream
        .computeMessageSize(1, getTrace());
    }
    if (callsStarted_ != 0L) {
      size += com.google.protobuf.CodedOutputStream
        .computeInt64Size(2, callsStarted_);
    }
    if (callsSucceeded_ != 0L) {
      size += com.google.protobuf.CodedOutputStream
        .computeInt64Size(3, callsSucceeded_);
    }
    if (callsFailed_ != 0L) {
      size += com.google.protobuf.CodedOutputStream
        .computeInt64Size(4, callsFailed_);
    }
    if (lastCallStartedTimestamp_ != null) {
      size += com.google.protobuf.CodedOutputStream
        .computeMessageSize(5, getLastCallStartedTimestamp());
    }
    size += unknownFields.getSerializedSize();
    memoizedSize = size;
    return size;
  }

  @java.lang.Override
  public boolean equals(final java.lang.Object obj) {
    if (obj == this) {
     return true;
    }
    if (!(obj instanceof io.grpc.channelz.v1.ServerData)) {
      return super.equals(obj);
    }
    io.grpc.channelz.v1.ServerData other = (io.grpc.channelz.v1.ServerData) obj;

    boolean result = true;
    result = result && (hasTrace() == other.hasTrace());
    if (hasTrace()) {
      result = result && getTrace()
          .equals(other.getTrace());
    }
    result = result && (getCallsStarted()
        == other.getCallsStarted());
    result = result && (getCallsSucceeded()
        == other.getCallsSucceeded());
    result = result && (getCallsFailed()
        == other.getCallsFailed());
    result = result && (hasLastCallStartedTimestamp() == other.hasLastCallStartedTimestamp());
    if (hasLastCallStartedTimestamp()) {
      result = result && getLastCallStartedTimestamp()
          .equals(other.getLastCallStartedTimestamp());
    }
    result = result && unknownFields.equals(other.unknownFields);
    return result;
  }

  @java.lang.Override
  public int hashCode() {
    if (memoizedHashCode != 0) {
      return memoizedHashCode;
    }
    int hash = 41;
    hash = (19 * hash) + getDescriptor().hashCode();
    if (hasTrace()) {
      hash = (37 * hash) + TRACE_FIELD_NUMBER;
      hash = (53 * hash) + getTrace().hashCode();
    }
    hash = (37 * hash) + CALLS_STARTED_FIELD_NUMBER;
    hash = (53 * hash) + com.google.protobuf.Internal.hashLong(
        getCallsStarted());
    hash = (37 * hash) + CALLS_SUCCEEDED_FIELD_NUMBER;
    hash = (53 * hash) + com.google.protobuf.Internal.hashLong(
        getCallsSucceeded());
    hash = (37 * hash) + CALLS_FAILED_FIELD_NUMBER;
    hash = (53 * hash) + com.google.protobuf.Internal.hashLong(
        getCallsFailed());
    if (hasLastCallStartedTimestamp()) {
      hash = (37 * hash) + LAST_CALL_STARTED_TIMESTAMP_FIELD_NUMBER;
      hash = (53 * hash) + getLastCallStartedTimestamp().hashCode();
    }
    hash = (29 * hash) + unknownFields.hashCode();
    memoizedHashCode = hash;
    return hash;
  }

  public static io.grpc.channelz.v1.ServerData parseFrom(
      java.nio.ByteBuffer data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.grpc.channelz.v1.ServerData parseFrom(
      java.nio.ByteBuffer data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.grpc.channelz.v1.ServerData parseFrom(
      com.google.protobuf.ByteString data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.grpc.channelz.v1.ServerData parseFrom(
      com.google.protobuf.ByteString data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.grpc.channelz.v1.ServerData parseFrom(byte[] data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.grpc.channelz.v1.ServerData parseFrom(
      byte[] data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.grpc.channelz.v1.ServerData parseFrom(java.io.InputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input);
  }
  public static io.grpc.channelz.v1.ServerData parseFrom(
      java.io.InputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input, extensionRegistry);
  }
  public static io.grpc.channelz.v1.ServerData parseDelimitedFrom(java.io.InputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseDelimitedWithIOException(PARSER, input);
  }
  public static io.grpc.channelz.v1.ServerData parseDelimitedFrom(
      java.io.InputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseDelimitedWithIOException(PARSER, input, extensionRegistry);
  }
  public static io.grpc.channelz.v1.ServerData parseFrom(
      com.google.protobuf.CodedInputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input);
  }
  public static io.grpc.channelz.v1.ServerData parseFrom(
      com.google.protobuf.CodedInputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input, extensionRegistry);
  }

  @java.lang.Override
  public Builder newBuilderForType() { return newBuilder(); }
  public static Builder newBuilder() {
    return DEFAULT_INSTANCE.toBuilder();
  }
  public static Builder newBuilder(io.grpc.channelz.v1.ServerData prototype) {
    return DEFAULT_INSTANCE.toBuilder().mergeFrom(prototype);
  }
  @java.lang.Override
  public Builder toBuilder() {
    return this == DEFAULT_INSTANCE
        ? new Builder() : new Builder().mergeFrom(this);
  }

  @java.lang.Override
  protected Builder newBuilderForType(
      com.google.protobuf.GeneratedMessageV3.BuilderParent parent) {
    Builder builder = new Builder(parent);
    return builder;
  }
  /**
   * <pre>
   * ServerData is data for a specific Server.
   * </pre>
   *
   * Protobuf type {@code grpc.channelz.v1.ServerData}
   */
  public static final class Builder extends
      com.google.protobuf.GeneratedMessageV3.Builder<Builder> implements
      // @@protoc_insertion_point(builder_implements:grpc.channelz.v1.ServerData)
      io.grpc.channelz.v1.ServerDataOrBuilder {
    public static final com.google.protobuf.Descriptors.Descriptor
        getDescriptor() {
      return io.grpc.channelz.v1.ChannelzProto.internal_static_grpc_channelz_v1_ServerData_descriptor;
    }

    @java.lang.Override
    protected com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
        internalGetFieldAccessorTable() {
      return io.grpc.channelz.v1.ChannelzProto.internal_static_grpc_channelz_v1_ServerData_fieldAccessorTable
          .ensureFieldAccessorsInitialized(
              io.grpc.channelz.v1.ServerData.class, io.grpc.channelz.v1.ServerData.Builder.class);
    }

    // Construct using io.grpc.channelz.v1.ServerData.newBuilder()
    private Builder() {
      maybeForceBuilderInitialization();
    }

    private Builder(
        com.google.protobuf.GeneratedMessageV3.BuilderParent parent) {
      super(parent);
      maybeForceBuilderInitialization();
    }
    private void maybeForceBuilderInitialization() {
      if (com.google.protobuf.GeneratedMessageV3
              .alwaysUseFieldBuilders) {
      }
    }
    @java.lang.Override
    public Builder clear() {
      super.clear();
      if (traceBuilder_ == null) {
        trace_ = null;
      } else {
        trace_ = null;
        traceBuilder_ = null;
      }
      callsStarted_ = 0L;

      callsSucceeded_ = 0L;

      callsFailed_ = 0L;

      if (lastCallStartedTimestampBuilder_ == null) {
        lastCallStartedTimestamp_ = null;
      } else {
        lastCallStartedTimestamp_ = null;
        lastCallStartedTimestampBuilder_ = null;
      }
      return this;
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.Descriptor
        getDescriptorForType() {
      return io.grpc.channelz.v1.ChannelzProto.internal_static_grpc_channelz_v1_ServerData_descriptor;
    }

    @java.lang.Override
    public io.grpc.channelz.v1.ServerData getDefaultInstanceForType() {
      return io.grpc.channelz.v1.ServerData.getDefaultInstance();
    }

    @java.lang.Override
    public io.grpc.channelz.v1.ServerData build() {
      io.grpc.channelz.v1.ServerData result = buildPartial();
      if (!result.isInitialized()) {
        throw newUninitializedMessageException(result);
      }
      return result;
    }

    @java.lang.Override
    public io.grpc.channelz.v1.ServerData buildPartial() {
      io.grpc.channelz.v1.ServerData result = new io.grpc.channelz.v1.ServerData(this);
      if (traceBuilder_ == null) {
        result.trace_ = trace_;
      } else {
        result.trace_ = traceBuilder_.build();
      }
      result.callsStarted_ = callsStarted_;
      result.callsSucceeded_ = callsSucceeded_;
      result.callsFailed_ = callsFailed_;
      if (lastCallStartedTimestampBuilder_ == null) {
        result.lastCallStartedTimestamp_ = lastCallStartedTimestamp_;
      } else {
        result.lastCallStartedTimestamp_ = lastCallStartedTimestampBuilder_.build();
      }
      onBuilt();
      return result;
    }

    @java.lang.Override
    public Builder clone() {
      return (Builder) super.clone();
    }
    @java.lang.Override
    public Builder setField(
        com.google.protobuf.Descriptors.FieldDescriptor field,
        java.lang.Object value) {
      return (Builder) super.setField(field, value);
    }
    @java.lang.Override
    public Builder clearField(
        com.google.protobuf.Descriptors.FieldDescriptor field) {
      return (Builder) super.clearField(field);
    }
    @java.lang.Override
    public Builder clearOneof(
        com.google.protobuf.Descriptors.OneofDescriptor oneof) {
      return (Builder) super.clearOneof(oneof);
    }
    @java.lang.Override
    public Builder setRepeatedField(
        com.google.protobuf.Descriptors.FieldDescriptor field,
        int index, java.lang.Object value) {
      return (Builder) super.setRepeatedField(field, index, value);
    }
    @java.lang.Override
    public Builder addRepeatedField(
        com.google.protobuf.Descriptors.FieldDescriptor field,
        java.lang.Object value) {
      return (Builder) super.addRepeatedField(field, value);
    }
    @java.lang.Override
    public Builder mergeFrom(com.google.protobuf.Message other) {
      if (other instanceof io.grpc.channelz.v1.ServerData) {
        return mergeFrom((io.grpc.channelz.v1.ServerData)other);
      } else {
        super.mergeFrom(other);
        return this;
      }
    }

    public Builder mergeFrom(io.grpc.channelz.v1.ServerData other) {
      if (other == io.grpc.channelz.v1.ServerData.getDefaultInstance()) return this;
      if (other.hasTrace()) {
        mergeTrace(other.getTrace());
      }
      if (other.getCallsStarted() != 0L) {
        setCallsStarted(other.getCallsStarted());
      }
      if (other.getCallsSucceeded() != 0L) {
        setCallsSucceeded(other.getCallsSucceeded());
      }
      if (other.getCallsFailed() != 0L) {
        setCallsFailed(other.getCallsFailed());
      }
      if (other.hasLastCallStartedTimestamp()) {
        mergeLastCallStartedTimestamp(other.getLastCallStartedTimestamp());
      }
      this.mergeUnknownFields(other.unknownFields);
      onChanged();
      return this;
    }

    @java.lang.Override
    public final boolean isInitialized() {
      return true;
    }

    @java.lang.Override
    public Builder mergeFrom(
        com.google.protobuf.CodedInputStream input,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws java.io.IOException {
      io.grpc.channelz.v1.ServerData parsedMessage = null;
      try {
        parsedMessage = PARSER.parsePartialFrom(input, extensionRegistry);
      } catch (com.google.protobuf.InvalidProtocolBufferException e) {
        parsedMessage = (io.grpc.channelz.v1.ServerData) e.getUnfinishedMessage();
        throw e.unwrapIOException();
      } finally {
        if (parsedMessage != null) {
          mergeFrom(parsedMessage);
        }
      }
      return this;
    }

    private io.grpc.channelz.v1.ChannelTrace trace_ = null;
    private com.google.protobuf.SingleFieldBuilderV3<
        io.grpc.channelz.v1.ChannelTrace, io.grpc.channelz.v1.ChannelTrace.Builder, io.grpc.channelz.v1.ChannelTraceOrBuilder> traceBuilder_;
    /**
     * <pre>
     * A trace of recent events on the server.  May be absent.
     * </pre>
     *
     * <code>.grpc.channelz.v1.ChannelTrace trace = 1;</code>
     */
    public boolean hasTrace() {
      return traceBuilder_ != null || trace_ != null;
    }
    /**
     * <pre>
     * A trace of recent events on the server.  May be absent.
     * </pre>
     *
     * <code>.grpc.channelz.v1.ChannelTrace trace = 1;</code>
     */
    public io.grpc.channelz.v1.ChannelTrace getTrace() {
      if (traceBuilder_ == null) {
        return trace_ == null ? io.grpc.channelz.v1.ChannelTrace.getDefaultInstance() : trace_;
      } else {
        return traceBuilder_.getMessage();
      }
    }
    /**
     * <pre>
     * A trace of recent events on the server.  May be absent.
     * </pre>
     *
     * <code>.grpc.channelz.v1.ChannelTrace trace = 1;</code>
     */
    public Builder setTrace(io.grpc.channelz.v1.ChannelTrace value) {
      if (traceBuilder_ == null) {
        if (value == null) {
          throw new NullPointerException();
        }
        trace_ = value;
        onChanged();
      } else {
        traceBuilder_.setMessage(value);
      }

      return this;
    }
    /**
     * <pre>
     * A trace of recent events on the server.  May be absent.
     * </pre>
     *
     * <code>.grpc.channelz.v1.ChannelTrace trace = 1;</code>
     */
    public Builder setTrace(
        io.grpc.channelz.v1.ChannelTrace.Builder builderForValue) {
      if (traceBuilder_ == null) {
        trace_ = builderForValue.build();
        onChanged();
      } else {
        traceBuilder_.setMessage(builderForValue.build());
      }

      return this;
    }
    /**
     * <pre>
     * A trace of recent events on the server.  May be absent.
     * </pre>
     *
     * <code>.grpc.channelz.v1.ChannelTrace trace = 1;</code>
     */
    public Builder mergeTrace(io.grpc.channelz.v1.ChannelTrace value) {
      if (traceBuilder_ == null) {
        if (trace_ != null) {
          trace_ =
            io.grpc.channelz.v1.ChannelTrace.newBuilder(trace_).mergeFrom(value).buildPartial();
        } else {
          trace_ = value;
        }
        onChanged();
      } else {
        traceBuilder_.mergeFrom(value);
      }

      return this;
    }
    /**
     * <pre>
     * A trace of recent events on the server.  May be absent.
     * </pre>
     *
     * <code>.grpc.channelz.v1.ChannelTrace trace = 1;</code>
     */
    public Builder clearTrace() {
      if (traceBuilder_ == null) {
        trace_ = null;
        onChanged();
      } else {
        trace_ = null;
        traceBuilder_ = null;
      }

      return this;
    }
    /**
     * <pre>
     * A trace of recent events on the server.  May be absent.
     * </pre>
     *
     * <code>.grpc.channelz.v1.ChannelTrace trace = 1;</code>
     */
    public io.grpc.channelz.v1.ChannelTrace.Builder getTraceBuilder() {
      
      onChanged();
      return getTraceFieldBuilder().getBuilder();
    }
    /**
     * <pre>
     * A trace of recent events on the server.  May be absent.
     * </pre>
     *
     * <code>.grpc.channelz.v1.ChannelTrace trace = 1;</code>
     */
    public io.grpc.channelz.v1.ChannelTraceOrBuilder getTraceOrBuilder() {
      if (traceBuilder_ != null) {
        return traceBuilder_.getMessageOrBuilder();
      } else {
        return trace_ == null ?
            io.grpc.channelz.v1.ChannelTrace.getDefaultInstance() : trace_;
      }
    }
    /**
     * <pre>
     * A trace of recent events on the server.  May be absent.
     * </pre>
     *
     * <code>.grpc.channelz.v1.ChannelTrace trace = 1;</code>
     */
    private com.google.protobuf.SingleFieldBuilderV3<
        io.grpc.channelz.v1.ChannelTrace, io.grpc.channelz.v1.ChannelTrace.Builder, io.grpc.channelz.v1.ChannelTraceOrBuilder> 
        getTraceFieldBuilder() {
      if (traceBuilder_ == null) {
        traceBuilder_ = new com.google.protobuf.SingleFieldBuilderV3<
            io.grpc.channelz.v1.ChannelTrace, io.grpc.channelz.v1.ChannelTrace.Builder, io.grpc.channelz.v1.ChannelTraceOrBuilder>(
                getTrace(),
                getParentForChildren(),
                isClean());
        trace_ = null;
      }
      return traceBuilder_;
    }

    private long callsStarted_ ;
    /**
     * <pre>
     * The number of incoming calls started on the server
     * </pre>
     *
     * <code>int64 calls_started = 2;</code>
     */
    public long getCallsStarted() {
      return callsStarted_;
    }
    /**
     * <pre>
     * The number of incoming calls started on the server
     * </pre>
     *
     * <code>int64 calls_started = 2;</code>
     */
    public Builder setCallsStarted(long value) {
      
      callsStarted_ = value;
      onChanged();
      return this;
    }
    /**
     * <pre>
     * The number of incoming calls started on the server
     * </pre>
     *
     * <code>int64 calls_started = 2;</code>
     */
    public Builder clearCallsStarted() {
      
      callsStarted_ = 0L;
      onChanged();
      return this;
    }

    private long callsSucceeded_ ;
    /**
     * <pre>
     * The number of incoming calls that have completed with an OK status
     * </pre>
     *
     * <code>int64 calls_succeeded = 3;</code>
     */
    public long getCallsSucceeded() {
      return callsSucceeded_;
    }
    /**
     * <pre>
     * The number of incoming calls that have completed with an OK status
     * </pre>
     *
     * <code>int64 calls_succeeded = 3;</code>
     */
    public Builder setCallsSucceeded(long value) {
      
      callsSucceeded_ = value;
      onChanged();
      return this;
    }
    /**
     * <pre>
     * The number of incoming calls that have completed with an OK status
     * </pre>
     *
     * <code>int64 calls_succeeded = 3;</code>
     */
    public Builder clearCallsSucceeded() {
      
      callsSucceeded_ = 0L;
      onChanged();
      return this;
    }

    private long callsFailed_ ;
    /**
     * <pre>
     * The number of incoming calls that have a completed with a non-OK status
     * </pre>
     *
     * <code>int64 calls_failed = 4;</code>
     */
    public long getCallsFailed() {
      return callsFailed_;
    }
    /**
     * <pre>
     * The number of incoming calls that have a completed with a non-OK status
     * </pre>
     *
     * <code>int64 calls_failed = 4;</code>
     */
    public Builder setCallsFailed(long value) {
      
      callsFailed_ = value;
      onChanged();
      return this;
    }
    /**
     * <pre>
     * The number of incoming calls that have a completed with a non-OK status
     * </pre>
     *
     * <code>int64 calls_failed = 4;</code>
     */
    public Builder clearCallsFailed() {
      
      callsFailed_ = 0L;
      onChanged();
      return this;
    }

    private com.google.protobuf.Timestamp lastCallStartedTimestamp_ = null;
    private com.google.protobuf.SingleFieldBuilderV3<
        com.google.protobuf.Timestamp, com.google.protobuf.Timestamp.Builder, com.google.protobuf.TimestampOrBuilder> lastCallStartedTimestampBuilder_;
    /**
     * <pre>
     * The last time a call was started on the server.
     * </pre>
     *
     * <code>.google.protobuf.Timestamp last_call_started_timestamp = 5;</code>
     */
    public boolean hasLastCallStartedTimestamp() {
      return lastCallStartedTimestampBuilder_ != null || lastCallStartedTimestamp_ != null;
    }
    /**
     * <pre>
     * The last time a call was started on the server.
     * </pre>
     *
     * <code>.google.protobuf.Timestamp last_call_started_timestamp = 5;</code>
     */
    public com.google.protobuf.Timestamp getLastCallStartedTimestamp() {
      if (lastCallStartedTimestampBuilder_ == null) {
        return lastCallStartedTimestamp_ == null ? com.google.protobuf.Timestamp.getDefaultInstance() : lastCallStartedTimestamp_;
      } else {
        return lastCallStartedTimestampBuilder_.getMessage();
      }
    }
    /**
     * <pre>
     * The last time a call was started on the server.
     * </pre>
     *
     * <code>.google.protobuf.Timestamp last_call_started_timestamp = 5;</code>
     */
    public Builder setLastCallStartedTimestamp(com.google.protobuf.Timestamp value) {
      if (lastCallStartedTimestampBuilder_ == null) {
        if (value == null) {
          throw new NullPointerException();
        }
        lastCallStartedTimestamp_ = value;
        onChanged();
      } else {
        lastCallStartedTimestampBuilder_.setMessage(value);
      }

      return this;
    }
    /**
     * <pre>
     * The last time a call was started on the server.
     * </pre>
     *
     * <code>.google.protobuf.Timestamp last_call_started_timestamp = 5;</code>
     */
    public Builder setLastCallStartedTimestamp(
        com.google.protobuf.Timestamp.Builder builderForValue) {
      if (lastCallStartedTimestampBuilder_ == null) {
        lastCallStartedTimestamp_ = builderForValue.build();
        onChanged();
      } else {
        lastCallStartedTimestampBuilder_.setMessage(builderForValue.build());
      }

      return this;
    }
    /**
     * <pre>
     * The last time a call was started on the server.
     * </pre>
     *
     * <code>.google.protobuf.Timestamp last_call_started_timestamp = 5;</code>
     */
    public Builder mergeLastCallStartedTimestamp(com.google.protobuf.Timestamp value) {
      if (lastCallStartedTimestampBuilder_ == null) {
        if (lastCallStartedTimestamp_ != null) {
          lastCallStartedTimestamp_ =
            com.google.protobuf.Timestamp.newBuilder(lastCallStartedTimestamp_).mergeFrom(value).buildPartial();
        } else {
          lastCallStartedTimestamp_ = value;
        }
        onChanged();
      } else {
        lastCallStartedTimestampBuilder_.mergeFrom(value);
      }

      return this;
    }
    /**
     * <pre>
     * The last time a call was started on the server.
     * </pre>
     *
     * <code>.google.protobuf.Timestamp last_call_started_timestamp = 5;</code>
     */
    public Builder clearLastCallStartedTimestamp() {
      if (lastCallStartedTimestampBuilder_ == null) {
        lastCallStartedTimestamp_ = null;
        onChanged();
      } else {
        lastCallStartedTimestamp_ = null;
        lastCallStartedTimestampBuilder_ = null;
      }

      return this;
    }
    /**
     * <pre>
     * The last time a call was started on the server.
     * </pre>
     *
     * <code>.google.protobuf.Timestamp last_call_started_timestamp = 5;</code>
     */
    public com.google.protobuf.Timestamp.Builder getLastCallStartedTimestampBuilder() {
      
      onChanged();
      return getLastCallStartedTimestampFieldBuilder().getBuilder();
    }
    /**
     * <pre>
     * The last time a call was started on the server.
     * </pre>
     *
     * <code>.google.protobuf.Timestamp last_call_started_timestamp = 5;</code>
     */
    public com.google.protobuf.TimestampOrBuilder getLastCallStartedTimestampOrBuilder() {
      if (lastCallStartedTimestampBuilder_ != null) {
        return lastCallStartedTimestampBuilder_.getMessageOrBuilder();
      } else {
        return lastCallStartedTimestamp_ == null ?
            com.google.protobuf.Timestamp.getDefaultInstance() : lastCallStartedTimestamp_;
      }
    }
    /**
     * <pre>
     * The last time a call was started on the server.
     * </pre>
     *
     * <code>.google.protobuf.Timestamp last_call_started_timestamp = 5;</code>
     */
    private com.google.protobuf.SingleFieldBuilderV3<
        com.google.protobuf.Timestamp, com.google.protobuf.Timestamp.Builder, com.google.protobuf.TimestampOrBuilder> 
        getLastCallStartedTimestampFieldBuilder() {
      if (lastCallStartedTimestampBuilder_ == null) {
        lastCallStartedTimestampBuilder_ = new com.google.protobuf.SingleFieldBuilderV3<
            com.google.protobuf.Timestamp, com.google.protobuf.Timestamp.Builder, com.google.protobuf.TimestampOrBuilder>(
                getLastCallStartedTimestamp(),
                getParentForChildren(),
                isClean());
        lastCallStartedTimestamp_ = null;
      }
      return lastCallStartedTimestampBuilder_;
    }
    @java.lang.Override
    public final Builder setUnknownFields(
        final com.google.protobuf.UnknownFieldSet unknownFields) {
      return super.setUnknownFieldsProto3(unknownFields);
    }

    @java.lang.Override
    public final Builder mergeUnknownFields(
        final com.google.protobuf.UnknownFieldSet unknownFields) {
      return super.mergeUnknownFields(unknownFields);
    }


    // @@protoc_insertion_point(builder_scope:grpc.channelz.v1.ServerData)
  }

  // @@protoc_insertion_point(class_scope:grpc.channelz.v1.ServerData)
  private static final io.grpc.channelz.v1.ServerData DEFAULT_INSTANCE;
  static {
    DEFAULT_INSTANCE = new io.grpc.channelz.v1.ServerData();
  }

  public static io.grpc.channelz.v1.ServerData getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private static final com.google.protobuf.Parser<ServerData>
      PARSER = new com.google.protobuf.AbstractParser<ServerData>() {
    @java.lang.Override
    public ServerData parsePartialFrom(
        com.google.protobuf.CodedInputStream input,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws com.google.protobuf.InvalidProtocolBufferException {
      return new ServerData(input, extensionRegistry);
    }
  };

  public static com.google.protobuf.Parser<ServerData> parser() {
    return PARSER;
  }

  @java.lang.Override
  public com.google.protobuf.Parser<ServerData> getParserForType() {
    return PARSER;
  }

  @java.lang.Override
  public io.grpc.channelz.v1.ServerData getDefaultInstanceForType() {
    return DEFAULT_INSTANCE;
  }

}

