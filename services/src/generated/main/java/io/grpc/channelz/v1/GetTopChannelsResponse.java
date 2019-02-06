// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: grpc/channelz/v1/channelz.proto

package io.grpc.channelz.v1;

/**
 * Protobuf type {@code grpc.channelz.v1.GetTopChannelsResponse}
 */
public  final class GetTopChannelsResponse extends
    com.google.protobuf.GeneratedMessageV3 implements
    // @@protoc_insertion_point(message_implements:grpc.channelz.v1.GetTopChannelsResponse)
    GetTopChannelsResponseOrBuilder {
private static final long serialVersionUID = 0L;
  // Use GetTopChannelsResponse.newBuilder() to construct.
  private GetTopChannelsResponse(com.google.protobuf.GeneratedMessageV3.Builder<?> builder) {
    super(builder);
  }
  private GetTopChannelsResponse() {
    channel_ = java.util.Collections.emptyList();
    end_ = false;
  }

  @java.lang.Override
  public final com.google.protobuf.UnknownFieldSet
  getUnknownFields() {
    return this.unknownFields;
  }
  private GetTopChannelsResponse(
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
            if (!((mutable_bitField0_ & 0x00000001) == 0x00000001)) {
              channel_ = new java.util.ArrayList<io.grpc.channelz.v1.Channel>();
              mutable_bitField0_ |= 0x00000001;
            }
            channel_.add(
                input.readMessage(io.grpc.channelz.v1.Channel.parser(), extensionRegistry));
            break;
          }
          case 16: {

            end_ = input.readBool();
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
      if (((mutable_bitField0_ & 0x00000001) == 0x00000001)) {
        channel_ = java.util.Collections.unmodifiableList(channel_);
      }
      this.unknownFields = unknownFields.build();
      makeExtensionsImmutable();
    }
  }
  public static final com.google.protobuf.Descriptors.Descriptor
      getDescriptor() {
    return io.grpc.channelz.v1.ChannelzProto.internal_static_grpc_channelz_v1_GetTopChannelsResponse_descriptor;
  }

  @java.lang.Override
  protected com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internalGetFieldAccessorTable() {
    return io.grpc.channelz.v1.ChannelzProto.internal_static_grpc_channelz_v1_GetTopChannelsResponse_fieldAccessorTable
        .ensureFieldAccessorsInitialized(
            io.grpc.channelz.v1.GetTopChannelsResponse.class, io.grpc.channelz.v1.GetTopChannelsResponse.Builder.class);
  }

  private int bitField0_;
  public static final int CHANNEL_FIELD_NUMBER = 1;
  private java.util.List<io.grpc.channelz.v1.Channel> channel_;
  /**
   * <pre>
   * list of channels that the connection detail service knows about.  Sorted in
   * ascending channel_id order.
   * Must contain at least 1 result, otherwise 'end' must be true.
   * </pre>
   *
   * <code>repeated .grpc.channelz.v1.Channel channel = 1;</code>
   */
  public java.util.List<io.grpc.channelz.v1.Channel> getChannelList() {
    return channel_;
  }
  /**
   * <pre>
   * list of channels that the connection detail service knows about.  Sorted in
   * ascending channel_id order.
   * Must contain at least 1 result, otherwise 'end' must be true.
   * </pre>
   *
   * <code>repeated .grpc.channelz.v1.Channel channel = 1;</code>
   */
  public java.util.List<? extends io.grpc.channelz.v1.ChannelOrBuilder> 
      getChannelOrBuilderList() {
    return channel_;
  }
  /**
   * <pre>
   * list of channels that the connection detail service knows about.  Sorted in
   * ascending channel_id order.
   * Must contain at least 1 result, otherwise 'end' must be true.
   * </pre>
   *
   * <code>repeated .grpc.channelz.v1.Channel channel = 1;</code>
   */
  public int getChannelCount() {
    return channel_.size();
  }
  /**
   * <pre>
   * list of channels that the connection detail service knows about.  Sorted in
   * ascending channel_id order.
   * Must contain at least 1 result, otherwise 'end' must be true.
   * </pre>
   *
   * <code>repeated .grpc.channelz.v1.Channel channel = 1;</code>
   */
  public io.grpc.channelz.v1.Channel getChannel(int index) {
    return channel_.get(index);
  }
  /**
   * <pre>
   * list of channels that the connection detail service knows about.  Sorted in
   * ascending channel_id order.
   * Must contain at least 1 result, otherwise 'end' must be true.
   * </pre>
   *
   * <code>repeated .grpc.channelz.v1.Channel channel = 1;</code>
   */
  public io.grpc.channelz.v1.ChannelOrBuilder getChannelOrBuilder(
      int index) {
    return channel_.get(index);
  }

  public static final int END_FIELD_NUMBER = 2;
  private boolean end_;
  /**
   * <pre>
   * If set, indicates that the list of channels is the final list.  Requesting
   * more channels can only return more if they are created after this RPC
   * completes.
   * </pre>
   *
   * <code>bool end = 2;</code>
   */
  public boolean getEnd() {
    return end_;
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
    for (int i = 0; i < channel_.size(); i++) {
      output.writeMessage(1, channel_.get(i));
    }
    if (end_ != false) {
      output.writeBool(2, end_);
    }
    unknownFields.writeTo(output);
  }

  @java.lang.Override
  public int getSerializedSize() {
    int size = memoizedSize;
    if (size != -1) return size;

    size = 0;
    for (int i = 0; i < channel_.size(); i++) {
      size += com.google.protobuf.CodedOutputStream
        .computeMessageSize(1, channel_.get(i));
    }
    if (end_ != false) {
      size += com.google.protobuf.CodedOutputStream
        .computeBoolSize(2, end_);
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
    if (!(obj instanceof io.grpc.channelz.v1.GetTopChannelsResponse)) {
      return super.equals(obj);
    }
    io.grpc.channelz.v1.GetTopChannelsResponse other = (io.grpc.channelz.v1.GetTopChannelsResponse) obj;

    boolean result = true;
    result = result && getChannelList()
        .equals(other.getChannelList());
    result = result && (getEnd()
        == other.getEnd());
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
    if (getChannelCount() > 0) {
      hash = (37 * hash) + CHANNEL_FIELD_NUMBER;
      hash = (53 * hash) + getChannelList().hashCode();
    }
    hash = (37 * hash) + END_FIELD_NUMBER;
    hash = (53 * hash) + com.google.protobuf.Internal.hashBoolean(
        getEnd());
    hash = (29 * hash) + unknownFields.hashCode();
    memoizedHashCode = hash;
    return hash;
  }

  public static io.grpc.channelz.v1.GetTopChannelsResponse parseFrom(
      java.nio.ByteBuffer data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.grpc.channelz.v1.GetTopChannelsResponse parseFrom(
      java.nio.ByteBuffer data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.grpc.channelz.v1.GetTopChannelsResponse parseFrom(
      com.google.protobuf.ByteString data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.grpc.channelz.v1.GetTopChannelsResponse parseFrom(
      com.google.protobuf.ByteString data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.grpc.channelz.v1.GetTopChannelsResponse parseFrom(byte[] data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.grpc.channelz.v1.GetTopChannelsResponse parseFrom(
      byte[] data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.grpc.channelz.v1.GetTopChannelsResponse parseFrom(java.io.InputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input);
  }
  public static io.grpc.channelz.v1.GetTopChannelsResponse parseFrom(
      java.io.InputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input, extensionRegistry);
  }
  public static io.grpc.channelz.v1.GetTopChannelsResponse parseDelimitedFrom(java.io.InputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseDelimitedWithIOException(PARSER, input);
  }
  public static io.grpc.channelz.v1.GetTopChannelsResponse parseDelimitedFrom(
      java.io.InputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseDelimitedWithIOException(PARSER, input, extensionRegistry);
  }
  public static io.grpc.channelz.v1.GetTopChannelsResponse parseFrom(
      com.google.protobuf.CodedInputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input);
  }
  public static io.grpc.channelz.v1.GetTopChannelsResponse parseFrom(
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
  public static Builder newBuilder(io.grpc.channelz.v1.GetTopChannelsResponse prototype) {
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
   * Protobuf type {@code grpc.channelz.v1.GetTopChannelsResponse}
   */
  public static final class Builder extends
      com.google.protobuf.GeneratedMessageV3.Builder<Builder> implements
      // @@protoc_insertion_point(builder_implements:grpc.channelz.v1.GetTopChannelsResponse)
      io.grpc.channelz.v1.GetTopChannelsResponseOrBuilder {
    public static final com.google.protobuf.Descriptors.Descriptor
        getDescriptor() {
      return io.grpc.channelz.v1.ChannelzProto.internal_static_grpc_channelz_v1_GetTopChannelsResponse_descriptor;
    }

    @java.lang.Override
    protected com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
        internalGetFieldAccessorTable() {
      return io.grpc.channelz.v1.ChannelzProto.internal_static_grpc_channelz_v1_GetTopChannelsResponse_fieldAccessorTable
          .ensureFieldAccessorsInitialized(
              io.grpc.channelz.v1.GetTopChannelsResponse.class, io.grpc.channelz.v1.GetTopChannelsResponse.Builder.class);
    }

    // Construct using io.grpc.channelz.v1.GetTopChannelsResponse.newBuilder()
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
        getChannelFieldBuilder();
      }
    }
    @java.lang.Override
    public Builder clear() {
      super.clear();
      if (channelBuilder_ == null) {
        channel_ = java.util.Collections.emptyList();
        bitField0_ = (bitField0_ & ~0x00000001);
      } else {
        channelBuilder_.clear();
      }
      end_ = false;

      return this;
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.Descriptor
        getDescriptorForType() {
      return io.grpc.channelz.v1.ChannelzProto.internal_static_grpc_channelz_v1_GetTopChannelsResponse_descriptor;
    }

    @java.lang.Override
    public io.grpc.channelz.v1.GetTopChannelsResponse getDefaultInstanceForType() {
      return io.grpc.channelz.v1.GetTopChannelsResponse.getDefaultInstance();
    }

    @java.lang.Override
    public io.grpc.channelz.v1.GetTopChannelsResponse build() {
      io.grpc.channelz.v1.GetTopChannelsResponse result = buildPartial();
      if (!result.isInitialized()) {
        throw newUninitializedMessageException(result);
      }
      return result;
    }

    @java.lang.Override
    public io.grpc.channelz.v1.GetTopChannelsResponse buildPartial() {
      io.grpc.channelz.v1.GetTopChannelsResponse result = new io.grpc.channelz.v1.GetTopChannelsResponse(this);
      int from_bitField0_ = bitField0_;
      int to_bitField0_ = 0;
      if (channelBuilder_ == null) {
        if (((bitField0_ & 0x00000001) == 0x00000001)) {
          channel_ = java.util.Collections.unmodifiableList(channel_);
          bitField0_ = (bitField0_ & ~0x00000001);
        }
        result.channel_ = channel_;
      } else {
        result.channel_ = channelBuilder_.build();
      }
      result.end_ = end_;
      result.bitField0_ = to_bitField0_;
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
      if (other instanceof io.grpc.channelz.v1.GetTopChannelsResponse) {
        return mergeFrom((io.grpc.channelz.v1.GetTopChannelsResponse)other);
      } else {
        super.mergeFrom(other);
        return this;
      }
    }

    public Builder mergeFrom(io.grpc.channelz.v1.GetTopChannelsResponse other) {
      if (other == io.grpc.channelz.v1.GetTopChannelsResponse.getDefaultInstance()) return this;
      if (channelBuilder_ == null) {
        if (!other.channel_.isEmpty()) {
          if (channel_.isEmpty()) {
            channel_ = other.channel_;
            bitField0_ = (bitField0_ & ~0x00000001);
          } else {
            ensureChannelIsMutable();
            channel_.addAll(other.channel_);
          }
          onChanged();
        }
      } else {
        if (!other.channel_.isEmpty()) {
          if (channelBuilder_.isEmpty()) {
            channelBuilder_.dispose();
            channelBuilder_ = null;
            channel_ = other.channel_;
            bitField0_ = (bitField0_ & ~0x00000001);
            channelBuilder_ = 
              com.google.protobuf.GeneratedMessageV3.alwaysUseFieldBuilders ?
                 getChannelFieldBuilder() : null;
          } else {
            channelBuilder_.addAllMessages(other.channel_);
          }
        }
      }
      if (other.getEnd() != false) {
        setEnd(other.getEnd());
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
      io.grpc.channelz.v1.GetTopChannelsResponse parsedMessage = null;
      try {
        parsedMessage = PARSER.parsePartialFrom(input, extensionRegistry);
      } catch (com.google.protobuf.InvalidProtocolBufferException e) {
        parsedMessage = (io.grpc.channelz.v1.GetTopChannelsResponse) e.getUnfinishedMessage();
        throw e.unwrapIOException();
      } finally {
        if (parsedMessage != null) {
          mergeFrom(parsedMessage);
        }
      }
      return this;
    }
    private int bitField0_;

    private java.util.List<io.grpc.channelz.v1.Channel> channel_ =
      java.util.Collections.emptyList();
    private void ensureChannelIsMutable() {
      if (!((bitField0_ & 0x00000001) == 0x00000001)) {
        channel_ = new java.util.ArrayList<io.grpc.channelz.v1.Channel>(channel_);
        bitField0_ |= 0x00000001;
       }
    }

    private com.google.protobuf.RepeatedFieldBuilderV3<
        io.grpc.channelz.v1.Channel, io.grpc.channelz.v1.Channel.Builder, io.grpc.channelz.v1.ChannelOrBuilder> channelBuilder_;

    /**
     * <pre>
     * list of channels that the connection detail service knows about.  Sorted in
     * ascending channel_id order.
     * Must contain at least 1 result, otherwise 'end' must be true.
     * </pre>
     *
     * <code>repeated .grpc.channelz.v1.Channel channel = 1;</code>
     */
    public java.util.List<io.grpc.channelz.v1.Channel> getChannelList() {
      if (channelBuilder_ == null) {
        return java.util.Collections.unmodifiableList(channel_);
      } else {
        return channelBuilder_.getMessageList();
      }
    }
    /**
     * <pre>
     * list of channels that the connection detail service knows about.  Sorted in
     * ascending channel_id order.
     * Must contain at least 1 result, otherwise 'end' must be true.
     * </pre>
     *
     * <code>repeated .grpc.channelz.v1.Channel channel = 1;</code>
     */
    public int getChannelCount() {
      if (channelBuilder_ == null) {
        return channel_.size();
      } else {
        return channelBuilder_.getCount();
      }
    }
    /**
     * <pre>
     * list of channels that the connection detail service knows about.  Sorted in
     * ascending channel_id order.
     * Must contain at least 1 result, otherwise 'end' must be true.
     * </pre>
     *
     * <code>repeated .grpc.channelz.v1.Channel channel = 1;</code>
     */
    public io.grpc.channelz.v1.Channel getChannel(int index) {
      if (channelBuilder_ == null) {
        return channel_.get(index);
      } else {
        return channelBuilder_.getMessage(index);
      }
    }
    /**
     * <pre>
     * list of channels that the connection detail service knows about.  Sorted in
     * ascending channel_id order.
     * Must contain at least 1 result, otherwise 'end' must be true.
     * </pre>
     *
     * <code>repeated .grpc.channelz.v1.Channel channel = 1;</code>
     */
    public Builder setChannel(
        int index, io.grpc.channelz.v1.Channel value) {
      if (channelBuilder_ == null) {
        if (value == null) {
          throw new NullPointerException();
        }
        ensureChannelIsMutable();
        channel_.set(index, value);
        onChanged();
      } else {
        channelBuilder_.setMessage(index, value);
      }
      return this;
    }
    /**
     * <pre>
     * list of channels that the connection detail service knows about.  Sorted in
     * ascending channel_id order.
     * Must contain at least 1 result, otherwise 'end' must be true.
     * </pre>
     *
     * <code>repeated .grpc.channelz.v1.Channel channel = 1;</code>
     */
    public Builder setChannel(
        int index, io.grpc.channelz.v1.Channel.Builder builderForValue) {
      if (channelBuilder_ == null) {
        ensureChannelIsMutable();
        channel_.set(index, builderForValue.build());
        onChanged();
      } else {
        channelBuilder_.setMessage(index, builderForValue.build());
      }
      return this;
    }
    /**
     * <pre>
     * list of channels that the connection detail service knows about.  Sorted in
     * ascending channel_id order.
     * Must contain at least 1 result, otherwise 'end' must be true.
     * </pre>
     *
     * <code>repeated .grpc.channelz.v1.Channel channel = 1;</code>
     */
    public Builder addChannel(io.grpc.channelz.v1.Channel value) {
      if (channelBuilder_ == null) {
        if (value == null) {
          throw new NullPointerException();
        }
        ensureChannelIsMutable();
        channel_.add(value);
        onChanged();
      } else {
        channelBuilder_.addMessage(value);
      }
      return this;
    }
    /**
     * <pre>
     * list of channels that the connection detail service knows about.  Sorted in
     * ascending channel_id order.
     * Must contain at least 1 result, otherwise 'end' must be true.
     * </pre>
     *
     * <code>repeated .grpc.channelz.v1.Channel channel = 1;</code>
     */
    public Builder addChannel(
        int index, io.grpc.channelz.v1.Channel value) {
      if (channelBuilder_ == null) {
        if (value == null) {
          throw new NullPointerException();
        }
        ensureChannelIsMutable();
        channel_.add(index, value);
        onChanged();
      } else {
        channelBuilder_.addMessage(index, value);
      }
      return this;
    }
    /**
     * <pre>
     * list of channels that the connection detail service knows about.  Sorted in
     * ascending channel_id order.
     * Must contain at least 1 result, otherwise 'end' must be true.
     * </pre>
     *
     * <code>repeated .grpc.channelz.v1.Channel channel = 1;</code>
     */
    public Builder addChannel(
        io.grpc.channelz.v1.Channel.Builder builderForValue) {
      if (channelBuilder_ == null) {
        ensureChannelIsMutable();
        channel_.add(builderForValue.build());
        onChanged();
      } else {
        channelBuilder_.addMessage(builderForValue.build());
      }
      return this;
    }
    /**
     * <pre>
     * list of channels that the connection detail service knows about.  Sorted in
     * ascending channel_id order.
     * Must contain at least 1 result, otherwise 'end' must be true.
     * </pre>
     *
     * <code>repeated .grpc.channelz.v1.Channel channel = 1;</code>
     */
    public Builder addChannel(
        int index, io.grpc.channelz.v1.Channel.Builder builderForValue) {
      if (channelBuilder_ == null) {
        ensureChannelIsMutable();
        channel_.add(index, builderForValue.build());
        onChanged();
      } else {
        channelBuilder_.addMessage(index, builderForValue.build());
      }
      return this;
    }
    /**
     * <pre>
     * list of channels that the connection detail service knows about.  Sorted in
     * ascending channel_id order.
     * Must contain at least 1 result, otherwise 'end' must be true.
     * </pre>
     *
     * <code>repeated .grpc.channelz.v1.Channel channel = 1;</code>
     */
    public Builder addAllChannel(
        java.lang.Iterable<? extends io.grpc.channelz.v1.Channel> values) {
      if (channelBuilder_ == null) {
        ensureChannelIsMutable();
        com.google.protobuf.AbstractMessageLite.Builder.addAll(
            values, channel_);
        onChanged();
      } else {
        channelBuilder_.addAllMessages(values);
      }
      return this;
    }
    /**
     * <pre>
     * list of channels that the connection detail service knows about.  Sorted in
     * ascending channel_id order.
     * Must contain at least 1 result, otherwise 'end' must be true.
     * </pre>
     *
     * <code>repeated .grpc.channelz.v1.Channel channel = 1;</code>
     */
    public Builder clearChannel() {
      if (channelBuilder_ == null) {
        channel_ = java.util.Collections.emptyList();
        bitField0_ = (bitField0_ & ~0x00000001);
        onChanged();
      } else {
        channelBuilder_.clear();
      }
      return this;
    }
    /**
     * <pre>
     * list of channels that the connection detail service knows about.  Sorted in
     * ascending channel_id order.
     * Must contain at least 1 result, otherwise 'end' must be true.
     * </pre>
     *
     * <code>repeated .grpc.channelz.v1.Channel channel = 1;</code>
     */
    public Builder removeChannel(int index) {
      if (channelBuilder_ == null) {
        ensureChannelIsMutable();
        channel_.remove(index);
        onChanged();
      } else {
        channelBuilder_.remove(index);
      }
      return this;
    }
    /**
     * <pre>
     * list of channels that the connection detail service knows about.  Sorted in
     * ascending channel_id order.
     * Must contain at least 1 result, otherwise 'end' must be true.
     * </pre>
     *
     * <code>repeated .grpc.channelz.v1.Channel channel = 1;</code>
     */
    public io.grpc.channelz.v1.Channel.Builder getChannelBuilder(
        int index) {
      return getChannelFieldBuilder().getBuilder(index);
    }
    /**
     * <pre>
     * list of channels that the connection detail service knows about.  Sorted in
     * ascending channel_id order.
     * Must contain at least 1 result, otherwise 'end' must be true.
     * </pre>
     *
     * <code>repeated .grpc.channelz.v1.Channel channel = 1;</code>
     */
    public io.grpc.channelz.v1.ChannelOrBuilder getChannelOrBuilder(
        int index) {
      if (channelBuilder_ == null) {
        return channel_.get(index);  } else {
        return channelBuilder_.getMessageOrBuilder(index);
      }
    }
    /**
     * <pre>
     * list of channels that the connection detail service knows about.  Sorted in
     * ascending channel_id order.
     * Must contain at least 1 result, otherwise 'end' must be true.
     * </pre>
     *
     * <code>repeated .grpc.channelz.v1.Channel channel = 1;</code>
     */
    public java.util.List<? extends io.grpc.channelz.v1.ChannelOrBuilder> 
         getChannelOrBuilderList() {
      if (channelBuilder_ != null) {
        return channelBuilder_.getMessageOrBuilderList();
      } else {
        return java.util.Collections.unmodifiableList(channel_);
      }
    }
    /**
     * <pre>
     * list of channels that the connection detail service knows about.  Sorted in
     * ascending channel_id order.
     * Must contain at least 1 result, otherwise 'end' must be true.
     * </pre>
     *
     * <code>repeated .grpc.channelz.v1.Channel channel = 1;</code>
     */
    public io.grpc.channelz.v1.Channel.Builder addChannelBuilder() {
      return getChannelFieldBuilder().addBuilder(
          io.grpc.channelz.v1.Channel.getDefaultInstance());
    }
    /**
     * <pre>
     * list of channels that the connection detail service knows about.  Sorted in
     * ascending channel_id order.
     * Must contain at least 1 result, otherwise 'end' must be true.
     * </pre>
     *
     * <code>repeated .grpc.channelz.v1.Channel channel = 1;</code>
     */
    public io.grpc.channelz.v1.Channel.Builder addChannelBuilder(
        int index) {
      return getChannelFieldBuilder().addBuilder(
          index, io.grpc.channelz.v1.Channel.getDefaultInstance());
    }
    /**
     * <pre>
     * list of channels that the connection detail service knows about.  Sorted in
     * ascending channel_id order.
     * Must contain at least 1 result, otherwise 'end' must be true.
     * </pre>
     *
     * <code>repeated .grpc.channelz.v1.Channel channel = 1;</code>
     */
    public java.util.List<io.grpc.channelz.v1.Channel.Builder> 
         getChannelBuilderList() {
      return getChannelFieldBuilder().getBuilderList();
    }
    private com.google.protobuf.RepeatedFieldBuilderV3<
        io.grpc.channelz.v1.Channel, io.grpc.channelz.v1.Channel.Builder, io.grpc.channelz.v1.ChannelOrBuilder> 
        getChannelFieldBuilder() {
      if (channelBuilder_ == null) {
        channelBuilder_ = new com.google.protobuf.RepeatedFieldBuilderV3<
            io.grpc.channelz.v1.Channel, io.grpc.channelz.v1.Channel.Builder, io.grpc.channelz.v1.ChannelOrBuilder>(
                channel_,
                ((bitField0_ & 0x00000001) == 0x00000001),
                getParentForChildren(),
                isClean());
        channel_ = null;
      }
      return channelBuilder_;
    }

    private boolean end_ ;
    /**
     * <pre>
     * If set, indicates that the list of channels is the final list.  Requesting
     * more channels can only return more if they are created after this RPC
     * completes.
     * </pre>
     *
     * <code>bool end = 2;</code>
     */
    public boolean getEnd() {
      return end_;
    }
    /**
     * <pre>
     * If set, indicates that the list of channels is the final list.  Requesting
     * more channels can only return more if they are created after this RPC
     * completes.
     * </pre>
     *
     * <code>bool end = 2;</code>
     */
    public Builder setEnd(boolean value) {
      
      end_ = value;
      onChanged();
      return this;
    }
    /**
     * <pre>
     * If set, indicates that the list of channels is the final list.  Requesting
     * more channels can only return more if they are created after this RPC
     * completes.
     * </pre>
     *
     * <code>bool end = 2;</code>
     */
    public Builder clearEnd() {
      
      end_ = false;
      onChanged();
      return this;
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


    // @@protoc_insertion_point(builder_scope:grpc.channelz.v1.GetTopChannelsResponse)
  }

  // @@protoc_insertion_point(class_scope:grpc.channelz.v1.GetTopChannelsResponse)
  private static final io.grpc.channelz.v1.GetTopChannelsResponse DEFAULT_INSTANCE;
  static {
    DEFAULT_INSTANCE = new io.grpc.channelz.v1.GetTopChannelsResponse();
  }

  public static io.grpc.channelz.v1.GetTopChannelsResponse getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private static final com.google.protobuf.Parser<GetTopChannelsResponse>
      PARSER = new com.google.protobuf.AbstractParser<GetTopChannelsResponse>() {
    @java.lang.Override
    public GetTopChannelsResponse parsePartialFrom(
        com.google.protobuf.CodedInputStream input,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws com.google.protobuf.InvalidProtocolBufferException {
      return new GetTopChannelsResponse(input, extensionRegistry);
    }
  };

  public static com.google.protobuf.Parser<GetTopChannelsResponse> parser() {
    return PARSER;
  }

  @java.lang.Override
  public com.google.protobuf.Parser<GetTopChannelsResponse> getParserForType() {
    return PARSER;
  }

  @java.lang.Override
  public io.grpc.channelz.v1.GetTopChannelsResponse getDefaultInstanceForType() {
    return DEFAULT_INSTANCE;
  }

}

