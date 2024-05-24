package io.grpc.protobuf.services;

import io.grpc.reflection.v1alpha.ServerReflectionRequest;
import org.junit.Before;
import org.junit.Rule;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@RunWith(JUnit4.class)
public class ProtoReflectionServiceTest {
  @Rule
  public final MockitoRule mocks = MockitoJUnit.rule();
  ProtoReflectionService protoReflectionService = (ProtoReflectionService) ProtoReflectionService.newInstance();
  @Mock
  ProtoReflectionServiceV1 mockProtoReflectionServiceV1;
  private final ServerReflectionRequest serverReflectionRequestV1Alpha = ServerReflectionRequest.newBuilder()
      .build();
  @Before
  public void setUp() {
    protoReflectionService.setProtoReflectionServiceV1(mockProtoReflectionServiceV1);
  }


}