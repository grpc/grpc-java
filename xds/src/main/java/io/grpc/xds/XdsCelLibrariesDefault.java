package io.grpc.xds;

import io.grpc.xds.XdsCelLibraries;
// import io.grpc.xds.InterpreterException;
import io.grpc.ServerCall;
import io.grpc.Metadata;

import com.google.api.expr.v1alpha1.Expr;
import com.google.common.collect.ImmutableMap;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableSet;
import com.google.protobuf.MessageLite;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class XdsCelLibrariesDefault implements XdsCelLibraries {
    public static void main(String[] args) {
        XdsCelLibraries.Dispatcher dispatcher = DefaultDispatcher.create();
        XdsCelLibraries.Interpreter interpreter = new DefaultInterpreter();
        Expr.Builder exprBuilder = Expr.newBuilder();
        Expr checkedResult = exprBuilder.build();

        Map<String, ?> apiAttributes = new HashMap<>();
        XdsCelLibraries.Activation activation = XdsCelLibraries.Activation.copyOf(apiAttributes);

        try {
            Object result = interpreter.createInterpretable(checkedResult).eval(activation);
        } catch (InterpreterException e) {

        }
        
    }

    public static class DefaultInterpreter implements XdsCelLibraries.Interpreter {
        public DefaultInterpreter() {

        }

        @Override
        public XdsCelLibraries.Interpretable createInterpretable(Expr expr) throws InterpreterException {
            return new DefaultInterpretable(expr);
        }

        private class DefaultInterpretable implements XdsCelLibraries.Interpretable {
            private final Expr expr;
            private final Metadata metadata;

            public DefaultInterpretable(Expr expr) {
                this.metadata = new Metadata();
                this.expr = expr;
            }

            @Override
            public Object eval(XdsCelLibraries.Activation activation) throws InterpreterException {
                return new Object();
            }
        }
    }

    public static class DefaultDispatcher implements XdsCelLibraries.Dispatcher {
        public static DefaultDispatcher create() {
            return new DefaultDispatcher();
        }

        @Override
        public Object dispatch(
            Metadata metadata, long exprId, String functionName, List<String> overloadIds, Object[] args) 
            throws InterpreterException {
                return new Object();
        }
    }
}

