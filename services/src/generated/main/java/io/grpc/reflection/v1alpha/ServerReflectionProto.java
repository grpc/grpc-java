// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: grpc/reflection/v1alpha/reflection.proto

package io.grpc.reflection.v1alpha;

@java.lang.Deprecated public final class ServerReflectionProto {
  private ServerReflectionProto() {}
  public static void registerAllExtensions(
      com.google.protobuf.ExtensionRegistryLite registry) {
  }

  public static void registerAllExtensions(
      com.google.protobuf.ExtensionRegistry registry) {
    registerAllExtensions(
        (com.google.protobuf.ExtensionRegistryLite) registry);
  }
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_grpc_reflection_v1alpha_ServerReflectionRequest_descriptor;
  static final 
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_grpc_reflection_v1alpha_ServerReflectionRequest_fieldAccessorTable;
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_grpc_reflection_v1alpha_ExtensionRequest_descriptor;
  static final 
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_grpc_reflection_v1alpha_ExtensionRequest_fieldAccessorTable;
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_grpc_reflection_v1alpha_ServerReflectionResponse_descriptor;
  static final 
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_grpc_reflection_v1alpha_ServerReflectionResponse_fieldAccessorTable;
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_grpc_reflection_v1alpha_FileDescriptorResponse_descriptor;
  static final 
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_grpc_reflection_v1alpha_FileDescriptorResponse_fieldAccessorTable;
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_grpc_reflection_v1alpha_ExtensionNumberResponse_descriptor;
  static final 
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_grpc_reflection_v1alpha_ExtensionNumberResponse_fieldAccessorTable;
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_grpc_reflection_v1alpha_ListServiceResponse_descriptor;
  static final 
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_grpc_reflection_v1alpha_ListServiceResponse_fieldAccessorTable;
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_grpc_reflection_v1alpha_ServiceResponse_descriptor;
  static final 
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_grpc_reflection_v1alpha_ServiceResponse_fieldAccessorTable;
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_grpc_reflection_v1alpha_ErrorResponse_descriptor;
  static final 
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_grpc_reflection_v1alpha_ErrorResponse_fieldAccessorTable;

  public static com.google.protobuf.Descriptors.FileDescriptor
      getDescriptor() {
    return descriptor;
  }
  private static  com.google.protobuf.Descriptors.FileDescriptor
      descriptor;
  static {
    java.lang.String[] descriptorData = {
      "\n(grpc/reflection/v1alpha/reflection.pro" +
      "to\022\027grpc.reflection.v1alpha\"\212\002\n\027ServerRe" +
      "flectionRequest\022\014\n\004host\030\001 \001(\t\022\032\n\020file_by" +
      "_filename\030\003 \001(\tH\000\022 \n\026file_containing_sym" +
      "bol\030\004 \001(\tH\000\022N\n\031file_containing_extension" +
      "\030\005 \001(\0132).grpc.reflection.v1alpha.Extensi" +
      "onRequestH\000\022\'\n\035all_extension_numbers_of_" +
      "type\030\006 \001(\tH\000\022\027\n\rlist_services\030\007 \001(\tH\000B\021\n" +
      "\017message_request\"E\n\020ExtensionRequest\022\027\n\017" +
      "containing_type\030\001 \001(\t\022\030\n\020extension_numbe" +
      "r\030\002 \001(\005\"\321\003\n\030ServerReflectionResponse\022\022\n\n" +
      "valid_host\030\001 \001(\t\022J\n\020original_request\030\002 \001" +
      "(\01320.grpc.reflection.v1alpha.ServerRefle" +
      "ctionRequest\022S\n\030file_descriptor_response" +
      "\030\004 \001(\0132/.grpc.reflection.v1alpha.FileDes" +
      "criptorResponseH\000\022Z\n\036all_extension_numbe" +
      "rs_response\030\005 \001(\01320.grpc.reflection.v1al" +
      "pha.ExtensionNumberResponseH\000\022N\n\026list_se" +
      "rvices_response\030\006 \001(\0132,.grpc.reflection." +
      "v1alpha.ListServiceResponseH\000\022@\n\016error_r" +
      "esponse\030\007 \001(\0132&.grpc.reflection.v1alpha." +
      "ErrorResponseH\000B\022\n\020message_response\"7\n\026F" +
      "ileDescriptorResponse\022\035\n\025file_descriptor" +
      "_proto\030\001 \003(\014\"K\n\027ExtensionNumberResponse\022" +
      "\026\n\016base_type_name\030\001 \001(\t\022\030\n\020extension_num" +
      "ber\030\002 \003(\005\"P\n\023ListServiceResponse\0229\n\007serv" +
      "ice\030\001 \003(\0132(.grpc.reflection.v1alpha.Serv" +
      "iceResponse\"\037\n\017ServiceResponse\022\014\n\004name\030\001" +
      " \001(\t\":\n\rErrorResponse\022\022\n\nerror_code\030\001 \001(" +
      "\005\022\025\n\rerror_message\030\002 \001(\t2\223\001\n\020ServerRefle" +
      "ction\022\177\n\024ServerReflectionInfo\0220.grpc.ref" +
      "lection.v1alpha.ServerReflectionRequest\032" +
      "1.grpc.reflection.v1alpha.ServerReflecti" +
      "onResponse(\0010\001B8\n\032io.grpc.reflection.v1a" +
      "lphaB\025ServerReflectionProtoP\001\270\001\001b\006proto3"
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
    internal_static_grpc_reflection_v1alpha_ServerReflectionRequest_descriptor =
      getDescriptor().getMessageTypes().get(0);
    internal_static_grpc_reflection_v1alpha_ServerReflectionRequest_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_grpc_reflection_v1alpha_ServerReflectionRequest_descriptor,
        new java.lang.String[] { "Host", "FileByFilename", "FileContainingSymbol", "FileContainingExtension", "AllExtensionNumbersOfType", "ListServices", "MessageRequest", });
    internal_static_grpc_reflection_v1alpha_ExtensionRequest_descriptor =
      getDescriptor().getMessageTypes().get(1);
    internal_static_grpc_reflection_v1alpha_ExtensionRequest_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_grpc_reflection_v1alpha_ExtensionRequest_descriptor,
        new java.lang.String[] { "ContainingType", "ExtensionNumber", });
    internal_static_grpc_reflection_v1alpha_ServerReflectionResponse_descriptor =
      getDescriptor().getMessageTypes().get(2);
    internal_static_grpc_reflection_v1alpha_ServerReflectionResponse_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_grpc_reflection_v1alpha_ServerReflectionResponse_descriptor,
        new java.lang.String[] { "ValidHost", "OriginalRequest", "FileDescriptorResponse", "AllExtensionNumbersResponse", "ListServicesResponse", "ErrorResponse", "MessageResponse", });
    internal_static_grpc_reflection_v1alpha_FileDescriptorResponse_descriptor =
      getDescriptor().getMessageTypes().get(3);
    internal_static_grpc_reflection_v1alpha_FileDescriptorResponse_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_grpc_reflection_v1alpha_FileDescriptorResponse_descriptor,
        new java.lang.String[] { "FileDescriptorProto", });
    internal_static_grpc_reflection_v1alpha_ExtensionNumberResponse_descriptor =
      getDescriptor().getMessageTypes().get(4);
    internal_static_grpc_reflection_v1alpha_ExtensionNumberResponse_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_grpc_reflection_v1alpha_ExtensionNumberResponse_descriptor,
        new java.lang.String[] { "BaseTypeName", "ExtensionNumber", });
    internal_static_grpc_reflection_v1alpha_ListServiceResponse_descriptor =
      getDescriptor().getMessageTypes().get(5);
    internal_static_grpc_reflection_v1alpha_ListServiceResponse_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_grpc_reflection_v1alpha_ListServiceResponse_descriptor,
        new java.lang.String[] { "Service", });
    internal_static_grpc_reflection_v1alpha_ServiceResponse_descriptor =
      getDescriptor().getMessageTypes().get(6);
    internal_static_grpc_reflection_v1alpha_ServiceResponse_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_grpc_reflection_v1alpha_ServiceResponse_descriptor,
        new java.lang.String[] { "Name", });
    internal_static_grpc_reflection_v1alpha_ErrorResponse_descriptor =
      getDescriptor().getMessageTypes().get(7);
    internal_static_grpc_reflection_v1alpha_ErrorResponse_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_grpc_reflection_v1alpha_ErrorResponse_descriptor,
        new java.lang.String[] { "ErrorCode", "ErrorMessage", });
  }

  // @@protoc_insertion_point(outer_class_scope)
}
