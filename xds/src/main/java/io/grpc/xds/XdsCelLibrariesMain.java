package io.grpc.xds;

import com.google.api.expr.v1alpha1.Expr;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.protobuf.Descriptors.Descriptor;
import io.grpc.Metadata;
import io.grpc.xds.InterpreterException;
import io.grpc.xds.XdsCelLibraries;
import io.grpc.xds.XdsCelLibrariesDefault;
import java.lang.String;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class XdsCelLibrariesMain {
  /** Main launches the server from the command line. */
  public static void main(String[] args) throws InterpreterException {
    List<Descriptor> descriptors = new ArrayList<>();
    XdsCelLibraries.RuntimeTypeProvider messageProvider = 
        XdsCelLibrariesDefault.DescriptorMessageProvider.dynamicMessages(descriptors);
    XdsCelLibraries.Dispatcher dispatcher = XdsCelLibrariesDefault.DefaultDispatcher.create();
    XdsCelLibraries.Interpreter interpreter = 
        new XdsCelLibrariesDefault.DefaultInterpreter(messageProvider, dispatcher);

    Expr checkedResult = Expr.newBuilder().build();

    Map<String, Object> map = new HashMap<>();
    map.put("requestUrlPath", new Object());
    map.put("requestHost", new Object());
    map.put("requestMethod", new Object());
    map.put("requestHeaders", new Object());
    map.put("sourceAddress", new Object());
    map.put("sourcePort", new Object());
    map.put("destinationAddress", new Object());

    ImmutableMap<String, Object> apiAttributes = ImmutableMap.copyOf(map);

    XdsCelLibraries.Activation activation = XdsCelLibraries.Activation.copyOf(apiAttributes);
    Object result = interpreter.createInterpretable(checkedResult).eval(activation);
  }
}