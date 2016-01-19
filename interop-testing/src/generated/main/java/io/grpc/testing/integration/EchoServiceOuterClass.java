// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: io/grpc/testing/integration/echo_service.proto

package io.grpc.testing.integration;

public final class EchoServiceOuterClass {
  private EchoServiceOuterClass() {}
  public static void registerAllExtensions(
      com.google.protobuf.ExtensionRegistry registry) {
  }
  public interface EchoRequestOrBuilder extends
      // @@protoc_insertion_point(interface_extends:grpc.testing.EchoRequest)
      com.google.protobuf.MessageOrBuilder {

    /**
     * <code>optional string text = 1;</code>
     */
    java.lang.String getText();
    /**
     * <code>optional string text = 1;</code>
     */
    com.google.protobuf.ByteString
        getTextBytes();
  }
  /**
   * Protobuf type {@code grpc.testing.EchoRequest}
   */
  public  static final class EchoRequest extends
      com.google.protobuf.GeneratedMessage implements
      // @@protoc_insertion_point(message_implements:grpc.testing.EchoRequest)
      EchoRequestOrBuilder {
    // Use EchoRequest.newBuilder() to construct.
    private EchoRequest(com.google.protobuf.GeneratedMessage.Builder<?> builder) {
      super(builder);
    }
    private EchoRequest() {
      text_ = "";
    }

    @java.lang.Override
    public final com.google.protobuf.UnknownFieldSet
    getUnknownFields() {
      return com.google.protobuf.UnknownFieldSet.getDefaultInstance();
    }
    private EchoRequest(
        com.google.protobuf.CodedInputStream input,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry) {
      this();
      int mutable_bitField0_ = 0;
      try {
        boolean done = false;
        while (!done) {
          int tag = input.readTag();
          switch (tag) {
            case 0:
              done = true;
              break;
            default: {
              if (!input.skipField(tag)) {
                done = true;
              }
              break;
            }
            case 10: {
              String s = input.readStringRequireUtf8();

              text_ = s;
              break;
            }
          }
        }
      } catch (com.google.protobuf.InvalidProtocolBufferException e) {
        throw new RuntimeException(e.setUnfinishedMessage(this));
      } catch (java.io.IOException e) {
        throw new RuntimeException(
            new com.google.protobuf.InvalidProtocolBufferException(
                e.getMessage()).setUnfinishedMessage(this));
      } finally {
        makeExtensionsImmutable();
      }
    }
    public static final com.google.protobuf.Descriptors.Descriptor
        getDescriptor() {
      return io.grpc.testing.integration.EchoServiceOuterClass.internal_static_grpc_testing_EchoRequest_descriptor;
    }

    protected com.google.protobuf.GeneratedMessage.FieldAccessorTable
        internalGetFieldAccessorTable() {
      return io.grpc.testing.integration.EchoServiceOuterClass.internal_static_grpc_testing_EchoRequest_fieldAccessorTable
          .ensureFieldAccessorsInitialized(
              io.grpc.testing.integration.EchoServiceOuterClass.EchoRequest.class, io.grpc.testing.integration.EchoServiceOuterClass.EchoRequest.Builder.class);
    }

    public static final int TEXT_FIELD_NUMBER = 1;
    private volatile java.lang.Object text_;
    /**
     * <code>optional string text = 1;</code>
     */
    public java.lang.String getText() {
      java.lang.Object ref = text_;
      if (ref instanceof java.lang.String) {
        return (java.lang.String) ref;
      } else {
        com.google.protobuf.ByteString bs = 
            (com.google.protobuf.ByteString) ref;
        java.lang.String s = bs.toStringUtf8();
        text_ = s;
        return s;
      }
    }
    /**
     * <code>optional string text = 1;</code>
     */
    public com.google.protobuf.ByteString
        getTextBytes() {
      java.lang.Object ref = text_;
      if (ref instanceof java.lang.String) {
        com.google.protobuf.ByteString b = 
            com.google.protobuf.ByteString.copyFromUtf8(
                (java.lang.String) ref);
        text_ = b;
        return b;
      } else {
        return (com.google.protobuf.ByteString) ref;
      }
    }

    private byte memoizedIsInitialized = -1;
    public final boolean isInitialized() {
      byte isInitialized = memoizedIsInitialized;
      if (isInitialized == 1) return true;
      if (isInitialized == 0) return false;

      memoizedIsInitialized = 1;
      return true;
    }

    public void writeTo(com.google.protobuf.CodedOutputStream output)
                        throws java.io.IOException {
      if (!getTextBytes().isEmpty()) {
        com.google.protobuf.GeneratedMessage.writeString(output, 1, text_);
      }
    }

    public int getSerializedSize() {
      int size = memoizedSize;
      if (size != -1) return size;

      size = 0;
      if (!getTextBytes().isEmpty()) {
        size += com.google.protobuf.GeneratedMessage.computeStringSize(1, text_);
      }
      memoizedSize = size;
      return size;
    }

    private static final long serialVersionUID = 0L;
    public static io.grpc.testing.integration.EchoServiceOuterClass.EchoRequest parseFrom(
        com.google.protobuf.ByteString data)
        throws com.google.protobuf.InvalidProtocolBufferException {
      return PARSER.parseFrom(data);
    }
    public static io.grpc.testing.integration.EchoServiceOuterClass.EchoRequest parseFrom(
        com.google.protobuf.ByteString data,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws com.google.protobuf.InvalidProtocolBufferException {
      return PARSER.parseFrom(data, extensionRegistry);
    }
    public static io.grpc.testing.integration.EchoServiceOuterClass.EchoRequest parseFrom(byte[] data)
        throws com.google.protobuf.InvalidProtocolBufferException {
      return PARSER.parseFrom(data);
    }
    public static io.grpc.testing.integration.EchoServiceOuterClass.EchoRequest parseFrom(
        byte[] data,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws com.google.protobuf.InvalidProtocolBufferException {
      return PARSER.parseFrom(data, extensionRegistry);
    }
    public static io.grpc.testing.integration.EchoServiceOuterClass.EchoRequest parseFrom(java.io.InputStream input)
        throws java.io.IOException {
      return PARSER.parseFrom(input);
    }
    public static io.grpc.testing.integration.EchoServiceOuterClass.EchoRequest parseFrom(
        java.io.InputStream input,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws java.io.IOException {
      return PARSER.parseFrom(input, extensionRegistry);
    }
    public static io.grpc.testing.integration.EchoServiceOuterClass.EchoRequest parseDelimitedFrom(java.io.InputStream input)
        throws java.io.IOException {
      return PARSER.parseDelimitedFrom(input);
    }
    public static io.grpc.testing.integration.EchoServiceOuterClass.EchoRequest parseDelimitedFrom(
        java.io.InputStream input,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws java.io.IOException {
      return PARSER.parseDelimitedFrom(input, extensionRegistry);
    }
    public static io.grpc.testing.integration.EchoServiceOuterClass.EchoRequest parseFrom(
        com.google.protobuf.CodedInputStream input)
        throws java.io.IOException {
      return PARSER.parseFrom(input);
    }
    public static io.grpc.testing.integration.EchoServiceOuterClass.EchoRequest parseFrom(
        com.google.protobuf.CodedInputStream input,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws java.io.IOException {
      return PARSER.parseFrom(input, extensionRegistry);
    }

    public Builder newBuilderForType() { return newBuilder(); }
    public static Builder newBuilder() {
      return DEFAULT_INSTANCE.toBuilder();
    }
    public static Builder newBuilder(io.grpc.testing.integration.EchoServiceOuterClass.EchoRequest prototype) {
      return DEFAULT_INSTANCE.toBuilder().mergeFrom(prototype);
    }
    public Builder toBuilder() {
      return this == DEFAULT_INSTANCE
          ? new Builder() : new Builder().mergeFrom(this);
    }

    @java.lang.Override
    protected Builder newBuilderForType(
        com.google.protobuf.GeneratedMessage.BuilderParent parent) {
      Builder builder = new Builder(parent);
      return builder;
    }
    /**
     * Protobuf type {@code grpc.testing.EchoRequest}
     */
    public static final class Builder extends
        com.google.protobuf.GeneratedMessage.Builder<Builder> implements
        // @@protoc_insertion_point(builder_implements:grpc.testing.EchoRequest)
        io.grpc.testing.integration.EchoServiceOuterClass.EchoRequestOrBuilder {
      public static final com.google.protobuf.Descriptors.Descriptor
          getDescriptor() {
        return io.grpc.testing.integration.EchoServiceOuterClass.internal_static_grpc_testing_EchoRequest_descriptor;
      }

      protected com.google.protobuf.GeneratedMessage.FieldAccessorTable
          internalGetFieldAccessorTable() {
        return io.grpc.testing.integration.EchoServiceOuterClass.internal_static_grpc_testing_EchoRequest_fieldAccessorTable
            .ensureFieldAccessorsInitialized(
                io.grpc.testing.integration.EchoServiceOuterClass.EchoRequest.class, io.grpc.testing.integration.EchoServiceOuterClass.EchoRequest.Builder.class);
      }

      // Construct using io.grpc.testing.integration.EchoServiceOuterClass.EchoRequest.newBuilder()
      private Builder() {
        maybeForceBuilderInitialization();
      }

      private Builder(
          com.google.protobuf.GeneratedMessage.BuilderParent parent) {
        super(parent);
        maybeForceBuilderInitialization();
      }
      private void maybeForceBuilderInitialization() {
        if (com.google.protobuf.GeneratedMessage.alwaysUseFieldBuilders) {
        }
      }
      public Builder clear() {
        super.clear();
        text_ = "";

        return this;
      }

      public com.google.protobuf.Descriptors.Descriptor
          getDescriptorForType() {
        return io.grpc.testing.integration.EchoServiceOuterClass.internal_static_grpc_testing_EchoRequest_descriptor;
      }

      public io.grpc.testing.integration.EchoServiceOuterClass.EchoRequest getDefaultInstanceForType() {
        return io.grpc.testing.integration.EchoServiceOuterClass.EchoRequest.getDefaultInstance();
      }

      public io.grpc.testing.integration.EchoServiceOuterClass.EchoRequest build() {
        io.grpc.testing.integration.EchoServiceOuterClass.EchoRequest result = buildPartial();
        if (!result.isInitialized()) {
          throw newUninitializedMessageException(result);
        }
        return result;
      }

      public io.grpc.testing.integration.EchoServiceOuterClass.EchoRequest buildPartial() {
        io.grpc.testing.integration.EchoServiceOuterClass.EchoRequest result = new io.grpc.testing.integration.EchoServiceOuterClass.EchoRequest(this);
        result.text_ = text_;
        onBuilt();
        return result;
      }

      public Builder mergeFrom(com.google.protobuf.Message other) {
        if (other instanceof io.grpc.testing.integration.EchoServiceOuterClass.EchoRequest) {
          return mergeFrom((io.grpc.testing.integration.EchoServiceOuterClass.EchoRequest)other);
        } else {
          super.mergeFrom(other);
          return this;
        }
      }

      public Builder mergeFrom(io.grpc.testing.integration.EchoServiceOuterClass.EchoRequest other) {
        if (other == io.grpc.testing.integration.EchoServiceOuterClass.EchoRequest.getDefaultInstance()) return this;
        if (!other.getText().isEmpty()) {
          text_ = other.text_;
          onChanged();
        }
        onChanged();
        return this;
      }

      public final boolean isInitialized() {
        return true;
      }

      public Builder mergeFrom(
          com.google.protobuf.CodedInputStream input,
          com.google.protobuf.ExtensionRegistryLite extensionRegistry)
          throws java.io.IOException {
        io.grpc.testing.integration.EchoServiceOuterClass.EchoRequest parsedMessage = null;
        try {
          parsedMessage = PARSER.parsePartialFrom(input, extensionRegistry);
        } catch (com.google.protobuf.InvalidProtocolBufferException e) {
          parsedMessage = (io.grpc.testing.integration.EchoServiceOuterClass.EchoRequest) e.getUnfinishedMessage();
          throw e;
        } finally {
          if (parsedMessage != null) {
            mergeFrom(parsedMessage);
          }
        }
        return this;
      }

      private java.lang.Object text_ = "";
      /**
       * <code>optional string text = 1;</code>
       */
      public java.lang.String getText() {
        java.lang.Object ref = text_;
        if (!(ref instanceof java.lang.String)) {
          com.google.protobuf.ByteString bs =
              (com.google.protobuf.ByteString) ref;
          java.lang.String s = bs.toStringUtf8();
          text_ = s;
          return s;
        } else {
          return (java.lang.String) ref;
        }
      }
      /**
       * <code>optional string text = 1;</code>
       */
      public com.google.protobuf.ByteString
          getTextBytes() {
        java.lang.Object ref = text_;
        if (ref instanceof String) {
          com.google.protobuf.ByteString b = 
              com.google.protobuf.ByteString.copyFromUtf8(
                  (java.lang.String) ref);
          text_ = b;
          return b;
        } else {
          return (com.google.protobuf.ByteString) ref;
        }
      }
      /**
       * <code>optional string text = 1;</code>
       */
      public Builder setText(
          java.lang.String value) {
        if (value == null) {
    throw new NullPointerException();
  }
  
        text_ = value;
        onChanged();
        return this;
      }
      /**
       * <code>optional string text = 1;</code>
       */
      public Builder clearText() {
        
        text_ = getDefaultInstance().getText();
        onChanged();
        return this;
      }
      /**
       * <code>optional string text = 1;</code>
       */
      public Builder setTextBytes(
          com.google.protobuf.ByteString value) {
        if (value == null) {
    throw new NullPointerException();
  }
  checkByteStringIsUtf8(value);
        
        text_ = value;
        onChanged();
        return this;
      }
      public final Builder setUnknownFields(
          final com.google.protobuf.UnknownFieldSet unknownFields) {
        return this;
      }

      public final Builder mergeUnknownFields(
          final com.google.protobuf.UnknownFieldSet unknownFields) {
        return this;
      }


      // @@protoc_insertion_point(builder_scope:grpc.testing.EchoRequest)
    }

    // @@protoc_insertion_point(class_scope:grpc.testing.EchoRequest)
    private static final io.grpc.testing.integration.EchoServiceOuterClass.EchoRequest DEFAULT_INSTANCE;
    static {
      DEFAULT_INSTANCE = new io.grpc.testing.integration.EchoServiceOuterClass.EchoRequest();
    }

    public static io.grpc.testing.integration.EchoServiceOuterClass.EchoRequest getDefaultInstance() {
      return DEFAULT_INSTANCE;
    }

    private static final com.google.protobuf.Parser<EchoRequest>
        PARSER = new com.google.protobuf.AbstractParser<EchoRequest>() {
      public EchoRequest parsePartialFrom(
          com.google.protobuf.CodedInputStream input,
          com.google.protobuf.ExtensionRegistryLite extensionRegistry)
          throws com.google.protobuf.InvalidProtocolBufferException {
        try {
          return new EchoRequest(input, extensionRegistry);
        } catch (RuntimeException e) {
          if (e.getCause() instanceof
              com.google.protobuf.InvalidProtocolBufferException) {
            throw (com.google.protobuf.InvalidProtocolBufferException)
                e.getCause();
          }
          throw e;
        }
      }
    };

    public static com.google.protobuf.Parser<EchoRequest> parser() {
      return PARSER;
    }

    @java.lang.Override
    public com.google.protobuf.Parser<EchoRequest> getParserForType() {
      return PARSER;
    }

    public io.grpc.testing.integration.EchoServiceOuterClass.EchoRequest getDefaultInstanceForType() {
      return DEFAULT_INSTANCE;
    }

  }

  public interface EchoResponseOrBuilder extends
      // @@protoc_insertion_point(interface_extends:grpc.testing.EchoResponse)
      com.google.protobuf.MessageOrBuilder {

    /**
     * <code>optional string text = 1;</code>
     */
    java.lang.String getText();
    /**
     * <code>optional string text = 1;</code>
     */
    com.google.protobuf.ByteString
        getTextBytes();
  }
  /**
   * Protobuf type {@code grpc.testing.EchoResponse}
   */
  public  static final class EchoResponse extends
      com.google.protobuf.GeneratedMessage implements
      // @@protoc_insertion_point(message_implements:grpc.testing.EchoResponse)
      EchoResponseOrBuilder {
    // Use EchoResponse.newBuilder() to construct.
    private EchoResponse(com.google.protobuf.GeneratedMessage.Builder<?> builder) {
      super(builder);
    }
    private EchoResponse() {
      text_ = "";
    }

    @java.lang.Override
    public final com.google.protobuf.UnknownFieldSet
    getUnknownFields() {
      return com.google.protobuf.UnknownFieldSet.getDefaultInstance();
    }
    private EchoResponse(
        com.google.protobuf.CodedInputStream input,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry) {
      this();
      int mutable_bitField0_ = 0;
      try {
        boolean done = false;
        while (!done) {
          int tag = input.readTag();
          switch (tag) {
            case 0:
              done = true;
              break;
            default: {
              if (!input.skipField(tag)) {
                done = true;
              }
              break;
            }
            case 10: {
              String s = input.readStringRequireUtf8();

              text_ = s;
              break;
            }
          }
        }
      } catch (com.google.protobuf.InvalidProtocolBufferException e) {
        throw new RuntimeException(e.setUnfinishedMessage(this));
      } catch (java.io.IOException e) {
        throw new RuntimeException(
            new com.google.protobuf.InvalidProtocolBufferException(
                e.getMessage()).setUnfinishedMessage(this));
      } finally {
        makeExtensionsImmutable();
      }
    }
    public static final com.google.protobuf.Descriptors.Descriptor
        getDescriptor() {
      return io.grpc.testing.integration.EchoServiceOuterClass.internal_static_grpc_testing_EchoResponse_descriptor;
    }

    protected com.google.protobuf.GeneratedMessage.FieldAccessorTable
        internalGetFieldAccessorTable() {
      return io.grpc.testing.integration.EchoServiceOuterClass.internal_static_grpc_testing_EchoResponse_fieldAccessorTable
          .ensureFieldAccessorsInitialized(
              io.grpc.testing.integration.EchoServiceOuterClass.EchoResponse.class, io.grpc.testing.integration.EchoServiceOuterClass.EchoResponse.Builder.class);
    }

    public static final int TEXT_FIELD_NUMBER = 1;
    private volatile java.lang.Object text_;
    /**
     * <code>optional string text = 1;</code>
     */
    public java.lang.String getText() {
      java.lang.Object ref = text_;
      if (ref instanceof java.lang.String) {
        return (java.lang.String) ref;
      } else {
        com.google.protobuf.ByteString bs = 
            (com.google.protobuf.ByteString) ref;
        java.lang.String s = bs.toStringUtf8();
        text_ = s;
        return s;
      }
    }
    /**
     * <code>optional string text = 1;</code>
     */
    public com.google.protobuf.ByteString
        getTextBytes() {
      java.lang.Object ref = text_;
      if (ref instanceof java.lang.String) {
        com.google.protobuf.ByteString b = 
            com.google.protobuf.ByteString.copyFromUtf8(
                (java.lang.String) ref);
        text_ = b;
        return b;
      } else {
        return (com.google.protobuf.ByteString) ref;
      }
    }

    private byte memoizedIsInitialized = -1;
    public final boolean isInitialized() {
      byte isInitialized = memoizedIsInitialized;
      if (isInitialized == 1) return true;
      if (isInitialized == 0) return false;

      memoizedIsInitialized = 1;
      return true;
    }

    public void writeTo(com.google.protobuf.CodedOutputStream output)
                        throws java.io.IOException {
      if (!getTextBytes().isEmpty()) {
        com.google.protobuf.GeneratedMessage.writeString(output, 1, text_);
      }
    }

    public int getSerializedSize() {
      int size = memoizedSize;
      if (size != -1) return size;

      size = 0;
      if (!getTextBytes().isEmpty()) {
        size += com.google.protobuf.GeneratedMessage.computeStringSize(1, text_);
      }
      memoizedSize = size;
      return size;
    }

    private static final long serialVersionUID = 0L;
    public static io.grpc.testing.integration.EchoServiceOuterClass.EchoResponse parseFrom(
        com.google.protobuf.ByteString data)
        throws com.google.protobuf.InvalidProtocolBufferException {
      return PARSER.parseFrom(data);
    }
    public static io.grpc.testing.integration.EchoServiceOuterClass.EchoResponse parseFrom(
        com.google.protobuf.ByteString data,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws com.google.protobuf.InvalidProtocolBufferException {
      return PARSER.parseFrom(data, extensionRegistry);
    }
    public static io.grpc.testing.integration.EchoServiceOuterClass.EchoResponse parseFrom(byte[] data)
        throws com.google.protobuf.InvalidProtocolBufferException {
      return PARSER.parseFrom(data);
    }
    public static io.grpc.testing.integration.EchoServiceOuterClass.EchoResponse parseFrom(
        byte[] data,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws com.google.protobuf.InvalidProtocolBufferException {
      return PARSER.parseFrom(data, extensionRegistry);
    }
    public static io.grpc.testing.integration.EchoServiceOuterClass.EchoResponse parseFrom(java.io.InputStream input)
        throws java.io.IOException {
      return PARSER.parseFrom(input);
    }
    public static io.grpc.testing.integration.EchoServiceOuterClass.EchoResponse parseFrom(
        java.io.InputStream input,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws java.io.IOException {
      return PARSER.parseFrom(input, extensionRegistry);
    }
    public static io.grpc.testing.integration.EchoServiceOuterClass.EchoResponse parseDelimitedFrom(java.io.InputStream input)
        throws java.io.IOException {
      return PARSER.parseDelimitedFrom(input);
    }
    public static io.grpc.testing.integration.EchoServiceOuterClass.EchoResponse parseDelimitedFrom(
        java.io.InputStream input,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws java.io.IOException {
      return PARSER.parseDelimitedFrom(input, extensionRegistry);
    }
    public static io.grpc.testing.integration.EchoServiceOuterClass.EchoResponse parseFrom(
        com.google.protobuf.CodedInputStream input)
        throws java.io.IOException {
      return PARSER.parseFrom(input);
    }
    public static io.grpc.testing.integration.EchoServiceOuterClass.EchoResponse parseFrom(
        com.google.protobuf.CodedInputStream input,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws java.io.IOException {
      return PARSER.parseFrom(input, extensionRegistry);
    }

    public Builder newBuilderForType() { return newBuilder(); }
    public static Builder newBuilder() {
      return DEFAULT_INSTANCE.toBuilder();
    }
    public static Builder newBuilder(io.grpc.testing.integration.EchoServiceOuterClass.EchoResponse prototype) {
      return DEFAULT_INSTANCE.toBuilder().mergeFrom(prototype);
    }
    public Builder toBuilder() {
      return this == DEFAULT_INSTANCE
          ? new Builder() : new Builder().mergeFrom(this);
    }

    @java.lang.Override
    protected Builder newBuilderForType(
        com.google.protobuf.GeneratedMessage.BuilderParent parent) {
      Builder builder = new Builder(parent);
      return builder;
    }
    /**
     * Protobuf type {@code grpc.testing.EchoResponse}
     */
    public static final class Builder extends
        com.google.protobuf.GeneratedMessage.Builder<Builder> implements
        // @@protoc_insertion_point(builder_implements:grpc.testing.EchoResponse)
        io.grpc.testing.integration.EchoServiceOuterClass.EchoResponseOrBuilder {
      public static final com.google.protobuf.Descriptors.Descriptor
          getDescriptor() {
        return io.grpc.testing.integration.EchoServiceOuterClass.internal_static_grpc_testing_EchoResponse_descriptor;
      }

      protected com.google.protobuf.GeneratedMessage.FieldAccessorTable
          internalGetFieldAccessorTable() {
        return io.grpc.testing.integration.EchoServiceOuterClass.internal_static_grpc_testing_EchoResponse_fieldAccessorTable
            .ensureFieldAccessorsInitialized(
                io.grpc.testing.integration.EchoServiceOuterClass.EchoResponse.class, io.grpc.testing.integration.EchoServiceOuterClass.EchoResponse.Builder.class);
      }

      // Construct using io.grpc.testing.integration.EchoServiceOuterClass.EchoResponse.newBuilder()
      private Builder() {
        maybeForceBuilderInitialization();
      }

      private Builder(
          com.google.protobuf.GeneratedMessage.BuilderParent parent) {
        super(parent);
        maybeForceBuilderInitialization();
      }
      private void maybeForceBuilderInitialization() {
        if (com.google.protobuf.GeneratedMessage.alwaysUseFieldBuilders) {
        }
      }
      public Builder clear() {
        super.clear();
        text_ = "";

        return this;
      }

      public com.google.protobuf.Descriptors.Descriptor
          getDescriptorForType() {
        return io.grpc.testing.integration.EchoServiceOuterClass.internal_static_grpc_testing_EchoResponse_descriptor;
      }

      public io.grpc.testing.integration.EchoServiceOuterClass.EchoResponse getDefaultInstanceForType() {
        return io.grpc.testing.integration.EchoServiceOuterClass.EchoResponse.getDefaultInstance();
      }

      public io.grpc.testing.integration.EchoServiceOuterClass.EchoResponse build() {
        io.grpc.testing.integration.EchoServiceOuterClass.EchoResponse result = buildPartial();
        if (!result.isInitialized()) {
          throw newUninitializedMessageException(result);
        }
        return result;
      }

      public io.grpc.testing.integration.EchoServiceOuterClass.EchoResponse buildPartial() {
        io.grpc.testing.integration.EchoServiceOuterClass.EchoResponse result = new io.grpc.testing.integration.EchoServiceOuterClass.EchoResponse(this);
        result.text_ = text_;
        onBuilt();
        return result;
      }

      public Builder mergeFrom(com.google.protobuf.Message other) {
        if (other instanceof io.grpc.testing.integration.EchoServiceOuterClass.EchoResponse) {
          return mergeFrom((io.grpc.testing.integration.EchoServiceOuterClass.EchoResponse)other);
        } else {
          super.mergeFrom(other);
          return this;
        }
      }

      public Builder mergeFrom(io.grpc.testing.integration.EchoServiceOuterClass.EchoResponse other) {
        if (other == io.grpc.testing.integration.EchoServiceOuterClass.EchoResponse.getDefaultInstance()) return this;
        if (!other.getText().isEmpty()) {
          text_ = other.text_;
          onChanged();
        }
        onChanged();
        return this;
      }

      public final boolean isInitialized() {
        return true;
      }

      public Builder mergeFrom(
          com.google.protobuf.CodedInputStream input,
          com.google.protobuf.ExtensionRegistryLite extensionRegistry)
          throws java.io.IOException {
        io.grpc.testing.integration.EchoServiceOuterClass.EchoResponse parsedMessage = null;
        try {
          parsedMessage = PARSER.parsePartialFrom(input, extensionRegistry);
        } catch (com.google.protobuf.InvalidProtocolBufferException e) {
          parsedMessage = (io.grpc.testing.integration.EchoServiceOuterClass.EchoResponse) e.getUnfinishedMessage();
          throw e;
        } finally {
          if (parsedMessage != null) {
            mergeFrom(parsedMessage);
          }
        }
        return this;
      }

      private java.lang.Object text_ = "";
      /**
       * <code>optional string text = 1;</code>
       */
      public java.lang.String getText() {
        java.lang.Object ref = text_;
        if (!(ref instanceof java.lang.String)) {
          com.google.protobuf.ByteString bs =
              (com.google.protobuf.ByteString) ref;
          java.lang.String s = bs.toStringUtf8();
          text_ = s;
          return s;
        } else {
          return (java.lang.String) ref;
        }
      }
      /**
       * <code>optional string text = 1;</code>
       */
      public com.google.protobuf.ByteString
          getTextBytes() {
        java.lang.Object ref = text_;
        if (ref instanceof String) {
          com.google.protobuf.ByteString b = 
              com.google.protobuf.ByteString.copyFromUtf8(
                  (java.lang.String) ref);
          text_ = b;
          return b;
        } else {
          return (com.google.protobuf.ByteString) ref;
        }
      }
      /**
       * <code>optional string text = 1;</code>
       */
      public Builder setText(
          java.lang.String value) {
        if (value == null) {
    throw new NullPointerException();
  }
  
        text_ = value;
        onChanged();
        return this;
      }
      /**
       * <code>optional string text = 1;</code>
       */
      public Builder clearText() {
        
        text_ = getDefaultInstance().getText();
        onChanged();
        return this;
      }
      /**
       * <code>optional string text = 1;</code>
       */
      public Builder setTextBytes(
          com.google.protobuf.ByteString value) {
        if (value == null) {
    throw new NullPointerException();
  }
  checkByteStringIsUtf8(value);
        
        text_ = value;
        onChanged();
        return this;
      }
      public final Builder setUnknownFields(
          final com.google.protobuf.UnknownFieldSet unknownFields) {
        return this;
      }

      public final Builder mergeUnknownFields(
          final com.google.protobuf.UnknownFieldSet unknownFields) {
        return this;
      }


      // @@protoc_insertion_point(builder_scope:grpc.testing.EchoResponse)
    }

    // @@protoc_insertion_point(class_scope:grpc.testing.EchoResponse)
    private static final io.grpc.testing.integration.EchoServiceOuterClass.EchoResponse DEFAULT_INSTANCE;
    static {
      DEFAULT_INSTANCE = new io.grpc.testing.integration.EchoServiceOuterClass.EchoResponse();
    }

    public static io.grpc.testing.integration.EchoServiceOuterClass.EchoResponse getDefaultInstance() {
      return DEFAULT_INSTANCE;
    }

    private static final com.google.protobuf.Parser<EchoResponse>
        PARSER = new com.google.protobuf.AbstractParser<EchoResponse>() {
      public EchoResponse parsePartialFrom(
          com.google.protobuf.CodedInputStream input,
          com.google.protobuf.ExtensionRegistryLite extensionRegistry)
          throws com.google.protobuf.InvalidProtocolBufferException {
        try {
          return new EchoResponse(input, extensionRegistry);
        } catch (RuntimeException e) {
          if (e.getCause() instanceof
              com.google.protobuf.InvalidProtocolBufferException) {
            throw (com.google.protobuf.InvalidProtocolBufferException)
                e.getCause();
          }
          throw e;
        }
      }
    };

    public static com.google.protobuf.Parser<EchoResponse> parser() {
      return PARSER;
    }

    @java.lang.Override
    public com.google.protobuf.Parser<EchoResponse> getParserForType() {
      return PARSER;
    }

    public io.grpc.testing.integration.EchoServiceOuterClass.EchoResponse getDefaultInstanceForType() {
      return DEFAULT_INSTANCE;
    }

  }

  private static com.google.protobuf.Descriptors.Descriptor
    internal_static_grpc_testing_EchoRequest_descriptor;
  private static
    com.google.protobuf.GeneratedMessage.FieldAccessorTable
      internal_static_grpc_testing_EchoRequest_fieldAccessorTable;
  private static com.google.protobuf.Descriptors.Descriptor
    internal_static_grpc_testing_EchoResponse_descriptor;
  private static
    com.google.protobuf.GeneratedMessage.FieldAccessorTable
      internal_static_grpc_testing_EchoResponse_fieldAccessorTable;

  public static com.google.protobuf.Descriptors.FileDescriptor
      getDescriptor() {
    return descriptor;
  }
  private static com.google.protobuf.Descriptors.FileDescriptor
      descriptor;
  static {
    java.lang.String[] descriptorData = {
      "\n.io/grpc/testing/integration/echo_servi" +
      "ce.proto\022\014grpc.testing\"\033\n\013EchoRequest\022\014\n" +
      "\004text\030\001 \001(\t\"\034\n\014EchoResponse\022\014\n\004text\030\001 \001(" +
      "\t2L\n\013EchoService\022=\n\004Echo\022\031.grpc.testing." +
      "EchoRequest\032\032.grpc.testing.EchoResponseB" +
      "\035\n\033io.grpc.testing.integrationb\006proto3"
    };
    com.google.protobuf.Descriptors.FileDescriptor.InternalDescriptorAssigner assigner =
        new com.google.protobuf.Descriptors.FileDescriptor.    InternalDescriptorAssigner() {
          public com.google.protobuf.ExtensionRegistry assignDescriptors(
              com.google.protobuf.Descriptors.FileDescriptor root) {
            descriptor = root;
            return null;
          }
        };
    com.google.protobuf.Descriptors.FileDescriptor
      .internalBuildGeneratedFileFrom(descriptorData,
        new com.google.protobuf.Descriptors.FileDescriptor[] {
        }, assigner);
    internal_static_grpc_testing_EchoRequest_descriptor =
      getDescriptor().getMessageTypes().get(0);
    internal_static_grpc_testing_EchoRequest_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessage.FieldAccessorTable(
        internal_static_grpc_testing_EchoRequest_descriptor,
        new java.lang.String[] { "Text", });
    internal_static_grpc_testing_EchoResponse_descriptor =
      getDescriptor().getMessageTypes().get(1);
    internal_static_grpc_testing_EchoResponse_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessage.FieldAccessorTable(
        internal_static_grpc_testing_EchoResponse_descriptor,
        new java.lang.String[] { "Text", });
  }

  // @@protoc_insertion_point(outer_class_scope)
}
