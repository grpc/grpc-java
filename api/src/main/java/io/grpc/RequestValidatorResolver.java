package io.grpc;

import com.google.protobuf.MessageLiteOrBuilder;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class RequestValidatorResolver {

    private List<RequestValidator<MessageLiteOrBuilder>> validators;

    private Map<String, RequestValidator<MessageLiteOrBuilder>> validatorMap;

    public RequestValidatorResolver(List<RequestValidator<MessageLiteOrBuilder>> validators) {
        this.validators = validators;

        validatorMap = validators.stream()
                .collect(Collectors.toMap(this::getClassName, it -> it));
    }

    private String getClassName(RequestValidator<MessageLiteOrBuilder> it) {
        Type[] genericInterfaces = it.getClass().getGenericInterfaces();

        if (genericInterfaces.length == 0) {
            return null;
        }

        return ((ParameterizedType) genericInterfaces[0]).getActualTypeArguments()[0].getTypeName();
    }

    public RequestValidator<MessageLiteOrBuilder> find(String typeName) {
        return validatorMap.get(typeName);
    }
}

