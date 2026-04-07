package io.github.cyfko.tests;

import io.github.cyfko.jpametamodel.providers.MethodSignatureValidator;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

public class MockMethodValidator implements MethodSignatureValidator {
    @Override
    public String validatePipeMethod(TypeElement methodType, String methodName, Elements elements, Types types) {
        if ("badPipeMethod".equals(methodName)) {
            return "This pipe method is universally rejected by the Mock SPI validator.";
        }
        return null;
    }

    @Override
    public String validateHandlerMethod(TypeElement methodType, String methodName, Elements elements, Types types) {
        if ("badHandlerMethod".equals(methodName)) {
            return "This handler method is universally rejected by the Mock SPI validator.";
        }
        return null;
    }
}
