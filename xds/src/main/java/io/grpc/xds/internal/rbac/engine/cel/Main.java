package io.grpc.xds.internal;

import com.google.api.expr.v1alpha1.CheckedExpr;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.protobuf.Descriptors.Descriptor;
import io.grpc.Metadata;
import io.grpc.xds.InterpreterException;
import java.lang.String;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Main {
  public static void main(String[] args) throws InterpreterException {
    List<Descriptor> descriptors = new ArrayList<>();
    RuntimeTypeProvider messageProvider = DescriptorMessageProvider.dynamicMessages(descriptors);
    Dispatcher dispatcher = DefaultDispatcher.create();
    Interpreter interpreter = new DefaultInterpreter(messageProvider, dispatcher);

    CheckedExpr checkedResult = CheckedExpr.newBuilder().build();

    Map<String, Object> map = new HashMap<>();
    map.put("requestUrlPath", new Object());
    map.put("requestHost", new Object());
    map.put("requestMethod", new Object());
    map.put("requestHeaders", new Object());
    map.put("sourceAddress", new Object());
    map.put("sourcePort", new Object());
    map.put("destinationAddress", new Object());

    ImmutableMap<String, Object> apiAttributes = ImmutableMap.copyOf(map);

    Activation activation = Activation.copyOf(apiAttributes);
    Object result = interpreter.createInterpretable(checkedResult).eval(activation);
  }
}